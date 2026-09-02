// SPFN Mobile — device-code enrollment (M8), one test per cell of the D table.
//
// The table is closed: D1–D18 are every state and every answer the waiting side of the
// contract's `deviceAuthorization` flow can meet, and each test is named after its cell.
// What the flow sends is compared against Contracts/fixtures/enrollment/enrollment.json,
// which a third implementation derived from the contract text (P10) — never against what
// the SDK happened to send.
//
// The wait is a value here, not elapsed time: the sleeper and both clocks are injected,
// so "obeys the server's interval" and "ends at the server's expiry" are assertions
// rather than stopwatch readings.
//
// SpfnDeviceCodeEnrollmentTest.kt is the counterpart and uses corresponding names.

import Foundation
import XCTest
import SPFNClient
import SPFNCore
import SPFNGenerated

final class SPFNDeviceCodeEnrollmentTests: XCTestCase
{
    private let baseURL = "https://example.invalid"

    /// The instant every case starts from, and the expiry the `start` answer names.
    private let startedAtMillis: Int64 = 1_750_000_000_000
    private var expiresAtMillis: Int64 { startedAtMillis + 600_000 }
    private let intervalMillis: Int64 = 5_000

    /// Synthetic test values; neither is a credential of anything.
    private static let deviceCode = "device-code-test-0001"

    /// As the server spells it — the client passes it through untouched.
    private static let userCode = "WDJB-MJHT"

    // MARK: - D1–D3: the flow is refused before anything is sent

