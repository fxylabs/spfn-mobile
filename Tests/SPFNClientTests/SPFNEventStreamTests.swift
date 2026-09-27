// SPFN Mobile — the whole event stream over the fakes (docs/architecture/event-stream-design.md §9-2).
//
// The token call goes through the real `execute` and a real session; the stream goes
// through the fake stream transport; timers wait on the manual sleeper until a test fires
// them. What is asserted is what reaches each boundary — signed headers on the token call,
// the exact stream URL — and what a listener receives. The Kotlin suite carries the same
// names.

import XCTest
@testable import SPFNClient
import SPFNCore
import SPFNGenerated

final class SPFNEventStreamTests: XCTestCase
{
    private static func decodeSessionID(_ value: SPFNCanonicalValue) throws -> String
    {
        try SPFNDecoding.string(SPFNDecoding.object(value, at: "$")["sessionId"], at: "$.sessionId")
    }

    func test_endToEnd_fakeTransport_opensDeliversReconnectsAndSignsOut() async throws
    {
        let fixture = try EventStreamFixture()
        let first = fixture.streams.enqueue()
        let log = SignalLog<String>()
        let listening = log.consume(fixture.events.listen("sessionActivity", decode: { try Self.decodeSessionID($0) }))

        let connected = await fixture.connect()
        XCTAssertTrue(connected)
        let tokenRequest = try XCTUnwrap(fixture.tokens.tokenRequests.first)
        XCTAssertEqual(tokenRequest.method, "POST")
        XCTAssertEqual(tokenRequest.url, "https://example.invalid/events/token")
        XCTAssertTrue(tokenRequest.headers.contains { $0.0 == SPFNWireHeaders.session }, "the token call is signed")

        let streamRequest = try XCTUnwrap(fixture.streams.requests.first)
        XCTAssertEqual(streamRequest.method, "GET")
        XCTAssertEqual(streamRequest.url, "https://example.invalid/events/stream?token=token-1&events=sessionActivity,sessionUnread")
        XCTAssertTrue(streamRequest.headers.contains { $0 == ("accept", "text/event-stream") })
        XCTAssertFalse(streamRequest.headers.contains { $0.0 == SPFNWireHeaders.session }, "the stream GET is not signed")

        first.send(ServerFrames.connected)
        let opened = await eventually { log.all.count == 2 }
        XCTAssertTrue(opened)
        XCTAssertEqual(fixture.events.state, .open(epoch: 1))
        XCTAssertEqual(log.all, [.reread(.attached), .reread(.opened(epoch: 1))])

        first.send(ServerFrames.ping + ServerFrames.activity(1, sessionID: "s-1", wsID: "w-1"))
        let framed = await eventually { log.all.last == .frame("s-1") }
        XCTAssertTrue(framed)

        let second = fixture.streams.enqueue()
        first.fail()
        let retrying = await eventually { fixture.events.state == .retrying(attempt: 2, delayMillis: 1_000, reason: .network) }
        XCTAssertTrue(retrying)
        let retryHeld = await eventually { fixture.sleeper.pending.contains(1_000) }
        XCTAssertTrue(retryHeld)
        fixture.sleeper.fire(1_000)
        let reconnected = await eventually { fixture.streams.requests.count == 2 }
        XCTAssertTrue(reconnected)
        XCTAssertTrue(fixture.streams.requests.last?.url.contains("token=token-2&") ?? false)
        second.send(ServerFrames.connected)
        let reopened = await eventually { log.all.last == .reread(.opened(epoch: 2)) }
        XCTAssertTrue(reopened)
        XCTAssertEqual(fixture.events.state, .open(epoch: 2))

        fixture.events.setSignedIn(nil)
        let signedOut = await eventually { fixture.events.state == .idle(.signedOut) && second.cancelled }
        XCTAssertTrue(signedOut)
        listening.cancel()
    }

    func test_endToEnd_silenceWatchdog_reconnects() async throws
    {
        let fixture = try EventStreamFixture()
        let quiet = fixture.streams.enqueue()
        let isOpen = await fixture.open(quiet)
        XCTAssertTrue(isOpen)
        let watched = await eventually { fixture.sleeper.pending.contains(25_000) }
        XCTAssertTrue(watched)
        fixture.sleeper.fire(25_000)
        let dropped = await eventually { fixture.events.state == .retrying(attempt: 2, delayMillis: 1_000, reason: .silence) }
        XCTAssertTrue(dropped)
        XCTAssertTrue(quiet.cancelled)
    }

