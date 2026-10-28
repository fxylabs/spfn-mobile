// SPFN Mobile — the L-table, cell by cell (docs/architecture/event-stream-design.md §4-2).
//
// The hub is plain code with a queue per listener, so most cells read that queue directly.
// The cells that are about the CONNECTION — attaching never calls the token path, leaving
// never reconnects — run the whole stream over the fakes and count what reached the
// server. The Kotlin suite carries the same names and one more, L-12: a throwing
// condition does not compile here, because the parameter's type has no `throws`.
//
// L-3's refusal is a `preconditionFailure`, which stops the process, so what is tested is
// the check it uses (`SPFNEventStreamConfiguration.contains`) and not the stop itself.

import XCTest
@testable import SPFNClient
import SPFNCore

final class SPFNEventListenerHubTests: XCTestCase
{
    private let attached = SPFNEventSignal<Activity>.reread(.attached)

    private func envelope(_ sessionID: String, _ wsID: String) -> String
    {
        "{\"event\":\"sessionActivity\",\"data\":{\"sessionId\":\"\(sessionID)\",\"wsId\":\"\(wsID)\"}}"
    }

    private func activity(
        _ hub: SPFNEventListenerHub,
        where condition: @escaping @Sendable (Activity) -> Bool = { _ in true }
    ) -> SPFNEventListenerHub.Listener<Activity>
    {
        hub.attach(Activity.eventName, decode: Activity.init(canonical:), condition: condition)
    }

    private func frames(_ listener: SPFNEventListenerHub.Listener<Activity>) -> [String]
    {
        listener.pending.compactMap
        {
            guard case .frame(let value) = $0
            else
            {
                return nil
            }
            return "\(value.sessionID)/\(value.wsID)"
        }
    }

    func test_l1_attach_sendsAttachedReread_noMachineInput() async throws
    {
        let fixture = try EventStreamFixture()
        let connection = fixture.streams.enqueue()
        let isOpen = await fixture.open(connection)
        XCTAssertTrue(isOpen)
        let log = SignalLog<Activity>()
        let task = log.consume(fixture.events.listen(Activity.self, where: { $0.wsID == "w-1" }))
        let received = await eventually { log.all.count == 1 }
        XCTAssertTrue(received)
        XCTAssertEqual(log.all, [attached])
        XCTAssertEqual(fixture.tokens.tokenRequests.count, 1)
        XCTAssertEqual(fixture.streams.requests.count, 1)
        XCTAssertEqual(fixture.events.state, .open(epoch: 1))
        task.cancel()
    }

    func test_l2_detach_removesQueue_connectionStays() async throws
    {
        let fixture = try EventStreamFixture()
        let connection = fixture.streams.enqueue()
        let isOpen = await fixture.open(connection)
        XCTAssertTrue(isOpen)
        let task = SignalLog<Activity>().consume(fixture.events.listen(Activity.self))
        task.cancel()
        await task.value
        connection.send(ServerFrames.activity(1, sessionID: "s-1", wsID: "w-1"))
        try await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertEqual(fixture.events.state, .open(epoch: 1))
        XCTAssertEqual(fixture.tokens.tokenRequests.count, 1)
        XCTAssertFalse(connection.cancelled)
    }

    func test_l3_unknownName_isRefused() throws
    {
        let configuration = try SPFNEventStreamConfiguration(events: ["sessionActivity"])
        XCTAssertTrue(configuration.contains("sessionActivity"))
        XCTAssertFalse(configuration.contains("sessionUnread"))
    }

