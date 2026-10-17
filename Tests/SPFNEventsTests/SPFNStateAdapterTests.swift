// SPFN Mobile — the observation adapters' cells that need no device (state-client-design §3-6, §4).
//
// The SwiftUI wrapper and model are a few lines over SPFNServerStateDriver and
// SPFNConnectionStatusDriver, which are plain Swift; these run here against a scripted state
// source. The wrapper's own release (A-5's `deinit`) and the missing attachment's
// `preconditionFailure` (A-8) are device cells. android/spfn-events/src/test/.../
// SpfnStateAdapterTest.kt carries the same names.

import Foundation
import XCTest
@testable import SPFNEvents
import SPFNClient
import SPFNCore
import SPFNState

@MainActor
final class SPFNStateAdapterTests: XCTestCase
{
    typealias Entry = SPFNStateEntry<String, SPFNNoStateData>

    nonisolated static let t1 = "2026-10-17T09:00:00.001Z"
    nonisolated static let consent = descriptor("consent")
    nonisolated static let runtime = descriptor("runtime", keyFields: ["workspaceId"])

    nonisolated static func descriptor(_ id: String, keyFields: [String] = []) -> SPFNStateDescriptor<String, SPFNNoStateData>
    {
        SPFNStateDescriptor(id: id, keyFields: keyFields, read: { _ in ScriptedSource.call() }, frame: { SPFNStateFrame(at: $0, value: $0, data: SPFNNoStateData()) })
    }

    nonisolated static func ready(_ value: String) -> Entry
    {
        .ready(SPFNStateFrame(at: t1, value: value, data: SPFNNoStateData()))
    }

    /// Lets the main-actor consuming task take what was yielded.
    func settle() async
    {
        for _ in 0 ..< 20
        {
            await Task.yield()
        }
    }

    func test_a1_cachedEntry_firstRenderIsReady() async
    {
        let source = ScriptedSource()
        source.entries["consent"] = Self.ready("A")
        var changes: [Entry] = []
        let driver = SPFNServerStateDriver<String, SPFNNoStateData> { changes.append($0) }
        driver.update(source: source, state: Self.consent, key: [:])
        XCTAssertEqual(driver.entry, Self.ready("A"), "the first body draws the cached entry, not loading")
        source.yield(Self.ready("A"), to: "consent")
        await settle()
        XCTAssertEqual(changes, [Self.ready("A")], "the subscription's first element, the same entry, changes nothing")
    }

    func test_a2_unseenKey_isLoadingUntilEntry() async
    {
        let source = ScriptedSource()
        let driver = SPFNServerStateDriver<String, SPFNNoStateData>()
        driver.update(source: source, state: Self.runtime, key: ["workspaceId": "w-a"])
        XCTAssertEqual(driver.entry, .loading)
        source.yield(.loading, to: "runtime")
        source.yield(Self.ready("R"), to: "runtime")
        await settle()
        XCTAssertEqual(driver.entry, Self.ready("R"))
    }

    func test_a3_equalKeyNewInstance_doesNotResubscribe() async
    {
        let source = ScriptedSource()
        let driver = SPFNServerStateDriver<String, SPFNNoStateData>()
        driver.update(source: source, state: Self.runtime, key: ["workspaceId": "w-a"])
        driver.update(source: source, state: Self.runtime, key: Dictionary(uniqueKeysWithValues: [("workspaceId", "w-" + "a")]))
        await settle()
        XCTAssertEqual(source.subscribed, 1)
        XCTAssertEqual(source.ended, 0)
    }

    func test_a4_keyChange_resubscribesCachedFirst() async
    {
        let source = ScriptedSource()
        source.entries["runtime w-b"] = Self.ready("B")
        let driver = SPFNServerStateDriver<String, SPFNNoStateData>()
        driver.update(source: source, state: Self.runtime, key: ["workspaceId": "w-a"])
        driver.update(source: source, state: Self.runtime, key: ["workspaceId": "w-b"])
        XCTAssertEqual(driver.entry, Self.ready("B"), "the new key's cached entry, at once")
        await settle()
        XCTAssertEqual(source.subscribed, 2)
        XCTAssertEqual(source.ended, 1, "the old key's subscription ended")
    }

    func test_a5_leaving_endsSubscription() async
    {
        let source = ScriptedSource()
        let driver = SPFNServerStateDriver<String, SPFNNoStateData>()
        driver.update(source: source, state: Self.consent, key: [:])
        driver.end()
        await settle()
        XCTAssertEqual(source.ended, 1)
    }

    func test_a6_entryChanges_onMain() async
    {
        let source = ScriptedSource()
        var changes = 0
        let driver = SPFNServerStateDriver<String, SPFNNoStateData>
        {
            _ in
            MainActor.assertIsolated("the entry changes on the main actor")
            changes += 1
        }
        driver.update(source: source, state: Self.consent, key: [:])
        let entry = Self.ready("A")
        await Task.detached { [source, entry] in source.yield(entry, to: "consent") }.value
        for _ in 0 ..< 1_000 where changes == 0
        {
            await Task.yield()
        }
        XCTAssertEqual(changes, 1, "an element yielded off the main actor reached the driver")
        XCTAssertEqual(driver.entry, Self.ready("A"))
    }

