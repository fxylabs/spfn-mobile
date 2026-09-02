// SPFN Mobile — the Swift SDK against a real socket.
//
// The same five cases the Android suite runs, against the same reference server, from a
// different process and a different codebase. That is what makes the pair evidence: two
// independent implementations agreeing with one server about one contract.
//
//   (a) three operations answer their declared types with the values sent
//   (b) an expired session costs exactly one re-handshake and then succeeds
//   (c) a revocation the client cannot fix surfaces as an auth failure
//   (d) a request replayed byte for byte is refused
//   (e) a timeout and a cancellation both work while a real server is holding the call
//
// Contract 0.10.0 adds the device-code flow, which needs two SDKs at once — one waiting
// and one approving — and so is five more:
//   (f) enrollment, a proof, a rotation and the new key's proof
//   (g) a waiting device is approved from a device already signed in, and can then prove
//   (h) a denial ends the wait, over the contract's one bodyless operation
//   (i) an expired code ends the wait locally, before the server is asked
//   (j) a second approval of one code is refused
//   (k) an approval nobody proved is refused by admission
//
// Every case records a receipt. `sh tools/reference-server/run-integration.sh` checks the
// receipts afterwards, so this suite skipping — which XCTest reports as success — is
// turned into a failure by the runner rather than being read as a pass.

import Foundation
import SPFNAuth
import SPFNClient
import SPFNCore
import SPFNGenerated
import XCTest

final class SPFNReferenceIntegrationTests: XCTestCase
{
    // TEST KEYPAIR ONLY — NOT A SECRET. The synthetic identity the reference server,
    // the primitives dev server and Contracts/fixtures/proof/proof-input.json all
    // pre-register the public half of; the private half is published on purpose and
    // authenticates nothing. Restated from the fixture's testKeyPair because this
    // suite runs from its own process and keeps no fixture loader.
    private static let clientID = "client-test-0001"
    private static let keyID = "key-test-0001"
    private static let privateKeyPkcs8B64 =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgMv3D4UvmGKjFeG3m"
        + "yLLfwlcOAQ9n8qoFmwrgGWBErsShRANCAARLvGS2Mr58zJ1PtRlx+5b+/NT2tT/5"
        + "E9VVAoqDGsHWx31uHo3VuqIHBT/O7D38tpD3WVY95ZI306VPw6UNhchi"

    /// Long enough that neither the timeout nor the cancellation can be a coincidence.
    private static let holdMillis: Int64 = 3_000

    // MARK: - (a)

    func testCaseAHandshakeEchoAndItemsListRoundTrip() async throws
    {
        let fixture = try await Fixture.start()

        let echoed = try await fixture.client.execute(
            SPFNGeneratedCalls.echoSend,
            request: SPFNEchoRequest(message: "over the wire", sequence: 42)
        )
        XCTAssertEqual(echoed.message, "over the wire")
        XCTAssertEqual(echoed.sequence, 42)
        XCTAssertGreaterThan(echoed.serverTimeMillis, 0)

        let first = try await fixture.client.execute(SPFNGeneratedCalls.itemsList, request: SPFNListItemsRequest(limit: 2))
        XCTAssertEqual(first.items.map(\.id), ["item-0001", "item-0002"])
        XCTAssertEqual(first.items.map(\.name), ["alpha", "bravo"])
        XCTAssertEqual(first.nextCursor, "item-0002")

        let rest = try await fixture.client.execute(
            SPFNGeneratedCalls.itemsList,
            request: SPFNListItemsRequest(limit: 10, cursor: "item-0002")
        )
        XCTAssertEqual(rest.items.map(\.id), ["item-0003", "item-0004", "item-0005"])
        XCTAssertNil(rest.nextCursor)

        let stats = try await fixture.control.stats()
        XCTAssertEqual(stats.handshakeCount, 1)
        XCTAssertEqual(stats.echoCount, 1)
        XCTAssertEqual(stats.itemsListCount, 2)
        XCTAssertEqual(stats.refusalCount, 0)

        try fixture.environment.record("swift-a")
    }

    // MARK: - (b)

    func testCaseBExpiredSessionCostsExactlyOneReHandshake() async throws
    {
        let fixture = try await Fixture.start()

        _ = try await fixture.client.execute(SPFNGeneratedCalls.echoSend, request: SPFNEchoRequest(message: "before", sequence: 1))
        let opened = try await fixture.control.stats()
        XCTAssertEqual(opened.handshakeCount, 1)

        // The server drops the session without touching the expiry it advertised, so the
        // client goes on believing in it and presents it — which is the only way to reach
        // the refusal that a re-handshake exists to answer.
        try await fixture.control.expireSessions()

        let after = try await fixture.client.execute(SPFNGeneratedCalls.echoSend, request: SPFNEchoRequest(message: "after", sequence: 2))
        XCTAssertEqual(after.message, "after")

        let stats = try await fixture.control.stats()
        XCTAssertEqual(stats.handshakeCount, 2, "exactly one re-handshake")
        XCTAssertEqual(stats.echoCount, 2, "the refused attempt was not applied")
        XCTAssertEqual(stats.refusalCount, 1)

        try fixture.environment.record("swift-b")
    }

