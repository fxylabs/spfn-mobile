// SPFN Mobile — what the event stream suites run against.
//
// `SPFNFakeStreamTransport` is the stream boundary with a script: a test queues a response
// (status, headers), then pushes chunks into it, ends it quietly or fails it.
// `TokenServer` answers the token call behind `execute` — the handshake it needs first,
// then one token per call. `ManualSleeper` holds every timer until the test fires it.
// No socket anywhere (design §9-2).
//
// android/spfn-client/src/test/.../EventStreamTestDoubles.kt is the counterpart.

import Foundation
@testable import SPFNClient
import SPFNCore
import SPFNGenerated

final class SPFNFakeStreamTransport: SPFNStreamTransport, @unchecked Sendable
{
    /// One scripted connection. Chunks go in with `send`; `finish` and `fail` end it.
    final class FakeStream: @unchecked Sendable
    {
        let statusCode: Int
        let headers: [(String, String)]
        private let chunks: AsyncThrowingStream<[UInt8], any Error>
        private let sink: AsyncThrowingStream<[UInt8], any Error>.Continuation
        private let lock = NSLock()
        private var wasCancelled = false

        init(statusCode: Int, headers: [(String, String)])
        {
            self.statusCode = statusCode
            self.headers = headers
            (chunks, sink) = AsyncThrowingStream.makeStream(of: [UInt8].self, throwing: (any Error).self)
        }

        var cancelled: Bool
        {
            lock.withLock { wasCancelled }
        }

        func send(_ text: String)
        {
            sink.yield(Array(text.utf8))
        }

        func finish()
        {
            sink.finish()
        }

        func fail()
        {
            sink.finish(throwing: SPFNTransportError.connectivity("scripted drop"))
        }

        func response() -> SPFNStreamResponse
        {
            SPFNStreamResponse(statusCode: statusCode, headers: headers, chunks: chunks)
            {
                [self] in
                lock.withLock { wasCancelled = true }
                sink.finish()
            }
        }
    }

    static let eventStreamHeaders = [("content-type", "text/event-stream")]

    private let lock = NSLock()
    private var scripted: [FakeStream] = []
    private var recorded: [SPFNTransportRequest] = []

    var requests: [SPFNTransportRequest]
    {
        lock.withLock { recorded }
    }

    /// Queues the next connection's answer and returns it for the test to drive.
    @discardableResult
    func enqueue(statusCode: Int = 200, headers: [(String, String)] = eventStreamHeaders) -> FakeStream
    {
        let stream = FakeStream(statusCode: statusCode, headers: headers)
        lock.withLock { scripted.append(stream) }
        return stream
    }

    /// With nothing queued the connection hangs, as a connect nobody answers does.
    func open(_ request: SPFNTransportRequest) async throws -> SPFNStreamResponse
    {
        let next: FakeStream? = lock.withLock
        {
            recorded.append(request)
            return scripted.isEmpty ? nil : scripted.removeFirst()
        }
        guard let next
        else
        {
            try await Task.sleep(nanoseconds: UInt64.max / 2)
            throw SPFNTransportError.cancelled
        }
        return next.response()
    }
}

/// Answers the handshake a session-requiring token call opens first, then the token path with `token-1`,
/// `token-2`, … — or with whatever `script` queues, in order, before falling back. Any
/// other path answers what `route` set for it: the key lifecycle's own calls.
final class TokenServer: SPFNTransport, @unchecked Sendable
{
    private let tokenPath: String
    private let lock = NSLock()
    private var scripted: [SPFNTransportResponse] = []
    private var routes: [String: SPFNTransportResponse] = [:]
    private var recorded: [SPFNTransportRequest] = []
    private var minted = 0

    init(tokenPath: String = "/events/token")
    {
        self.tokenPath = tokenPath
    }

    var requests: [SPFNTransportRequest]
    {
        lock.withLock { recorded }
    }

    var tokenRequests: [SPFNTransportRequest]
    {
        requests.filter { $0.url.hasSuffix(tokenPath) }
    }

    var handshakeRequests: [SPFNTransportRequest]
    {
        requests.filter { $0.url.hasSuffix(SPFNGeneratedOperations.authClientProofHandshake.path) }
    }

