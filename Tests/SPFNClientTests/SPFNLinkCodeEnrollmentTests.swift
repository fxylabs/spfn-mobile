// SPFN Mobile — link-code enrollment (M9), one test per cell of the L table.
//
// The flow is device-code enrollment turned round, so most of the table is the D table's
// rules asked again of the new entry point: the claim, the key's life, the wait, the
// deadline and the long poll. What only this flow has is the code it reads — refused
// locally when it cannot be one — and the three refusals the contract's `deviceLink`
// section names, which an app shows as three different things.
//
// The `redeem` body is compared against the `deviceStart` fixture that a third
// implementation derived from the contract text (P10): the contract gives
// RedeemDeviceLinkRequest StartDeviceAuthRequest's fields plus `userCode`, so the same
// key material must produce the same bytes plus that one member.
//
// SpfnLinkCodeEnrollmentTest.kt is the counterpart and uses corresponding names.

import Foundation
import XCTest
import SPFNClient
import SPFNCore
import SPFNGenerated

final class SPFNLinkCodeEnrollmentTests: XCTestCase
{
    private let baseURL = "https://example.invalid"

    /// The instant every case starts from, and the expiry the `redeem` answer names.
    private let startedAtMillis: Int64 = 1_750_000_000_000
    private var expiresAtMillis: Int64 { startedAtMillis + 300_000 }
    private let intervalMillis: Int64 = 5_000
    private let matchNumber: Int64 = 37

    /// Synthetic test values; neither is a credential of anything.
    private static let deviceCode = "device-code-test-0002"

    /// As a person might type it, and as the server stores it.
    private static let typedCode = " wdjb-mjht "
    private static let storedCode = "WDJBMJHT"

    // MARK: - L1–L3: the flow is refused before anything is sent