    // MARK: - (c)

    func testCaseCRevokedKeySurfacesAfterTheReHandshakeFailsToo() async throws
    {
        let fixture = try await Fixture.start()

        _ = try await fixture.client.execute(SPFNGeneratedCalls.echoSend, request: SPFNEchoRequest(message: "before", sequence: 1))
        try await fixture.control.revokeKey(Self.keyID)

        do
        {
            _ = try await fixture.client.execute(SPFNGeneratedCalls.echoSend, request: SPFNEchoRequest(message: "after", sequence: 2))
            XCTFail("a revoked key must not answer an operation")
        }
        catch SPFNClientError.auth(let failure)
        {
            XCTAssertEqual(failure.code, .sessionRevoked)
            XCTAssertEqual(failure.httpStatus, 401)
        }

        let stats = try await fixture.control.stats()
        XCTAssertEqual(stats.refusalCount, 2, "the operation and the re-handshake were both refused")
        XCTAssertEqual(stats.handshakeCount, 1, "no second session was ever opened")

        try fixture.environment.record("swift-c")
    }

    // MARK: - (d)

    func testCaseDReplayedRequestIsRefused() async throws
    {
        let fixture = try await Fixture.start()

        let operation = SPFNGeneratedOperations.echoSend
        let body = SPFNCanonicalJSON.encode(try SPFNEchoRequest(message: "replay me", sequence: 3).canonicalValue())

        // Assembled through the session, so the nonce, the timestamp and the proof are the
        // ones the SDK would really have sent. Replaying is then the SDK's own request sent
        // twice, not a hand-built approximation of one.
        let headers = try await fixture.session.proofHeaders(operation: operation, canonicalBody: body)
        let request = SPFNTransportRequest(
            method: operation.method,
            url: fixture.environment.baseURL + operation.path,
            headers: headers,
            body: body,
            timeoutMillis: 5_000
        )

        let first = try await fixture.transport.execute(request)
        XCTAssertEqual(first.statusCode, 200)

        let replayed = try await fixture.transport.execute(request)
        XCTAssertEqual(replayed.statusCode, 401)
        let envelope = try SPFNErrorEnvelope.decode(try SPFNCanonicalJSON.parse(replayed.body))
        XCTAssertEqual(envelope.code, "PROOF_REPLAYED")

        try fixture.environment.record("swift-d")
    }

    // MARK: - (e)

    func testCaseETimeoutAndCancellationAgainstAWaitingServer() async throws
    {
        let fixture = try await Fixture.start(timeoutMillis: 400)

        _ = try await fixture.client.execute(SPFNGeneratedCalls.echoSend, request: SPFNEchoRequest(message: "warm up", sequence: 1))

        try await fixture.control.hold(
            path: SPFNGeneratedOperations.echoSend.path,
            millis: Self.holdMillis,
            count: 1
        )
        do
        {
            _ = try await fixture.client.execute(SPFNGeneratedCalls.echoSend, request: SPFNEchoRequest(message: "too slow", sequence: 2))
            XCTFail("a held request must not answer inside the deadline")
        }
        catch SPFNClientError.transport(let error)
        {
            XCTAssertEqual(error, .timedOut)
        }

        try await fixture.control.hold(
            path: SPFNGeneratedOperations.echoSend.path,
            millis: Self.holdMillis,
            count: 1
        )
        try await assertCancellationStopsTheCall(fixture)

        try fixture.environment.record("swift-e")
    }

    private func assertCancellationStopsTheCall(_ fixture: Fixture) async throws
    {
        let client = fixture.client
        let startedAt = Date()
        let call = Task
        {
            try await client.execute(SPFNGeneratedCalls.echoSend, request: SPFNEchoRequest(message: "give up", sequence: 3))
        }

        try await Task.sleep(nanoseconds: 200_000_000)
        call.cancel()

        do
        {
            _ = try await call.value
            XCTFail("a cancelled call must not answer")
        }
        catch
        {
            let elapsedMillis = Int64(Date().timeIntervalSince(startedAt) * 1000)
            XCTAssertLessThan(
                elapsedMillis,
                Self.holdMillis,
                "cancellation waited for the server instead of stopping the call"
            )
            let stopped = error is CancellationError ||
                (error as? SPFNClientError) == SPFNClientError.transport(.cancelled)
            XCTAssertTrue(stopped, "expected a cancellation, got \(error)")
        }
    }