    func script(_ response: SPFNTransportResponse)
    {
        lock.withLock { scripted.append(response) }
    }

    func route(_ path: String, _ response: SPFNTransportResponse)
    {
        lock.withLock { routes[path] = response }
    }

    private func routed(_ request: SPFNTransportRequest) -> SPFNTransportResponse?
    {
        lock.withLock { routes.first { request.url.hasSuffix($0.key) }?.value }
    }

    func execute(_ request: SPFNTransportRequest) async throws -> SPFNTransportResponse
    {
        let isToken = request.url.hasSuffix(tokenPath)
        let answer: SPFNTransportResponse? = lock.withLock
        {
            recorded.append(request)
            guard isToken
            else
            {
                return nil
            }
            if !scripted.isEmpty
            {
                return scripted.removeFirst()
            }
            minted += 1
            return .json(200, "{\"token\":\"token-\(minted)\"}")
        }
        if let answer
        {
            return answer
        }
        if request.url.hasSuffix(SPFNGeneratedOperations.authClientProofHandshake.path)
        {
            return .json(200, SessionFixtureValues.handshakeResponseBody)
        }
        if let routed = routed(request)
        {
            return routed
        }
        return .json(404, ExecuteFixtures.errorEnvelope(code: "Error"))
    }
}

/// Holds every sleep until the test fires it by duration. Cancellation ends a held sleep
/// with `CancellationError`, as `Task.sleep` does.
final class ManualSleeper: SPFNSleeper, @unchecked Sendable
{
    private struct Held
    {
        let millis: Int64
        let continuation: CheckedContinuation<Void, any Error>
    }

    private let lock = NSLock()
    private var held: [UUID: Held] = [:]

    var pending: [Int64]
    {
        lock.withLock { held.values.map(\.millis).sorted() }
    }

    func sleep(millis: Int64) async throws
    {
        let id = UUID()
        try await withTaskCancellationHandler
        {
            try await withCheckedThrowingContinuation
            {
                (continuation: CheckedContinuation<Void, any Error>) in
                let cancelled = lock.withLock
                {
                    guard !Task.isCancelled
                    else
                    {
                        return true
                    }
                    held[id] = Held(millis: millis, continuation: continuation)
                    return false
                }
                if cancelled
                {
                    continuation.resume(throwing: CancellationError())
                }
            }
        }
        onCancel:
        {
            lock.withLock { held.removeValue(forKey: id) }?.continuation.resume(throwing: CancellationError())
        }
    }

    /// Ends every held sleep of exactly `millis`. Returns how many it ended.
    @discardableResult
    func fire(_ millis: Int64) -> Int
    {
        let due = lock.withLock
        {
            let keys = held.filter { $0.value.millis == millis }.map(\.key)
            return keys.compactMap { held.removeValue(forKey: $0) }
        }
        due.forEach { $0.continuation.resume() }
        return due.count
    }
}

/// Everything one listener received, readable while its task is still consuming.
final class SignalLog<Event: Sendable>: @unchecked Sendable
{
    private let lock = NSLock()
    private var signals: [SPFNEventSignal<Event>] = []

    var all: [SPFNEventSignal<Event>]
    {
        lock.withLock { signals }
    }

    func append(_ signal: SPFNEventSignal<Event>)
    {
        lock.withLock { signals.append(signal) }
    }

    /// Consumes `stream` into this log until the task is cancelled.
    func consume(_ stream: AsyncStream<SPFNEventSignal<Event>>) -> Task<Void, Never>
    {
        Task
        {
            for await signal in stream
            {
                append(signal)
            }
        }
    }
}

/// A weak reference a `@Sendable` closure may read: whether an object was released.
final class WeakReference<Object: AnyObject>: @unchecked Sendable
{
    weak var value: Object?

    init(_ value: Object?)
    {
        self.value = value
    }
}

/// Polls `condition` until it holds or two seconds pass. The engine runs on its own actor,
/// so a test waits for an outcome rather than for a number of scheduler turns.
func eventually(_ condition: @Sendable () -> Bool) async -> Bool
{
    for _ in 0 ..< 2_000
    {
        if condition()
        {
            return true
        }
        try? await Task.sleep(nanoseconds: 1_000_000)
    }
    return condition()
}