    func testD1AnEnrolledInstallIsRefusedAndSendsNothing() async throws
    {
        let transport = ScriptedTransport([])
        let store = InMemoryKeyStore()
        try enrol(store, key: try testKey(), clientID: "client-test-0001")
        let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let thrown = await failure { _ = try await lifecycle.enrollByDeviceCode { _, _ in } }

        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .alreadyEnrolled)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "nothing is sent for a refusal the state already knows")
    }

    func testD2AnUnresolvedRotationIsRefusedAndSendsNothing() async throws
    {
        let transport = ScriptedTransport([])
        let store = InMemoryKeyStore()
        try store.save(
            try testKey().record(clientID: "client-test-0001", createdAtMillis: startedAtMillis),
            slot: SPFNKeyLifecycle.candidateSlot
        )
        let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let thrown = await failure { _ = try await lifecycle.enrollByDeviceCode { _, _ in } }

        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .rotationUnresolved)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0)
    }

    /// D3, first direction: a social enrollment is waiting on its sign-in, so the device
    /// code call is refused. One flag guards both entry points — two would let each flow
    /// read `unenrolled` and register a key the other does not know about.
    func testD3ASocialEnrollmentInFlightRefusesTheDeviceCodeCall() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, "{\"isNewUser\":true,\"keyId\":\"key-test-0001\",\"userId\":\"user-test-0001\"}")),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(
            transport,
            store: store,
            keys: [try testKey(), try testKey()],
            keyIDs: ["key-test-0001", "key-test-0002"]
        )
        let arrived = Gate()
        let release = Gate()

        let social = Task
        {
            try await lifecycle.enroll(provider: "apple")
            { _ in
                await arrived.open()
                await release.wait()
                return "idtoken-first"
            }
        }
        await arrived.wait()

        let thrown = await failure { _ = try await lifecycle.enrollByDeviceCode { _, _ in } }
        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .enrollmentInFlight)

        await release.open()
        let settled = try await social.value
        XCTAssertEqual(settled.keyID, "key-test-0001", "the social enrollment is the one that settled")
        let calls = await transport.callCount
        XCTAssertEqual(calls, 1, "exactly one registration reached the server")
    }

    /// D3, the other direction: a device-code call is waiting for an approval, so a
    /// social enrollment is refused. Both directions are needed — a flag claimed by one
    /// entry point and read by neither would pass the test above.
    func testD3ADeviceCodeCallInFlightRefusesASocialEnrollment() async throws
    {
        let waiting = Gate()
        let release = Gate()
        let transport = ScriptedTransport([startAnswer(), approvedAnswer()])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(
            transport,
            store: store,
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

        let thrown = await failure { _ = try await lifecycle.enroll(provider: "apple") { _ in "idtoken-second" } }
        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .enrollmentInFlight)

        await release.open()
        let settled = try await device.value
        XCTAssertEqual(settled.keyID, "key-test-0001")
        let calls = await transport.callCount
        XCTAssertEqual(calls, 2, "the social enrollment sent nothing")
    }

    // MARK: - D4, D18: what `start` puts on the wire

    func testD4StartSendsTheFixtureBytesAndShowsTheCodeOnce() async throws
    {
        let fixture = try deviceStartFixture()
        let expected = try fixture["byPlatform"].orFail("byPlatform").object()[SPFNClientIdentity.kind]
            .orFail(SPFNClientIdentity.kind).object()
        let transport = ScriptedTransport([startAnswer(), approvedAnswer()])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let shown = ShownCodes()
        _ = try await lifecycle.enrollByDeviceCode(deviceName: try fixture.text("deviceName"))
        { userCode, expiresAt in
            shown.record(userCode: userCode, expiresAtMillis: expiresAt, saved: store.loadSync(SPFNKeyLifecycle.activeSlot))
        }

        let received = await transport.received
        let sent = try XCTUnwrap(received.first)
        XCTAssertEqual(sent.method, "POST")
        XCTAssertEqual(sent.url, baseURL + (try fixture.text("path")))
        XCTAssertEqual(
            sent.headers.map { [$0.0, $0.1] },
            try fixture.headerPairs("headers").map { [$0.0, $0.1] }
                + SPFNClientIdentity.headers.map { [$0.0, $0.1] },
            "an unproven start carries the fixture's headers and then the identity"
        )
        XCTAssertEqual(
            String(decoding: sent.body ?? [], as: UTF8.self),
            try expected.text("canonical"),
            "the start body must be the fixture bytes exactly"
        )

        XCTAssertEqual(shown.count, 1, "the code is shown exactly once")
        XCTAssertEqual(shown.userCodes, [Self.userCode])
        XCTAssertEqual(shown.expiries, [expiresAtMillis])
        XCTAssertTrue(shown.sawNothingSaved, "nothing is saved before the approval")
    }

    /// D18: the `platform` the body registers the key under is the same value the
    /// identity header announces. Read off one captured request, so the two cannot be
    /// kept in step by the fixture the previous case reads.
    func testD18TheStartBodyPlatformIsTheClientKindHeaderValue() async throws
    {
        let transport = ScriptedTransport([startAnswer(), approvedAnswer()])
        let lifecycle = makeLifecycle(
            transport,
            store: InMemoryKeyStore(),
            keys: [try testKey()],
            keyIDs: ["key-test-0001"]
        )

        _ = try await lifecycle.enrollByDeviceCode { _, _ in }

        let received = await transport.received
        let sent = try XCTUnwrap(received.first)
        let kind = try XCTUnwrap(sent.headers.first { $0.0 == SPFNWireHeaders.clientKind }?.1)
        let body = try SPFNCanonicalJSON.parse(sent.body ?? []).object()
        XCTAssertEqual(
            try body.text("platform"),
            kind,
            "the parked key's platform is what this build announces itself as"
        )
    }

    // MARK: - D5, D6: `start` never answers

    func testD5AStartTransportFailureDestroysTheKeyAndShowsNoCode() async throws
    {
        let transport = ScriptedTransport([.failure(SPFNTransportError.timedOut)])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let shown = ShownCodes()
        let thrown = await failure
        {
            _ = try await lifecycle.enrollByDeviceCode { code, expiry in shown.record(userCode: code, expiresAtMillis: expiry, saved: nil) }
        }

        XCTAssertEqual(thrown as? SPFNClientError, .transport(.timedOut))
        XCTAssertEqual(shown.count, 0, "a code nobody was given must not be shown")
        try await assertNoKeySurvived(store, lifecycle)
    }

    func testD6AStartRefusalDestroysTheKeyAndSurfacesTheRefusal() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(400, ExecuteFixtures.errorEnvelope(code: "ValidationError"))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let shown = ShownCodes()
        let thrown = await failure
        {
            _ = try await lifecycle.enrollByDeviceCode { code, expiry in shown.record(userCode: code, expiresAtMillis: expiry, saved: nil) }
        }

        guard case .server(let refusal)? = thrown as? SPFNClientError
        else
        {
            return XCTFail("expected a refusal, got \(String(describing: thrown))")
        }
        XCTAssertEqual(refusal.code, .validationError)
        XCTAssertEqual(shown.count, 0)
        try await assertNoKeySurvived(store, lifecycle)
    }

    // MARK: - D7, D8: the two answers the poll is written for

    func testD7APendingAnswerWaitsItsIntervalAndPollsTheSameCodeAgain() async throws
    {
        let secondInterval: Int64 = 7_000
        let transport = ScriptedTransport([startAnswer(), pendingAnswer(secondInterval), approvedAnswer()])
        let sleeper = ScriptedSleeper()
        let lifecycle = makeLifecycle(
            transport,
            store: InMemoryKeyStore(),
            keys: [try testKey()],
            keyIDs: ["key-test-0001"],
            sleeper: sleeper
        )

        _ = try await lifecycle.enrollByDeviceCode { _, _ in }

        XCTAssertEqual(
            sleeper.waits,
            [intervalMillis, secondInterval],
            "the first wait is the start answer's interval and the second is the pending answer's"
        )
        let polled = try await polledDeviceCodes(transport)
        XCTAssertEqual(polled, [Self.deviceCode, Self.deviceCode])
    }

    func testD8AnApprovedAnswerSavesTheParkedKeyAndAnswersTheLogin() async throws
    {
        let transport = ScriptedTransport([
            startAnswer(),
            approvedAnswer(userID: "user-test-0007", passwordChangeRequired: true),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let result = try await lifecycle.enrollByDeviceCode { _, _ in }

        XCTAssertEqual(
            result,
            SPFNDeviceCodeEnrollmentResult(
                clientID: "user-test-0007",
                keyID: "key-test-0001",
                passwordChangeRequired: true
            )
        )
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .enrolled)
        let active = try XCTUnwrap(store.loadSync(SPFNKeyLifecycle.activeSlot))
        XCTAssertEqual(active.clientID, "user-test-0007", "the approved poll's userId is the clientID every proof carries")
        XCTAssertEqual(active.keyID, "key-test-0001")
        let provider = try await lifecycle.activeProvider()
        XCTAssertEqual(provider?.clientID, "user-test-0007")
    }

    // MARK: - D9–D13: every refusal the poll can meet

    func testD9ADeniedPollDestroysTheKeyAndCarriesTheCode() async throws
    {
        try await assertPollRefusalEndsTheWait("DeviceAuthDeniedError", httpStatus: 403, expected: .deviceAuthDeniedError)
    }

    func testD10AnExpiredPollDestroysTheKeyAndCarriesTheCode() async throws
    {
        try await assertPollRefusalEndsTheWait("DeviceAuthExpiredError", httpStatus: 400, expected: .deviceAuthExpiredError)
    }

    func testD11ANotFoundPollDestroysTheKeyAndCarriesTheCode() async throws
    {
        try await assertPollRefusalEndsTheWait("DeviceAuthNotFoundError", httpStatus: 404, expected: .deviceAuthNotFoundError)
    }

    /// D12: the one refusal the contract marks retryable. The code is still live and this
    /// device only asked too fast, so the wait resumes on the interval it already had.
    func testD12ARateLimitKeepsPollingAndKeepsTheKey() async throws
    {
        let transport = ScriptedTransport([
            startAnswer(),
            .success(.json(429, ExecuteFixtures.errorEnvelope(code: "TooManyRequestsError"))),
            approvedAnswer(),
        ])
        let sleeper = ScriptedSleeper()
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(
            transport,
            store: store,
            keys: [try testKey()],
            keyIDs: ["key-test-0001"],
            sleeper: sleeper
        )

        let result = try await lifecycle.enrollByDeviceCode { _, _ in }

        XCTAssertEqual(result.keyID, "key-test-0001")
        XCTAssertEqual(
            sleeper.waits,
            [intervalMillis, intervalMillis],
            "the rate limit does not change the interval the server asked for"
        )
        let calls = await transport.callCount
        XCTAssertEqual(calls, 3)
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .enrolled, "the parked key survives a rate limit")
    }

    /// D13: a code this contract does not declare. The client refuses it as an unknown
    /// code rather than rounding it to a neighbour, and a wait it cannot interpret is a
    /// wait it ends — polling on would wait out a code that may never move.
    func testD13AnUnlistedErrorCodeEndsTheWait() async throws
    {
        let transport = ScriptedTransport([
            startAnswer(),
            .success(.json(400, ExecuteFixtures.errorEnvelope(code: "SOMETHING_ELSE_V2"))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let thrown = await failure { _ = try await lifecycle.enrollByDeviceCode { _, _ in } }

        XCTAssertEqual(thrown as? SPFNClientError, .decoding(.unknownErrorCode))
        let calls = await transport.callCount
        XCTAssertEqual(calls, 2, "no further poll is sent")
        try await assertNoKeySurvived(store, lifecycle)
    }

    // MARK: - D14: a poll whose answer was lost

    func testD14APollTransportFailureIsAskedAgainAfterTheInterval() async throws
    {
        let transport = ScriptedTransport([
            startAnswer(),
            .failure(SPFNTransportError.connectivity("the network went away")),
            approvedAnswer(),
        ])
        let sleeper = ScriptedSleeper()
        let lifecycle = makeLifecycle(
            transport,
            store: InMemoryKeyStore(),
            keys: [try testKey()],
            keyIDs: ["key-test-0001"],
            sleeper: sleeper
        )

        let result = try await lifecycle.enrollByDeviceCode { _, _ in }

        XCTAssertEqual(result.keyID, "key-test-0001")
        XCTAssertEqual(
            sleeper.waits,
            [intervalMillis, intervalMillis],
            "a lost answer costs the same wait, not a new one"
        )
        let polled = try await polledDeviceCodes(transport)
        XCTAssertEqual(polled, [Self.deviceCode, Self.deviceCode])
    }

    // MARK: - D15: the deadline, judged on the proof clock

    /// D15: the wait ends locally at the expiry `start` named, and it is the proof clock
    /// — the one `core.time` synchronised — that says so. The device's own wall clock is
    /// moved past the expiry first and changes nothing, which is the point: a device with
    /// a wrong clock must not give up early or poll a code it was told is dead.
    func testD15TheProofClockDeadlineEndsTheWaitWithoutAnotherPoll() async throws
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
        let transport = ScriptedTransport([startAnswer(), pendingAnswer(intervalMillis)])
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

        let thrown = await failure { _ = try await lifecycle.enrollByDeviceCode { _, _ in } }

        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .deviceCodeExpired)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 2, "a wall clock past the expiry does not end the wait; the proof clock does")
        try await assertNoKeySurvived(store, lifecycle)
    }

    // MARK: - D16: the caller withdraws

    func testD16CancellationDuringTheWaitDestroysTheKeyAndSendsNoPoll() async throws
    {
        let waiting = Gate()
        let transport = ScriptedTransport([startAnswer(), approvedAnswer()])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(
            transport,
            store: store,
            keys: [try testKey()],
            keyIDs: ["key-test-0001"],
            sleeper: ScriptedSleeper
            { _ in
                await waiting.open()
                // Far longer than the test takes; the cancellation is what ends it.
                try? await Task.sleep(nanoseconds: 30_000_000_000)
            }
        )

        let call = Task { try await lifecycle.enrollByDeviceCode { _, _ in } }
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
    }

    // MARK: - D17: an answer that names a branch it does not carry

    /// D17: the branch is read from `status`, and the fields that branch requires are
    /// then required. A default would turn a server that answered half a login into a
    /// login — `passwordChangeRequired` absent read as `false` is a rule the account may
    /// not have.
    func testD17ABranchMissingItsOwnFieldsIsADecodingRefusal() async throws
    {
        let incomplete = [
            "{\"status\":\"pending\"}",
            "{\"passwordChangeRequired\":false,\"status\":\"approved\"}",
            "{\"status\":\"approved\",\"userId\":\"user-test-0001\"}",
            "{\"intervalMillis\":0,\"status\":\"pending\"}",
            "{\"intervalMillis\":-1,\"status\":\"pending\"}",
        ]
        for body in incomplete
        {
            let transport = ScriptedTransport([startAnswer(), .success(.json(200, body))])
            let store = InMemoryKeyStore()
            let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

            let thrown = await failure { _ = try await lifecycle.enrollByDeviceCode { _, _ in } }

            XCTAssertEqual(
                thrown as? SPFNClientError,
                .decoding(.notTheDeclaredResponse),
                "'\(body)' was accepted"
            )
            let calls = await transport.callCount
            XCTAssertEqual(calls, 2, "no further poll is sent for \(body)")
            try await assertNoKeySurvived(store, lifecycle)
        }
    }

    // MARK: - Assembly

    /// D9–D11 differ only in the code, so the shared body is written once.
    private func assertPollRefusalEndsTheWait(
        _ wireCode: String,
        httpStatus: Int,
        expected: SPFNGeneratedErrorCode
    ) async throws
    {
        let transport = ScriptedTransport([
            startAnswer(),
            .success(.json(httpStatus, ExecuteFixtures.errorEnvelope(code: wireCode))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let thrown = await failure { _ = try await lifecycle.enrollByDeviceCode { _, _ in } }

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

    /// What every non-approved exit owes. On this platform the key is a value that dies
    /// with the call's frame, so "destroyed" is "nothing was persisted and nothing can
    /// sign"; the Android counterpart additionally asserts the Keystore alias is gone,
    /// which is the same rule costing different work (P15).
    private func assertNoKeySurvived(_ store: InMemoryKeyStore, _ lifecycle: SPFNKeyLifecycle) async throws
    {
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.activeSlot), "nothing was persisted")
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.candidateSlot))
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .unenrolled)
    }

    /// The `deviceCode` every poll request carried, in order.
    private func polledDeviceCodes(_ transport: ScriptedTransport) async throws -> [String]
    {
        let received = await transport.received
        return try received
            .filter { $0.url.hasSuffix(SPFNGeneratedOperations.authDevicePoll.path) }
            .map { try SPFNCanonicalJSON.parse($0.body ?? []).object().text("deviceCode") }
    }

    private func startAnswer() -> Result<SPFNTransportResponse, any Error>
    {
        .success(.json(200, "{\"deviceCode\":\"\(Self.deviceCode)\",\"expiresAtMillis\":\(expiresAtMillis),"
            + "\"intervalMillis\":\(intervalMillis),\"userCode\":\"\(Self.userCode)\"}"))
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

    private func enrol(_ store: InMemoryKeyStore, key: SPFNCustodyKey, clientID: String) throws
    {
        try store.save(
            key.record(clientID: clientID, createdAtMillis: startedAtMillis),
            slot: SPFNKeyLifecycle.activeSlot
        )
    }

    private func makeLifecycle(
        _ transport: any SPFNTransport,
        store: InMemoryKeyStore,
        keys: [SPFNCustodyKey],
        keyIDs: [String],
        clock: FakeClock? = nil,
        proofClock: FakeClock? = nil,
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

/// What the `showCode` callback was handed, and what the store held when it ran.
///
/// A class rather than captured locals: the callback is `@Sendable` and runs wherever the
/// call happens to be, so the recording has to be somewhere both sides can see safely.
final class ShownCodes: @unchecked Sendable
{
    private let lock = NSLock()
    private var codes: [String] = []
    private var expiryValues: [Int64] = []
    private var savedRecords: [SPFNStoredKey?] = []

    func record(userCode: String, expiresAtMillis: Int64, saved: SPFNStoredKey?)
    {
        lock.lock()
        defer { lock.unlock() }
        codes.append(userCode)
        expiryValues.append(expiresAtMillis)
        savedRecords.append(saved)
    }

    var count: Int
    {
        lock.lock()
        defer { lock.unlock() }
        return codes.count
    }

    var userCodes: [String]
    {
        lock.lock()
        defer { lock.unlock() }
        return codes
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

/// A one-shot gate: `wait()` returns once `open()` has been called, whichever order the
/// two happen in. The concurrency cases need one call to be provably inside its wait
/// before the other starts, and a sleep would be a race the test loses half the time.
actor Gate
{
    private var opened = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    func open()
    {
        opened = true
        for continuation in waiting
        {
            continuation.resume()
        }
        waiting = []
    }

    func wait() async
    {
        guard !opened
        else
        {
            return
        }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            waiting.append(continuation)
        }
    }
}