    // MARK: - (f)

    /// The REST enrollment surface end to end: enrollment, a proof round trip with the
    /// enrolled key, a rotation proved by it, and a proof round trip with the new key —
    /// while the replaced key is refused at the revocation step with SESSION_REVOKED.
    ///
    /// Runs only when the runner says the target implements the REST surface
    /// (`SPFN_INTEGRATION_REST_OPS=1`, which run-integration.sh sets in local mode).
    /// The primitives dev server carries the three dev operations and no `/_auth`
    /// surface, so against it this case is out of scope and its receipt is not expected.
    func testCaseFEnrollmentProofRotationAndNewKeyProof() async throws
    {
        let fixture = try await Fixture.start()
        guard ProcessInfo.processInfo.environment["SPFN_INTEGRATION_REST_OPS"] == "1"
        else
        {
            let reason = "SPFN integration case f SKIPPED: SPFN_INTEGRATION_REST_OPS is not set, "
                + "so the target is assumed to carry only the dev three-operation surface."
            print(reason)
            throw XCTSkip(reason)
        }

        // Software custody on purpose: the runner is a headless process with no
        // enclave entitlement, and hardware custody is the COMPATIBILITY axis.
        let store = IntegrationKeyStore()
        let lifecycle = SPFNKeyLifecycle(
            transport: fixture.transport,
            store: store,
            baseURL: fixture.environment.baseURL,
            proofClock: fixture.proofClock,
            makeKey: { SPFNCustodyKey.generate(keyID: $0, preferSecureEnclave: false) }
        )

        // The token is minted inside the sign-in closure because it has to carry the
        // nonce, and the nonce is the fingerprint of a key that does not exist until the
        // enrollment generates it. That ordering is the whole reason the entry point
        // takes a closure. Google echoes the raw value, so `requestValue` here is the
        // fingerprint the reference server compares the body against.
        let userID = "user-swift-f-0001"
        let enrolled = try await lifecycle.enroll(provider: "google")
        { nonce in
            "spfn-test-idtoken.google.\(userID).\(nonce.requestValue)"
        }
        XCTAssertEqual(enrolled.clientID, userID)
        XCTAssertTrue(enrolled.isNewUser)

        // A proven round trip under the enrolled key: handshake, echo, exact values.
        let loadedFirst = try await lifecycle.activeProvider()
        let firstProvider = try XCTUnwrap(loadedFirst)
        let echoed = try await fixture.client(signingWith: firstProvider).execute(
            SPFNGeneratedCalls.echoSend,
            request: SPFNEchoRequest(message: "enrolled key proves", sequence: 61)
        )
        XCTAssertEqual(echoed.message, "enrolled key proves")

        // Rotate under the old key's proof; the lifecycle swaps to the new key.
        let rotated = try await lifecycle.rotate()
        XCTAssertEqual(rotated.clientID, userID)
        XCTAssertNotEqual(rotated.keyID, enrolled.keyID)

        let loadedNew = try await lifecycle.activeProvider()
        let newProvider = try XCTUnwrap(loadedNew)
        XCTAssertEqual(newProvider.keyID, rotated.keyID)
        let again = try await fixture.client(signingWith: newProvider).execute(
            SPFNGeneratedCalls.echoSend,
            request: SPFNEchoRequest(message: "rotated key proves", sequence: 62)
        )
        XCTAssertEqual(again.message, "rotated key proves")

        // The replaced key is refused at the revocation step. Rotation records the old
        // key as revoked — the real server writes it as one, with a reason — and
        // `clientProofV1.revocationRule` fixes the outcome for a revoked keyId:
        // SESSION_REVOKED, never PROOF_INVALID.
        do
        {
            _ = try await fixture.client(signingWith: firstProvider).execute(
                SPFNGeneratedCalls.echoSend,
                request: SPFNEchoRequest(message: "stale key", sequence: 63)
            )
            XCTFail("the replaced key must not prove anything")
        }
        catch SPFNClientError.auth(let refusal)
        {
            XCTAssertEqual(refusal.code, .sessionRevoked)
        }

        try fixture.environment.record("swift-f")
    }

    // MARK: - The device-code flow: two SDKs, one code
    //
    // Every case here runs SDK A (waiting, with a fresh store) and SDK B (already signed
    // in, the approver) against one server. A's entry point blocks until somebody
    // answers, so it runs in its own task and the case does B's half in between — which
    // is exactly the shape the flow has in life.