/// The SSE frames the server writes, spelled as `@spfn/core` 0.3.0-beta.13 spells them.
enum ServerFrames
{
    static let connected = "event: connected\ndata: {\"subscribedEvents\":[\"sessionActivity\",\"sessionUnread\"],\"timestamp\":1750000000000}\n\n"

    static let ping = "event: ping\ndata: {\"timestamp\":1750000010000}\n\n"

    static func activity(_ id: Int, sessionID: String, wsID: String) -> String
    {
        "id: \(id)\nevent: sessionActivity\ndata: {\"event\":\"sessionActivity\",\"data\":{\"sessionId\":\"\(sessionID)\",\"wsId\":\"\(wsID)\"}}\n\n"
    }
}

/// The payload the hub and stream suites listen for.
struct Activity: SPFNEventPayload, Equatable
{
    static let eventName = "sessionActivity"

    let sessionID: String
    let wsID: String

    init(sessionID: String, wsID: String)
    {
        self.sessionID = sessionID
        self.wsID = wsID
    }

    init(canonical: SPFNCanonicalValue) throws
    {
        let members = try SPFNDecoding.object(canonical, at: "$")
        sessionID = try SPFNDecoding.string(members["sessionId"], at: "$.sessionId")
        wsID = try SPFNDecoding.string(members["wsId"], at: "$.wsId")
    }
}

/// A stream over the fakes with a real key lifecycle and a real `execute` in front of the
/// token; a real session too when `tokenRequiresSession` asks for one. The install starts signed in as `client-test-0001` with
/// `key-test-0001`; keys the lifecycle generates later are named by `keyIDs`, in order.
struct EventStreamFixture
{
    let tokens = TokenServer()
    let streams = SPFNFakeStreamTransport()
    let sleeper = ManualSleeper()
    let store = InMemoryKeyStore()
    let keyLifecycle: SPFNKeyLifecycle
    let events: SPFNEventStream

    init(
        events names: [String] = ["sessionUnread", "sessionActivity"],
        keyIDs: [String] = [],
        tokenRequiresSession: Bool = false
    ) throws
    {
        let clock = FakeClock(SessionFixtureValues.issuedAtMillis)
        let key = SPFNCustodyKey.generate(keyID: "key-test-0001", preferSecureEnclave: false)
        try store.save(key.record(clientID: SessionFixtureValues.clientID, createdAtMillis: clock.nowMillis()), slot: SPFNKeyLifecycle.activeSlot)
        let queued = ScriptedQueue(keyIDs)
        keyLifecycle = SPFNKeyLifecycle(
            transport: tokens,
            store: store,
            // A trailing slash, which the stream URL must not double.
            baseURL: "https://example.invalid/",
            clock: clock,
            proofClock: clock,
            nonceGenerator: ScriptedNonceGenerator([]),
            newKeyID: { queued.next() ?? "key-unexpected" },
            makeKey: { SPFNCustodyKey.generate(keyID: $0, preferSecureEnclave: false) }
        )
        events = SPFNEventStream(
            keyLifecycle: keyLifecycle,
            configuration: try SPFNEventStreamConfiguration(events: names, tokenRequiresSession: tokenRequiresSession),
            transport: streams,
            sleeper: sleeper,
            clock: FakeClock(SessionFixtureValues.issuedAtMillis),
            jitter: { 1.0 }
        )
    }

    /// Signs in, comes to the foreground and waits for the stream request.
    func connect(expectingRequests count: Int = 1) async -> Bool
    {
        events.setSignedIn(SessionFixtureValues.clientID)
        events.setForeground(true)
        return await eventually { streams.requests.count == count }
    }

    /// `connect`, then the server's `connected` frame on `stream`.
    func open(_ stream: SPFNFakeStreamTransport.FakeStream, epoch: Int = 1) async -> Bool
    {
        guard await connect()
        else
        {
            return false
        }
        stream.send(ServerFrames.connected)
        return await eventually { events.state == .open(epoch: epoch) }
    }
}
