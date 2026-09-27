// SPFN Mobile — the server event stream: one connection, owned by the SDK.
//
// An app configures it once and hands it three facts as they change — foreground,
// signed-in client id, network. From those alone the object decides when a connection
// exists: only while the app is in the foreground and somebody is signed in. Screens only
// listen; attaching, detaching and a listener's condition never touch the connection
// (docs/architecture/event-stream-design.md §3).
//
// This file is the runner. The decisions are SPFNEventStreamMachine's, a pure function;
// the engine actor below performs its effects — the token call through `execute`, the
// stream through the stream transport, the timers — and feeds each result back stamped
// with the generation it was issued under.
//
// Ownership, because Swift 6 checks it and a cycle would keep a signed-out stream alive:
// this object owns the inbox's continuation and the engine; the engine owns the tasks it
// starts; nothing the engine starts holds this object. When the app lets go of it, `deinit`
// finishes the inbox, the loop ends and the engine cancels whatever is still running.
//
// The token call is signed by the key signed in when it is made, not by one fixed at
// construction: the object outlives every sign-in, rotation and account switch, and the
// key lifecycle is the one place that knows which key that is (SPFNKeyLifecycle.signedInClient()).
//
// The token is never a field. It travels from the token call's result to the stream
// request inside the two values between them, and it describes itself as `redacted` (§6).
//
// android/spfn-client/.../SpfnEventStream.kt is the same object in Kotlin.

import Foundation
import SPFNCore

public final class SPFNEventStream: Sendable
{
    public let configuration: SPFNEventStreamConfiguration
    private let hub: SPFNEventListenerHub
    private let broadcaster: SPFNStateBroadcaster
    private let inbox: AsyncStream<SPFNEventInput>.Continuation

    /// - Parameters:
    ///   - keyLifecycle: whose signed-in key signs each token call, read again at every call
    ///     (E-41, E-43). The stream opens on its `baseURL`.
    ///   - sleeper: the timers' clock — silence, retry and stable. Injected so a suite can
    ///     fire them without waiting.
    ///   - clock: wall time, read for a Retry-After given as an HTTP-date.
    ///   - jitter: a factor in [0.5, 1.0] for each backoff delay.
    public init(
        keyLifecycle: SPFNKeyLifecycle,
        configuration: SPFNEventStreamConfiguration,
        transport: any SPFNStreamTransport = SPFNURLSessionStreamTransport(),
        sleeper: any SPFNSleeper = SPFNTaskSleeper(),
        clock: any SPFNClock = SPFNSystemClock(),
        jitter: @escaping @Sendable () -> Double = { Double.random(in: 0.5 ... 1.0) }
    )
    {
        let (inputs, inbox) = AsyncStream.makeStream(of: SPFNEventInput.self)
        let hub = SPFNEventListenerHub(deliveryBuffer: configuration.deliveryBuffer)
        let broadcaster = SPFNStateBroadcaster(.idle(.signedOut))
        let engine = SPFNEventStreamEngine(
            machine: SPFNEventStreamMachine(configuration: configuration, jitter: jitter),
            services: SPFNEventStreamServices(
                keyLifecycle: keyLifecycle,
                baseURL: keyLifecycle.baseURL,
                configuration: configuration,
                transport: transport,
                sleeper: sleeper,
                clock: clock
            ),
            hub: hub,
            broadcaster: broadcaster,
            inbox: inbox
        )
        self.configuration = configuration
        self.hub = hub
        self.broadcaster = broadcaster
        self.inbox = inbox
        Task
        {
            for await input in inputs
            {
                await engine.process(input)
            }
            await engine.shutDown()
        }
    }

    deinit
    {
        inbox.finish()
    }

    // MARK: - The three inputs

    /// True while the app is in the foreground. False after construction.
    public func setForeground(_ isForeground: Bool)
    {
        inbox.yield(.setForeground(isForeground))
    }

    /// The signed-in client id, or nil. Another id is another account (E-43).
    public func setSignedIn(_ clientID: String?)
    {
        inbox.yield(.setSignedIn(clientID))
    }

    /// True after construction; without a path monitor, backoff recovers on its own.
    public func setNetworkAvailable(_ isAvailable: Bool)
    {
        inbox.yield(.setNetworkAvailable(isAvailable))
    }

    // MARK: - Diagnostics

    /// For diagnostics and decoration. A screen reads again on a reread signal, not on this.
    public var state: SPFNEventStreamState
    {
        broadcaster.current
    }

    /// Every state change, starting with the current state.
    public var states: AsyncStream<SPFNEventStreamState>
    {
        broadcaster.subscribe()
    }

    /// Dropped, filtered and unexpected frame counts, summed over every listener.
    public var diagnostics: SPFNEventDiagnostics
    {
        hub.diagnostics
    }