    /// Case g, the happy path: A shows a code, B recognises the device by its fingerprint
    /// prefix and approves, A's next poll lands the approval, and A can then prove with
    /// the key that approval registered — and rotate it, because a key enrolled this way
    /// has to be indistinguishable from one `enroll()` produced.
    func testCaseGAWaitingDeviceIsApprovedAndCanThenProveAndRotate() async throws
    {
        let fixture = try await Fixture.start()
        try skipUnlessRestOps("g")

        let approver = try await enrolApprover(fixture, userID: "user-swift-g-0001")
        let waiting = waitingDevice(fixture)
        let shown = ShownCode()

        let signIn = Self.startSignIn(waiting.lifecycle, showing: shown)
        let userCode = await shown.value()

        // B looks before it decides: the answer names the device that is waiting, and the
        // prefix is over A's real public key rather than over anything B chose.
        let described = try await approver.execute(SPFNGeneratedCalls.authDeviceInfo, request: SPFNDeviceAuthInfoRequest(userCode: userCode))
        XCTAssertEqual(described.deviceName, Self.deviceName)
        XCTAssertEqual(
            described.fingerprintPrefix,
            String(SPFNDigest.sha256Hex(try XCTUnwrap(waiting.mintedPublicKey())).prefix(Self.fingerprintPrefixLength))
        )

        let approved = try await approver.execute(
            SPFNGeneratedCalls.authDeviceApprove,
            request: SPFNApproveDeviceAuthRequest(userCode: userCode)
        )
        XCTAssertEqual(approved.deviceName, Self.deviceName, "approve answers with the device it just let in")

        let settled = try await signIn.value
        XCTAssertEqual(settled.clientID, approver.clientID, "the approver's account is the one A joined")
        XCTAssertEqual(settled.keyID, waiting.keyID)
        XCTAssertFalse(settled.passwordChangeRequired)
        let state = try await waiting.lifecycle.state()
        XCTAssertEqual(state, .enrolled)

        let loaded = try await waiting.lifecycle.activeProvider()
        let provider = try XCTUnwrap(loaded)
        let echoed = try await fixture.client(signingWith: provider).execute(
            SPFNGeneratedCalls.echoSend,
            request: SPFNEchoRequest(message: "approved key proves", sequence: 71)
        )
        XCTAssertEqual(echoed.message, "approved key proves")

        // A record this flow saved must be one every other lifecycle path accepts.
        let rotated = try await waiting.lifecycle.rotate()
        XCTAssertEqual(rotated.clientID, approver.clientID)
        XCTAssertNotEqual(rotated.keyID, settled.keyID)
        let loadedNew = try await waiting.lifecycle.activeProvider()
        let rotatedEcho = try await fixture.client(signingWith: try XCTUnwrap(loadedNew)).execute(
            SPFNGeneratedCalls.echoSend,
            request: SPFNEchoRequest(message: "rotated after device sign-in", sequence: 72)
        )
        XCTAssertEqual(rotatedEcho.message, "rotated after device sign-in")

        try fixture.environment.record("swift-g")
    }

    /// Case h, the denial — and the end-to-end proof of the contract's one bodyless
    /// operation: `deny` answers 204 with an empty body, the SDK decodes that as the unit
    /// value, and A ends holding no key at all.
    func testCaseHADenialEndsTheWaitAndLeavesNoKey() async throws
    {
        let fixture = try await Fixture.start()
        try skipUnlessRestOps("h")

        let approver = try await enrolApprover(fixture, userID: "user-swift-h-0001")
        let waiting = waitingDevice(fixture)
        let shown = ShownCode()

        let signIn = Self.startSignIn(waiting.lifecycle, showing: shown)
        let userCode = await shown.value()

        let denied = try await approver.execute(SPFNGeneratedCalls.authDeviceDeny, request: SPFNDenyDeviceAuthRequest(userCode: userCode))
        XCTAssertEqual(denied, SPFNNoResponse.value, "a bodyless operation answers with the unit value")

        do
        {
            _ = try await signIn.value
            XCTFail("a denied device must not enroll")
        }
        catch SPFNClientError.server(let refusal)
        {
            XCTAssertEqual(refusal.code, .deviceAuthDeniedError)
        }
        let state = try await waiting.lifecycle.state()
        XCTAssertEqual(state, .unenrolled)
        let provider = try await waiting.lifecycle.activeProvider()
        XCTAssertNil(provider, "a refused device keeps no key")

        try fixture.environment.record("swift-h")
    }

