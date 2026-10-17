// SPFN Mobile — the state client's runner over a scripted stream and a scripted `execute`.
//
// The cells are the core's (SPFNStateClientTests); these prove the wiring around it: the
// sequences, the synchronous reads, the calls, the generated descriptors compiling against
// the client, and `close()`. android/spfn-state/src/test/.../SpfnStateClientRuntimeTest.kt
// is the counterpart.

import Foundation
import XCTest
@testable import SPFNState
import SPFNClient
import SPFNCore

final class SPFNStateClientRuntimeTests: XCTestCase
{
    static let t1 = "2026-10-17T09:00:00.001Z"
    static let t2 = "2026-10-17T09:00:00.002Z"

    func makeClient(_ stream: ScriptedStream, _ executor: ScriptedExecutor, initial: SPFNStateInitial? = nil) throws -> SPFNStateClient
    {
        let lifecycle = SPFNKeyLifecycle(transport: NoTransport(), store: NoKeyStore(), baseURL: "https://example.test")
        let events = SPFNEventStream(keyLifecycle: lifecycle, configuration: stream.configuration)
        return try SPFNStateClient(
            configuration: .init(
                stream: events,
                keyLifecycle: lifecycle,
                states: StateFixtureStates.all,
                snapshot: StateFixtureStates.snapshot,
                initial: initial
            ),
            stream: stream,
            executor: executor,
            sleeper: SPFNTaskSleeper(),
            schedule: { work in Task { work() } }
        )
    }

    static var consentSnapshot: SPFNCanonicalValue
    {
        try! SPFNCanonicalJSON.parse(
            """
            {"at":"\(t1)","states":{"consent":{"value":"pending","data":{"marketing":false,"version":1}}},\
            "manifest":{"aiAccounts":{"key":["workspaceId"],"write":false},"consent":{"key":[],"write":true}}}
            """
        )
    }

    func test_subscribe_yieldsCurrentFirst_thenFramesChange() async throws
    {
        let stream = try ScriptedStream()
        let executor = ScriptedExecutor(answers: [Self.consentSnapshot])
        let client = try makeClient(stream, executor)
        var entries = client.subscribe(StateFixtureStates.consent).makeAsyncIterator()
        let first = await entries.next()
        XCTAssertEqual(first?.status, .loading, "current first: nothing yet")
        let ready = await entries.next()
        XCTAssertEqual(ready?.value, .pending, "the first snapshot's entry, decoded by the generated read")
        XCTAssertEqual(client.current(StateFixtureStates.consent).value, .pending, "current answers at once")
        stream.push(.attached)
        stream.push(.opened(SPFNStreamConnected(epoch: 1, query: [:], subscribedEvents: StateFixtureStates.events, deniedEvents: [], initial: StateFixtureStates.events)))
        stream.push(.frame(name: "state.consent.changed", value: try SPFNCanonicalJSON.parse("{\"at\":\"\(Self.t2)\",\"value\":\"agreed\",\"data\":{\"marketing\":true,\"version\":1}}")))
        let changed = await entries.next()
        XCTAssertEqual(changed?.value, .agreed)
        XCTAssertEqual(changed?.frame?.data.marketing, true)
        stream.push(.frame(name: "state.consent.changed", value: .object(["at": .string(Self.t2)])))
        for _ in 0 ..< 1_000 where client.droppedFrames == 0
        {
            await Task.yield()
        }
        XCTAssertEqual(client.droppedFrames, 1, "a frame its decoder cannot read is counted, not applied")
        client.close()
        let end = await entries.next()
        XCTAssertNil(end, "close() ends the subscription")
    }

    func test_write_appliesTheAnswerThroughTheReadDecoder() async throws
    {
        let answer = try SPFNCanonicalJSON.parse("{\"at\":\"\(Self.t2)\",\"value\":\"agreed\",\"data\":{\"marketing\":true,\"version\":1}}")
        let executor = ScriptedExecutor(answers: [Self.consentSnapshot, answer])
        let client = try makeClient(try ScriptedStream(), executor)
        let frame = try await client.write(StateFixtureStates.consent, input: .init(agreed: true, marketing: true))
        XCTAssertEqual(frame.value, .agreed)
        XCTAssertEqual(client.current(StateFixtureStates.consent).value, .agreed)
        XCTAssertEqual(executor.operations, ["getStates", "putConsentState"])
    }