    func test_l4_overflow_replacesQueueWithOneReread()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 2)
        let slow = activity(hub)
        let other = activity(hub)
        for index in 0 ..< 3
        {
            hub.deliver(name: "sessionActivity", data: envelope("s-\(index)", "w-1"))
        }
        XCTAssertEqual(slow.pending, [.reread(.overflow)])
        for index in 0 ..< 2
        {
            hub.deliver(name: "sessionActivity", data: envelope("s-next-\(index)", "w-1"))
        }
        XCTAssertEqual(slow.pending.count, 3)
        XCTAssertEqual(other.pending.first, .reread(.overflow))
    }

    func test_l5_attachWhileNotOpen_getsAttachedOnly() async throws
    {
        let fixture = try EventStreamFixture()
        let connection = fixture.streams.enqueue()
        let log = SignalLog<Activity>()
        let task = log.consume(fixture.events.listen(Activity.self))
        let attachedOnly = await eventually { log.all.count == 1 }
        XCTAssertTrue(attachedOnly)
        XCTAssertEqual(fixture.events.state, .idle(.signedOut))

        let isOpen = await fixture.open(connection)
        XCTAssertTrue(isOpen)
        let reread = await eventually { log.all.count == 2 }
        XCTAssertTrue(reread)
        XCTAssertEqual(log.all, [attached, .reread(.opened(epoch: 1))])
        task.cancel()
    }

    func test_l6_navigation_doesNotReconnect() async throws
    {
        let fixture = try EventStreamFixture()
        let connection = fixture.streams.enqueue()
        let isOpen = await fixture.open(connection)
        XCTAssertTrue(isOpen)
        for _ in 0 ..< 10
        {
            let screenA = SignalLog<Activity>().consume(fixture.events.listen(Activity.self))
            screenA.cancel()
            let screenB = SignalLog<String>().consume(fixture.events.listen("sessionUnread", decode: { "\($0)" }))
            screenB.cancel()
            await screenA.value
            await screenB.value
        }
        XCTAssertEqual(fixture.tokens.tokenRequests.count, 1)
        XCTAssertEqual(fixture.streams.requests.count, 1)
        XCTAssertEqual(fixture.events.state, .open(epoch: 1))
    }

    func test_l7_twoListenersNoCondition_bothGetFrame()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 4)
        let first = activity(hub)
        let second = activity(hub)
        hub.deliver(name: "sessionActivity", data: envelope("s-1", "w-1"))
        let frame = SPFNEventSignal<Activity>.frame(Activity(sessionID: "s-1", wsID: "w-1"))
        XCTAssertEqual(first.pending, [attached, frame])
        XCTAssertEqual(second.pending, [attached, frame])
    }

    func test_l9_conditionTrue_enqueuesFrame()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 4)
        let listener = activity(hub) { $0.wsID == "w-1" }
        hub.deliver(name: "sessionActivity", data: envelope("s-1", "w-1"))
        XCTAssertEqual(listener.pending, [attached, .frame(Activity(sessionID: "s-1", wsID: "w-1"))])
    }

    func test_l10_conditionFalse_countsFilteredAndSendsNothing()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 4)
        let listener = activity(hub) { $0.wsID == "w-1" }
        hub.deliver(name: "sessionActivity", data: envelope("s-1", "w-2"))
        XCTAssertEqual(listener.pending, [attached])
        XCTAssertEqual(hub.diagnostics, SPFNEventDiagnostics(filteredFrames: 1))
    }

    func test_l10_filteredFrames_doNotOverflow()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 3)
        let listener = activity(hub) { $0.wsID == "w-1" }
        for index in 0 ..< 6
        {
            hub.deliver(name: "sessionActivity", data: envelope("s-\(index)", "w-2"))
        }
        XCTAssertEqual(listener.pending, [attached])
        XCTAssertEqual(hub.diagnostics.filteredFrames, 6)
    }

    func test_l11_decodeFailure_skipsCondition_countsDropped()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 4)
        let calls = SignalLog<Bool>()
        let listener = hub.attach(
            "sessionActivity",
            decode: { _ -> Activity in throw SPFNTransportError.timedOut },
            condition: { _ in calls.append(.frame(true)); return true }
        )
        hub.deliver(name: "sessionActivity", data: envelope("s-1", "w-1"))
        hub.deliver(name: "sessionActivity", data: "not json")
        XCTAssertEqual(calls.all.count, 0)
        XCTAssertEqual(listener.pending, [attached])
        XCTAssertEqual(hub.diagnostics, SPFNEventDiagnostics(droppedFrames: 2))
    }

    func test_l13_reread_ignoresCondition()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 1)
        let listener = activity(hub) { _ in false }
        hub.reread(epoch: 3)
        XCTAssertEqual(listener.pending, [.reread(.opened(epoch: 3))])

        let overflowing = SPFNEventListenerHub(deliveryBuffer: 1)
        let never = activity(overflowing) { _ in false }
        let always = activity(overflowing)
        for index in 0 ..< 2
        {
            overflowing.deliver(name: "sessionActivity", data: envelope("s-\(index)", "w-1"))
        }
        XCTAssertEqual(never.pending, [attached])
        XCTAssertEqual(always.pending, [.reread(.overflow)])
        XCTAssertEqual(activity(overflowing) { _ in false }.pending.first, attached)
    }

    func test_l14_twoConditions_sameName_eachGetsOwnMatches()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 8)
        let home = activity(hub) { $0.wsID == "w-a" }
        let detail = activity(hub) { $0.sessionID == "s-x" }
        hub.deliver(name: "sessionActivity", data: envelope("s-x", "w-a"))
        hub.deliver(name: "sessionActivity", data: envelope("s-y", "w-a"))
        hub.deliver(name: "sessionActivity", data: envelope("s-x", "w-b"))
        hub.deliver(name: "sessionActivity", data: envelope("s-z", "w-c"))
        XCTAssertEqual(frames(home), ["s-x/w-a", "s-y/w-a"])
        XCTAssertEqual(frames(detail), ["s-x/w-a", "s-x/w-b"])
        XCTAssertEqual(hub.diagnostics.filteredFrames, 4)
    }

    func test_l15_conditionChange_reattaches_withoutReconnect() async throws
    {
        let fixture = try EventStreamFixture()
        let connection = fixture.streams.enqueue()
        let isOpen = await fixture.open(connection)
        XCTAssertTrue(isOpen)
        let log = SignalLog<Activity>()
        let first = log.consume(fixture.events.listen(Activity.self, where: { $0.wsID == "w-a" }))
        let firstAttached = await eventually { log.all.count == 1 }
        XCTAssertTrue(firstAttached)
        first.cancel()
        await first.value
        let second = log.consume(fixture.events.listen(Activity.self, where: { $0.wsID == "w-b" }))
        connection.send(ServerFrames.activity(1, sessionID: "s-1", wsID: "w-a") + ServerFrames.activity(2, sessionID: "s-2", wsID: "w-b"))
        let received = await eventually { log.all.count == 3 }
        XCTAssertTrue(received)
        XCTAssertEqual(log.all, [attached, attached, .frame(Activity(sessionID: "s-2", wsID: "w-b"))])
        XCTAssertEqual(fixture.tokens.tokenRequests.count, 1)
        second.cancel()
    }

    func test_l16_unavailableName_signalsItsListenersOnce()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 4)
        let missing = hub.attach("sessionUnread", decode: { "\($0)" }, condition: { _ in true })
        let present = activity(hub)
        hub.markUnavailable(["sessionUnread"])
        hub.markUnavailable(["sessionUnread"])
        hub.reread(epoch: 1)
        XCTAssertEqual(missing.pending, [.unavailable, .reread(.opened(epoch: 1))])
        XCTAssertEqual(present.pending, [.reread(.opened(epoch: 1))])
    }

    func test_l17_attachToUnavailableName_getsAttachedThenUnavailable()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 4)
        hub.markUnavailable(["sessionUnread"])
        let late = hub.attach("sessionUnread", decode: { "\($0)" }, condition: { _ in true })
        XCTAssertEqual(late.pending, [.reread(.attached), .unavailable])
    }

    func test_l18_unavailableNames_leaveOtherListenersAndConnectionAlone() async throws
    {
        let fixture = try EventStreamFixture()
        let rejected = fixture.streams.enqueue(statusCode: 400, headers: [("content-type", "application/json")])
        rejected.send("{\"error\":\"Invalid event names\",\"invalidEvents\":[\"sessionUnread\"],\"validEvents\":[\"sessionActivity\"]}")
        rejected.finish()
        let narrowed = fixture.streams.enqueue()
        let unread = SignalLog<String>()
        let activity = SignalLog<Activity>()
        let tasks = [
            unread.consume(fixture.events.listen("sessionUnread", decode: { "\($0)" })),
            activity.consume(fixture.events.listen(Activity.self)),
        ]
        let reconnected = await fixture.connect(expectingRequests: 2)
        XCTAssertTrue(reconnected)
        narrowed.send(ServerFrames.connected + ServerFrames.activity(1, sessionID: "s-1", wsID: "w-1"))
        let delivered = await eventually { activity.all.count == 3 && unread.all.count == 3 }
        XCTAssertTrue(delivered)

        XCTAssertEqual(fixture.events.state, .open(epoch: 1, unavailableEvents: ["sessionUnread"]))
        XCTAssertTrue(fixture.streams.requests.last?.url.hasSuffix("&events=sessionActivity") ?? false)
        XCTAssertEqual(unread.all, [.reread(.attached), .unavailable, .reread(.opened(epoch: 1))])
        XCTAssertEqual(activity.all, [attached, .reread(.opened(epoch: 1)), .frame(Activity(sessionID: "s-1", wsID: "w-1"))])
        tasks.forEach { $0.cancel() }
    }

    func test_payload_listen_equalsNameListen() async throws
    {
        let fixture = try EventStreamFixture()
        let connection = fixture.streams.enqueue()
        let isOpen = await fixture.open(connection)
        XCTAssertTrue(isOpen)
        let byPayload = SignalLog<Activity>()
        let byName = SignalLog<Activity>()
        let tasks = [
            byPayload.consume(fixture.events.listen(Activity.self, where: { $0.wsID == "w-1" })),
            byName.consume(fixture.events.listen("sessionActivity", decode: Activity.init(canonical:), where: { $0.wsID == "w-1" })),
        ]
        connection.send(ServerFrames.activity(1, sessionID: "s-1", wsID: "w-1") + ServerFrames.activity(2, sessionID: "s-2", wsID: "w-2"))
        let delivered = await eventually { byPayload.all.count == 2 && byName.all.count == 2 }
        XCTAssertTrue(delivered)
        XCTAssertEqual(byPayload.all, byName.all)
        tasks.forEach { $0.cancel() }
    }
    // MARK: - Revision 4: wire listeners (state-client-design §5-1, S-2 and S-3)

    private static let both: Set<String> = ["sessionActivity", "sessionUnread"]

    private static func connected(_ epoch: Int, query: [String: String] = [:]) -> SPFNStreamConnected
    {
        SPFNStreamConnected(epoch: epoch, query: query, subscribedEvents: ["sessionActivity"], deniedEvents: ["sessionUnread"], initial: [])
    }

    private func unread(_ count: Int) -> String
    {
        "{\"event\":\"sessionUnread\",\"data\":{\"count\":\(count)}}"
    }

    private func value(_ sessionID: String, _ wsID: String) -> SPFNCanonicalValue
    {
        .object(["sessionId": .string(sessionID), "wsId": .string(wsID)])
    }

    func test_l19_wireAttachWhileNotOpen_getsAttachedThenOpenedOnOpen() async throws
    {
        let fixture = try EventStreamFixture()
        let first = fixture.streams.enqueue()
        let isOpen = await fixture.open(first)
        XCTAssertTrue(isOpen)
        first.fail()
        let retrying = await eventually { fixture.events.state == .retrying(attempt: 2, delayMillis: 1_000, reason: .network) }
        XCTAssertTrue(retrying)

        let log = WireLog()
        let task = log.consume(fixture.events.listenWire(["sessionActivity"]))
        let attached = await eventually { log.all.count == 1 }
        XCTAssertTrue(attached)
        try await Task.sleep(nanoseconds: 20_000_000)
        XCTAssertEqual(log.all, [.attached], "no stale opened from the connection that ended")

        let second = fixture.streams.enqueue()
        fixture.sleeper.fire(1_000)
        let reconnected = await eventually { fixture.streams.requests.count == 2 }
        XCTAssertTrue(reconnected)
        second.send(ServerFrames.connectedDenying)
        let opened = await eventually { log.all.count == 2 }
        XCTAssertTrue(opened)
        XCTAssertEqual(
            log.all,
            [.attached, .opened(SPFNStreamConnected(epoch: 2, query: [:], subscribedEvents: ["sessionActivity"], deniedEvents: ["sessionUnread"], initial: ["sessionActivity"], clientID: SessionFixtureValues.clientID))]
        )
        task.cancel()
    }

    func test_l20_wireAttachWhileOpen_getsAttachedThenCurrentOpened() async throws
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 4)
        hub.connected(Self.connected(3, query: ["workspaceId": "w-1"]))
        let listener = hub.attachWire(Self.both)
        XCTAssertEqual(listener.pending, [.attached, .opened(Self.connected(3, query: ["workspaceId": "w-1"]))])

        hub.disconnected()
        XCTAssertEqual(hub.attachWire(Self.both).pending, [.attached])

        let fixture = try EventStreamFixture()
        let connection = fixture.streams.enqueue()
        let isOpen = await fixture.open(connection)
        XCTAssertTrue(isOpen)
        let log = WireLog()
        let task = log.consume(fixture.events.listenWire(["sessionActivity"]))
        let both = await eventually { log.all.count == 2 }
        XCTAssertTrue(both)
        XCTAssertEqual(
            log.all,
            [.attached, .opened(SPFNStreamConnected(epoch: 1, query: [:], subscribedEvents: ["sessionActivity", "sessionUnread"], deniedEvents: [], initial: nil, clientID: SessionFixtureValues.clientID))]
        )
        XCTAssertEqual(fixture.tokens.tokenRequests.count, 1, "attaching never reaches the connection")
        task.cancel()
    }

    func test_l21_wireListener_severalNamesOneQueueInOrder()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 8)
        let listener = hub.attachWire(Self.both)
        let onlyActivity = hub.attachWire(["sessionActivity"])
        hub.deliver(name: "sessionActivity", data: envelope("s-1", "w-1"))
        hub.deliver(name: "sessionUnread", data: unread(2))
        hub.deliver(name: "sessionActivity", data: envelope("s-2", "w-1"))
        XCTAssertEqual(listener.pending, [
            .attached,
            .frame(name: "sessionActivity", value: value("s-1", "w-1")),
            .frame(name: "sessionUnread", value: .object(["count": .integer(2)])),
            .frame(name: "sessionActivity", value: value("s-2", "w-1")),
        ])
        XCTAssertEqual(onlyActivity.pending.count, 3)
    }

    func test_l22_wireUnknownName_isRefused() throws
    {
        let configuration = try SPFNEventStreamConfiguration(events: ["sessionActivity", "sessionUnread"])
        XCTAssertTrue(["sessionActivity", "sessionUnread"].allSatisfy(configuration.contains))
        XCTAssertFalse(["sessionActivity", "state.billing.changed"].allSatisfy(configuration.contains))
    }

    func test_l23_wireOverflow_dropsFramesKeepsConnectionSignals()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 2)
        let slow = hub.attachWire(["sessionActivity"])
        hub.connected(Self.connected(1))
        hub.subscription(name: "sessionActivity", status: .revoked)
        for index in 0 ..< 3
        {
            hub.deliver(name: "sessionActivity", data: envelope("s-\(index)", "w-1"))
        }
        XCTAssertEqual(slow.pending, [.attached, .opened(Self.connected(1)), .subscription(name: "sessionActivity", status: .revoked), .overflow])
        hub.deliver(name: "sessionActivity", data: envelope("s-next", "w-1"))
        XCTAssertEqual(slow.pending.last, .frame(name: "sessionActivity", value: value("s-next", "w-1")))
    }

    func test_l24_wireUnavailable_namesItsMissingNamesOnce()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 4)
        let listener = hub.attachWire(Self.both)
        let other = hub.attachWire(["sessionActivity"])
        hub.markUnavailable(["sessionUnread"])
        hub.markUnavailable(["sessionUnread"])
        XCTAssertEqual(listener.pending, [.attached, .unavailable(["sessionUnread"])])
        XCTAssertEqual(other.pending, [.attached])
        XCTAssertEqual(hub.attachWire(Self.both).pending, [.attached, .unavailable(["sessionUnread"])])
    }

    func test_l25_subscription_reachesWireListenersOfThatNameOnly()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 4)
        let screen = activity(hub)
        let wire = hub.attachWire(["sessionActivity"])
        let unreadWire = hub.attachWire(["sessionUnread"])
        hub.subscription(name: "sessionActivity", status: .revoked)
        hub.subscription(name: "sessionActivity", status: .granted)
        XCTAssertEqual(wire.pending, [
            .attached,
            .subscription(name: "sessionActivity", status: .revoked),
            .subscription(name: "sessionActivity", status: .granted),
        ])
        XCTAssertEqual(unreadWire.pending, [.attached])
        XCTAssertEqual(screen.pending, [attached])
        XCTAssertEqual(hub.diagnostics, SPFNEventDiagnostics())
    }

    func test_l26_wireOpened_dropsOldConnectionsSignals()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 4)
        let listener = hub.attachWire(Self.both)
        hub.connected(Self.connected(1))
        hub.deliver(name: "sessionActivity", data: envelope("s-1", "w-1"))
        hub.subscription(name: "sessionUnread", status: .granted)
        hub.markUnavailable(["sessionUnread"])
        hub.connected(Self.connected(2))
        XCTAssertEqual(listener.pending, [.attached, .unavailable(["sessionUnread"]), .opened(Self.connected(2))])
    }
}