    /// Case i, expiry: the wait ends on A's own deadline before the server is asked, and a
    /// poll sent by hand afterwards is what shows the server would have refused it too.
    ///
    /// A's sleeper is injected here and nowhere else in this file. The case has to advance
    /// the server's clock while A is between two polls, and racing a real wait would make
    /// the assertion "no poll was sent" true or false by scheduling. A's proof clock is
    /// injected for the same reason it exists: the shipped process clock anchors once and
    /// then runs on this machine's monotonic source, which cannot follow a server clock a
    /// test moved fifteen minutes forward.
    func testCaseIAnExpiredCodeEndsTheWaitBeforeTheServerIsAsked() async throws
    {
        let fixture = try await Fixture.start()
        try skipUnlessRestOps("i")
        try skipUnlessTestClock()

        let clockMoved = Gate()
        let waiting = waitingDevice(
            fixture,
            sleeper: GatedSleeper(gate: clockMoved),
            proofClock: ServerSampledProofClock()
        )

        let shown = ShownCode()
        let signIn = Self.startSignIn(waiting.lifecycle, showing: shown)
        _ = await shown.value()

        guard try await fixture.control.advanceClock(millis: Self.expiryAdvanceMillis)
        else
        {
            signIn.cancel()
            return XCTFail(
                "case i was expected to run but the target's clock cannot be moved; "
                    + "run without SPFN_INTEGRATION_TEST_CLOCK=1 when the target is on the wall clock"
            )
        }
        await clockMoved.open()

        do
        {
            _ = try await signIn.value
            XCTFail("an expired code must not enroll")
        }
        catch
        {
            XCTAssertEqual(error as? SPFNKeyLifecycleError, .deviceCodeExpired, "the expiry is A's own judgment")
        }
        let polls = await waiting.transport.pollCount()
        XCTAssertEqual(polls, 0, "no poll was sent for a code A knows is dead")
        let state = try await waiting.lifecycle.state()
        XCTAssertEqual(state, .unenrolled)

        // And the server's own answer, on a code this case parks by hand: the two ends
        // agree that an expired record is refused rather than kept waiting on.
        let parked = try await fixture.client.execute(
            SPFNGeneratedCalls.authDeviceStart,
            request: Self.startRequest(keyID: "key-swift-i-0002", publicKeySpkiDer: Self.freshKeySpkiDer())
        )
        _ = try await fixture.control.advanceClock(millis: Self.expiryAdvanceMillis)
        do
        {
            _ = try await fixture.client.execute(
                SPFNGeneratedCalls.authDevicePoll,
                request: SPFNPollDeviceAuthRequest(deviceCode: parked.deviceCode)
            )
            XCTFail("the server must refuse an expired code")
        }
        catch SPFNClientError.server(let refusal)
        {
            XCTAssertEqual(refusal.code, .deviceAuthExpiredError)
        }

        try fixture.environment.record("swift-i")
    }

    /// Case j: a decision on a device is made once, and the second approval is told so.
    func testCaseJASecondApprovalOfOneCodeIsRefused() async throws
    {
        let fixture = try await Fixture.start()
        try skipUnlessRestOps("j")

        let approver = try await enrolApprover(fixture, userID: "user-swift-j-0001")
        let waiting = waitingDevice(fixture)
        let shown = ShownCode()

        let signIn = Self.startSignIn(waiting.lifecycle, showing: shown)
        let userCode = await shown.value()

        _ = try await approver.execute(SPFNGeneratedCalls.authDeviceApprove, request: SPFNApproveDeviceAuthRequest(userCode: userCode))
        do
        {
            _ = try await approver.execute(SPFNGeneratedCalls.authDeviceApprove, request: SPFNApproveDeviceAuthRequest(userCode: userCode))
            XCTFail("a second approval must be refused")
        }
        catch SPFNClientError.server(let refusal)
        {
            XCTAssertEqual(refusal.code, .deviceAuthAlreadyHandledError)
        }

        let settled = try await signIn.value
        XCTAssertEqual(settled.clientID, approver.clientID, "the first approval still stands")

        try fixture.environment.record("swift-j")
    }

    /// Case k: `approve` is the one call that binds an account, so it is the one that has
    /// to be proved. Sent through the transport rather than the client, because the SDK
    /// cannot be talked into sending a proven operation unproven — which is itself the
    /// point, and is why this is asserted against the server instead.
    func testCaseKAnApprovalNobodyProvedIsRefusedByAdmission() async throws
    {
        let fixture = try await Fixture.start()
        try skipUnlessRestOps("k")

        let approver = try await enrolApprover(fixture, userID: "user-swift-k-0001")
        let waiting = waitingDevice(fixture)
        let shown = ShownCode()

        let signIn = Self.startSignIn(waiting.lifecycle, showing: shown)
        let userCode = await shown.value()

        let operation = SPFNGeneratedOperations.authDeviceApprove
        let unproven = try await fixture.transport.execute(
            SPFNTransportRequest(
                method: operation.method,
                url: fixture.environment.baseURL + operation.path,
                headers: [("content-type", "application/json")],
                body: SPFNCanonicalJSON.encode(try SPFNApproveDeviceAuthRequest(userCode: userCode).canonicalValue()),
                timeoutMillis: 5_000
            )
        )
        XCTAssertGreaterThanOrEqual(unproven.statusCode, 400, "an unproven approval must not be applied")
        let envelope = try SPFNErrorEnvelope.decode(try SPFNCanonicalJSON.parse(unproven.body))
        XCTAssertEqual(envelope.code, "CONTRACT_UNSUPPORTED")

        // The record was not touched, which the approval that still works proves.
        _ = try await approver.execute(SPFNGeneratedCalls.authDeviceApprove, request: SPFNApproveDeviceAuthRequest(userCode: userCode))
        let settled = try await signIn.value
        XCTAssertEqual(settled.clientID, approver.clientID)

        try fixture.environment.record("swift-k")
    }