    // MARK: - Listening

    /// `listen(_:decode:where:)` by a payload description: its `eventName` and its initialiser.
    public func listen<Payload: SPFNEventPayload>(
        _ payload: Payload.Type,
        where condition: @escaping @Sendable (Payload) -> Bool = { _ in true }
    ) -> AsyncStream<SPFNEventSignal<Payload>>
    {
        listen(Payload.eventName, decode: Payload.init(canonical:), where: condition)
    }

    /// Signals for one configured event name: `reread(.attached)` first, then the frames that
    /// decode and pass `condition`, and a reread whenever what the screen holds may be stale.
    ///
    /// Attaches when CALLED, so call it right before `for await` (§8 H-9), inside a
    /// `.task(id:)` keyed by whatever `condition` captures (H-11). Cancelling the consuming
    /// task detaches; neither touches the connection. `condition` runs off the main actor,
    /// which is what `@Sendable` enforces.
    ///
    /// A name outside the configured events stops the process, in release builds too (L-3).
    public func listen<Event: Sendable>(
        _ name: String,
        decode: @escaping @Sendable (SPFNCanonicalValue) throws -> Event,
        where condition: @escaping @Sendable (Event) -> Bool = { _ in true }
    ) -> AsyncStream<SPFNEventSignal<Event>>
    {
        guard configuration.contains(name)
        else
        {
            preconditionFailure("'\(name)' is not in the configured events")
        }
        let listener = hub.attach(name, decode: decode, condition: condition)
        return AsyncStream(unfolding: { await listener.next() }, onCancel: { [hub] in hub.detach(listener) })
    }
}

/// What the engine reaches beyond the machine. A value, so the tasks it starts can carry
/// it without carrying the engine.
struct SPFNEventStreamServices: Sendable
{
    static let tokenOperationID = "events.token"
    static let tokenAuthProfile = "clientProofV1"
    static let streamHeadersTimeoutMillis: Int64 = 15_000
    static let refusalBodyLimit = 65_536

    let keyLifecycle: SPFNKeyLifecycle
    let baseURL: String
    let configuration: SPFNEventStreamConfiguration
    let transport: any SPFNStreamTransport
    let sleeper: any SPFNSleeper
    let clock: any SPFNClock

    /// The token call, through `execute`: signed by the key signed in at this moment, and
    /// sessionless unless the configuration says the server wants one (§2-2), in which case
    /// `execute` opens it and re-handshakes once. With nobody signed in nothing is sent, and the answer is the
    /// one an unsigned call would have met; the sign-out itself follows as `setSignedIn(nil)`.
    func mint(generation: Int64) async -> SPFNEventInput
    {
        do
        {
            guard let client = try await keyLifecycle.signedInClient()
            else
            {
                return .tokenFailed(generation: generation, failure: .unauthorized)
            }
            return .tokenMinted(generation: generation, token: try await client.execute(tokenCall(), request: ()))
        }
        catch
        {
            return .tokenFailed(generation: generation, failure: Self.tokenFailure(error, nowMillis: clock.nowMillis()))
        }
    }

    private func tokenCall() -> SPFNCall<Void, SPFNEventStreamToken>
    {
        SPFNCall(
            operation: SPFNOperation(
                id: Self.tokenOperationID,
                method: "POST",
                path: configuration.tokenPath,
                authProfile: Self.tokenAuthProfile,
                requiresSession: configuration.tokenRequiresSession,
                declaresResponse: true
            ),
            encode: { _ in nil },
            decode: Self.decodeToken
        )
    }

    /// Opens the stream and feeds everything it produces into `inbox`, stamped `generation`.
    func stream(
        generation: Int64,
        names: [String],
        token: SPFNEventStreamToken,
        into inbox: AsyncStream<SPFNEventInput>.Continuation
    ) async
    {
        guard let response = try? await transport.open(streamRequest(names: names, token: token))
        else
        {
            inbox.yield(.streamFailed(generation: generation))
            return
        }
        defer { response.cancel() }
        let answer = Self.streamAnswer(
            status: response.statusCode,
            headers: response.headers,
            body: await Self.bodyIfRefused(response),
            nowMillis: clock.nowMillis()
        )
        inbox.yield(.streamAnswered(generation: generation, answer: answer))
        guard answer == .eventStream
        else
        {
            return
        }
        inbox.yield(await Self.read(response, generation: generation, into: inbox))
    }

    private static func read(
        _ response: SPFNStreamResponse,
        generation: Int64,
        into inbox: AsyncStream<SPFNEventInput>.Continuation
    ) async -> SPFNEventInput
    {
        var parser = SPFNSSELineParser()
        do
        {
            for try await chunk in response.chunks
            {
                inbox.yield(.bytesReceived(generation: generation))
                for event in parser.feed(chunk)
                {
                    inbox.yield(.frameReceived(generation: generation, event: event))
                }
            }
            return .streamEnded(generation: generation)
        }
        catch
        {
            return .streamFailed(generation: generation)
        }
    }

