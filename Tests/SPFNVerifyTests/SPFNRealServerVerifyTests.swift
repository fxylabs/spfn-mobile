// SPFN Mobile — the Swift SDK against a real SPFN server.
//
// Everything else this repository proves, it proves against tools/reference-server — a
// fake built from this repository's own reading of the contract. These nine cases run
// against the published @spfn/auth on a real PostgreSQL instead, using only operations a
// deployed SPFN server serves (decision 01kz854yzrnaf5r2cp8bews7h5):
//
//   (r1) /_auth/login with the seeded account enrolls a freshly generated key
//   (r2) a proven auth.keys.list under the enrolled key names it, active
//   (r3) auth.keys.rotate under the old key registers the candidate
//   (r4) the new key proves a call while the replaced key is refused with SESSION_REVOKED
//   (r5) auth.keys.revoke removes a named key and auth.keys.revokeAll spares the caller
//
// Contract 0.10.0's device-code flow adds four more, the real-server twins of the
// reference suite's cases g, h, j and k:
//
//   (r6) a device waiting on a code is approved from a device already signed in
//   (r7) a denial ends the wait and leaves the waiting device holding nothing
//   (r8) a second approval of one code is refused and the first one still stands
//   (r9) an approval nobody proved is refused and applies nothing
//
// Reference case i — a code that expired — has no twin here. Judging an expiry means
// moving a clock, a real server has none to move, and sitting out the contract's ten
// minute TTL for one assertion is a hang rather than a case.
//
// No /control surface and no social enrolment: a real server has no test hooks and
// verifies id_tokens against the provider's real keys, so the cases are written to need
// neither. Every case records a receipt; run.sh fails the run when one is missing,
// because a skipped XCTest is reported as a passing XCTest.
//
// The seeded account allows 10 logins per minute (the server's auth-login rate limit).
// One run spends 7: six for r1–r5, and one for the approver the four device cells share.
// A device-code enrolment costs no login at all, which is why the waiting devices are
// free and the approver is a static task rather than a fixture each cell builds. A second
// full run inside the same minute can meet a 429 on its later cases — a visible
// unknown-code failure, not a silent skip.

import Dispatch
import Foundation
import SPFNAuth
import SPFNClient
import SPFNCore
import SPFNGenerated
import XCTest

final class SPFNRealServerVerifyTests: XCTestCase
{
    private static let timeoutMillis: Int64 = 10_000

    // MARK: - (r1)

    func testR1LoginEnrollsAFreshlyGeneratedKey() async throws
    {
        let fixture = try Fixture()

        let enrolled = try await fixture.enroll()

        XCTAssertFalse(enrolled.response.userId.isEmpty)
        XCTAssertFalse(enrolled.response.publicId.isEmpty)
        XCTAssertEqual(enrolled.response.email, fixture.environment.email)

        try fixture.environment.record("swift-r1")
    }

    // MARK: - (r2)

    func testR2ProvenKeysListNamesTheEnrolledKey() async throws
    {
        let fixture = try Fixture()

        let enrolled = try await fixture.enroll()
        let listed = try await fixture.client(signingWith: enrolled.provider).execute(
            Calls.keysList,
            request: SPFNListKeysRequest()
        )

        let own = try XCTUnwrap(
            listed.keys.first { $0.keyId == enrolled.key.keyID },
            "the key this case enrolled is missing from its own keys.list"
        )
        XCTAssertTrue(own.isActive)
        XCTAssertNil(own.revokedAtMillis)
        XCTAssertFalse(own.fingerprintPrefix.isEmpty)
        XCTAssertTrue(
            enrolled.fingerprint.hasPrefix(own.fingerprintPrefix),
            "the server's fingerprint prefix does not match the key this case generated"
        )

        try fixture.environment.record("swift-r2")
    }

    // MARK: - (r3)

    func testR3RotateUnderTheOldKeyRegistersTheCandidate() async throws
    {
        let fixture = try Fixture()

        let enrolled = try await fixture.enroll()
        let lifecycle = fixture.lifecycle(enrolled: enrolled)

        let rotated = try await lifecycle.rotate()

        XCTAssertEqual(rotated.clientID, enrolled.response.userId)
        XCTAssertNotEqual(rotated.keyID, enrolled.key.keyID)
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .enrolled)