    // MARK: - What the five device cases are built out of

    /// SDK B: a device already signed in, which is who approves.
    private struct Approver
    {
        let clientID: String
        let client: SPFNClient

        func execute<Request, Response>(
            _ call: SPFNCall<Request, Response>,
            request: Request
        ) async throws -> Response
        {
            try await client.execute(call, request: request)
        }
    }

    /// SDK A: the waiting device, with its own store, minted keys and counted transport.
    private struct WaitingDevice
    {
        let lifecycle: SPFNKeyLifecycle
        let keyID: String
        let transport: CountingTransport
        let minted: MintedKeys

        /// A's public key before the approval lands. There is no record to read it from
        /// until then, so the mint is recorded as it happens.
        func mintedPublicKey() -> [UInt8]?
        {
            minted.firstPublicKeySpkiDer
        }
    }

    private func enrolApprover(_ fixture: Fixture, userID: String) async throws -> Approver
    {
        let lifecycle = SPFNKeyLifecycle(
            transport: fixture.transport,
            store: IntegrationKeyStore(),
            baseURL: fixture.environment.baseURL,
            proofClock: fixture.proofClock,
            makeKey: { SPFNCustodyKey.generate(keyID: $0, preferSecureEnclave: false) }
        )
        let enrolled = try await lifecycle.enroll(provider: "google")
        { nonce in
            "spfn-test-idtoken.google.\(userID).\(nonce.requestValue)"
        }
        let loaded = try await lifecycle.activeProvider()
        let provider = try XCTUnwrap(loaded)
        return Approver(clientID: enrolled.clientID, client: fixture.client(signingWith: provider))
    }

    private func waitingDevice(
        _ fixture: Fixture,
        sleeper: any SPFNSleeper = SPFNTaskSleeper(),
        proofClock: (any SPFNProofClock)? = nil
    ) -> WaitingDevice
    {
        let keyID = "key-\(UUID().uuidString.lowercased())"
        let minted = MintedKeys()
        let transport = CountingTransport(fixture.transport)
        let lifecycle = SPFNKeyLifecycle(
            transport: transport,
            store: IntegrationKeyStore(),
            baseURL: fixture.environment.baseURL,
            proofClock: proofClock ?? fixture.proofClock,
            sleeper: sleeper,
            // The first key is the named one, so a case can talk about the key it parked;
            // every later one is fresh, because a rotation that reused the id would mint
            // its candidate over the old key's own name.
            newKeyID: { minted.nextKeyID(first: keyID) },
            makeKey: { minted.record(SPFNCustodyKey.generate(keyID: $0, preferSecureEnclave: false)) }
        )
        return WaitingDevice(lifecycle: lifecycle, keyID: keyID, transport: transport, minted: minted)
    }

    private static func startRequest(keyID: String, publicKeySpkiDer: [UInt8]) -> SPFNStartDeviceAuthRequest
    {
        SPFNStartDeviceAuthRequest(
            publicKey: Data(publicKeySpkiDer).base64EncodedString(),
            keyId: keyID,
            fingerprint: SPFNDigest.sha256Hex(publicKeySpkiDer),
            algorithm: .es256,
            deviceName: deviceName,
            platform: .ios
        )
    }

    private static func freshKeySpkiDer() -> [UInt8]
    {
        SPFNCustodyKey.generate(keyID: "key-swift-probe", preferSecureEnclave: false).publicKeySpkiDer
    }

    /// The device cases need the `/_auth` surface. In process it is always there; against
    /// a named target it is not, so the run says which it has and a case that cannot be
    /// arranged skips loudly rather than asserting something weaker.
    private func skipUnlessRestOps(_ caseLetter: String) throws
    {
        guard ProcessInfo.processInfo.environment["SPFN_INTEGRATION_REST_OPS"] == "1"
        else
        {
            let reason = "SPFN integration case \(caseLetter) SKIPPED: SPFN_INTEGRATION_REST_OPS is not set, "
                + "so the target is assumed to carry only the dev three-operation surface."
            print(reason)
            throw XCTSkip(reason)
        }
    }

