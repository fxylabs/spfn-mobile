// SPFN Mobile — the E-table, cell by cell (docs/architecture/event-stream-design.md §4-1).
//
// Every test is named for its cell, and android/spfn-client/src/test/.../
// SpfnEventStreamMachineTest.kt carries the same names without the `test_` prefix;
// tools/validate/validate.sh compares the two lists. The machine is a pure function, so
// nothing here waits: an input goes in, a state and its effects come out.

import XCTest
@testable import SPFNClient

final class SPFNEventStreamMachineTests: XCTestCase
{
    static let clientA = "client-a"
    static let clientB = "client-b"
    static let envelope = "{\"event\":\"sessionActivity\",\"data\":{\"sessionId\":\"s-1\",\"wsId\":\"w-1\"}}"
    static let now: Int64 = 784_111_777_000
    static let connected = SPFNSSEEvent(name: "connected", data: "{\"subscribedEvents\":[\"sessionActivity\"],\"timestamp\":1}")
    static let token = SPFNEventStreamToken(String(repeating: "a", count: 64))

    /// A run of inputs from the initial state; `effects` are the last step's.
    final class Run
    {
        let machine: SPFNEventStreamMachine
        var state: SPFNEventMachineState
        var effects: [SPFNEventEffect] = []

        init() throws
        {
            machine = SPFNEventStreamMachine(
                configuration: try SPFNEventStreamConfiguration(events: ["sessionUnread", "sessionActivity"]),
                jitter: { 1.0 }
            )
            state = machine.initial()
        }

        var generation: Int64
        {
            state.generation
        }

        var publicState: SPFNEventStreamState
        {
            state.publicState
        }

        @discardableResult
        func feed(_ input: SPFNEventInput) -> Run
        {
            let step = machine.step(state, input)
            state = step.state
            effects = step.effects
            return self
        }

        @discardableResult
        func issued(_ make: (Int64) -> SPFNEventInput) -> Run
        {
            feed(make(generation))
        }

        func signedInForeground() -> Run
        {
            feed(.setSignedIn(clientA)).feed(.setForeground(true))
        }

        func streaming() -> Run
        {
            signedInForeground().issued { .tokenMinted(generation: $0, token: token) }
        }

        func headers() -> Run
        {
            streaming().issued { .streamAnswered(generation: $0, answer: .eventStream) }
        }

        func open() -> Run
        {
            headers().issued { .frameReceived(generation: $0, event: connected) }
        }

        func retrying() -> Run
        {
            signedInForeground().issued { .tokenFailed(generation: $0, failure: .network) }
        }

        func offline() -> Run
        {
            retrying().feed(.setNetworkAvailable(false))
        }

        func closed() -> Run
        {
            signedInForeground().issued { .tokenFailed(generation: $0, failure: .unauthorized) }
        }

        var retryDelay: Int64?
        {
            guard case .retrying(_, let delay, _) = publicState
            else
            {
                return nil
            }
            return delay
        }
    }

    private func run() throws -> Run
    {
        try Run()
    }