    /// A refusal's body, read to its end (a 400 names the valid events); nothing for a 2xx.
    /// A body cut short is still a refusal, read on its status.
    private static func bodyIfRefused(_ response: SPFNStreamResponse) async -> [UInt8]
    {
        guard !(200 ... 299).contains(response.statusCode)
        else
        {
            return []
        }
        var body: [UInt8] = []
        do
        {
            for try await chunk in response.chunks where body.count < refusalBodyLimit
            {
                body += chunk
            }
        }
        catch
        {
            return body
        }
        return body
    }

    private func streamRequest(names: [String], token: SPFNEventStreamToken) -> SPFNTransportRequest
    {
        let query = "?token=" + Self.encode(token.value) + "&events=" + names.map(Self.encode).joined(separator: ",")
        return SPFNTransportRequest(
            method: "GET",
            url: baseURL + configuration.streamPath + query,
            // Not signed: the server reads only the token on this path, and a proof here
            // would be spent for nothing (§2-2). The identity headers ride as on every request.
            headers: SPFNClientIdentity.headers + [("accept", "text/event-stream"), ("cache-control", "no-cache")],
            timeoutMillis: Self.streamHeadersTimeoutMillis
        )
    }

    private static func encode(_ value: String) -> String
    {
        value.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn: "-._~"))) ?? value
    }

    /// `{"token": "<64 hex>"}`. Anything else is the declared response not arriving (E-9).
    static func decodeToken(_ value: SPFNCanonicalValue) throws -> SPFNEventStreamToken
    {
        let token = try SPFNDecoding.string(SPFNDecoding.object(value, at: "$")["token"], at: "$.token")
        guard !token.isEmpty
        else
        {
            throw SPFNDecodingError.typeMismatch(path: "$.token", expected: "a non-empty token")
        }
        return SPFNEventStreamToken(token)
    }

    /// E-4 … E-9: what `execute` classified, read as the machine's vocabulary.
    static func tokenFailure(_ error: any Error, nowMillis: Int64) -> SPFNTokenFailure
    {
        switch error as? SPFNClientError
        {
        case .auth?:
            return .unauthorized
        case .server(let failure)? where failure.httpStatus == 403:
            return .forbidden
        case .server(let failure)?:
            let retryAfter = failure.httpStatus == 429 ? SPFNRetryAfter.millis(failure.retryAfter, nowMillis: nowMillis) : nil
            return .serverError(status: failure.httpStatus, retryAfterMillis: retryAfter)
        case .transport?:
            return .network
        default:
            return .unreadable
        }
    }

    /// E-12 … E-19: a stream response's status, headers and refusal body, read.
    static func streamAnswer(status: Int, headers: [(String, String)], body: [UInt8], nowMillis: Int64) -> SPFNStreamAnswer
    {
        switch status
        {
        case 200 ... 299:
            return isEventStream(headers) ? .eventStream : .notEventStream
        case 401:
            return .tokenRejected
        case 400:
            return invalidEvents(body) ?? .badRequest
        case 403:
            return .forbidden
        case 429:
            return .serverError(status: 429, retryAfterMillis: SPFNRetryAfter.millis(SPFNRetryAfter.header(in: headers), nowMillis: nowMillis))
        default:
            return .serverError(status: status, retryAfterMillis: nil)
        }
    }

    private static func isEventStream(_ headers: [(String, String)]) -> Bool
    {
        headers.contains
        {
            $0.0.lowercased() == "content-type"
                && $0.1.trimmingCharacters(in: .whitespaces).lowercased().hasPrefix("text/event-stream")
        }
    }

    /// The server's `{"error", "invalidEvents": [...], "validEvents": [...]}`, or nil.
    private static func invalidEvents(_ body: [UInt8]) -> SPFNStreamAnswer?
    {
        guard case .object(let members)? = try? SPFNCanonicalJSON.parse(body),
              let invalid = texts(members["invalidEvents"]),
              let valid = texts(members["validEvents"])
        else
        {
            return nil
        }
        return .invalidEvents(invalid: invalid, valid: valid)
    }

    private static func texts(_ value: SPFNCanonicalValue?) -> [String]?
    {
        guard case .array(let elements)? = value
        else
        {
            return nil
        }
        let texts = elements.compactMap
        {
            element -> String? in
            guard case .string(let text) = element
            else
            {
                return nil
            }
            return text
        }
        return texts.count == elements.count ? texts : nil
    }
}