    func test_connection_currentFirst_thenStreamStates() async throws
    {
        let stream = try ScriptedStream()
        let client = try makeClient(stream, ScriptedExecutor(answers: [Self.consentSnapshot]))
        var statuses = client.subscribeConnection().makeAsyncIterator()
        let first = await statuses.next()
        XCTAssertEqual(first, .reconnecting(attempt: 0))
        let entries = client.subscribe(StateFixtureStates.consent)
        stream.state(.open(epoch: 1))
        let next = await statuses.next()
        XCTAssertEqual(next, .connected)
        _ = entries
    }
}

/// The stream as the state client sees it: its signals and states pushed by the test.
final class ScriptedStream: SPFNStateStream, @unchecked Sendable
{
    let configuration: SPFNEventStreamConfiguration
    private let lock = NSLock()
    private var wire: AsyncStream<SPFNWireSignal>.Continuation?
    private var watchers: [AsyncStream<SPFNEventStreamState>.Continuation] = []
    private var last: SPFNEventStreamState?
    private var pending: [SPFNWireSignal] = []
    private(set) var queries: [[String: String]] = []

    init() throws
    {
        configuration = try SPFNEventStreamConfiguration(events: StateFixtureStates.events)
    }

    var states: AsyncStream<SPFNEventStreamState>
    {
        let (stream, continuation) = AsyncStream.makeStream(of: SPFNEventStreamState.self)
        // Like the real stream: a watcher starts with the current state, whenever it attaches.
        let current = lock.withLock
        {
            watchers.append(continuation)
            return last
        }
        current.map { _ = continuation.yield($0) }
        return stream
    }

    func listenWire(_ names: [String]) -> AsyncStream<SPFNWireSignal>
    {
        let (stream, continuation) = AsyncStream.makeStream(of: SPFNWireSignal.self)
        let queued = lock.withLock
        {
            wire = continuation
            defer { pending = [] }
            return pending
        }
        queued.forEach { continuation.yield($0) }
        return stream
    }

    func push(_ signal: SPFNWireSignal)
    {
        let target = lock.withLock
        {
            if wire == nil
            {
                pending.append(signal)
            }
            return wire
        }
        target?.yield(signal)
    }

    func state(_ state: SPFNEventStreamState)
    {
        let targets = lock.withLock
        {
            last = state
            return watchers
        }
        targets.forEach { $0.yield(state) }
    }

    func setQuery(_ fields: [String: String]) throws
    {
        lock.withLock { queries.append(fields) }
    }

    func reconnectNow() {}
}

/// `execute` answering from a queue, recording the operation of each call.
final class ScriptedExecutor: SPFNStateExecutor, @unchecked Sendable
{
    private let lock = NSLock()
    private var answers: [SPFNCanonicalValue]
    private var sent: [String] = []

    init(answers: [SPFNCanonicalValue])
    {
        self.answers = answers
    }

    var operations: [String]
    {
        lock.withLock { sent }
    }

    func execute<Request: Sendable, Response: Sendable>(_ call: SPFNCall<Request, Response>, request: Request) async throws -> Response
    {
        let answer = lock.withLock
        {
            sent.append(call.operation.id)
            return answers.isEmpty ? nil : answers.removeFirst()
        }
        guard let answer
        else
        {
            throw SPFNStateClientError.signedOut
        }
        return try call.decode(answer)
    }
}

struct NoTransport: SPFNTransport
{
    func execute(_ request: SPFNTransportRequest) async throws -> SPFNTransportResponse
    {
        throw SPFNTransportError.connectivity("not in this suite")
    }
}

struct NoKeyStore: SPFNKeyStore
{
    func load(slot: String) throws -> SPFNStoredKey?
    {
        nil
    }

    func save(_ record: SPFNStoredKey, slot: String) throws {}

    func delete(slot: String) throws {}
}