    func test_a7_connectionStatus_currentFirst() async
    {
        let source = ScriptedSource()
        var changes: [SPFNStateConnection] = []
        let driver = SPFNConnectionStatusDriver { changes.append($0) }
        driver.update(source: source)
        XCTAssertEqual(driver.connection, .reconnecting(attempt: 0), "the first body draws connection()")
        source.connectionChanged(.reconnecting(attempt: 0))
        source.connectionChanged(.connected)
        source.connectionChanged(.connected)
        await settle()
        XCTAssertEqual(changes, [.connected], "one change; the same status is not drawn again")
    }

    /// A device cell in Swift (`preconditionFailure` in the wrapper); here, what it says.
    func test_a8_withoutAttachment_isProgrammerError()
    {
        XCTAssertTrue(spfnMissingStateClient.contains(".spfnEventStream(_:keyLifecycle:stateClient:)"))
    }

    func test_a9_stateClientOfOtherStream_isProgrammerError() throws
    {
        let lifecycle = SPFNKeyLifecycle(transport: RefusingTransport(), store: EmptyKeyStore(), baseURL: "https://example.test")
        let configuration = try SPFNEventStreamConfiguration(events: ["state.consent.changed"])
        let mine = SPFNEventStream(keyLifecycle: lifecycle, configuration: configuration)
        let other = SPFNEventStream(keyLifecycle: lifecycle, configuration: configuration)
        let client = try SPFNStateClient(configuration: .init(
            stream: mine,
            keyLifecycle: lifecycle,
            states: [Self.consent],
            snapshot: { _ in ScriptedSource.call() as SPFNCall<Void, SPFNCanonicalValue> }
        ))
        XCTAssertTrue(spfnIsStateClient(client, of: mine))
        XCTAssertFalse(spfnIsStateClient(client, of: other), "the attachment refuses a client made for another stream")
        XCTAssertThrowsError(try SPFNStateClient(configuration: client.configuration), "and a stream has one state client")
    }
}

/// A state client stand-in: entries by "id key", subscriptions the test pushes into.
final class ScriptedSource: SPFNStateSource, SPFNConnectionSource, @unchecked Sendable
{
    private let lock = NSLock()
    private var sinks: [String: @Sendable (Any) -> Void] = [:]
    private var connections: [AsyncStream<SPFNStateConnection>.Continuation] = []
    private var subscriptions = 0
    private var endings = 0
    var entries: [String: SPFNStateEntry<String, SPFNNoStateData>] = [:]

    static func call<Response>() -> SPFNCall<Void, Response>
    {
        SPFNCall(
            operation: SPFNOperation(id: "test.unsent", method: "GET", path: "/unsent", authProfile: "none", requiresSession: false, declaresResponse: true),
            encode: { _ in nil },
            decode: { _ in throw SPFNDecodingError.typeMismatch(path: "$", expected: "nothing") }
        )
    }

    var subscribed: Int
    {
        lock.withLock { subscriptions }
    }

    var ended: Int
    {
        lock.withLock { endings }
    }

    private static func name(_ id: String, _ key: SPFNStateKey) -> String
    {
        ([id] + key.values.sorted()).joined(separator: " ")
    }

    func current<Value, Data>(_ state: SPFNStateDescriptor<Value, Data>, key: SPFNStateKey) -> SPFNStateEntry<Value, Data>
    {
        (entries[Self.name(state.id, key)] as? SPFNStateEntry<Value, Data>) ?? .loading
    }

    func subscribe<Value, Data>(_ state: SPFNStateDescriptor<Value, Data>, key: SPFNStateKey) -> AsyncStream<SPFNStateEntry<Value, Data>>
    {
        let (stream, continuation) = AsyncStream.makeStream(of: SPFNStateEntry<Value, Data>.self)
        continuation.onTermination = { [weak self] _ in self?.lock.withLock { self?.endings += 1 } }
        lock.withLock
        {
            subscriptions += 1
            sinks[state.id] = { ($0 as? SPFNStateEntry<Value, Data>).map { _ = continuation.yield($0) } }
        }
        return stream
    }

    func yield(_ entry: SPFNStateEntry<String, SPFNNoStateData>, to id: String)
    {
        lock.withLock { sinks[id] }?(entry)
    }

    func connection() -> SPFNStateConnection
    {
        .reconnecting(attempt: 0)
    }

    func subscribeConnection() -> AsyncStream<SPFNStateConnection>
    {
        let (stream, continuation) = AsyncStream.makeStream(of: SPFNStateConnection.self)
        lock.withLock { connections.append(continuation) }
        return stream
    }

    func connectionChanged(_ connection: SPFNStateConnection)
    {
        lock.withLock { connections }.forEach { $0.yield(connection) }
    }
}

/// Nobody is signed in and nothing is sent: the A-9 client never reaches the network.
struct RefusingTransport: SPFNTransport
{
    func execute(_ request: SPFNTransportRequest) async throws -> SPFNTransportResponse
    {
        throw SPFNTransportError.connectivity("not in this suite")
    }
}

struct EmptyKeyStore: SPFNKeyStore
{
    func load(slot: String) throws -> SPFNStoredKey?
    {
        nil
    }

    func save(_ record: SPFNStoredKey, slot: String) throws {}

    func delete(slot: String) throws {}
}