/// Runs the machine's effects. Every input is processed here, one at a time, so the
/// machine's state is never touched concurrently.
actor SPFNEventStreamEngine
{
    private let machine: SPFNEventStreamMachine
    private let services: SPFNEventStreamServices
    private let hub: SPFNEventListenerHub
    private let broadcaster: SPFNStateBroadcaster
    private let inbox: AsyncStream<SPFNEventInput>.Continuation
    private var state: SPFNEventMachineState
    private var tokenTask: Task<Void, Never>?
    private var streamTask: Task<Void, Never>?
    private var silenceTask: Task<Void, Never>?
    private var retryTask: Task<Void, Never>?
    private var stableTask: Task<Void, Never>?

    init(
        machine: SPFNEventStreamMachine,
        services: SPFNEventStreamServices,
        hub: SPFNEventListenerHub,
        broadcaster: SPFNStateBroadcaster,
        inbox: AsyncStream<SPFNEventInput>.Continuation
    )
    {
        self.machine = machine
        self.services = services
        self.hub = hub
        self.broadcaster = broadcaster
        self.inbox = inbox
        self.state = machine.initial()
    }

    func process(_ input: SPFNEventInput)
    {
        let step = machine.step(state, input)
        state = step.state
        step.effects.forEach(perform)
    }

    /// The owner let go: nothing keeps running for a stream nobody holds.
    func shutDown()
    {
        for task in [tokenTask, streamTask, silenceTask, retryTask, stableTask]
        {
            task?.cancel()
        }
        broadcaster.finish()
    }

    private func perform(_ effect: SPFNEventEffect)
    {
        switch effect
        {
        case .mintToken(let generation):
            tokenTask = Task { [services, inbox] in inbox.yield(await services.mint(generation: generation)) }
        case .cancelToken:
            tokenTask?.cancel()
        case .openStream(let generation, let names, let token):
            streamTask = Task
            {
                [services, inbox] in
                await services.stream(generation: generation, names: names, token: token, into: inbox)
            }
        case .closeStream:
            streamTask?.cancel()
        case .startSilenceTimer(let generation, let millis):
            silenceTask = restart(silenceTask, millis: millis, expiry: .silenceElapsed(generation: generation))
        case .startRetryTimer(let generation, let millis):
            retryTask = restart(retryTask, millis: millis, expiry: .retryElapsed(generation: generation))
        case .startStableTimer(let generation, let millis):
            stableTask = restart(stableTask, millis: millis, expiry: .stableElapsed(generation: generation))
        case .cancelTimers:
            for task in [silenceTask, retryTask, stableTask]
            {
                task?.cancel()
            }
        case .deliver(let name, let data):
            hub.deliver(name: name, data: data)
        case .reread(let epoch):
            hub.reread(epoch: epoch)
        case .markUnavailable(let names):
            hub.markUnavailable(names)
        case .countUnexpected:
            hub.countUnexpected()
        case .publish(let state):
            broadcaster.publish(state)
        }
    }

    private func restart(_ previous: Task<Void, Never>?, millis: Int64, expiry: SPFNEventInput) -> Task<Void, Never>
    {
        previous?.cancel()
        return Task
        {
            [sleeper = services.sleeper, inbox] in
            guard (try? await sleeper.sleep(millis: millis)) != nil, !Task.isCancelled
            else
            {
                return
            }
            inbox.yield(expiry)
        }
    }
}

/// The current state and everyone watching it. Each subscriber's `AsyncStream` is
/// finished exactly once: by its own termination, or by `finish` when the engine stops.
final class SPFNStateBroadcaster: @unchecked Sendable
{
    private let lock = NSLock()
    private var value: SPFNEventStreamState
    private var subscribers: [UUID: AsyncStream<SPFNEventStreamState>.Continuation] = [:]

    init(_ initial: SPFNEventStreamState)
    {
        value = initial
    }

    var current: SPFNEventStreamState
    {
        lock.withLock { value }
    }

    func subscribe() -> AsyncStream<SPFNEventStreamState>
    {
        let (stream, continuation) = AsyncStream.makeStream(of: SPFNEventStreamState.self)
        let id = UUID()
        continuation.onTermination = { [weak self] _ in self?.remove(id) }
        let current = lock.withLock
        {
            subscribers[id] = continuation
            return value
        }
        continuation.yield(current)
        return stream
    }

    func publish(_ state: SPFNEventStreamState)
    {
        let targets = lock.withLock
        {
            value = state
            return Array(subscribers.values)
        }
        for target in targets
        {
            target.yield(state)
        }
    }

    func finish()
    {
        let targets = lock.withLock
        {
            defer { subscribers.removeAll() }
            return Array(subscribers.values)
        }
        for target in targets
        {
            target.finish()
        }
    }

    private func remove(_ id: UUID)
    {
        _ = lock.withLock { subscribers.removeValue(forKey: id) }
    }
}