    /// Case i additionally needs a clock a test may move, which a launched server has not.
    private func skipUnlessTestClock() throws
    {
        guard ProcessInfo.processInfo.environment["SPFN_INTEGRATION_TEST_CLOCK"] == "1"
        else
        {
            let reason = "SPFN integration case i SKIPPED: the target runs on the wall clock, "
                + "so an expired device code cannot be arranged."
            print(reason)
            throw XCTSkip(reason)
        }
    }

    /// The label the waiting device gives itself; display only, nothing is authorized by it.
    private static let deviceName = "Swift waiting device"

    /// Past any device code's TTL, so the record is expired whatever state it is in.
    private static let expiryAdvanceMillis: Int64 = 900_000

    /// Upstream `KEY_FINGERPRINT_PREFIX_LENGTH`, which is what the approver is shown.
    private static let fingerprintPrefixLength = 8

    // MARK: - Fixture

    /// One SDK client pointed at the running reference server, with the server reset.
    private struct Fixture: Sendable
    {
        let environment: SPFNIntegrationEnvironment
        let control: SPFNReferenceControlClient
        let transport: any SPFNTransport

        /// One `SPFNProcessServerClock` per fixture, and never `.shared`.
        ///
        /// The shipped clock anchors once per instance: it fetches `core.time` on its
        /// first read and afterwards derives time from this machine's monotonic source.
        /// Case i moves the launched server's clock fifteen minutes forward, so an anchor
        /// taken before that is fifteen minutes behind afterwards — and a process-wide
        /// anchor is taken by whichever case ran first and then kept for every case after
        /// it. A fresh instance per fixture re-anchors after any move, so each case is
        /// signing against the server time its own case really sees.
        let proofClock: SPFNProcessServerClock
        let session: SPFNSession
        let client: SPFNClient

        static func start(timeoutMillis: Int64 = 5_000) async throws -> Fixture
        {
            let environment = try SPFNIntegrationEnvironment.current()
            let control = SPFNReferenceControlClient(environment: environment)

            // Reset first: a case that revoked a key must not decide the outcome of the
            // case that runs after it.
            try await control.reset()

            guard let privateKeyDer = Data(base64Encoded: SPFNReferenceIntegrationTests.privateKeyPkcs8B64)
            else
            {
                throw SPFNIntegrationFailure.control("the test private key constant is not base64")
            }

            let transport = SPFNURLSessionTransport()
            let proofClock = SPFNProcessServerClock()
            let session = SPFNSession(
                transport: transport,
                keyProvider: try SPFNSoftwareKeyProvider(
                    clientID: SPFNReferenceIntegrationTests.clientID,
                    keyID: SPFNReferenceIntegrationTests.keyID,
                    privateKeyDer: [UInt8](privateKeyDer)
                ),
                baseURL: environment.baseURL,
                clock: proofClock,
                timeoutMillis: timeoutMillis
            )
            return Fixture(
                environment: environment,
                control: control,
                transport: transport,
                proofClock: proofClock,
                session: session,
                client: SPFNClient(transport: transport, session: session, timeoutMillis: timeoutMillis)
            )
        }

        /// A client over a session signing with [provider] — what case f uses to prove
        /// with a key the lifecycle enrolled rather than the pre-registered fixture key.
        func client(signingWith provider: any SPFNKeyProvider, timeoutMillis: Int64 = 5_000) -> SPFNClient
        {
            let signingSession = SPFNSession(
                transport: transport,
                keyProvider: provider,
                baseURL: environment.baseURL,
                clock: proofClock,
                timeoutMillis: timeoutMillis
            )
            return SPFNClient(transport: transport, session: signingSession, timeoutMillis: timeoutMillis)
        }
    }

    /// The lifecycle's store for one integration run. In memory: what case f proves is
    /// the wire, and the persistence seam has its own suite.
    private final class IntegrationKeyStore: SPFNKeyStore, @unchecked Sendable
    {
        private let lock = NSLock()
        private var records: [String: SPFNStoredKey] = [:]

        func load(slot: String) throws -> SPFNStoredKey?
        {
            lock.lock()
            defer { lock.unlock() }
            return records[slot]
        }

        func save(_ record: SPFNStoredKey, slot: String) throws
        {
            lock.lock()
            defer { lock.unlock() }
            records[slot] = record
        }

        func delete(slot: String) throws
        {
            lock.lock()
            defer { lock.unlock() }
            records[slot] = nil
        }
    }

