// SPFN Mobile — the state client: the server's states, kept current over the app's stream.
//
// `@spfn/core/state/client` on the native SDK. A subscription is a sequence that yields the
// current entry first and every change after; `current` and `connection` answer at once;
// `get`, `write` and `snapshot` go through the generated calls and `execute` (§2-13-3).
//
// This file is the runner. The decisions are SPFNStateCore's; the engine actor below
// performs its effects — requests, the stream's query, the retry timer, the subscribers —
// and feeds each result back. The synchronous reads (`current`, `connection`) come from
// values the engine publishes under one lock, the way SPFNEventStream answers `state`.
// The engine never calls into the main actor: what crosses is one element per change.
//
// The stream is the app's and stays the app's: this client attaches a wire listener and
// writes the connection query, and never opens or closes the connection itself (§2-2).
//
// android/spfn-state/.../SpfnStateClient.kt is the same client in Kotlin.

import Foundation
import SPFNClient
import SPFNCore

public final class SPFNStateClient: Sendable
{
    public let configuration: SPFNStateClientConfiguration
    private let shared: SPFNStateShared
    private let inbox: AsyncStream<SPFNStateEngineInput>.Continuation
    private let ids = SPFNStateCounter()
    private let claim: ObjectIdentifier

    /// - Throws: `SPFNStateClientError.invalidConfiguration` naming what is wrong: no state, an
    ///   id twice, a `state.<id>.changed` missing from the stream's events, or a stream that
    ///   already has a state client — the connection query has one owner (§2-13-1).
    public convenience init(configuration: SPFNStateClientConfiguration) throws
    {
        try self.init(
            configuration: configuration,
            stream: configuration.stream,
            executor: SPFNKeyLifecycleExecutor(keyLifecycle: configuration.keyLifecycle),
            sleeper: SPFNTaskSleeper(),
            schedule: { work in Task { work() } }
        )
    }

