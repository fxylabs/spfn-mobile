// SPFN Mobile — the Swift SDK against a real SPFN server.
//
// Everything else this repository proves, it proves against tools/reference-server — a
// fake built from this repository's own reading of the contract. These five cases run
// against the published @spfn/auth on a real PostgreSQL instead, using only operations a
// deployed SPFN server serves (decision 01kz854yzrnaf5r2cp8bews7h5):
//
//   (r1) /_auth/login with the seeded account enrolls a freshly generated key
//   (r2) a proven auth.keys.list under the enrolled key names it, active
//   (r3) auth.keys.rotate under the old key registers the candidate
//   (r4) the new key proves a call while the replaced key is refused with PROOF_INVALID
//   (r5) auth.keys.revoke removes a named key and auth.keys.revokeAll spares the caller
//
// No /control surface and no social enrolment: a real server has no test hooks and
// verifies id_tokens against the provider's real keys, so the cases are written to need
// neither. Every case records a receipt; run.sh fails the run when one is missing,
// because a skipped XCTest is reported as a passing XCTest.
//
// The seeded account allows 10 logins per minute (the server's auth-login rate limit).
// One run spends 6, so a second full run inside the same minute can meet a 429 on its
// later cases — a visible unknown-code failure, not a silent skip.

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

        // The replaced key is refused, and the refusal discloses nothing: the same
        // PROOF_INVALID an unregistered key answers.
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
            XCTAssertEqual(refusal.code, .proofInvalid)
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

    // MARK: - Fixture

    /// One enrolled identity: the key this case generated, the signer built on it, and
    /// the server's answer that named its owner.
    private struct Enrolled
    {
        let key: SPFNCustodyKey
        let fingerprint: String
        let provider: SPFNSecureEnclaveKeyProvider
        let response: SPFNLoginResponse
    }

    private struct Fixture
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
            encode: { $0.canonicalValue },
            decode: { try SPFNLoginResponse(canonical: $0) }
        )

        static let keysList = SPFNCall<SPFNListKeysRequest, SPFNListKeysResponse>(
            operation: SPFNGeneratedOperations.authKeysList,
            encode: { $0.canonicalValue },
            decode: { try SPFNListKeysResponse(canonical: $0) }
        )

        static let keysRevoke = SPFNCall<SPFNRevokeKeyRequest, SPFNRevokeKeyResponse>(
            operation: SPFNGeneratedOperations.authKeysRevoke,
            encode: { $0.canonicalValue },
            decode: { try SPFNRevokeKeyResponse(canonical: $0) }
        )

        static let keysRevokeAll = SPFNCall<SPFNRevokeAllKeysRequest, SPFNRevokeAllKeysResponse>(
            operation: SPFNGeneratedOperations.authKeysRevokeAll,
            encode: { $0.canonicalValue },
            decode: { try SPFNRevokeAllKeysResponse(canonical: $0) }
        )
    }
}
