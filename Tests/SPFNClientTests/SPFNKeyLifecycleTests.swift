// SPFN Mobile — the key lifecycle contract: M1–M7 pinned.
//
// The flows run over the same scripted transport the execute suite uses, with the
// fixture keypairs injected as the "generated" keys — which is what lets the wire
// bytes a flow produces be compared against Contracts/fixtures byte for byte instead
// of against whatever the implementation happened to send (the P10 rule).
//
// SpfnKeyLifecycleTest.kt is the counterpart and uses corresponding case names.

#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

import Foundation
import XCTest
import SPFNAuth
import SPFNClient
import SPFNCore
import SPFNGenerated

final class SPFNKeyLifecycleTests: XCTestCase
{
    private let baseURL = "https://example.invalid"
    private let ttlMillis: Int64 = SPFNGeneratedContract.keyPolicyTtlDays * 24 * 60 * 60 * 1_000

    // MARK: - M1 + M2: enrollment sends the fixture bytes and persists the identity

    func testEnrollSendsTheExactFixtureBytesAndPersistsTheIdentity() async throws
    {
        let fixture = try enrollmentFixture()
        let oauthNative = try fixture["oauthNative"].orFail("oauthNative").object()
        let value = try oauthNative["value"].orFail("value").object()

        let transport = ScriptedTransport([
            .success(.json(200, "{\"isNewUser\":true,\"keyId\":\"key-test-0001\",\"userId\":\"user-test-0001\"}")),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = try makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        // The fixture's token is answered as the sign-in's result. The nonce is not
        // passed at all any more: the lifecycle derives it from the key it generated,
        // and the body assertion below is what proves it derived the fixture's value.
        let fixtureToken = try value.text("idToken")
        let result = try await lifecycle.enroll(provider: try oauthNative.text("provider"))
        { _ in fixtureToken }

        XCTAssertEqual(result, SPFNEnrollmentResult(clientID: "user-test-0001", keyID: "key-test-0001", isNewUser: true))

        let received = await transport.received
        let sent = try XCTUnwrap(received.first)
        XCTAssertEqual(sent.method, "POST")
        XCTAssertEqual(sent.url, baseURL + (try oauthNative.text("path")))
        XCTAssertEqual(
            sent.headers.map { [$0.0, $0.1] },
            try oauthNative.headerPairs("headers").map { [$0.0, $0.1] }
                + SPFNClientIdentity.headers.map { [$0.0, $0.1] },
            "an unproven enrollment carries the fixture's headers and then the identity"
        )
        XCTAssertEqual(
            String(decoding: sent.body ?? [], as: UTF8.self),
            try oauthNative.text("canonical"),
            "the enrollment body must be the fixture bytes exactly (M1)"
        )

        // M2: the identity the server issued is what future proofs carry.
        let active = try XCTUnwrap(store.loadSync(SPFNKeyLifecycle.activeSlot))
        XCTAssertEqual(active.clientID, "user-test-0001")
        XCTAssertEqual(active.keyID, "key-test-0001")
        let loadedProvider = try await lifecycle.activeProvider()
        let provider = try XCTUnwrap(loadedProvider)
        XCTAssertEqual(provider.clientID, "user-test-0001")
        XCTAssertEqual(provider.keyID, "key-test-0001")
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .enrolled)
    }

    /// The fingerprint the flow computes must be the fixture's own derivation of the
    /// same rule — the two platforms' byte-level agreement rides on this value (P9).
    func testTheEnrollmentFingerprintMatchesTheFixtureDerivation() throws
    {
        let fixture = try enrollmentFixture()
        let fingerprints = try fixture["fingerprints"].orFail("fingerprints").object()
        let key = try testKey()

        XCTAssertEqual(
            SPFNDigest.sha256Hex(key.publicKeySpkiDer),
            try fingerprints.text("testKeySpkiSha256Hex")
        )
        XCTAssertEqual(
            SPFNDigest.sha256Hex(try wrongKey().publicKeySpkiDer),
            try fingerprints.text("wrongKeySpkiSha256Hex")
        )
    }

    // MARK: - M3: a failed enrollment leaves no orphan

    func testAFailedEnrollmentDestroysTheGeneratedKey() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(409, ExecuteFixtures.errorEnvelope(code: "CONTRACT_UNSUPPORTED"))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = try makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "google") { _ in "idtoken-test" }
        }

        XCTAssertNotNil(thrown)
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.activeSlot), "nothing was persisted for a refused enrollment")
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.candidateSlot))
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .unenrolled)
    }

    func testEnrollRefusesAProviderThatIsNotAPathSegment() async throws
    {
        let transport = ScriptedTransport([])
        let lifecycle = try makeLifecycle(transport, store: InMemoryKeyStore(), keys: [], keyIDs: [])

        // The full-width "ｇoogle" is the P9 case: a Unicode-aware character class
        // would wave it through where the ASCII-explicit rule must not.
        for provider in ["", "Google", "google/../evil", "goo gle", "google{", "구글", "ｇoogle"]
        {
            let thrown = await failure
            {
                _ = try await lifecycle.enroll(provider: provider) { _ in "t" }
            }
            XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .malformedProviderID, "'\(provider)' was accepted")
        }
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0)
    }

    // MARK: - M4: rotation swaps on success, with the fixture's exact wire shape

    func testRotateSendsTheWireVectorAndSwapsToTheCandidate() async throws
    {
        let vector = try WireFixtures.vector("rotate-key")
        let expected = try vector.headerPairs("headers")
        let byName = Dictionary(uniqueKeysWithValues: expected)
        let issuedAt = try XCTUnwrap(Int64(try XCTUnwrap(byName[SPFNWireHeaders.issuedAtMillis])))
        let nonce = try XCTUnwrap(byName[SPFNWireHeaders.nonce])

        let transport = ScriptedTransport([
            .success(.json(200, "{\"keyId\":\"key-test-0002\",\"success\":true}")),
        ])
        let store = InMemoryKeyStore()
        try enrol(store, key: try testKey(), clientID: "client-test-0001", createdAt: issuedAt)
        let lifecycle = try makeLifecycle(
            transport,
            store: store,
            keys: [try wrongKey()],
            keyIDs: ["key-test-0002"],
            clock: FakeClock(issuedAt),
            nonces: [nonce]
        )

        let result = try await lifecycle.rotate()

        XCTAssertEqual(result, SPFNEnrollmentResult(clientID: "client-test-0001", keyID: "key-test-0002", isNewUser: false))

        let received = await transport.received
        let sent = try XCTUnwrap(received.first)
        XCTAssertEqual(sent.url, baseURL + (try vector.text("path")))
        try assertHeadersMatchWireVector(sent.headers, expected: expected, vector: vector)
        XCTAssertEqual(String(decoding: sent.body ?? [], as: UTF8.self), try vector.text("canonicalBody"))

        // Exactly one signable key, and it is the new one.
        let active = try XCTUnwrap(store.loadSync(SPFNKeyLifecycle.activeSlot))
        XCTAssertEqual(active.keyID, "key-test-0002")
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.candidateSlot))
        let loadedProvider = try await lifecycle.activeProvider()
        let provider = try XCTUnwrap(loadedProvider)
        XCTAssertEqual(provider.keyID, "key-test-0002")
        let signature = try provider.sign([UInt8]("probe".utf8))
        let publicKey = try P256.Signing.PublicKey(derRepresentation: Data(try wrongKey().publicKeySpkiDer))
        XCTAssertTrue(publicKey.isValidSignature(
            try P256.Signing.ECDSASignature(rawRepresentation: Data(signature)),
            for: Data([UInt8]("probe".utf8))
        ))
    }

    // MARK: - M5: every way a rotation fails, exactly one signable key

    /// A refusal in the same call that sent the request: the server did not apply it,
    /// so the candidate is destroyed and the old key stays the one signer.
    func testARefusedRotationKeepsTheOldKeyAndDiscardsTheCandidate() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "PROOF_INVALID"))),
        ])
        let store = InMemoryKeyStore()
        try enrol(store, key: try testKey(), clientID: "client-test-0001")
        let lifecycle = try makeLifecycle(transport, store: store, keys: [try wrongKey()], keyIDs: ["key-test-0002"])

        let thrown = await failure { _ = try await lifecycle.rotate() }

        guard case .auth(let refusal)? = thrown as? SPFNClientError, refusal.code == .proofInvalid
        else
        {
            return XCTFail("expected the refusal itself, got \(String(describing: thrown))")
        }
        XCTAssertEqual(store.loadSync(SPFNKeyLifecycle.activeSlot)?.keyID, "key-test-0001")
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.candidateSlot))
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .enrolled)
    }

    /// A transport failure is the one outcome where the server's state is unknown:
    /// the machine parks in rotationPending, and the old key stays the only signer.
    func testATransportFailureParksTheRotationWithTheOldKeyActive() async throws
    {
        let transport = ScriptedTransport([.failure(SPFNTransportError.timedOut)])
        let store = InMemoryKeyStore()
        try enrol(store, key: try testKey(), clientID: "client-test-0001")
        let lifecycle = try makeLifecycle(transport, store: store, keys: [try wrongKey()], keyIDs: ["key-test-0002"])

        let thrown = await failure { _ = try await lifecycle.rotate() }

        XCTAssertEqual(thrown as? SPFNClientError, .transport(.timedOut))
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .rotationPending)
        let loadedProvider = try await lifecycle.activeProvider()
        let provider = try XCTUnwrap(loadedProvider)
        XCTAssertEqual(provider.keyID, "key-test-0001", "the candidate never becomes signable by existing")

        // And while unresolved, no second rotation and no enrollment may start.
        let rotateAgain = await failure { _ = try await lifecycle.rotate() }
        XCTAssertEqual(rotateAgain as? SPFNKeyLifecycleError, .rotationUnresolved)
    }

    /// Resume, case one: the server never saw the first attempt. The re-send succeeds
    /// and the swap completes as if nothing had died.
    func testResumeRetriesARotationTheServerNeverApplied() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, "{\"keyId\":\"key-test-0002\",\"success\":true}")),
        ])
        let store = InMemoryKeyStore()
        try enrol(store, key: try testKey(), clientID: "client-test-0001")
        try store.save(
            try wrongKey().record(clientID: "client-test-0001", createdAtMillis: 1_750_000_000_000),
            slot: SPFNKeyLifecycle.candidateSlot
        )
        let lifecycle = try makeLifecycle(transport, store: store, keys: [], keyIDs: [])

        let result = try await lifecycle.resumeRotation()

        XCTAssertEqual(result.keyID, "key-test-0002")
        XCTAssertEqual(store.loadSync(SPFNKeyLifecycle.activeSlot)?.keyID, "key-test-0002")
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.candidateSlot))
    }

    /// Resume, case two: PROOF_INVALID against a proof this SDK assembled correctly
    /// means the old key is no longer registered — the earlier attempt WAS applied,
    /// and the candidate is the key the server now honours.
    func testResumePromotesTheCandidateWhenTheOldKeyIsNoLongerRegistered() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "PROOF_INVALID"))),
        ])
        let store = InMemoryKeyStore()
        try enrol(store, key: try testKey(), clientID: "client-test-0001")
        try store.save(
            try wrongKey().record(clientID: "client-test-0001", createdAtMillis: 1_750_000_000_000),
            slot: SPFNKeyLifecycle.candidateSlot
        )
        let lifecycle = try makeLifecycle(transport, store: store, keys: [], keyIDs: [])

        let result = try await lifecycle.resumeRotation()

        XCTAssertEqual(result.keyID, "key-test-0002")
        XCTAssertEqual(store.loadSync(SPFNKeyLifecycle.activeSlot)?.keyID, "key-test-0002")
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.candidateSlot))
    }

    /// Resume, case three: a death between the swap and the candidate cleanup. Both
    /// slots name one key, and the resume is only the cleanup — no network at all.
    func testResumeAfterADeathBetweenSwapAndCleanupOnlyCleansUp() async throws
    {
        let transport = ScriptedTransport([])
        let store = InMemoryKeyStore()
        let record = try wrongKey().record(clientID: "client-test-0001", createdAtMillis: 1_750_000_000_000)
        try store.save(record, slot: SPFNKeyLifecycle.activeSlot)
        try store.save(record, slot: SPFNKeyLifecycle.candidateSlot)
        let lifecycle = try makeLifecycle(transport, store: store, keys: [], keyIDs: [])

        let result = try await lifecycle.resumeRotation()

        XCTAssertEqual(result.keyID, "key-test-0002")
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.candidateSlot))
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "a settled rotation costs no request")
    }

    // MARK: - M6: SESSION_REVOKED wipes

    func testSessionRevokedDuringRotationWipesEverything() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "SESSION_REVOKED"))),
        ])
        let store = InMemoryKeyStore()
        try enrol(store, key: try testKey(), clientID: "client-test-0001")
        let lifecycle = try makeLifecycle(transport, store: store, keys: [try wrongKey()], keyIDs: ["key-test-0002"])

        _ = await failure { _ = try await lifecycle.rotate() }

        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.activeSlot))
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.candidateSlot))
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .unenrolled, "the re-enrollment-required signal a caller reads")
    }

    func testNoteSessionRevokedIsTheSameWipe() async throws
    {
        let store = InMemoryKeyStore()
        try enrol(store, key: try testKey(), clientID: "client-test-0001")
        let lifecycle = try makeLifecycle(ScriptedTransport([]), store: store, keys: [], keyIDs: [])

        try await lifecycle.noteSessionRevoked()

        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.activeSlot))
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .unenrolled)
    }

    // MARK: - M7: the TTL judgment

    func testRotationDueFollowsTheKeyPolicyTtl() async throws
    {
        let createdAt: Int64 = 1_750_000_000_000
        let store = InMemoryKeyStore()
        try enrol(store, key: try testKey(), clientID: "client-test-0001", createdAt: createdAt)

        // One millisecond inside the TTL: not due, and the remainder says how close.
        let clock = FakeClock(createdAt + ttlMillis - 1)
        let lifecycle = try makeLifecycle(ScriptedTransport([]), store: store, keys: [], keyIDs: [], clock: clock)
        let remaining = try await lifecycle.keyRemainingMillis()
        XCTAssertEqual(remaining, 1)
        let dueEarly = try await lifecycle.rotationDue()
        XCTAssertFalse(dueEarly)

        // With a lead time, the same moment is already due — the foreground trigger.
        let dueWithLead = try await lifecycle.rotationDue(leadTimeMillis: 24 * 60 * 60 * 1_000)
        XCTAssertTrue(dueWithLead)

        // At the boundary the key has reached its TTL.
        clock.set(createdAt + ttlMillis)
        let dueAtBoundary = try await lifecycle.rotationDue()
        XCTAssertTrue(dueAtBoundary)

        // No key, nothing due.
        try store.delete(slot: SPFNKeyLifecycle.activeSlot)
        let remainingWithout = try await lifecycle.keyRemainingMillis()
        XCTAssertNil(remainingWithout)
        let dueWithout = try await lifecycle.rotationDue()
        XCTAssertFalse(dueWithout)
    }

    // MARK: - Assembly

    private func enrollmentFixture() throws -> [String: SPFNCanonicalValue]
    {
        try WireFixtures.load("Contracts/fixtures/enrollment/enrollment.json").object()
    }

    /// The fixture test keypair as a custody key (TEST ONLY — published on purpose).
    private func testKey() throws -> SPFNCustodyKey
    {
        let keyPair = try WireFixtures.wire()["testKeyPair"].orFail("testKeyPair").object()
        return try SPFNCustodyKey.software(
            keyID: try keyPair.text("keyId"),
            privateKeyDer: try base64(try keyPair.text("privateKeyPkcs8Base64"))
        )
    }

    /// The second fixture keypair, standing in for a freshly generated rotation key.
    private func wrongKey() throws -> SPFNCustodyKey
    {
        let proof = try WireFixtures.load("Contracts/fixtures/proof/proof-input.json").object()
        let keyPair = try proof["wrongKeyPair"].orFail("wrongKeyPair").object()
        return try SPFNCustodyKey.software(
            keyID: try keyPair.text("keyId"),
            privateKeyDer: try base64(try keyPair.text("privateKeyPkcs8Base64"))
        )
    }

    private func base64(_ text: String) throws -> [UInt8]
    {
        guard let data = Data(base64Encoded: text)
        else
        {
            throw FixtureFailure.shape("not base64")
        }
        return [UInt8](data)
    }

    private func enrol(
        _ store: InMemoryKeyStore,
        key: SPFNCustodyKey,
        clientID: String,
        createdAt: Int64 = 1_750_000_000_000
    ) throws
    {
        try store.save(key.record(clientID: clientID, createdAtMillis: createdAt), slot: SPFNKeyLifecycle.activeSlot)
    }

    private func makeLifecycle(
        _ transport: any SPFNTransport,
        store: InMemoryKeyStore,
        keys: [SPFNCustodyKey],
        keyIDs: [String],
        clock: FakeClock = FakeClock(1_750_000_000_000),
        nonces: [String] = []
    ) throws -> SPFNKeyLifecycle
    {
        let keyQueue = ScriptedQueue(keys)
        let idQueue = ScriptedQueue(keyIDs)
        return SPFNKeyLifecycle(
            transport: transport,
            store: store,
            baseURL: baseURL,
            clock: clock,
            proofClock: clock,
            nonceGenerator: ScriptedNonceGenerator(nonces),
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

/// Hands out scripted values in order, thread-safely, from a Sendable closure.
final class ScriptedQueue<Element>: @unchecked Sendable
{
    private let lock = NSLock()
    private var remaining: [Element]

    init(_ elements: [Element])
    {
        remaining = elements
    }

    func next() -> Element?
    {
        lock.lock()
        defer { lock.unlock() }
        return remaining.isEmpty ? nil : remaining.removeFirst()
    }
}

extension InMemoryKeyStore
{
    /// The async-free read the assertions use.
    func loadSync(_ slot: String) -> SPFNStoredKey?
    {
        try? load(slot: slot)
    }
}