    /// The seam the suites use: a scripted stream, a scripted `execute`, a held timer.
    init(
        configuration: SPFNStateClientConfiguration,
        stream: any SPFNStateStream,
        executor: any SPFNStateExecutor,
        sleeper: any SPFNSleeper,
        schedule: @escaping @Sendable (@escaping @Sendable () -> Void) -> Void
    ) throws
    {
        let accesses = try configuration.accesses(carriedBy: stream.configuration)
        guard SPFNStateClaims.claim(ObjectIdentifier(stream))
        else
        {
            throw SPFNStateClientError.invalidConfiguration("stream: it already has a state client")
        }
        let (inputs, inbox) = AsyncStream.makeStream(of: SPFNStateEngineInput.self)
        let shared = SPFNStateShared()
        let engine = SPFNStateEngine(
            core: SPFNStateCore(
                configuration: .init(
                    states: accesses,
                    snapshot: configuration.snapshot,
                    retryDelayMillis: configuration.retryDelayMillis,
                    maxRetries: configuration.maxRetries
                ),
                initial: configuration.initial
            ),
            services: SPFNStateServices(
                stream: stream,
                names: accesses.map(\.eventName),
                executor: executor,
                sleeper: sleeper,
                schedule: schedule,
                onError: configuration.onError
            ),
            shared: shared,
            inbox: inbox
        )
        self.configuration = configuration
        self.shared = shared
        self.inbox = inbox
        self.claim = ObjectIdentifier(stream)
        Task
        {
            await engine.attach()
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
        SPFNStateClaims.release(claim)
    }

    /// The default `onError`: the error's name only. A key and a value are the app's data.
    public static func logError(_ error: any Error)
    {
        print("SPFNState: \(SPFNStateShared.name(of: error))")
    }

    // MARK: - States

    /// The entry of `state` under `key` now, then every change of its status, value or data.
    /// Attaches when CALLED, so call it right before `for await`; ending the iteration
    /// unsubscribes. A refused subscription (unknown id, other key fields) yields nothing and
    /// ends, its reason sent to `onError` (K-8, K-9).
    public func subscribe<Value, Data>(_ state: SPFNStateDescriptor<Value, Data>, key: SPFNStateKey = [:]) -> AsyncStream<SPFNStateEntry<Value, Data>>
    {
        let subscription = ids.next()
        let (entries, continuation) = AsyncStream.makeStream(of: SPFNStateEntry<Value, Data>.self)
        continuation.onTermination = { [inbox] _ in inbox.yield(.core(.unsubscribe(subscription: subscription))) }
        let sink = SPFNStateSink(
            deliver: { continuation.yield(SPFNStateShared.entry($0)) },
            finish: { continuation.finish() }
        )
        inbox.yield(.subscribe(subscription, SPFNStateSlot(id: state.id, key: key), sink))
        return entries
    }

    /// The entry now; `loading` when nothing has arrived for this key.
    public func current<Value, Data>(_ state: SPFNStateDescriptor<Value, Data>, key: SPFNStateKey = [:]) -> SPFNStateEntry<Value, Data>
    {
        SPFNStateShared.entry(shared.cell(SPFNStateSlot(id: state.id, key: key)))
    }

    /// Reads `state` now — after the first snapshot when the manifest is not known yet — and
    /// applies the answer under the ordering rule.
    public func get<Value, Data>(_ state: SPFNStateDescriptor<Value, Data>, key: SPFNStateKey = [:]) async throws -> SPFNStateFrame<Value, Data>
    {
        let slot = SPFNStateSlot(id: state.id, key: key)
        return try Self.frame(await call { .get(call: $0, slot: slot) })
    }

    /// Writes `state` and applies the answer at once, before the write's own frame. Refused
    /// before any request when the manifest says the state has no write (I-11).
    public func write<Value, Data, Input>(
        _ state: SPFNWritableStateDescriptor<Value, Data, Input>,
        input: Input,
        key: SPFNStateKey = [:]
    ) async throws -> SPFNStateFrame<Value, Data>
    {
        let slot = SPFNStateSlot(id: state.id, key: key)
        let call = state.write(key)
        let request = SPFNStateRequest(kind: .write(slot)) { try await $0.execute(call, request: input) }
        let decode: @Sendable (SPFNCanonicalValue) throws -> any Sendable = { [access = state.access] in try access.decode(key, $0) }
        return try Self.frame(await self.call { .write(call: $0, slot: slot, request: request, decode: decode) })
    }

    /// Reads every state at once — by default with the stream's query — and applies it: each
    /// state present, and `revoked` for a subscribed one it omits. A refusal changes nothing.
    public func snapshot(query: SPFNStateKey? = nil) async throws -> SPFNStatesSnapshot
    {
        guard case .snapshot(let snapshot) = try await call({ .snapshot(call: $0, query: query) })
        else
        {
            throw SPFNStateClientError.invalidConfiguration("snapshot")
        }
        return snapshot
    }

    // MARK: - Connection

    public func connection() -> SPFNStateConnection
    {
        shared.connection
    }

    /// The status now, then every change; the same status is not repeated (C-22, C-23).
    public func subscribeConnection() -> AsyncStream<SPFNStateConnection>
    {
        shared.subscribeConnection()
    }

    /// Restart what gave up or is still retrying, now: the first snapshot before the
    /// manifest, the stream after it. Nothing when connected or with nothing subscribed.
    public func retry()
    {
        inbox.yield(.core(.retry))
    }

    /// Ends every subscription and the connection sequences, detaches from the stream and
    /// empties its query. The stream stays open: it is the app's. A later `subscribe` starts
    /// again. Idempotent.
    public func close()
    {
        inbox.yield(.core(.close))
    }

    /// Frames whose payload the state's decoder could not read (§2-4).
    public var droppedFrames: Int
    {
        shared.droppedFrames
    }

    // MARK: - Calls

    private func call(_ make: @escaping @Sendable (Int) -> SPFNStateInput) async throws -> SPFNStateResolution
    {
        let id = ids.next()
        return try await withCheckedThrowingContinuation
        {
            [inbox] continuation in
            inbox.yield(.call(continuation, make(id), id))
        }
    }

    private static func frame<Value, Data>(_ resolution: SPFNStateResolution) throws -> SPFNStateFrame<Value, Data>
    {
        guard case .frame(let typed) = resolution, let frame = typed as? SPFNStateFrame<Value, Data>
        else
        {
            throw SPFNStateClientError.invalidConfiguration("descriptor")
        }
        return frame
    }
}

/// The stream as the state client uses it. `SPFNEventStream` is the one the app has; the
/// suites script one.
protocol SPFNStateStream: AnyObject, Sendable
{
    var configuration: SPFNEventStreamConfiguration { get }
    var states: AsyncStream<SPFNEventStreamState> { get }
    func listenWire(_ names: [String]) -> AsyncStream<SPFNWireSignal>
    func setQuery(_ fields: [String: String]) throws
    func reconnectNow()
}

extension SPFNEventStream: SPFNStateStream {}

/// `execute` by whoever is signed in at the moment of the call (§2-3). Nobody: nothing is sent.
struct SPFNKeyLifecycleExecutor: SPFNStateExecutor
{
    let keyLifecycle: SPFNKeyLifecycle

    func execute<Request: Sendable, Response: Sendable>(_ call: SPFNCall<Request, Response>, request: Request) async throws -> Response
    {
        guard let client = try await keyLifecycle.signedInClient()
        else
        {
            throw SPFNStateClientError.signedOut
        }
        return try await client.execute(call, request: request)
    }
}

/// Which streams have a state client: the query has one owner (§2-2).
enum SPFNStateClaims
{
    private static let lock = NSLock()
    nonisolated(unsafe) private static var claimed: Set<ObjectIdentifier> = []

    static func claim(_ stream: ObjectIdentifier) -> Bool
    {
        lock.withLock { claimed.insert(stream).inserted }
    }

    static func release(_ stream: ObjectIdentifier)
    {
        _ = lock.withLock { claimed.remove(stream) }
    }
}

final class SPFNStateCounter: @unchecked Sendable
{
    private let lock = NSLock()
    private var value = 0

    func next() -> Int
    {
        lock.withLock
        {
            value += 1
            return value
        }
    }
}