    func test_endToEnd_token429_honoursRetryAfter() async throws
    {
        let fixture = try EventStreamFixture()
        fixture.tokens.script(SPFNTransportResponse(
            statusCode: 429,
            headers: [("content-type", "application/json"), ("Retry-After", "12")] + SPFNTransportResponse.announcement(),
            body: Array(ExecuteFixtures.errorEnvelope(code: "TooManyRequestsError").utf8)
        ))
        fixture.events.setSignedIn(SessionFixtureValues.clientID)
        fixture.events.setForeground(true)
        let limited = await eventually { fixture.events.state == .retrying(attempt: 2, delayMillis: 12_000, reason: .serverError(status: 429)) }
        XCTAssertTrue(limited)
        let held = await eventually { fixture.sleeper.pending.contains(12_000) }
        XCTAssertTrue(held)
        XCTAssertEqual(fixture.tokens.tokenRequests.count, 1)
        fixture.sleeper.fire(12_000)
        let retried = await eventually { fixture.tokens.tokenRequests.count == 2 }
        XCTAssertTrue(retried)
    }

    /// Defect anticipation, the Swift half: the engine is an actor, every listener's
    /// `AsyncStream` is ended exactly once, and nothing the engine starts holds the stream
    /// object — so letting go of it ends the loop, the timers and the listeners.
    func test_release_endsListenersAndStates() async throws
    {
        var fixture: EventStreamFixture? = try EventStreamFixture()
        let connection = try XCTUnwrap(fixture).streams.enqueue()
        let isOpen = await fixture?.open(connection) ?? false
        XCTAssertTrue(isOpen)
        let released = WeakReference(fixture?.events)
        let states = try XCTUnwrap(fixture).events.states
        let statesEnded = Task
        {
            for await _ in states {}
            return true
        }
        let listening = SignalLog<Activity>().consume(try XCTUnwrap(fixture).events.listen(Activity.self))
        fixture = nil
        let gone = await eventually { released.value == nil }
        XCTAssertTrue(gone, "the stream object outlived its owner: something holds it")
        let ended = await statesEnded.value
        XCTAssertTrue(ended)
        listening.cancel()
        await listening.value
    }

    func test_listeners_neverReachMachine() async throws
    {
        let fixture = try EventStreamFixture()
        let connection = fixture.streams.enqueue()
        let isOpen = await fixture.open(connection)
        XCTAssertTrue(isOpen)
        let random = SeededGeneratorBox(seed: SPFNEventStreamPropertyTests.seed)
        var live: [Task<Void, Never>] = []
        for step in 0 ..< 1_000
        {
            if !live.isEmpty, random.int(2) == 0
            {
                live.remove(at: random.int(live.count)).cancel()
            }
            else
            {
                let passes = random.int(2) == 0
                live.append(SignalLog<String>().consume(fixture.events.listen("sessionActivity", decode: { try Self.decodeSessionID($0) }, where: { _ in passes })))
            }
            if step % 50 == 0
            {
                connection.send(ServerFrames.activity(step, sessionID: "s-\(step)", wsID: "w-1"))
            }
        }
        live.forEach { $0.cancel() }
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(fixture.tokens.tokenRequests.count, 1)
        XCTAssertEqual(fixture.streams.requests.count, 1)
        XCTAssertTrue(fixture.streams.requests.first?.url.hasSuffix("&events=sessionActivity,sessionUnread") ?? false)
        XCTAssertEqual(fixture.events.state, .open(epoch: 1))
    }

    // MARK: - The token call signs with the key signed in at the moment of the call