    private func assertMints(_ run: Run, file: StaticString = #filePath, line: UInt = #line)
    {
        XCTAssertTrue(run.effects.contains(.mintToken(generation: run.generation)), "expected a token call in \(run.effects)", file: file, line: line)
    }

    private func assertAbandons(_ run: Run, file: StaticString = #filePath, line: UInt = #line)
    {
        for effect in SPFNEventEffect.abandon
        {
            XCTAssertTrue(run.effects.contains(effect), "expected \(effect) in \(run.effects)", file: file, line: line)
        }
    }

    private func answer(_ status: Int, _ headers: [(String, String)] = [], body: String = "") -> SPFNStreamAnswer
    {
        SPFNEventStreamServices.streamAnswer(status: status, headers: headers, body: Array(body.utf8), nowMillis: Self.now)
    }

    func test_e1_signedInForeground_mintsToken() throws
    {
        let foregroundLast = try run().signedInForeground()
        XCTAssertEqual(foregroundLast.publicState, .connecting(attempt: 1))
        assertMints(foregroundLast)

        let signedInLast = try run().feed(.setForeground(true)).feed(.setSignedIn(Self.clientA))
        XCTAssertEqual(signedInLast.publicState, .connecting(attempt: 1))
        assertMints(signedInLast)
    }

    func test_e3_token_opensStreamWithSortedEvents() throws
    {
        let run = try run().streaming()
        XCTAssertEqual(run.publicState, .connecting(attempt: 1))
        XCTAssertEqual(run.effects, [.openStream(generation: run.generation, names: ["sessionActivity", "sessionUnread"], token: Self.token)])
    }

    func test_e4_tokenAuthRefusal_closesUnauthorized() throws
    {
        let run = try run().closed()
        XCTAssertEqual(run.publicState, .closed(.unauthorized))
        XCTAssertFalse(run.effects.contains { if case .startRetryTimer = $0 { return true } else { return false } })
    }

    func test_e5_token403_closesForbidden() throws
    {
        let run = try run().signedInForeground().issued { .tokenFailed(generation: $0, failure: .forbidden) }
        XCTAssertEqual(run.publicState, .closed(.forbidden))
    }

    func test_e6_token5xxOr429_retriesServerError() throws
    {
        for status in [500, 503, 429]
        {
            let run = try run().signedInForeground().issued { .tokenFailed(generation: $0, failure: .serverError(status: status, retryAfterMillis: nil)) }
            XCTAssertEqual(run.publicState, .retrying(attempt: 2, delayMillis: 1_000, reason: .serverError(status: status)))
            XCTAssertTrue(run.effects.contains(.startRetryTimer(generation: run.generation, millis: 1_000)))
        }
    }

    func test_e7_tokenTransport_retriesNetwork() throws
    {
        XCTAssertEqual(try run().retrying().publicState, .retrying(attempt: 2, delayMillis: 1_000, reason: .network))

        let offline = try run().signedInForeground()
            .feed(.setNetworkAvailable(false))
            .issued { .tokenFailed(generation: $0, failure: .network) }
        XCTAssertEqual(offline.publicState, .offline(attempt: 2))
    }

    func test_e8_tokenNotAnEnvelope_retriesUnreadable() throws
    {
        let failure = SPFNEventStreamServices.tokenFailure(SPFNClientError.decoding(.notAnErrorEnvelope, onSuccessStatus: false), nowMillis: 0)
        XCTAssertEqual(failure, .unreadable)
        let run = try run().signedInForeground().issued { .tokenFailed(generation: $0, failure: failure) }
        XCTAssertEqual(run.publicState, .retrying(attempt: 2, delayMillis: 1_000, reason: .unreadable))
    }

    func test_e9_token2xxUnreadable_retriesUnreadable() throws
    {
        let failure = SPFNEventStreamServices.tokenFailure(SPFNClientError.decoding(.notTheDeclaredResponse, onSuccessStatus: true), nowMillis: 0)
        XCTAssertEqual(failure, .unreadable)
        let run = try run().signedInForeground().issued { .tokenFailed(generation: $0, failure: failure) }
        XCTAssertEqual(run.publicState, .retrying(attempt: 2, delayMillis: 1_000, reason: .unreadable))
    }

    func test_e10_signOutDuringToken_idlesSignedOut() throws
    {
        let run = try run().signedInForeground().feed(.setSignedIn(nil))
        XCTAssertEqual(run.publicState, .idle(.signedOut))
        XCTAssertTrue(run.effects.contains(.cancelToken))
    }

    func test_e11_backgroundDuringToken_idlesBackground() throws
    {
        let run = try run().signedInForeground().feed(.setForeground(false))
        XCTAssertEqual(run.publicState, .idle(.background))
        XCTAssertTrue(run.effects.contains(.cancelToken))
    }

    func test_e12_eventStreamHeaders_startWatchdog_notYetOpen() throws
    {
        let run = try run().headers()
        XCTAssertEqual(run.publicState, .connecting(attempt: 1))
        XCTAssertEqual(run.effects, [.startSilenceTimer(generation: run.generation, millis: 25_000)])
    }

    func test_e13_connected_opensNewEpoch_rereadsListeners() throws
    {
        let run = try run().open()
        XCTAssertEqual(run.publicState, .open(epoch: 1))
        XCTAssertTrue(run.effects.contains(.reread(epoch: 1)))
        XCTAssertTrue(run.effects.contains(.startStableTimer(generation: run.generation, millis: 30_000)))
        XCTAssertTrue(run.effects.contains(.publish(.open(epoch: 1))))
    }

    func test_e14_notEventStream_retriesUnreadable() throws
    {
        let notStream = answer(200, [("content-type", "text/html")])
        XCTAssertEqual(notStream, .notEventStream)
        let run = try run().streaming().issued { .streamAnswered(generation: $0, answer: notStream) }
        XCTAssertEqual(run.publicState, .retrying(attempt: 2, delayMillis: 1_000, reason: .unreadable))
        XCTAssertTrue(run.effects.contains(.closeStream))
    }

    func test_e15_stream401_retriesTokenOnce_thenBacksOff() throws
    {
        XCTAssertEqual(answer(401), .tokenRejected)
        let first = try run().streaming().issued { .streamAnswered(generation: $0, answer: .tokenRejected) }
        XCTAssertEqual(first.publicState, .connecting(attempt: 1))
        assertMints(first)

        let second = first.issued { .tokenMinted(generation: $0, token: Self.token) }
            .issued { .streamAnswered(generation: $0, answer: .tokenRejected) }
        XCTAssertEqual(second.publicState, .retrying(attempt: 2, delayMillis: 1_000, reason: .tokenRejected))
    }

    func test_e16_invalidEvents_reconnectsWithValidEvents() throws
    {
        let refusal = answer(400, body: "{\"error\":\"Invalid event names\",\"invalidEvents\":[\"sessionUnread\"],\"validEvents\":[\"other\",\"sessionActivity\"]}")
        XCTAssertEqual(refusal, .invalidEvents(invalid: ["sessionUnread"], valid: ["other", "sessionActivity"]))

        let narrowed = try run().streaming().issued { .streamAnswered(generation: $0, answer: refusal) }
        XCTAssertEqual(narrowed.publicState, .connecting(attempt: 1))
        assertMints(narrowed)
        XCTAssertTrue(narrowed.effects.contains(.markUnavailable(["sessionUnread"])))

        let reopened = narrowed.issued { .tokenMinted(generation: $0, token: Self.token) }
        XCTAssertEqual(reopened.effects, [.openStream(generation: reopened.generation, names: ["sessionActivity"], token: Self.token)])

        let open = reopened.issued { .streamAnswered(generation: $0, answer: .eventStream) }
            .issued { .frameReceived(generation: $0, event: Self.connected) }
        XCTAssertEqual(open.publicState, .open(epoch: 1, unavailableEvents: ["sessionUnread"]))
    }

    func test_e17_otherBadRequest_closesUnknownEventsEmpty() throws
    {
        let refusal = answer(400, body: "{\"error\":\"Missing events parameter\"}")
        XCTAssertEqual(refusal, .badRequest)
        let run = try run().streaming().issued { .streamAnswered(generation: $0, answer: refusal) }
        XCTAssertEqual(run.publicState, .closed(.unknownEvents([])))
    }

    func test_e18_stream403_closesForbidden() throws
    {
        let run = try run().streaming().issued { .streamAnswered(generation: $0, answer: answer(403)) }
        XCTAssertEqual(run.publicState, .closed(.forbidden))
    }

    func test_e19_stream5xxOr3xx_retriesServerError() throws
    {
        for status in [302, 500, 502, 429]
        {
            let run = try run().streaming().issued { .streamAnswered(generation: $0, answer: answer(status)) }
            XCTAssertEqual(run.publicState, .retrying(attempt: 2, delayMillis: 1_000, reason: .serverError(status: status)))
        }
    }

    func test_e20_streamTransportError_retriesNetwork() throws
    {
        let run = try run().streaming().issued { .streamFailed(generation: $0) }
        XCTAssertEqual(run.publicState, .retrying(attempt: 2, delayMillis: 1_000, reason: .network))
    }

    func test_e21_silenceBeforeConnected_retriesSilence() throws
    {
        let run = try run().headers().issued { .silenceElapsed(generation: $0) }
        XCTAssertEqual(run.publicState, .retrying(attempt: 2, delayMillis: 1_000, reason: .silence))
        XCTAssertTrue(run.effects.contains(.closeStream))
    }

    func test_e22_configuredFrame_deliversAndRestartsWatchdog() throws
    {
        let run = try run().open().issued { .frameReceived(generation: $0, event: SPFNSSEEvent(name: "sessionActivity", data: Self.envelope)) }
        XCTAssertEqual(run.publicState, .open(epoch: 1))
        XCTAssertEqual(run.effects, [.deliver(name: "sessionActivity", data: Self.envelope), .startSilenceTimer(generation: run.generation, millis: 25_000)])
    }

    func test_e23_unconfiguredFrame_countsUnexpected() throws
    {
        let run = try run().open().issued { .frameReceived(generation: $0, event: SPFNSSEEvent(name: "somethingElse", data: Self.envelope)) }
        XCTAssertEqual(run.effects, [.countUnexpected(name: "somethingElse"), .startSilenceTimer(generation: run.generation, millis: 25_000)])

        // A configured name nobody listens to is delivered and dropped by the hub, uncounted.
        let hub = SPFNEventListenerHub(deliveryBuffer: 4)
        hub.deliver(name: "sessionActivity", data: Self.envelope)
        XCTAssertEqual(hub.diagnostics, SPFNEventDiagnostics())
    }

    func test_e24_oneDecoderThrows_dropsForThatListenerOnly()
    {
        let hub = SPFNEventListenerHub(deliveryBuffer: 4)
        let failing = hub.attach("sessionActivity", decode: { _ -> String in throw SPFNTransportError.timedOut }, condition: { _ in true })
        let reading = hub.attach("sessionActivity", decode: { "\($0)" }, condition: { _ in true })
        hub.deliver(name: "sessionActivity", data: Self.envelope)
        XCTAssertEqual(hub.diagnostics.droppedFrames, 1)
        XCTAssertEqual(failing.pending, [.reread(.attached)])
        XCTAssertEqual(reading.pending.count, 2)
    }

    func test_e25_ping_restartsWatchdogOnly() throws
    {
        let run = try run().open().issued { .frameReceived(generation: $0, event: SPFNSSEEvent(name: "ping", data: "{\"timestamp\":1}")) }
        XCTAssertEqual(run.effects, [.startSilenceTimer(generation: run.generation, millis: 25_000)])
    }

    func test_e26_commentOrBlankLine_restartsWatchdog() throws
    {
        var parser = SPFNSSELineParser()
        XCTAssertEqual(parser.feed(Array(": keep-alive\n\nretry: 5\n\n".utf8)), [])
        let run = try run().open().issued { .bytesReceived(generation: $0) }
        XCTAssertEqual(run.effects, [.startSilenceTimer(generation: run.generation, millis: 25_000)])
    }

    func test_e27_silenceWhenOpen_retriesSilence_fromOneWhenStable() throws
    {
        let unstable = try run().open().issued { .silenceElapsed(generation: $0) }
        XCTAssertEqual(unstable.publicState, .retrying(attempt: 2, delayMillis: 1_000, reason: .silence))
        XCTAssertTrue(unstable.effects.contains(.closeStream))

        let stable = try run().open().issued { .stableElapsed(generation: $0) }.issued { .silenceElapsed(generation: $0) }
        XCTAssertEqual(stable.publicState, .retrying(attempt: 1, delayMillis: 1_000, reason: .silence))
    }

    func test_e28_cleanEnd_retriesServerClosed() throws
    {
        let run = try run().open().issued { .streamEnded(generation: $0) }
        XCTAssertEqual(run.publicState, .retrying(attempt: 2, delayMillis: 1_000, reason: .serverClosed))
    }

    func test_e29_streamErrorWhenOpen_retriesNetwork() throws
    {
        let run = try run().open().issued { .streamFailed(generation: $0) }
        XCTAssertEqual(run.publicState, .retrying(attempt: 2, delayMillis: 1_000, reason: .network))
    }

    func test_e33_retryTimer_mintsToken() throws
    {
        let run = try run().retrying().issued { .retryElapsed(generation: $0) }
        XCTAssertEqual(run.publicState, .connecting(attempt: 2))
        assertMints(run)
    }

    func test_e34_networkLostWhileRetrying_goesOffline() throws
    {
        let run = try run().offline()
        XCTAssertEqual(run.publicState, .offline(attempt: 2))
        XCTAssertTrue(run.effects.contains(.cancelTimers))
    }

    func test_e35_networkBackWhileOffline_mintsAtOnce() throws
    {
        let run = try run().offline().feed(.setNetworkAvailable(true))
        XCTAssertEqual(run.publicState, .connecting(attempt: 2))
        assertMints(run)
    }

    func test_e36_networkLostWhileOpen_staysOpen() throws
    {
        let run = try run().open().feed(.setNetworkAvailable(false))
        XCTAssertEqual(run.publicState, .open(epoch: 1))
        XCTAssertEqual(run.effects, [])
    }

    func test_e37_background_closesEverything() throws
    {
        for start in [try run().streaming(), try run().open(), try run().retrying(), try run().offline()]
        {
            start.feed(.setForeground(false))
            XCTAssertEqual(start.publicState, .idle(.background))
            assertAbandons(start)
        }
    }

    func test_e38_foregroundAgain_mintsWithoutBackoff() throws
    {
        let run = try run().open().feed(.setForeground(false)).feed(.setForeground(true))
        XCTAssertEqual(run.publicState, .connecting(attempt: 1))
        assertMints(run)

        let offline = try self.run().open().feed(.setForeground(false)).feed(.setNetworkAvailable(false)).feed(.setForeground(true))
        XCTAssertEqual(offline.publicState, .offline(attempt: 1))
    }

    func test_e39_lateResults_ignoredInIdleAndClosed() throws
    {
        for start in [try run().open().feed(.setForeground(false)), try run().closed()]
        {
            let stale = start.generation - 1
            let before = start.state
            for late in Self.lateInputs(stale)
            {
                start.feed(late)
                XCTAssertEqual(start.state, before)
                XCTAssertEqual(start.effects, [])
            }
        }
    }

    func test_e40_signOut_closesOpenStream() throws
    {
        for start in [try run().streaming(), try run().open(), try run().retrying(), try run().offline(), try run().closed()]
        {
            start.feed(.setSignedIn(nil))
            XCTAssertEqual(start.publicState, .idle(.signedOut))
            assertAbandons(start)
        }
    }

    func test_e41_keyRotation_changesNothing() throws
    {
        // A rotation keeps the client id, so the lifecycle's value does not move and the
        // only input it could produce is the same id again.
        let run = try run().open().feed(.setSignedIn(Self.clientA))
        XCTAssertEqual(run.publicState, .open(epoch: 1))
        XCTAssertEqual(run.effects, [])
    }

    func test_e42_revokedSession_tokenThroughExecute() throws
    {
        // Sessionless (the default) the refusal is the answer; with a session-guarded token
        // route execute re-handshakes once first. A refusal closes, and the wipe that
        // `noteSessionRevoked` performs arrives next as a sign-out.
        XCTAssertEqual(try run().closed().feed(.setSignedIn(nil)).publicState, .idle(.signedOut))
        XCTAssertEqual(try run().signedInForeground().feed(.setSignedIn(nil)).publicState, .idle(.signedOut))
    }

    func test_e43_accountSwitch_reconnectsWithNewToken() throws
    {
        let starts = [try run().signedInForeground(), try run().streaming(), try run().open(), try run().retrying(), try run().offline(), try run().closed()]
        for start in starts
        {
            start.feed(.setNetworkAvailable(true)).feed(.setSignedIn(Self.clientB))
            XCTAssertEqual(start.publicState, .connecting(attempt: 1))
            assertAbandons(start)
            assertMints(start)
        }
        let offline = try run().open().feed(.setNetworkAvailable(false)).feed(.setSignedIn(Self.clientB))
        XCTAssertEqual(offline.publicState, .offline(attempt: 1))
    }

    func test_e44_closed_leavesOnlyThroughBackground() throws
    {
        let run = try run().closed().feed(.setForeground(false))
        XCTAssertEqual(run.publicState, .idle(.background))
        run.feed(.setForeground(true))
        XCTAssertEqual(run.publicState, .connecting(attempt: 1))
        assertMints(run)
    }

    func test_e45_closed_ignoresNetworkAndRepeatedInputs() throws
    {
        let run = try run().closed()
        for input: SPFNEventInput in [.setNetworkAvailable(false), .setNetworkAvailable(true), .setForeground(true), .setSignedIn(Self.clientA)]
        {
            run.feed(input)
            XCTAssertEqual(run.publicState, .closed(.unauthorized))
            XCTAssertEqual(run.effects, [])
        }
    }

    func test_e46_repeatedInputsWhenOpen_areIdempotent() throws
    {
        let run = try run().open()
        for input: SPFNEventInput in [.setForeground(true), .setSignedIn(Self.clientA), .setNetworkAvailable(true)]
        {
            run.feed(input)
            XCTAssertEqual(run.publicState, .open(epoch: 1))
            XCTAssertEqual(run.effects, [])
        }
    }

    func test_e47_stableTimer_resetsAttempt() throws
    {
        let flaky = try run().retrying().issued { .retryElapsed(generation: $0) }
            .issued { .tokenMinted(generation: $0, token: Self.token) }
            .issued { .streamAnswered(generation: $0, answer: .eventStream) }
            .issued { .frameReceived(generation: $0, event: Self.connected) }
        XCTAssertEqual(flaky.state.attempt, 2)
        flaky.issued { .stableElapsed(generation: $0) }
        XCTAssertEqual(flaky.publicState, .open(epoch: 1))
        XCTAssertEqual(flaky.effects, [])
        XCTAssertEqual(flaky.state.attempt, 1)
    }

    func test_e48_conditionsMetWithoutNetwork_goesOffline() throws
    {
        let run = try run().feed(.setNetworkAvailable(false)).signedInForeground()
        XCTAssertEqual(run.publicState, .offline(attempt: 1))
        XCTAssertFalse(run.effects.contains { if case .mintToken = $0 { return true } else { return false } })
    }

    func test_e49_signedOutIdle_recordsConditions() throws
    {
        let run = try run().feed(.setForeground(true)).feed(.setNetworkAvailable(false))
        XCTAssertEqual(run.publicState, .idle(.signedOut))
        XCTAssertEqual(run.effects, [])
        run.feed(.setSignedIn(Self.clientA))
        XCTAssertEqual(run.publicState, .offline(attempt: 1))
    }

    func test_e50_signOutInBackground_changesReasonOnly() throws
    {
        let background = try run().open().feed(.setForeground(false))
        background.feed(.setSignedIn(Self.clientB))
        XCTAssertEqual(background.publicState, .idle(.background))
        XCTAssertEqual(background.effects, [])

        background.feed(.setSignedIn(nil))
        XCTAssertEqual(background.publicState, .idle(.signedOut))
        XCTAssertEqual(background.effects, [.publish(.idle(.signedOut))])
    }

    func test_e51_networkLostDuringConnecting_letsCallFinish() throws
    {
        for start in [try run().signedInForeground(), try run().streaming()]
        {
            start.feed(.setNetworkAvailable(false))
            XCTAssertEqual(start.publicState, .connecting(attempt: 1))
            XCTAssertEqual(start.effects, [])
        }
        let failed = try run().streaming().feed(.setNetworkAvailable(false)).issued { .streamFailed(generation: $0) }
        XCTAssertEqual(failed.publicState, .offline(attempt: 2))
    }

    func test_e52_invalidEventsWithEmptyIntersection_closesUnknownEvents() throws
    {
        let empty = SPFNStreamAnswer.invalidEvents(invalid: ["sessionUnread", "sessionActivity"], valid: ["other"])
        let run = try run().streaming().issued { .streamAnswered(generation: $0, answer: empty) }
        XCTAssertEqual(run.publicState, .closed(.unknownEvents(["sessionActivity", "sessionUnread"])))

        // A server that refuses names it also calls valid cannot narrow anything: closed, no loop.
        let contradictory = SPFNStreamAnswer.invalidEvents(invalid: ["sessionUnread"], valid: ["sessionActivity", "sessionUnread"])
        let closed = try self.run().streaming().issued { .streamAnswered(generation: $0, answer: contradictory) }
        XCTAssertEqual(closed.publicState, .closed(.unknownEvents(["sessionUnread"])))
    }

    func test_e53_retryAfterSeconds_extendsDelay() throws
    {
        let wait = SPFNRetryAfter.millis("7", nowMillis: Self.now)
        XCTAssertEqual(wait, 7_000)
        XCTAssertEqual(try run().signedInForeground().issued { .tokenFailed(generation: $0, failure: .serverError(status: 429, retryAfterMillis: wait)) }.retryDelay, 7_000)

        let limited = answer(429, [("Retry-After", "7")])
        XCTAssertEqual(try run().streaming().issued { .streamAnswered(generation: $0, answer: limited) }.retryDelay, 7_000)

        // Shorter than the backoff: the backoff stands.
        XCTAssertEqual(try run().signedInForeground().issued { .tokenFailed(generation: $0, failure: .serverError(status: 429, retryAfterMillis: 200)) }.retryDelay, 1_000)
    }

    func test_e54_retryAfterHttpDate_extendsDelay() throws
    {
        // `now` is Sun, 06 Nov 1994 08:49:37 GMT; the header asks for 90 seconds after it.
        XCTAssertEqual(SPFNRetryAfter.millis("Sun, 06 Nov 1994 08:51:07 GMT", nowMillis: Self.now), 90_000)
        XCTAssertEqual(SPFNRetryAfter.millis("Sun, 06 Nov 1994 08:00:00 GMT", nowMillis: Self.now), 0)

        let limited = answer(429, [("retry-after", "Sun, 06 Nov 1994 08:51:07 GMT")])
        XCTAssertEqual(try run().streaming().issued { .streamAnswered(generation: $0, answer: limited) }.retryDelay, 90_000)
    }

    func test_e55_retryAfterMalformed_isIgnored() throws
    {
        for malformed in ["", "soon", "-5", "1.5", "tomorrow"]
        {
            XCTAssertNil(SPFNRetryAfter.millis(malformed, nowMillis: Self.now), malformed)
            let limited = answer(429, [("Retry-After", malformed)])
            XCTAssertEqual(try run().streaming().issued { .streamAnswered(generation: $0, answer: limited) }.retryDelay, 1_000)
        }
        XCTAssertNil(SPFNRetryAfter.millis(nil, nowMillis: Self.now))
    }

    func test_e56_retryAfterAboveCap_isCapped() throws
    {
        XCTAssertEqual(SPFNRetryAfter.millis("3600", nowMillis: Self.now), 300_000)
        XCTAssertEqual(SPFNRetryAfter.millis("99999999999999999999", nowMillis: Self.now), 300_000)
        XCTAssertEqual(SPFNRetryAfter.millis("Mon, 07 Nov 1994 08:49:37 GMT", nowMillis: Self.now), 300_000)
        XCTAssertEqual(try run().signedInForeground().issued { .tokenFailed(generation: $0, failure: .serverError(status: 429, retryAfterMillis: 9_000_000)) }.retryDelay, 300_000)
    }

    // MARK: - The signed-in boundary (state-client-design §2-14)

    /// W-23: the connected value names the account its connection was opened for — A for
    /// A's, B for the one an account switch begins (E-43).
    func test_w23_opened_namesTheAccountOfItsConnection() throws
    {
        let run = try run().open()
        XCTAssertEqual(Self.connectedValue(run.effects)?.clientID, Self.clientA)
        let switched = run.feed(.setSignedIn(Self.clientB))
            .issued { .tokenMinted(generation: $0, token: Self.token) }
            .issued { .streamAnswered(generation: $0, answer: .eventStream) }
            .issued { .frameReceived(generation: $0, event: Self.connected) }
        XCTAssertEqual(switched.publicState, .open(epoch: 2))
        XCTAssertEqual(Self.connectedValue(switched.effects)?.clientID, Self.clientB)
    }

    private static func connectedValue(_ effects: [SPFNEventEffect]) -> SPFNStreamConnected?
    {
        for case .connected(let connected) in effects
        {
            return connected
        }
        return nil
    }

    // MARK: - Revision 4 (state-client-design §5-1)

    static let query = ["workspaceId": "w-1"]

    private func isRetryTimer(_ effect: SPFNEventEffect) -> Bool
    {
        guard case .startRetryTimer = effect
        else
        {
            return false
        }
        return true
    }

    private static func subscriptionFrame(_ status: String, _ name: String) -> SPFNSSEEvent
    {
        SPFNSSEEvent(name: "subscription.\(status)", data: "{\"event\":\"\(name)\"}")
    }

    func test_e57_setQueryWhenOpen_reopensAtOnceWithNewToken() throws
    {
        let open = try run().open()
        let before = open.generation
        let reopened = open.feed(.setQuery(Self.query))
        XCTAssertEqual(reopened.publicState, .connecting(attempt: 1))
        XCTAssertGreaterThan(reopened.generation, before)
        assertAbandons(reopened)
        assertMints(reopened)
        XCTAssertFalse(reopened.effects.contains(where: isRetryTimer), "no backoff before the reopen")

        let streaming = reopened.issued { .tokenMinted(generation: $0, token: Self.token) }
        XCTAssertEqual(
            streaming.effects,
            [.openStream(generation: streaming.generation, names: ["sessionActivity", "sessionUnread"], token: Self.token, query: Self.query)]
        )
        let again = streaming.issued { .streamAnswered(generation: $0, answer: .eventStream) }
            .issued { .frameReceived(generation: $0, event: Self.connected) }
        XCTAssertEqual(again.publicState, .open(epoch: 2))
        XCTAssertTrue(again.effects.contains(.reread(epoch: 2)))
    }

    func test_e58_setQueryWhileConnecting_restartsWithNewToken() throws
    {
        for connecting in [try run().signedInForeground(), try run().streaming(), try run().headers()]
        {
            let before = connecting.generation
            let restarted = connecting.feed(.setQuery(Self.query))
            XCTAssertEqual(restarted.publicState, .connecting(attempt: 1))
            XCTAssertGreaterThan(restarted.generation, before)
            assertAbandons(restarted)
            assertMints(restarted)
            XCTAssertFalse(restarted.effects.contains(where: isRetryTimer))
        }
    }

    func test_e59_setQueryWhenNotConnecting_isRememberedForNextConnection() throws
    {
        let waiting = [try run(), try run().feed(.setSignedIn(Self.clientA)), try run().retrying(), try run().offline(), try run().closed()]
        for run in waiting
        {
            let before = run.state
            run.feed(.setQuery(Self.query))
            XCTAssertEqual(run.effects, [])
            XCTAssertEqual(run.publicState, before.publicState)
            XCTAssertEqual(run.generation, before.generation)
        }

        let retried = try run().retrying().feed(.setQuery(Self.query))
            .issued { .retryElapsed(generation: $0) }
            .issued { .tokenMinted(generation: $0, token: Self.token) }
        XCTAssertEqual(
            retried.effects,
            [.openStream(generation: retried.generation, names: ["sessionActivity", "sessionUnread"], token: Self.token, query: Self.query)]
        )
    }

    func test_e60_setQuerySameValue_isIgnored() throws
    {
        let open = try run().open().feed(.setQuery([:]))
        XCTAssertEqual(open.effects, [])
        XCTAssertEqual(open.publicState, .open(epoch: 1))

        let reopened = open.feed(.setQuery(Self.query))
            .issued { .tokenMinted(generation: $0, token: Self.token) }
            .issued { .streamAnswered(generation: $0, answer: .eventStream) }
            .issued { .frameReceived(generation: $0, event: Self.connected) }
        let generation = reopened.generation
        reopened.feed(.setQuery(Self.query))
        XCTAssertEqual(reopened.effects, [])
        XCTAssertEqual(reopened.publicState, .open(epoch: 2))
        XCTAssertEqual(reopened.generation, generation)
    }

    func test_e61_setQueryReservedField_isRefused() throws
    {
        XCTAssertThrowsError(try SPFNEventStreamConfiguration.requireQuery(["token": "x"]))
        {
            XCTAssertEqual($0 as? SPFNEventStreamError, .reservedQueryField("token"))
        }
        XCTAssertThrowsError(try SPFNEventStreamConfiguration.requireQuery(["workspaceId": "w-1", "events": "a"]))
        {
            XCTAssertEqual($0 as? SPFNEventStreamError, .reservedQueryField("events"))
        }
        XCTAssertNoThrow(try SPFNEventStreamConfiguration.requireQuery(Self.query))
        let fixture = try EventStreamFixture()
        XCTAssertThrowsError(try fixture.events.setQuery(["token": "x"]))
    }

    func test_e62_streamRequest_carriesQuery_andReopensWithoutBackoff() async throws
    {
        let fixture = try EventStreamFixture()
        let first = fixture.streams.enqueue()
        try fixture.events.setQuery(["workspaceId": "w-1", "scope": "a&b"])
        let isOpen = await fixture.open(first)
        XCTAssertTrue(isOpen)
        XCTAssertEqual(
            fixture.streams.requests.first?.url,
            "https://example.invalid/events/stream?token=token-1&events=sessionActivity,sessionUnread&scope=a%26b&workspaceId=w-1"
        )

        let second = fixture.streams.enqueue()
        try fixture.events.setQuery(["workspaceId": "w-2"])
        let reopened = await eventually { fixture.streams.requests.count == 2 }
        XCTAssertTrue(reopened, "the reopen needs no timer")
        XCTAssertFalse(fixture.sleeper.pending.contains(1_000), "no backoff timer was started")
        XCTAssertTrue(first.cancelled)
        XCTAssertEqual(fixture.tokens.tokenRequests.count, 2)
        XCTAssertEqual(
            fixture.streams.requests.last?.url,
            "https://example.invalid/events/stream?token=token-2&events=sessionActivity,sessionUnread&workspaceId=w-2"
        )
        second.send(ServerFrames.connected)
        let epochTwo = await eventually { fixture.events.state == .open(epoch: 2) }
        XCTAssertTrue(epochTwo)
    }

    func test_e63_subscriptionFrame_configuredEvent_goesToWireListeners() throws
    {
        let revoked = try run().open().issued { .frameReceived(generation: $0, event: Self.subscriptionFrame("revoked", "sessionActivity")) }
        XCTAssertEqual(
            revoked.effects,
            [.subscription(name: "sessionActivity", status: .revoked), .startSilenceTimer(generation: revoked.generation, millis: 25_000)]
        )
        let granted = revoked.issued { .frameReceived(generation: $0, event: Self.subscriptionFrame("granted", "sessionUnread")) }
        XCTAssertEqual(
            granted.effects,
            [.subscription(name: "sessionUnread", status: .granted), .startSilenceTimer(generation: granted.generation, millis: 25_000)]
        )
    }

    func test_e64_subscriptionFrame_unconfiguredEvent_isDroppedUncounted() throws
    {
        let frames = [
            Self.subscriptionFrame("revoked", "somethingElse"),
            SPFNSSEEvent(name: "subscription.granted", data: "not json"),
            SPFNSSEEvent(name: "subscription.granted", data: "{\"data\":{}}"),
        ]
        for frame in frames
        {
            let run = try run().open().issued { .frameReceived(generation: $0, event: frame) }
            XCTAssertEqual(run.effects, [.startSilenceTimer(generation: run.generation, millis: 25_000)])
        }
    }

    func test_e65_reconnectNowWhileRetrying_mintsAtOnce_cancelsTimer() throws
    {
        let retrying = try run().retrying()
            .issued { .retryElapsed(generation: $0) }
            .issued { .tokenFailed(generation: $0, failure: .network) }
        XCTAssertEqual(retrying.publicState, .retrying(attempt: 3, delayMillis: 2_000, reason: .network))
        let waited = retrying.generation
        let now = retrying.feed(.reconnectNow)
        XCTAssertEqual(now.publicState, .connecting(attempt: 1))
        XCTAssertTrue(now.effects.contains(.cancelTimers))
        assertMints(now)

        let current = now.state
        now.feed(.retryElapsed(generation: waited))
        XCTAssertEqual(now.effects, [], "the cancelled timer cannot start a second attempt")
        XCTAssertEqual(now.state, current)
    }

    func test_e66_reconnectNowWhenClosed_mintsAtOne() throws
    {
        let closed = try run().closed()
        let now = closed.feed(.reconnectNow)
        XCTAssertEqual(now.publicState, .connecting(attempt: 1))
        assertMints(now)

        // Closed after a narrowing: the full configured list is tried again.
        let narrowed = try run().streaming()
            .issued { .streamAnswered(generation: $0, answer: .invalidEvents(invalid: ["sessionActivity"], valid: ["sessionUnread"])) }
            .issued { .tokenMinted(generation: $0, token: Self.token) }
            .issued { .streamAnswered(generation: $0, answer: .invalidEvents(invalid: ["sessionUnread"], valid: [])) }
        XCTAssertEqual(narrowed.publicState, .closed(.unknownEvents(["sessionUnread"])))
        let again = narrowed.feed(.reconnectNow).issued { .tokenMinted(generation: $0, token: Self.token) }
        XCTAssertEqual(again.effects, [.openStream(generation: again.generation, names: ["sessionActivity", "sessionUnread"], token: Self.token)])
    }

    func test_e67_reconnectNowWhenOpenConnectingOrIdle_doesNothing() throws
    {
        for run in [try run(), try run().signedInForeground(), try run().streaming(), try run().headers(), try run().open()]
        {
            let before = run.state
            run.feed(.reconnectNow)
            XCTAssertEqual(run.effects, [])
            XCTAssertEqual(run.state, before)
        }
    }

    func test_e68_reconnectNowWhenOffline_doesNothing() throws
    {
        let offline = try run().offline()
        let before = offline.state
        for _ in 0 ..< 3
        {
            offline.feed(.reconnectNow)
            XCTAssertEqual(offline.effects, [])
            XCTAssertEqual(offline.state, before)
        }
        XCTAssertEqual(offline.publicState, .offline(attempt: 2))
    }

    private static func lateInputs(_ generation: Int64) -> [SPFNEventInput]
    {
        [
            .tokenMinted(generation: generation, token: token),
            .tokenFailed(generation: generation, failure: .network),
            .streamAnswered(generation: generation, answer: .eventStream),
            .streamFailed(generation: generation),
            .streamEnded(generation: generation),
            .bytesReceived(generation: generation),
            .frameReceived(generation: generation, event: connected),
            .silenceElapsed(generation: generation),
            .retryElapsed(generation: generation),
            .stableElapsed(generation: generation),
        ]
    }
}