        try fixture.environment.record("swift-r3")
    }

    // MARK: - (r4)

    func testR4NewKeyProvesWhileTheReplacedKeyIsRefused() async throws
    {
        let fixture = try Fixture()

        let enrolled = try await fixture.enroll()
        let lifecycle = fixture.lifecycle(enrolled: enrolled)
        let rotated = try await lifecycle.rotate()

        // The new key proves a real operation.
        let loadedNew = try await lifecycle.activeProvider()
        let newProvider = try XCTUnwrap(loadedNew)
        XCTAssertEqual(newProvider.keyID, rotated.keyID)
        let listed = try await fixture.client(signingWith: newProvider).execute(
            Calls.keysList,
            request: SPFNListKeysRequest()
        )
        XCTAssertNotNil(listed.keys.first { $0.keyId == rotated.keyID })

        // The replaced key is refused at the revocation step. Rotation records the old
        // key as revoked, and `clientProofV1.revocationRule` fixes the outcome for any
        // revoked keyId: SESSION_REVOKED, never PROOF_INVALID. The first run against
        // `@spfn/auth@0.2.0-beta.91` answered exactly that while this test still
        // expected PROOF_INVALID from the pre-contract-envelope server.
        do
        {
            _ = try await fixture.client(signingWith: enrolled.provider).execute(
                Calls.keysList,
                request: SPFNListKeysRequest()
            )
            XCTFail("the replaced key must not prove anything")
        }
        catch SPFNClientError.auth(let refusal)
        {
            XCTAssertEqual(refusal.code, .sessionRevoked)
        }

        try fixture.environment.record("swift-r4")
    }

    // MARK: - (r5)

    func testR5RevokeRemovesANamedKeyAndRevokeAllSparesTheCaller() async throws
    {
        let fixture = try Fixture()

        // Two keys on the seeded account — two devices that can sign for it.
        let keeper = try await fixture.enroll()
        let victim = try await fixture.enroll()

        let revoked = try await fixture.client(signingWith: keeper.provider).execute(
            Calls.keysRevoke,
            request: SPFNRevokeKeyRequest(keyId: victim.key.keyID)
        )
        XCTAssertEqual(revoked.keyId, victim.key.keyID)
        XCTAssertFalse(revoked.selfRevoked)

        let swept = try await fixture.client(signingWith: keeper.provider).execute(
            Calls.keysRevokeAll,
            request: SPFNRevokeAllKeysRequest()
        )
        XCTAssertFalse(swept.currentKeyRevoked, "revokeAll spared the calling key by default")

        // The caller survived its own sweep and still proves.
        let listed = try await fixture.client(signingWith: keeper.provider).execute(
            Calls.keysList,
            request: SPFNListKeysRequest()
        )
        let own = try XCTUnwrap(listed.keys.first { $0.keyId == keeper.key.keyID })
        XCTAssertTrue(own.isActive)

        try fixture.environment.record("swift-r5")
    }

    // MARK: - The device-code cells: two SDKs, one code
    //
    // Each of the four runs a waiting device with a fresh in-memory store beside the one
    // approver the whole class shares. The waiting device's call blocks until somebody
    // answers, so it runs in its own task and the cell does the approver's half in
    // between — which is the shape the flow has in life.
    //
    // The waiting side obeys the interval the server named, and this deployment names
    // five seconds, so every cell here spends one real wait. The approver answers
    // immediately after the code is shown, so the waiting device's first poll settles it
    // and no cell waits twice.

    // MARK: - (r6)

    func testR6AnApprovedWaitingDeviceEnrollsOnTheApproversAccount() async throws
    {
        let fixture = try Fixture()
        let approver = try await Self.approverTask.value
        let approving = fixture.client(signingWith: approver.provider)
        let waiting = fixture.waitingDevice()
        let shown = ShownCode()

        let signIn = Self.startSignIn(waiting.lifecycle, showing: shown)
        let userCode = await shown.value()

        // The approver looks before it decides, and what it is shown is derived from the
        // waiting device's own public key rather than from anything the approver chose.
        let described = try await approving.execute(
            Calls.deviceInfo,
            request: SPFNDeviceAuthInfoRequest(userCode: userCode)
        )
        XCTAssertFalse(described.fingerprintPrefix.isEmpty)
        XCTAssertTrue(
            waiting.fingerprint.hasPrefix(described.fingerprintPrefix),
            "the prefix the approver is shown does not open the waiting key's fingerprint"
        )
        XCTAssertEqual(described.platform, .ios)

        _ = try await approving.execute(
            Calls.deviceApprove,
            request: SPFNApproveDeviceAuthRequest(userCode: userCode)
        )

        let settled = try await signIn.value
        // Registered the moment it exists rather than at the end of the cell: an
        // assertion that fails below must not leave a key on the seeded account.
        await Self.ledger.enrolled(waitingKeyID: settled.keyID)

        XCTAssertEqual(settled.clientID, approver.response.userId, "the account the device joined is the approver's")
        XCTAssertEqual(settled.keyID, waiting.keyID)
        // Whether the seeded account owes a password change is the deployment's business:
        // an expected value here would be a value read off one run rather than one the
        // contract states. What is asserted is that the flow carried the field through as
        // the declared type, which a `pending` poll's absent value once could not.
        XCTAssertTrue(
            type(of: settled.passwordChangeRequired) == Bool.self,
            "the approval's login rule reached the caller as the boolean the contract declares"
        )
        let state = try await waiting.lifecycle.state()
        XCTAssertEqual(state, .enrolled)

        // The key the approval registered is a key this server signs for.
        let loaded = try await waiting.lifecycle.activeProvider()
        let provider = try XCTUnwrap(loaded)
        let listed = try await fixture.client(signingWith: provider).execute(
            Calls.keysList,
            request: SPFNListKeysRequest()
        )
        let own = try XCTUnwrap(
            listed.keys.first { $0.keyId == settled.keyID },
            "the key the approval registered is missing from its own keys.list"
        )
        XCTAssertTrue(own.isActive)
        XCTAssertNil(own.revokedAtMillis)

        try fixture.environment.record("swift-r6")
    }

    // MARK: - (r7)

    func testR7ADenialEndsTheWaitAndLeavesNoKey() async throws
    {
        let fixture = try Fixture()
        let approver = try await Self.approverTask.value
        let waiting = fixture.waitingDevice()
        let shown = ShownCode()

        let signIn = Self.startSignIn(waiting.lifecycle, showing: shown)
        let userCode = await shown.value()

        let denied = try await fixture.client(signingWith: approver.provider).execute(
            Calls.deviceDeny,
            request: SPFNDenyDeviceAuthRequest(userCode: userCode)
        )
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

        try fixture.environment.record("swift-r7")
    }

    // MARK: - (r8)

    func testR8ASecondApprovalIsRefusedAndTheFirstStillStands() async throws
    {
        let fixture = try Fixture()
        let approver = try await Self.approverTask.value
        let approving = fixture.client(signingWith: approver.provider)
        let waiting = fixture.waitingDevice()
        let shown = ShownCode()

        let signIn = Self.startSignIn(waiting.lifecycle, showing: shown)
        let userCode = await shown.value()

        _ = try await approving.execute(
            Calls.deviceApprove,
            request: SPFNApproveDeviceAuthRequest(userCode: userCode)
        )
        do
        {
            _ = try await approving.execute(
                Calls.deviceApprove,
                request: SPFNApproveDeviceAuthRequest(userCode: userCode)
            )
            XCTFail("a decision on a device is made once")
        }
        catch SPFNClientError.server(let refusal)
        {
            XCTAssertEqual(refusal.code, .deviceAuthAlreadyHandledError)
        }

        // The refusal changed nothing: the approval that came first is still the one the
        // waiting device enrolls on.
        let settled = try await signIn.value
        await Self.ledger.enrolled(waitingKeyID: settled.keyID)
        XCTAssertEqual(settled.clientID, approver.response.userId, "the first approval still stands")
        XCTAssertEqual(settled.keyID, waiting.keyID)
        let state = try await waiting.lifecycle.state()
        XCTAssertEqual(state, .enrolled)

        try fixture.environment.record("swift-r8")
    }

    // MARK: - (r9)

    func testR9AnApprovalNobodyProvedAppliesNothing() async throws
    {
        let fixture = try Fixture()
        let approver = try await Self.approverTask.value
        let waiting = fixture.waitingDevice()
        let shown = ShownCode()

        let signIn = Self.startSignIn(waiting.lifecycle, showing: shown)
        let userCode = await shown.value()

        // `approve` is the one call that binds an account, so it is the one that has to be
        // proved. Sent through the transport rather than the client, because the SDK
        // cannot be talked into sending a proven operation unproven — which is itself the
        // point, and is why this is asserted against the server instead.
        let operation = SPFNGeneratedOperations.authDeviceApprove
        let unproven = try await fixture.transport.execute(
            SPFNTransportRequest(
                method: operation.method,
                url: fixture.environment.baseURL + operation.path,
                headers: [("content-type", "application/json")],
                body: SPFNCanonicalJSON.encode(try SPFNApproveDeviceAuthRequest(userCode: userCode).canonicalValue()),
                timeoutMillis: SPFNRealServerVerifyTests.timeoutMillis
            )
        )
        XCTAssertGreaterThanOrEqual(unproven.statusCode, 400, "an unproven approval must not be admitted")

        // Which refusal it is stays the deployment's answer. This app answered 401
        // `UnauthorizedError` on 2026-09-02 — a code the mobile contract does not list —
        // where the reference server refuses the same call with CONTRACT_UNSUPPORTED.
        // Both are refusals, and pinning either would pin a real server to one of two
        // defensible answers. What must hold on every server is below: the record the
        // unproven call named was not touched, which the approval that still works shows.
        _ = try await fixture.client(signingWith: approver.provider).execute(
            Calls.deviceApprove,
            request: SPFNApproveDeviceAuthRequest(userCode: userCode)
        )
        let settled = try await signIn.value
        await Self.ledger.enrolled(waitingKeyID: settled.keyID)
        XCTAssertEqual(settled.clientID, approver.response.userId)
        let state = try await waiting.lifecycle.state()
        XCTAssertEqual(state, .enrolled)

        try fixture.environment.record("swift-r9")
    }

    // MARK: - What the four device cells are built out of

    /// The one approver the device cells share, enrolled once for all four.
    ///
    /// `/_auth/login` is the only call in this file that spends the seeded account's
    /// ten-a-minute budget; r1–r5 already spend six of it, and a device-code enrolment
    /// spends none. So the four cells enrol one approver between them: whichever cell
    /// asks first starts this task, and the other three await the identity it already
    /// holds.
    ///
    /// Started by the first cell that reads it, never by anything else. r5's
    /// `auth.keys.revokeAll` spares only its own caller, so an approver enrolled before r5
    /// ran would be swept away by it; enrolled on first use, it comes after r5 in the
    /// alphabetical order the runner takes these methods in.
    private static let approverTask = Task<Enrolled, any Error>
    {
        let fixture = try Fixture()
        let enrolled = try await fixture.enroll()
        await SPFNRealServerVerifyTests.ledger.adopt(enrolled)
        return enrolled
    }

    /// The waiting device: one lifecycle over its own store, and the key it will park.
    private struct WaitingDevice
    {
        let lifecycle: SPFNKeyLifecycle
        let keyID: String

        /// The SHA-256 of the parked key's SPKI DER — what the prefix the approver is
        /// shown has to open. There is no record to read it from until the approval
        /// lands, which is why the key is generated before the lifecycle asks for it.
        let fingerprint: String
    }

    /// Starts a waiting device's sign-in as its own task. A helper rather than an inline
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

    /// The label the waiting device gives itself; display only, nothing is authorized by
    /// it, and the approver is shown it as the device asking to be let in.
    private static let deviceName = "Swift verify waiting device"

    // MARK: - Taking the device cells' keys off the seeded account again

    /// What the device cells put on the seeded account, and what can take it off.
    ///
    /// The account is the same one on every run, so a run that kept its keys would add
    /// four of them each time. Each cell registers the key its approval registered; the
    /// class's teardown revokes those and then the approver's own, which is what revokes
    /// them.
    private actor DeviceCellLedger
    {
        private var approver: Enrolled?
        private var waitingKeyIDs: [String] = []

        func adopt(_ enrolled: Enrolled)
        {
            approver = enrolled
        }

        func enrolled(waitingKeyID: String)
        {
            waitingKeyIDs.append(waitingKeyID)
        }

        /// Everything to revoke and the identity that may revoke it, emptied as it is
        /// handed over so nothing is revoked twice.
        func drain() -> (approver: Enrolled, waitingKeyIDs: [String])?
        {
            guard let approver
            else
            {
                return nil
            }
            let pending = waitingKeyIDs
            waitingKeyIDs = []
            self.approver = nil
            return (approver, pending)
        }
    }

    private static let ledger = DeviceCellLedger()

    /// The class's last act: every key the device cells added is revoked, the approver's
    /// last because it is the key the others are revoked with.
    ///
    /// `class func tearDown` is synchronous and revocation is not, so the work runs in a
    /// task this waits on, and the wait is bounded — a cleanup that hung would hold the
    /// whole run open, and a key left behind is a mess to sweep rather than a failed
    /// assertion. Nothing here reads `approverTask`: reading it would start it, and spend
    /// a login on a run that had no device cell in it at all.
    override class func tearDown()
    {
        let finished = DispatchSemaphore(value: 0)
        Task.detached
        {
            await SPFNRealServerVerifyTests.revokeWhatTheDeviceCellsEnrolled()
            finished.signal()
        }
        _ = finished.wait(timeout: .now() + 60)
        super.tearDown()
    }

    private static func revokeWhatTheDeviceCellsEnrolled() async
    {
        guard let drained = await ledger.drain(), let fixture = try? Fixture()
        else
        {
            return
        }
        let revoking = fixture.client(signingWith: drained.approver.provider)
        for keyID in drained.waitingKeyIDs + [drained.approver.key.keyID]
        {
            _ = try? await revoking.execute(
                Calls.keysRevoke,
                request: SPFNRevokeKeyRequest(keyId: keyID)
            )
        }
    }

    // MARK: - Fixture

    /// One enrolled identity: the key this case generated, the signer built on it, and
    /// the server's answer that named its owner.
    private struct Enrolled: Sendable
    {
        let key: SPFNCustodyKey
        let fingerprint: String
        let provider: SPFNSecureEnclaveKeyProvider
        let response: SPFNLoginResponse
    }

    private struct Fixture: Sendable
    {
        let environment: SPFNVerifyEnvironment
        let transport: any SPFNTransport

        init() throws
        {
            environment = try SPFNVerifyEnvironment.current()
            transport = SPFNURLSessionTransport()
        }

        /// Enrolls a freshly generated software key through /_auth/login.
        ///
        /// Software custody on purpose: the runner is a headless process with no
        /// enclave entitlement, and hardware custody is the COMPATIBILITY axis.
        func enroll() async throws -> Enrolled
        {
            let key = SPFNCustodyKey.generate(
                keyID: UUID().uuidString.lowercased(),
                preferSecureEnclave: false
            )
            let fingerprint = SPFNDigest.sha256Hex(key.publicKeySpkiDer)

            let response = try await client(signingWith: nil).execute(
                Calls.login,
                request: SPFNLoginRequest(
                    email: environment.email,
                    password: environment.password,
                    publicKey: Data(key.publicKeySpkiDer).base64EncodedString(),
                    keyId: key.keyID,
                    fingerprint: fingerprint,
                    algorithm: .es256
                )
            )

            return Enrolled(
                key: key,
                fingerprint: fingerprint,
                provider: SPFNSecureEnclaveKeyProvider(clientID: response.userId, key: key),
                response: response
            )
        }

        /// A device holding no key at all, ready to ask for one by code — what the four
        /// device cells wait with.
        ///
        /// The key it will park is generated here and handed to the lifecycle through the
        /// same two seams the reference suite uses, rather than recovered afterwards.
        /// These cells never rotate, so one waiting device mints exactly one key, and
        /// naming it in advance is what lets a cell state the fingerprint the approver is
        /// about to be shown a prefix of.
        func waitingDevice() -> WaitingDevice
        {
            let key = SPFNCustodyKey.generate(
                keyID: "key-\(UUID().uuidString.lowercased())",
                preferSecureEnclave: false
            )
            return WaitingDevice(
                lifecycle: SPFNKeyLifecycle(
                    transport: transport,
                    store: VerifyKeyStore(),
                    baseURL: environment.baseURL,
                    timeoutMillis: SPFNRealServerVerifyTests.timeoutMillis,
                    newKeyID: { key.keyID },
                    makeKey: { _ in key }
                ),
                keyID: key.keyID,
                fingerprint: SPFNDigest.sha256Hex(key.publicKeySpkiDer)
            )
        }

        /// A lifecycle whose active slot holds [enrolled] — what r3 and r4 rotate with.
        /// The store is in memory: these cases prove the wire, and the persistence seam
        /// has its own suite.
        func lifecycle(enrolled: Enrolled) -> SPFNKeyLifecycle
        {
            let store = VerifyKeyStore()
            // The record is written the way enrollment would have written it, so the
            // lifecycle cannot tell this key from one it enrolled itself.
            try? store.save(
                enrolled.key.record(
                    clientID: enrolled.response.userId,
                    createdAtMillis: Int64(Date().timeIntervalSince1970 * 1000)
                ),
                slot: SPFNKeyLifecycle.activeSlot
            )
            return SPFNKeyLifecycle(
                transport: transport,
                store: store,
                baseURL: environment.baseURL,
                timeoutMillis: SPFNRealServerVerifyTests.timeoutMillis,
                makeKey: { SPFNCustodyKey.generate(keyID: $0, preferSecureEnclave: false) }
            )
        }

        /// A client signing with [provider], or an unproven one for the login call —
        /// whose signer is never consulted, mirroring the lifecycle's enrollment client.
        func client(signingWith provider: SPFNSecureEnclaveKeyProvider?) -> SPFNClient
        {
            let keyProvider: any SPFNKeyProvider = provider
                ?? SPFNSecureEnclaveKeyProvider(
                    clientID: "",
                    key: SPFNCustodyKey.generate(keyID: "unenrolled", preferSecureEnclave: false)
                )
            let session = SPFNSession(
                transport: transport,
                keyProvider: keyProvider,
                baseURL: environment.baseURL,
                timeoutMillis: SPFNRealServerVerifyTests.timeoutMillis
            )
            return SPFNClient(
                transport: transport,
                session: session,
                timeoutMillis: SPFNRealServerVerifyTests.timeoutMillis
            )
        }
    }

    /// The lifecycle's store for one verify run. In memory on purpose.
    private final class VerifyKeyStore: SPFNKeyStore, @unchecked Sendable
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

    /// The call descriptors for the operations these cases drive.
    private enum Calls
    {
        static let login = SPFNCall<SPFNLoginRequest, SPFNLoginResponse>(
            operation: SPFNGeneratedOperations.authEnrollLogin,
            encode: { try $0.canonicalValue() },
            decode: { try SPFNLoginResponse(canonical: $0) }
        )

        static let keysList = SPFNCall<SPFNListKeysRequest, SPFNListKeysResponse>(
            operation: SPFNGeneratedOperations.authKeysList,
            encode: { try $0.canonicalValue() },
            decode: { try SPFNListKeysResponse(canonical: $0) }
        )

        static let keysRevoke = SPFNCall<SPFNRevokeKeyRequest, SPFNRevokeKeyResponse>(
            operation: SPFNGeneratedOperations.authKeysRevoke,
            encode: { try $0.canonicalValue() },
            decode: { try SPFNRevokeKeyResponse(canonical: $0) }
        )

        static let keysRevokeAll = SPFNCall<SPFNRevokeAllKeysRequest, SPFNRevokeAllKeysResponse>(
            operation: SPFNGeneratedOperations.authKeysRevokeAll,
            encode: { try $0.canonicalValue() },
            decode: { try SPFNRevokeAllKeysResponse(canonical: $0) }
        )

        // The approver's three device-code operations, the same descriptors the reference
        // suite sends: `deny` is the contract's one bodyless operation and is built through
        // the factory, never by hand.
        static let deviceInfo = SPFNCall<SPFNDeviceAuthInfoRequest, SPFNDeviceAuthInfoResponse>(
            operation: SPFNGeneratedOperations.authDeviceInfo,
            encode: { try $0.canonicalValue() },
            decode: { try SPFNDeviceAuthInfoResponse(canonical: $0) }
        )

        static let deviceApprove = SPFNCall<SPFNApproveDeviceAuthRequest, SPFNDeviceAuthInfoResponse>(
            operation: SPFNGeneratedOperations.authDeviceApprove,
            encode: { try $0.canonicalValue() },
            decode: { try SPFNDeviceAuthInfoResponse(canonical: $0) }
        )

        static let deviceDeny = SPFNCall<SPFNDenyDeviceAuthRequest, SPFNNoResponse>.noResponse(
            operation: SPFNGeneratedOperations.authDeviceDeny,
            encode: { try $0.canonicalValue() }
        )
    }
}

// MARK: - What the device cells inject

/// What the `showCode` callback was handed, and a way to wait for it.
///
/// The callback is synchronous — the SDK calls it the moment `start` answers and does not
/// wait for it — so this cannot be an actor: it is a lock plus the continuations of
/// whoever asked for the code before it existed. The reference suite has the same class
/// for the same reason; the two suites are separate targets and share no code.
private final class ShownCode: @unchecked Sendable
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
