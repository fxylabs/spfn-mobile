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
}