    /// E-41: a rotation leaves the open stream alone, and the next token call — here the
    /// reconnect after a drop — is signed by the new key, for the same account.
    func test_tokenCall_afterRotate_signsWithTheNewKey() async throws
    {
        let fixture = try EventStreamFixture(keyIDs: ["key-test-0002"])
        fixture.tokens.route(SPFNGeneratedOperations.authKeysRotate.path, .json(200, "{\"keyId\":\"key-test-0002\",\"success\":true}"))
        let first = fixture.streams.enqueue()
        let isOpen = await fixture.open(first)
        XCTAssertTrue(isOpen)
        XCTAssertEqual(Self.signer(of: fixture.tokens.tokenRequests.last), [SessionFixtureValues.clientID, "key-test-0001"])

        _ = try await fixture.keyLifecycle.rotate()
        XCTAssertEqual(fixture.events.state, .open(epoch: 1), "a rotation does not touch the connection")
        fixture.streams.enqueue()
        first.fail()
        let retryHeld = await eventually { fixture.sleeper.pending.contains(1_000) }
        XCTAssertTrue(retryHeld)
        fixture.sleeper.fire(1_000)
        let reconnected = await eventually { fixture.streams.requests.count == 2 }
        XCTAssertTrue(reconnected)
        XCTAssertEqual(fixture.tokens.tokenRequests.count, 2)
        XCTAssertEqual(Self.signer(of: fixture.tokens.tokenRequests.last), [SessionFixtureValues.clientID, "key-test-0002"])
    }

    /// E-43: another account's key is enrolled while the stream is open for the first; the
    /// token call that follows `setSignedIn(other)` signs as the other account.
    func test_tokenCall_afterSignedInAsOther_signsAsTheOtherAccount() async throws
    {
        let fixture = try EventStreamFixture(keyIDs: ["key-test-0002"])
        fixture.tokens.route(
            "/_auth/oauth/google/native",
            .json(200, "{\"mfaRequired\":false,\"isNewUser\":false,\"keyId\":\"key-test-0002\",\"userId\":\"client-test-0002\"}")
        )
        let first = fixture.streams.enqueue()
        let isOpen = await fixture.open(first)
        XCTAssertTrue(isOpen)

        try await fixture.keyLifecycle.wipe()
        _ = try await fixture.keyLifecycle.enroll(provider: "google") { _ in "id-token-test" }
        fixture.streams.enqueue()
        fixture.events.setSignedIn("client-test-0002")
        let reconnected = await eventually { fixture.streams.requests.count == 2 && first.cancelled }
        XCTAssertTrue(reconnected)
        XCTAssertEqual(fixture.tokens.tokenRequests.count, 2)
        XCTAssertEqual(Self.signer(of: fixture.tokens.tokenRequests.last), ["client-test-0002", "key-test-0002"])
    }

    /// E-40: after a wipe nothing is sent. The sign-out the attachment passes on leaves the
    /// stream idle; a sign-in input with no key behind it sends nothing either.
    func test_afterWipe_noTokenCall_idleSignedOut() async throws
    {
        let fixture = try EventStreamFixture()
        let first = fixture.streams.enqueue()
        let isOpen = await fixture.open(first)
        XCTAssertTrue(isOpen)
        let sentBefore = fixture.tokens.requests.count

        try await fixture.keyLifecycle.wipe()
        fixture.events.setSignedIn(await fixture.keyLifecycle.signedInClientID)
        let signedOut = await eventually { fixture.events.state == .idle(.signedOut) && first.cancelled }
        XCTAssertTrue(signedOut)

        fixture.events.setSignedIn(SessionFixtureValues.clientID)
        let refused = await eventually { fixture.events.state == .closed(.unauthorized) }
        XCTAssertTrue(refused)
        XCTAssertEqual(fixture.tokens.requests.count, sentBefore, "no handshake and no token call without a key")
        XCTAssertEqual(fixture.streams.requests.count, 1)
    }

    /// The client id and key id a signed request names.
    private static func signer(of request: SPFNTransportRequest?) -> [String?]
    {
        [SPFNWireHeaders.clientID, SPFNWireHeaders.keyID].map { name in request?.headers.first { $0.0 == name }?.1 }
    }

    func test_token_neverPrinted() async throws
    {
        let token = SPFNEventStreamToken(String(repeating: "f", count: 64))
        XCTAssertEqual("\(token)", "SPFNEventStreamToken(redacted)")
        XCTAssertFalse(String(reflecting: SPFNEventEffect.openStream(generation: 1, names: ["a"], token: token)).contains("ffff"))

        let fixture = try EventStreamFixture()
        fixture.streams.enqueue()
        let connected = await fixture.connect()
        XCTAssertTrue(connected)
        XCTAssertFalse("\(try XCTUnwrap(fixture.streams.requests.first))".contains("token-1"))
        XCTAssertFalse("\(fixture.events.state)".contains("token"))
    }
}