    /// L1: a code that is not eight characters of the alphabet once folded is refused
    /// before a key exists — no request, no key generated, no claim left behind.
    func testL1AMalformedCodeIsRefusedBeforeAKeyOrARequest() async throws
    {
        let malformed = [
            "", "WDJB-MJH", "WDJB-MJHTX", "WDJB-MJH0", "WDJB-MJHI", "WDJB-MJHL", "WDJB-MJHO",
            "WDJB_MJHT", "WDJB–MJHT", "https://example.invalid/WDJB-MJHT", "WDJB-MJHß",
        ]
        for code in malformed
        {
            let generated = GenerationCounter()
            let transport = ScriptedTransport([])
            let lifecycle = SPFNKeyLifecycle(
                transport: transport,
                store: InMemoryKeyStore(),
                baseURL: baseURL,
                clock: FakeClock(startedAtMillis),
                proofClock: FakeClock(startedAtMillis),
                nonceGenerator: ScriptedNonceGenerator([]),
                sleeper: ScriptedSleeper(),
                newKeyID: { "key-test-0001" },
                makeKey: { [key = try testKey()] _ in generated.record(key) }
            )

            let thrown = await failure { _ = try await lifecycle.enrollByLinkCode(code: code) { _, _ in } }

            XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .malformedLinkCode, "'\(code)' was accepted")
            let calls = await transport.callCount
            XCTAssertEqual(calls, 0, "nothing is sent for '\(code)'")
            XCTAssertEqual(generated.count, 0, "no key is generated for '\(code)'")
        }
    }

    func testL2AnEnrolledInstallIsRefusedAndSendsNothing() async throws
    {
        let transport = ScriptedTransport([])
        let store = InMemoryKeyStore()
        try store.save(
            try testKey().record(clientID: "client-test-0001", createdAtMillis: startedAtMillis),
            slot: SPFNKeyLifecycle.activeSlot
        )
        let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let thrown = await failure { _ = try await lifecycle.enrollByLinkCode(code: Self.typedCode) { _, _ in } }

        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .alreadyEnrolled)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0)
    }

    /// L3, first direction: a device-code wait is running, so the link-code call is
    /// refused. One claim covers every enrollment entry point.
    func testL3ADeviceCodeWaitInFlightRefusesTheLinkCodeCall() async throws
    {
        let waiting = Gate()
        let release = Gate()
        let transport = ScriptedTransport([
            .success(.json(200, "{\"deviceCode\":\"device-code-test-0001\",\"expiresAtMillis\":\(expiresAtMillis),"
                + "\"intervalMillis\":\(intervalMillis),\"userCode\":\"WDJB-MJHT\"}")),
            approvedAnswer(),
        ])
        let lifecycle = makeLifecycle(
            transport,
            store: InMemoryKeyStore(),
            keys: [try testKey(), try testKey()],
            keyIDs: ["key-test-0001", "key-test-0002"],
            sleeper: ScriptedSleeper
            { _ in
                await waiting.open()
                await release.wait()
            }
        )

        let device = Task { try await lifecycle.enrollByDeviceCode { _, _ in } }
        await waiting.wait()

        let thrown = await failure { _ = try await lifecycle.enrollByLinkCode(code: Self.typedCode) { _, _ in } }
        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .enrollmentInFlight)

        await release.open()
        let settled = try await device.value
        XCTAssertEqual(settled.keyID, "key-test-0001")
        let calls = await transport.callCount
        XCTAssertEqual(calls, 2, "the link-code call sent nothing")
    }

    /// L3, the other direction: a link-code wait is running, so a device-code call is
    /// refused.
    func testL3ALinkCodeWaitInFlightRefusesTheDeviceCodeCall() async throws
    {
        let waiting = Gate()
        let release = Gate()
        let transport = ScriptedTransport([redeemAnswer(), approvedAnswer()])
        let lifecycle = makeLifecycle(
            transport,
            store: InMemoryKeyStore(),
            keys: [try testKey(), try testKey()],
            keyIDs: ["key-test-0001", "key-test-0002"],
            sleeper: ScriptedSleeper
            { _ in
                await waiting.open()
                await release.wait()
            }
        )

        let code = Self.typedCode
        let link = Task { try await lifecycle.enrollByLinkCode(code: code) { _, _ in } }
        await waiting.wait()

        let thrown = await failure { _ = try await lifecycle.enrollByDeviceCode { _, _ in } }
        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .enrollmentInFlight)

        await release.open()
        let settled = try await link.value
        XCTAssertEqual(settled.keyID, "key-test-0001")
        let calls = await transport.callCount
        XCTAssertEqual(calls, 2, "the device-code call sent nothing")
    }

    // MARK: - L4: what `redeem` puts on the wire

    /// L4: the body is the `deviceStart` fixture's, member for member, plus the folded
    /// code; the match is shown once, before anything is saved.
    func testL4RedeemSendsTheStartFieldsPlusTheFoldedCodeAndShowsTheMatchOnce() async throws
    {
        let fixture = try deviceStartFixture()
        let startBody = try fixture["byPlatform"].orFail("byPlatform").object()[SPFNClientIdentity.kind]
            .orFail(SPFNClientIdentity.kind).object().text("canonical")
        var expected = try SPFNCanonicalJSON.parse(Array(startBody.utf8)).object()
        expected["userCode"] = .string(Self.storedCode)
        let transport = ScriptedTransport([redeemAnswer(), approvedAnswer()])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let shown = ShownMatches()
        _ = try await lifecycle.enrollByLinkCode(code: Self.typedCode, deviceName: try fixture.text("deviceName"))
        { match, expiresAt in
            shown.record(match: match, expiresAtMillis: expiresAt, saved: store.loadSync(SPFNKeyLifecycle.activeSlot))
        }

        let received = await transport.received
        let sent = try XCTUnwrap(received.first)
        XCTAssertEqual(sent.method, "POST")
        XCTAssertEqual(sent.url, baseURL + SPFNGeneratedOperations.authDeviceLinkRedeem.path)
        XCTAssertEqual(try SPFNCanonicalJSON.parse(sent.body ?? []).object(), expected)
        let kind = try XCTUnwrap(sent.headers.first { $0.0 == SPFNWireHeaders.clientKind }?.1)
        XCTAssertEqual(expected["platform"], .string(kind), "the parked key's platform is this build's kind")

        XCTAssertEqual(shown.matches, [matchNumber], "the match is shown exactly once")
        XCTAssertEqual(shown.expiries, [expiresAtMillis])
        XCTAssertTrue(shown.sawNothingSaved, "nothing is saved before the approval")
    }

    // MARK: - L5–L7: `redeem` refused or unreadable

    /// L5: never issued, or used by another device — "code not found".
    func testL5ARedeemNotFoundIsItsOwnOutcomeAndDestroysTheKey() async throws
    {
        try await assertRedeemRefusal("DeviceLinkNotFoundError", httpStatus: 404, expected: .deviceLinkNotFoundError)
    }

    /// L6: the code died — "code expired". Distinct from L5 by code and by status.
    func testL6ARedeemExpiredIsItsOwnOutcomeAndDestroysTheKey() async throws
    {
        try await assertRedeemRefusal("DeviceLinkExpiredError", httpStatus: 400, expected: .deviceLinkExpiredError)
    }

    /// L7: a match number outside the contract's 10–99 is one the signed-in device will
    /// never offer. It is refused as an answer this client cannot read, and not shown.
    func testL7AMatchNumberOutsideTheContractRangeIsRefusedAndNotShown() async throws
    {
        for match: Int64 in [9, 100, 0, -37]
        {
            let transport = ScriptedTransport([redeemAnswer(match: match), approvedAnswer()])
            let store = InMemoryKeyStore()
            let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

            let shown = ShownMatches()
            let thrown = await failure
            {
                _ = try await lifecycle.enrollByLinkCode(code: Self.typedCode)
                { shownMatch, expiry in shown.record(match: shownMatch, expiresAtMillis: expiry, saved: nil) }
            }

            XCTAssertEqual(thrown as? SPFNClientError, .decoding(.notTheDeclaredResponse, onSuccessStatus: true), "\(match)")
            XCTAssertEqual(shown.matches, [], "\(match) was shown")
            let calls = await transport.callCount
            XCTAssertEqual(calls, 1, "no poll follows \(match)")
            try await assertNoKeySurvived(store, lifecycle)
        }
    }

    // MARK: - L8: approval

    func testL8AnApprovedPollSavesTheParkedKeyExactlyAsEnrollmentDoes() async throws
    {
        let transport = ScriptedTransport([
            redeemAnswer(),
            pendingAnswer(intervalMillis),
            approvedAnswer(userID: "user-test-0007", passwordChangeRequired: true),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let result = try await lifecycle.enrollByLinkCode(code: Self.typedCode) { _, _ in }

        XCTAssertEqual(
            result,
            SPFNDeviceCodeEnrollmentResult(clientID: "user-test-0007", keyID: "key-test-0001", passwordChangeRequired: true)
        )
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .enrolled)
        let active = try XCTUnwrap(store.loadSync(SPFNKeyLifecycle.activeSlot))
        XCTAssertEqual(active.clientID, "user-test-0007")
        XCTAssertEqual(active.keyID, "key-test-0001")
        let provider = try await lifecycle.activeProvider()
        XCTAssertEqual(provider?.clientID, "user-test-0007")
        let polled = try await polledDeviceCodes(transport)
        XCTAssertEqual(polled, [Self.deviceCode, Self.deviceCode])
    }

    // MARK: - L9–L11: the poll refusals that end the wait

    /// L9: the signed-in device refused, or picked another number — "not approved".
    func testL9ADeniedPollDestroysTheKeyAndCarriesTheCode() async throws
    {
        try await assertPollRefusalEndsTheWait("DeviceLinkDeniedError", httpStatus: 403, expected: .deviceLinkDeniedError)
    }

    func testL10AnExpiredPollDestroysTheKeyAndCarriesTheCode() async throws
    {
        try await assertPollRefusalEndsTheWait("DeviceLinkExpiredError", httpStatus: 400, expected: .deviceLinkExpiredError)
    }

    /// L11: the approval was collected by another poll. Ended as D11 ends a device-code
    /// wait: the key this call parked is not the one that poll registered for anyone.
    func testL11ANotFoundPollDestroysTheKeyAndCarriesTheCode() async throws
    {
        try await assertPollRefusalEndsTheWait("DeviceLinkNotFoundError", httpStatus: 404, expected: .deviceLinkNotFoundError)
    }

    // MARK: - L12: the deadline, judged on the proof clock

    func testL12TheProofClockDeadlineEndsTheWaitWithoutAnotherPoll() async throws
    {
        let wallClock = FakeClock(startedAtMillis)
        let proofClock = FakeClock(startedAtMillis)
        let expiry = expiresAtMillis
        let sleeper = ScriptedSleeper
        { wait in
            if wait == 1
            {
                wallClock.set(expiry + 1)
            }
            else
            {
                proofClock.set(expiry)
            }
        }
        let transport = ScriptedTransport([redeemAnswer(), pendingAnswer(intervalMillis)])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(
            transport,
            store: store,
            keys: [try testKey()],
            keyIDs: ["key-test-0001"],
            clock: wallClock,
            proofClock: proofClock,
            sleeper: sleeper
        )

        let thrown = await failure { _ = try await lifecycle.enrollByLinkCode(code: Self.typedCode) { _, _ in } }

        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .linkCodeExpired)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 2, "a wall clock past the expiry does not end the wait; the proof clock does")
        try await assertNoKeySurvived(store, lifecycle)
    }

    // MARK: - L13: the caller withdraws

    /// L13: cancelled between `redeem` and the approval. The key goes, the install stays
    /// unenrolled, and the claim is released — the next enrollment is not refused as one
    /// still in flight.
    func testL13CancellationAfterRedeemDestroysTheKeyAndReleasesTheClaim() async throws
    {
        let waiting = Gate()
        let transport = ScriptedTransport([redeemAnswer(), redeemAnswer(), approvedAnswer()])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(
            transport,
            store: store,
            keys: [try testKey(), try testKey()],
            keyIDs: ["key-test-0001", "key-test-0002"],
            sleeper: ScriptedSleeper
            { wait in
                guard wait == 1
                else
                {
                    return
                }
                await waiting.open()
                // Far longer than the test takes; the cancellation is what ends it.
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        )

        let code = Self.typedCode
        let call = Task { try await lifecycle.enrollByLinkCode(code: code) { _, _ in } }
        await waiting.wait()
        call.cancel()

        do
        {
            _ = try await call.value
            XCTFail("a cancelled wait must not enroll")
        }
        catch
        {
            XCTAssertTrue(error is CancellationError, "expected the platform's cancellation, got \(error)")
        }
        let calls = await transport.callCount
        XCTAssertEqual(calls, 1, "a cancelled wait sends no poll")
        try await assertNoKeySurvived(store, lifecycle)

        _ = try await lifecycle.enrollByLinkCode(code: Self.typedCode) { _, _ in }
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .enrolled, "the claim was released with the cancelled call")
    }

    // MARK: - L14, L15: the long poll and the version check

    /// L14: every link poll asks to be held, its deadline outlasts the hold, and a held
    /// `pending` is asked again at once.
    func testL14EveryPollAsksToBeHeldAndAHeldPendingPollsAgainAtOnce() async throws
    {
        let transport = ScriptedTransport([redeemAnswer(), pendingAnswer(0), approvedAnswer()])
        let sleeper = ScriptedSleeper()
        let lifecycle = makeLifecycle(
            transport,
            store: InMemoryKeyStore(),
            keys: [try testKey()],
            keyIDs: ["key-test-0001"],
            sleeper: sleeper
        )

        _ = try await lifecycle.enrollByLinkCode(code: Self.typedCode) { _, _ in }

        XCTAssertEqual(sleeper.waits, [intervalMillis], "only the redeem answer's interval was slept")
        let received = await transport.received
        let polls = received.filter { $0.url.hasSuffix(SPFNGeneratedOperations.authDeviceLinkPoll.path) }
        XCTAssertEqual(polls.count, 2)
        for poll in polls
        {
            let body = try SPFNCanonicalJSON.parse(poll.body ?? []).object()
            XCTAssertEqual(body["waitMillis"], .integer(20_000))
            XCTAssertEqual(poll.timeoutMillis, 15_000 + 20_000)
        }
        XCTAssertEqual(received.first?.timeoutMillis, 15_000, "redeem is not held")
    }

    /// L15: the new operations ride the execute path, so a server announcing a contract
    /// this SDK does not admit is refused on `redeem` like any other call — here 0.13.1,
    /// which predates the device link — and the key goes with it.
    func testL15ARedeemFromAServerOutsideTheAdmittedRangeIsAContractRefusal() async throws
    {
        let transport = ScriptedTransport([
            .success(SPFNTransportResponse(
                statusCode: 200,
                headers: [("content-type", "application/json")] + SPFNTransportResponse.announcement(version: "0.13.1"),
                body: Array(redeemBody().utf8)
            )),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let thrown = await failure { _ = try await lifecycle.enrollByLinkCode(code: Self.typedCode) { _, _ in } }

        XCTAssertEqual(
            thrown as? SPFNClientError,
            .contract(SPFNContractMismatch(
                reason: .outsideAdmittedRange,
                serverVersion: "0.13.1",
                admittedRange: SPFNGeneratedContract.binding.admittedRange
            ))
        )
        try await assertNoKeySurvived(store, lifecycle)
    }

    // MARK: - Assembly

    /// L5 and L6 differ only in the code.
    private func assertRedeemRefusal(
        _ wireCode: String,
        httpStatus: Int,
        expected: SPFNGeneratedErrorCode
    ) async throws
    {
        let transport = ScriptedTransport([
            .success(.json(httpStatus, ExecuteFixtures.errorEnvelope(code: wireCode))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let shown = ShownMatches()
        let thrown = await failure
        {
            _ = try await lifecycle.enrollByLinkCode(code: Self.typedCode)
            { match, expiry in shown.record(match: match, expiresAtMillis: expiry, saved: nil) }
        }

        guard case .server(let refusal)? = thrown as? SPFNClientError
        else
        {
            return XCTFail("expected a refusal, got \(String(describing: thrown))")
        }
        XCTAssertEqual(refusal.code, expected)
        XCTAssertEqual(refusal.httpStatus, httpStatus)
        XCTAssertEqual(shown.matches, [], "a match nobody was given must not be shown")
        try await assertNoKeySurvived(store, lifecycle)
    }

    /// L9–L11 differ only in the code.
    private func assertPollRefusalEndsTheWait(
        _ wireCode: String,
        httpStatus: Int,
        expected: SPFNGeneratedErrorCode
    ) async throws
    {
        let transport = ScriptedTransport([
            redeemAnswer(),
            .success(.json(httpStatus, ExecuteFixtures.errorEnvelope(code: wireCode))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let thrown = await failure { _ = try await lifecycle.enrollByLinkCode(code: Self.typedCode) { _, _ in } }

        guard case .server(let refusal)? = thrown as? SPFNClientError
        else
        {
            return XCTFail("expected a refusal, got \(String(describing: thrown))")
        }
        XCTAssertEqual(refusal.code, expected)
        XCTAssertEqual(refusal.httpStatus, httpStatus)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 2, "no further poll is sent")
        try await assertNoKeySurvived(store, lifecycle)
    }

    /// What every non-approved exit owes; see the device-code suite for why this is
    /// less work here than on Android.
    private func assertNoKeySurvived(_ store: InMemoryKeyStore, _ lifecycle: SPFNKeyLifecycle) async throws
    {
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.activeSlot), "nothing was persisted")
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.candidateSlot))
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .unenrolled)
    }

    /// The `deviceCode` every link poll carried, in order.
    private func polledDeviceCodes(_ transport: ScriptedTransport) async throws -> [String]
    {
        let received = await transport.received
        return try received
            .filter { $0.url.hasSuffix(SPFNGeneratedOperations.authDeviceLinkPoll.path) }
            .map { try SPFNCanonicalJSON.parse($0.body ?? []).object().text("deviceCode") }
    }

    private func redeemBody(match: Int64? = nil) -> String
    {
        "{\"deviceCode\":\"\(Self.deviceCode)\",\"expiresAtMillis\":\(expiresAtMillis),"
            + "\"intervalMillis\":\(intervalMillis),\"matchNumber\":\(match ?? matchNumber)}"
    }

    private func redeemAnswer(match: Int64? = nil) -> Result<SPFNTransportResponse, any Error>
    {
        .success(.json(200, redeemBody(match: match)))
    }

    private func pendingAnswer(_ interval: Int64) -> Result<SPFNTransportResponse, any Error>
    {
        .success(.json(200, "{\"intervalMillis\":\(interval),\"status\":\"pending\"}"))
    }

    private func approvedAnswer(
        userID: String = "user-test-0001",
        passwordChangeRequired: Bool = false
    ) -> Result<SPFNTransportResponse, any Error>
    {
        .success(.json(200, "{\"passwordChangeRequired\":\(passwordChangeRequired),"
            + "\"publicId\":\"public-test-0001\",\"status\":\"approved\",\"userId\":\"\(userID)\"}"))
    }

    private func deviceStartFixture() throws -> [String: SPFNCanonicalValue]
    {
        try WireFixtures.load("Contracts/fixtures/enrollment/enrollment.json")
            .object()["deviceStart"]
            .orFail("deviceStart")
            .object()
    }

    /// The fixture test keypair as a custody key (TEST ONLY — published on purpose).
    private func testKey() throws -> SPFNCustodyKey
    {
        let keyPair = try WireFixtures.wire()["testKeyPair"].orFail("testKeyPair").object()
        guard let der = Data(base64Encoded: try keyPair.text("privateKeyPkcs8Base64"))
        else
        {
            throw FixtureFailure.shape("not base64")
        }
        return try SPFNCustodyKey.software(keyID: try keyPair.text("keyId"), privateKeyDer: [UInt8](der))
    }

    private func makeLifecycle(
        _ transport: any SPFNTransport,
        store: InMemoryKeyStore,
        keys: [SPFNCustodyKey],
        keyIDs: [String],
        clock: FakeClock? = nil,
        proofClock: (any SPFNProofClock)? = nil,
        sleeper: any SPFNSleeper = ScriptedSleeper()
    ) -> SPFNKeyLifecycle
    {
        let keyQueue = ScriptedQueue(keys)
        let idQueue = ScriptedQueue(keyIDs)
        return SPFNKeyLifecycle(
            transport: transport,
            store: store,
            baseURL: baseURL,
            clock: clock ?? FakeClock(startedAtMillis),
            proofClock: proofClock ?? FakeClock(startedAtMillis),
            nonceGenerator: ScriptedNonceGenerator([]),
            sleeper: sleeper,
            newKeyID: { idQueue.next() ?? "key-unexpected" },
            makeKey: { keyID in keyQueue.next() ?? SPFNCustodyKey.generate(keyID: keyID, preferSecureEnclave: false) }
        )
    }

    private func failure(
        _ body: () async throws -> Void,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async -> (any Error)?
    {
        do
        {
            try await body()
        }
        catch
        {
            return error
        }
        XCTFail("expected a throw", file: file, line: line)
        return nil
    }
}

/// What the `showMatch` callback was handed, and what the store held when it ran — the
/// `ShownCodes` recorder for a number instead of a code.
final class ShownMatches: @unchecked Sendable
{
    private let lock = NSLock()
    private var matchValues: [Int64] = []
    private var expiryValues: [Int64] = []
    private var savedRecords: [SPFNStoredKey?] = []

    func record(match: Int64, expiresAtMillis: Int64, saved: SPFNStoredKey?)
    {
        lock.lock()
        defer { lock.unlock() }
        matchValues.append(match)
        expiryValues.append(expiresAtMillis)
        savedRecords.append(saved)
    }

    var matches: [Int64]
    {
        lock.lock()
        defer { lock.unlock() }
        return matchValues
    }

    var expiries: [Int64]
    {
        lock.lock()
        defer { lock.unlock() }
        return expiryValues
    }

    var sawNothingSaved: Bool
    {
        lock.lock()
        defer { lock.unlock() }
        return savedRecords.allSatisfy { $0 == nil }
    }
}