    /// Starts the waiting device's sign-in as its own task. A helper rather than an inline
    /// `Task { ... }` with a trailing closure, which the Swift 6.2 region-isolation checker
    /// refuses to analyse ("pattern that the region based isolation checker does not
    /// understand how to check").
    private static func startSignIn(
        _ lifecycle: SPFNKeyLifecycle,
        showing shown: ShownCode
    ) -> Task<SPFNDeviceCodeEnrollmentResult, any Error>
    {
        Task
        {
            try await lifecycle.enrollByDeviceCode(
                deviceName: Self.deviceName,
                showCode: { code, _ in shown.record(code) }
            )
        }
    }
}

// MARK: - What the device cases inject

/// What the `showCode` callback was handed, and a way to wait for it.
///
/// The callback is synchronous — the SDK calls it the moment `start` answers and does not
/// wait for it — so this cannot be an actor: it is a lock plus the continuations of
/// whoever asked for the code before it existed.
final class ShownCode: @unchecked Sendable
{
    private let lock = NSLock()
    private var code: String?
    private var waiting: [CheckedContinuation<String, Never>] = []

    func record(_ userCode: String)
    {
        lock.lock()
        code = userCode
        let pending = waiting
        waiting = []
        lock.unlock()
        for continuation in pending
        {
            continuation.resume(returning: userCode)
        }
    }

    func value() async -> String
    {
        await withCheckedContinuation { (continuation: CheckedContinuation<String, Never>) in
            lock.lock()
            if let code
            {
                lock.unlock()
                continuation.resume(returning: code)
                return
            }
            waiting.append(continuation)
            lock.unlock()
        }
    }
}

/// A one-shot gate: `wait()` returns once `open()` has been called, whichever order the
/// two happen in. Case i needs the waiting device to be provably between two polls before
/// the clock moves, and a sleep would be a race the case loses half the time.
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

/// A wait that ends when a case says so rather than when a duration elapses.
struct GatedSleeper: SPFNSleeper
{
    let gate: Gate

    func sleep(millis _: Int64) async throws
    {
        await gate.wait()
    }
}

/// Counts what one SDK sent, by path.
///
/// Case i asserts that a device whose code expired sent no poll at all, and "no request"
/// is only assertable per client: the fixture's transport is shared with the approver,
/// which is busy sending its own.
actor CountingTransport: SPFNTransport
{
    private let delegate: any SPFNTransport
    private var urls: [String] = []

    init(_ delegate: any SPFNTransport)
    {
        self.delegate = delegate
    }

    func pollCount() -> Int
    {
        urls.filter { $0.hasSuffix(SPFNGeneratedOperations.authDevicePoll.path) }.count
    }

    func execute(_ request: SPFNTransportRequest) async throws -> SPFNTransportResponse
    {
        urls.append(request.url)
        return try await delegate.execute(request)
    }
}

/// The keys one waiting device minted, in order.
///
/// On this platform a parked key is a value inside the call that made it, so a case that
/// has to know the waiting device's public half before the approval lands records the
/// mint as it happens rather than reading a record that does not exist yet.
final class MintedKeys: @unchecked Sendable
{
    private let lock = NSLock()
    private var keys: [SPFNCustodyKey] = []
    private var issued = 0

    func nextKeyID(first: String) -> String
    {
        lock.lock()
        defer { lock.unlock() }
        issued += 1
        return issued == 1 ? first : "key-\(UUID().uuidString.lowercased())"
    }

    @discardableResult
    func record(_ key: SPFNCustodyKey) -> SPFNCustodyKey
    {
        lock.lock()
        defer { lock.unlock() }
        keys.append(key)
        return key
    }

    var firstPublicKeySpkiDer: [UInt8]?
    {
        lock.lock()
        defer { lock.unlock() }
        return keys.first?.publicKeySpkiDer
    }
}

/// A proof clock that asks the server every time.
///
/// The shipped process clock anchors once and then runs on this machine's monotonic
/// source, which is right in life and wrong for the one case that moves the server's
/// clock fifteen minutes forward: the anchor would not follow, and the device would judge
/// its deadline against a server time that stopped being true. Sampling per call is what
/// the Android suite gets for free from an in-process server whose test clock is also its
/// monotonic source.
struct ServerSampledProofClock: SPFNProofClock
{
    func nowMillis(
        transport: any SPFNTransport,
        baseURL: String,
        timeoutMillis: Int64
    ) async throws -> Int64
    {
        let operation = SPFNGeneratedOperations.coreTime
        let response = try await transport.execute(
            SPFNTransportRequest(
                method: operation.method,
                url: baseURL + operation.path,
                headers: [],
                body: nil,
                timeoutMillis: timeoutMillis
            )
        )
        return try SPFNServerTimeResponse(canonical: try SPFNCanonicalJSON.parse(response.body)).serverTimeMillis
    }
}
