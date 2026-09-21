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

    func testE1_enrollSendsTheExactFixtureBytesAndPersistsTheIdentity() async throws
    {
        let fixture = try enrollmentFixture()
        let oauthNative = try fixture["oauthNative"].orFail("oauthNative").object()
        let value = try oauthNative["value"].orFail("value").object()

        let transport = ScriptedTransport([
            .success(.json(200, "{\"mfaRequired\":false,\"isNewUser\":true,\"keyId\":\"key-test-0001\",\"userId\":\"user-test-0001\"}")),
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

        // The optional flag defaults to false. A challenge alongside mfaRequired=false
        // does not change the branch: only the discriminant decides enrollment.
        let returningTransport = ScriptedTransport([
            .success(.json(200, """
            {"mfaRequired":false,"keyId":"key-test-0001","userId":"user-test-0001",\
            "challenge":{"secret":"unused-challenge","expiresAtMillis":1750000060000}}
            """)),
        ])
        let returningStore = InMemoryKeyStore()
        let returning = try makeLifecycle(returningTransport, store: returningStore, keys: [try testKey()], keyIDs: ["key-test-0001"])
        let returningResult = try await returning.enroll(provider: "google") { _ in "idtoken-test" }
        XCTAssertEqual(returningResult, SPFNEnrollmentResult(clientID: "user-test-0001", keyID: "key-test-0001", isNewUser: false))
        XCTAssertEqual(returningStore.loadSync(SPFNKeyLifecycle.activeSlot)?.keyID, "key-test-0001")
    }

    func testE2_successNamingAnotherKeyIsRefusedAndStoresNothing() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, """
            {"mfaRequired":false,"keyId":"key-other-9999","userId":"user-test-0001"}
            """)),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = try makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])
        let thrown = await failure { _ = try await lifecycle.enroll(provider: "google") { _ in "idtoken-test" } }
        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .serverNamedAnotherKey(sent: "key-test-0001", received: "key-other-9999"))
        try await assertEnrollmentDiscarded(lifecycle, store: store)
    }

    func testE3_missingEnrollmentIdentityIsRefusedAndStoresNothing() async throws
    {
        for body in [
            "{\"mfaRequired\":false,\"keyId\":\"key-test-0001\"}",
            "{\"mfaRequired\":false,\"userId\":\"user-test-0001\"}",
        ]
        {
            let transport = ScriptedTransport([.success(.json(200, body))])
            let store = InMemoryKeyStore()
            let lifecycle = try makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])
            let thrown = await failure { _ = try await lifecycle.enroll(provider: "google") { _ in "idtoken-test" } }
            XCTAssertEqual(thrown as? SPFNClientError, .decoding(.notTheDeclaredResponse, onSuccessStatus: true))
            try await assertEnrollmentDiscarded(lifecycle, store: store)
        }
    }

    func testE4_secondFactorIsExplicitlyRefusedAndStoresNothing() async throws
    {
        for body in [
            "{\"mfaRequired\":true}",
            """
            {"mfaRequired":true,"challenge":{"secret":"private-challenge","expiresAtMillis":1750000060000}}
            """,
            """
            {"mfaRequired":true,"keyId":"key-test-0001","userId":"user-test-0001","isNewUser":true}
            """,
        ]
        {
            let transport = ScriptedTransport([.success(.json(202, body))])
            let store = InMemoryKeyStore()
            let lifecycle = try makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])
            let thrown = await failure { _ = try await lifecycle.enroll(provider: "google") { _ in "idtoken-test" } }
            XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .secondFactorRequired)
            XCTAssertEqual(thrown?.localizedDescription, "this SDK version does not finish a second-factor sign-in")
            XCTAssertFalse(String(describing: thrown).contains("private-challenge"))
            try await assertEnrollmentDiscarded(lifecycle, store: store)
        }
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

    func testE5_failedEnrollmentPreservesTheErrorAndDestroysTheKey() async throws
    {
        let outcomes: [Result<SPFNTransportResponse, any Error>] = [
            .success(.json(409, ExecuteFixtures.errorEnvelope(code: "CONTRACT_UNSUPPORTED"))),
            .success(.json(500, ExecuteFixtures.errorEnvelope(code: "Error"))),
            .failure(SPFNTransportError.timedOut),
            .success(.json(200, "{}")),
            .success(.json(200, "not-json")),
        ]
        for (index, outcome) in outcomes.enumerated()
        {
            let transport = ScriptedTransport([outcome])
            let store = InMemoryKeyStore()
            let lifecycle = try makeLifecycle(transport, store: store, keys: [try testKey()], keyIDs: ["key-test-0001"])
            let thrown = await failure { _ = try await lifecycle.enroll(provider: "google") { _ in "idtoken-test" } }
            switch index
            {
            case 0, 1:
                guard case .server(let serverFailure) = thrown as? SPFNClientError
                else { return XCTFail("expected the server error, got \(String(describing: thrown))") }
                XCTAssertEqual(serverFailure.httpStatus, index == 0 ? 409 : 500)
                XCTAssertEqual(serverFailure.code.rawValue, index == 0 ? "CONTRACT_UNSUPPORTED" : "Error")
            case 2:
                XCTAssertEqual(thrown as? SPFNClientError, .transport(.timedOut))
            case 3:
                XCTAssertEqual(thrown as? SPFNClientError, .decoding(.notTheDeclaredResponse, onSuccessStatus: true))
            default:
                XCTAssertEqual(thrown as? SPFNClientError, .decoding(.notCanonicalJSON, onSuccessStatus: true))
            }
            try await assertEnrollmentDiscarded(lifecycle, store: store)
        }
    }

    private func assertEnrollmentDiscarded(_ lifecycle: SPFNKeyLifecycle, store: InMemoryKeyStore) async throws
    {
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.activeSlot))
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.candidateSlot))
        let provider = try await lifecycle.activeProvider()
        XCTAssertNil(provider)
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

    // MARK: - The unproven path holds no key at all

    /// Every custody key this lifecycle makes goes through the injected `makeKey`, and it
    /// makes exactly one per enrollment.
    ///
    /// Two halves, because neither is enough on its own. The count is what a suite can
    /// observe; the source scan is what makes the count mean something, since a key
    /// generated by calling `SPFNCustodyKey.generate` directly would not pass through the
    /// seam and no assertion could see it. That is exactly what the unproven path used to
    /// do — a throwaway keypair per call, minted to fill a slot nothing reads.
    func test_theUnprovenPathGeneratesNoThrowawayKey() async throws
    {
        let generated = GenerationCounter()
        let store = InMemoryKeyStore()
        let lifecycle = SPFNKeyLifecycle(
            transport: ScriptedTransport([
                .success(.json(200, "{\"mfaRequired\":false,\"isNewUser\":true,\"keyId\":\"key-test-0001\",\"userId\":\"user-test-0001\"}")),
            ]),
            store: store,
            baseURL: baseURL,
            clock: FakeClock(1_750_000_000_000),
            proofClock: FakeClock(1_750_000_000_000),
            nonceGenerator: ScriptedNonceGenerator([]),
            newKeyID: { "key-test-0001" },
            makeKey: { [key = try testKey()] _ in generated.record(key) }
        )

        _ = try await lifecycle.enroll(provider: "google") { _ in "idtoken-test" }

        XCTAssertEqual(generated.count, 1, "an enrollment registers one key and mints no other")

        let source = try String(contentsOf: Self.lifecycleSource, encoding: .utf8)
        XCTAssertGreaterThan(source.count, 10_000, "the lifecycle source was not read")
        let generators = source
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.hasPrefix("//") && !$0.hasPrefix("///") }
            .filter { $0.contains("SPFNCustodyKey.generate") }
        XCTAssertEqual(
            generators,
            ["makeKey: @escaping @Sendable (String) -> SPFNCustodyKey = { SPFNCustodyKey.generate(keyID: $0) }"],
            "every custody key this file makes must come through the injected seam, "
                + "or a suite counting that seam is counting half the keys"
        )
    }

    /// Where the lifecycle's own source lives, from this file's position in the tree.
    private static let lifecycleSource = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // Tests/SPFNClientTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // repository root
        .appendingPathComponent("Sources/SPFNClient/SPFNKeyLifecycle.swift")

    // MARK: - M5: the rotation table, one case per cell

    // Every way a rotation can end, twice: once through `rotate()` and once through
    // `resumeRotation()`. The two entry points read one classification, and a pair of
    // cases per row is what keeps that true — a second table growing beside the first is
    // exactly the drift the shared function exists to prevent. The rows are named K1–K10
    // here, in docs/architecture/README.md, and in the two implementations.

    /// K1: the answer decoded and named the key that was sent. The swap completes.
    func test_rotate_K1_decodedAnswerNamingTheKeySent_promotesTheCandidate() async throws
    {
        let install = try rotatingInstall(ScriptedTransport([
            .success(.json(200, "{\"keyId\":\"key-test-0002\",\"success\":true}")),
        ]))

        let result = try await install.lifecycle.rotate()

        XCTAssertEqual(result, SPFNEnrollmentResult(clientID: "client-test-0001", keyID: "key-test-0002", isNewUser: false))
        XCTAssertEqual(try install.store.load(slot: SPFNKeyLifecycle.activeSlot)?.keyID, "key-test-0002")
        XCTAssertNil(try install.store.load(slot: SPFNKeyLifecycle.candidateSlot))
        let state = try await install.lifecycle.state()
        XCTAssertEqual(state, .enrolled)
    }

    /// K2: no answer at all. The server may or may not have applied it.
    func test_rotate_K2_transportFailure_keepsCandidateAndStaysPending() async throws
    {
        let install = try rotatingInstall(ScriptedTransport([.failure(SPFNTransportError.timedOut)]))

        let thrown = await failure { _ = try await install.lifecycle.rotate() }

        XCTAssertEqual(thrown as? SPFNClientError, .transport(.timedOut))
        try await assertCandidateHeld(install)

        // And while unresolved, no second rotation may start.
        let rotateAgain = await failure { _ = try await install.lifecycle.rotate() }
        XCTAssertEqual(rotateAgain as? SPFNKeyLifecycleError, .rotationUnresolved)
    }

    /// K3: the server answered 2xx and this SDK could not read the answer. It said yes,
    /// so the candidate is a key it may already honour — the row this suite exists for.
    func test_rotate_K3_decodingOn2xx_keepsCandidateAndStaysPending() async throws
    {
        for (body, expected) in Self.unreadableSuccesses
        {
            let install = try rotatingInstall(ScriptedTransport([.success(.json(200, body))]))

            let thrown = await failure { _ = try await install.lifecycle.rotate() }

            XCTAssertEqual(thrown as? SPFNClientError, .decoding(expected, onSuccessStatus: true), "body: \(body)")
            try await assertCandidateHeld(install)
        }
    }

    /// K4: the same unreadability over a REFUSAL. The server answered no, so nothing was
    /// applied and the candidate goes.
    func test_rotate_K4_decodingOnNon2xx_discardsCandidateAndKeepsTheOldKey() async throws
    {
        for (body, expected) in Self.unreadableRefusals
        {
            let install = try rotatingInstall(ScriptedTransport([.success(.json(401, body))]))

            let thrown = await failure { _ = try await install.lifecycle.rotate() }

            XCTAssertEqual(thrown as? SPFNClientError, .decoding(expected, onSuccessStatus: false), "body: \(body)")
            try await assertCandidateRefused(install)
        }
    }

    /// K5: the old key itself is dead (M6).
    func test_rotate_K5_sessionRevoked_wipesEverySlot() async throws
    {
        let install = try rotatingInstall(ScriptedTransport([
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "SESSION_REVOKED"))),
        ]))

        _ = await failure { _ = try await install.lifecycle.rotate() }

        try await assertWiped(install)
    }

    /// K6: any other refusal the server decided on, whether it authenticated the request
    /// or refused it on contract grounds.
    func test_rotate_K6_otherRefusal_discardsCandidateAndKeepsTheOldKey() async throws
    {
        for code in ["PROOF_REPLAYED", "VALIDATION_ERROR"]
        {
            let install = try rotatingInstall(ScriptedTransport([
                .success(.json(400, ExecuteFixtures.errorEnvelope(code: code))),
            ]))

            let thrown = await failure { _ = try await install.lifecycle.rotate() }

            XCTAssertNotNil(thrown as? SPFNClientError, "code: \(code)")
            try await assertCandidateRefused(install)
        }
    }

    /// K7: the proof would not assemble, so no request ever existed.
    func test_rotate_K7_proofAssemblyFailure_discardsCandidateAndKeepsTheOldKey() async throws
    {
        let transport = ScriptedTransport([])
        let install = try rotatingInstall(transport, clientID: Self.clientIDWithAControlCharacter)

        let thrown = await failure { _ = try await install.lifecycle.rotate() }

        XCTAssertEqual(thrown as? SPFNAuthError, .controlCharacterInProofField("clientId"))
        XCTAssertNil(try install.store.load(slot: SPFNKeyLifecycle.candidateSlot))
        let state = try await install.lifecycle.state()
        XCTAssertEqual(state, .enrolled)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "an unassembled proof costs no request")
    }

    /// K8, the clock half: the proof's timestamp could not be anchored, so nothing was
    /// sent. This is the row that used to escape both catch clauses.
    func test_rotate_K8_clockSynchronizationFailure_discardsCandidateAndKeepsTheOldKey() async throws
    {
        let transport = ScriptedTransport([])
        let install = try rotatingInstall(
            transport,
            proofClock: ScriptedProofClock(1_750_000_000_000, throwing: [SPFNClockSynchronizationError.requestFailed])
        )

        let thrown = await failure { _ = try await install.lifecycle.rotate() }

        XCTAssertEqual(thrown as? SPFNClockSynchronizationError, .requestFailed)
        try await assertCandidateRefused(install)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "a clock that would not answer costs no request")
    }

    /// K8, the store half: the candidate could not be written, which is the other way a
    /// rotation fails before a request exists. `rotate()` is the only path with a write
    /// before the send; a resume's candidate is already on disk.
    func test_rotate_K8_candidateStoreFailure_leavesNoCandidateAndKeepsTheOldKey() async throws
    {
        let transport = ScriptedTransport([])
        let store = SlotRefusingKeyStore(refusing: SPFNKeyLifecycle.candidateSlot)
        let install = try rotatingInstall(transport, store: store)

        let thrown = await failure { _ = try await install.lifecycle.rotate() }

        XCTAssertEqual(thrown as? SlotRefusingKeyStore.Refusal, .writeRefused)
        try await assertCandidateRefused(install)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "a candidate that could not be persisted is never sent")
    }

    /// K9: a 2xx that decoded and named a key this call never sent. The server applied
    /// SOMETHING, so the candidate is held exactly as in K3 rather than destroyed.
    func test_rotate_K9_serverNamedAnotherKey_keepsCandidateAndStaysPending() async throws
    {
        let install = try rotatingInstall(ScriptedTransport([
            .success(.json(200, "{\"keyId\":\"key-test-0009\",\"success\":true}")),
        ]))

        let thrown = await failure { _ = try await install.lifecycle.rotate() }

        XCTAssertEqual(
            thrown as? SPFNKeyLifecycleError,
            .serverNamedAnotherKey(sent: "key-test-0002", received: "key-test-0009")
        )
        try await assertCandidateHeld(install)
    }

    /// K10: cancellation is a row of its own and not an escape. The request may already
    /// be with the server, so a withdrawn call settles no more than a lost one.
    func test_rotate_K10_transportCancelled_keepsCandidateAndStaysPending() async throws
    {
        let install = try rotatingInstall(ScriptedTransport([.failure(SPFNTransportError.cancelled)]))

        let thrown = await failure { _ = try await install.lifecycle.rotate() }

        XCTAssertEqual(thrown as? SPFNClientError, .transport(.cancelled))
        try await assertCandidateHeld(install)
    }

    /// K1 on resume: the server never saw the first attempt, and the re-send settles it.
    func test_resume_K1_decodedAnswerNamingTheKeySent_promotesTheCandidate() async throws
    {
        let install = try pendingInstall(ScriptedTransport([
            .success(.json(200, "{\"keyId\":\"key-test-0002\",\"success\":true}")),
        ]))

        let result = try await install.lifecycle.resumeRotation()

        XCTAssertEqual(result.keyID, "key-test-0002")
        XCTAssertEqual(try install.store.load(slot: SPFNKeyLifecycle.activeSlot)?.keyID, "key-test-0002")
        XCTAssertNil(try install.store.load(slot: SPFNKeyLifecycle.candidateSlot))
    }

    /// K2 on resume: a resume that is lost the same way the first send was leaves the
    /// machine where it was, ready to be resumed again.
    func test_resume_K2_transportFailure_keepsCandidateAndStaysPending() async throws
    {
        let install = try pendingInstall(ScriptedTransport([.failure(SPFNTransportError.timedOut)]))

        let thrown = await failure { _ = try await install.lifecycle.resumeRotation() }

        XCTAssertEqual(thrown as? SPFNClientError, .transport(.timedOut))
        try await assertCandidateHeld(install)
    }

    /// K3 on resume.
    func test_resume_K3_decodingOn2xx_keepsCandidateAndStaysPending() async throws
    {
        for (body, expected) in Self.unreadableSuccesses
        {
            let install = try pendingInstall(ScriptedTransport([.success(.json(200, body))]))

            let thrown = await failure { _ = try await install.lifecycle.resumeRotation() }

            XCTAssertEqual(thrown as? SPFNClientError, .decoding(expected, onSuccessStatus: true), "body: \(body)")
            try await assertCandidateHeld(install)
        }
    }

    /// K4 on resume.
    func test_resume_K4_decodingOnNon2xx_discardsCandidateAndKeepsTheOldKey() async throws
    {
        for (body, expected) in Self.unreadableRefusals
        {
            let install = try pendingInstall(ScriptedTransport([.success(.json(401, body))]))

            let thrown = await failure { _ = try await install.lifecycle.resumeRotation() }

            XCTAssertEqual(thrown as? SPFNClientError, .decoding(expected, onSuccessStatus: false), "body: \(body)")
            try await assertCandidateRefused(install)
        }
    }

    /// K5 on resume.
    func test_resume_K5_sessionRevoked_wipesEverySlot() async throws
    {
        let install = try pendingInstall(ScriptedTransport([
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "SESSION_REVOKED"))),
        ]))

        _ = await failure { _ = try await install.lifecycle.resumeRotation() }

        try await assertWiped(install)
    }

    /// K6 on resume. `PROOF_INVALID` is the one auth code that does NOT land here — it
    /// completes the rotation instead, which is the asymmetry the case below pins.
    func test_resume_K6_otherRefusal_discardsCandidateAndKeepsTheOldKey() async throws
    {
        for code in ["PROOF_REPLAYED", "VALIDATION_ERROR"]
        {
            let install = try pendingInstall(ScriptedTransport([
                .success(.json(400, ExecuteFixtures.errorEnvelope(code: code))),
            ]))

            let thrown = await failure { _ = try await install.lifecycle.resumeRotation() }

            XCTAssertNotNil(thrown as? SPFNClientError, "code: \(code)")
            try await assertCandidateRefused(install)
        }
    }

    /// K7 on resume.
    func test_resume_K7_proofAssemblyFailure_discardsCandidateAndKeepsTheOldKey() async throws
    {
        let transport = ScriptedTransport([])
        let install = try pendingInstall(transport, clientID: Self.clientIDWithAControlCharacter)

        let thrown = await failure { _ = try await install.lifecycle.resumeRotation() }

        XCTAssertEqual(thrown as? SPFNAuthError, .controlCharacterInProofField("clientId"))
        try await assertCandidateRefused(install)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "an unassembled proof costs no request")
    }

    /// K8 on resume. Only the clock half is reachable here: a resume writes nothing
    /// before it sends, because the candidate it re-sends is already on disk.
    func test_resume_K8_clockSynchronizationFailure_discardsCandidateAndKeepsTheOldKey() async throws
    {
        let transport = ScriptedTransport([])
        let install = try pendingInstall(
            transport,
            proofClock: ScriptedProofClock(1_750_000_000_000, throwing: [SPFNClockSynchronizationError.requestFailed])
        )

        let thrown = await failure { _ = try await install.lifecycle.resumeRotation() }

        XCTAssertEqual(thrown as? SPFNClockSynchronizationError, .requestFailed)
        try await assertCandidateRefused(install)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "a clock that would not answer costs no request")
    }

    /// K9 on resume: the key id is compared again, and disagreeing again settles nothing.
    func test_resume_K9_serverNamedAnotherKey_keepsCandidateAndStaysPending() async throws
    {
        let install = try pendingInstall(ScriptedTransport([
            .success(.json(200, "{\"keyId\":\"key-test-0009\",\"success\":true}")),
        ]))

        let thrown = await failure { _ = try await install.lifecycle.resumeRotation() }

        XCTAssertEqual(
            thrown as? SPFNKeyLifecycleError,
            .serverNamedAnotherKey(sent: "key-test-0002", received: "key-test-0009")
        )
        try await assertCandidateHeld(install)
    }

    /// K10 on resume.
    func test_resume_K10_transportCancelled_keepsCandidateAndStaysPending() async throws
    {
        let install = try pendingInstall(ScriptedTransport([.failure(SPFNTransportError.cancelled)]))

        let thrown = await failure { _ = try await install.lifecycle.resumeRotation() }

        XCTAssertEqual(thrown as? SPFNClientError, .transport(.cancelled))
        try await assertCandidateHeld(install)
    }

    /// The two ways a 2xx can be unreadable, and the failure each is named by. Both are
    /// K3: the status is what decides the row, not which of them arrived.
    private static let unreadableSuccesses: [(String, SPFNDecodingFailure)] = [
        ("{not canonical json", .notCanonicalJSON),
        ("{}", .notTheDeclaredResponse),
    ]

    /// The two ways a refusal can be unreadable. Both are K4.
    private static let unreadableRefusals: [(String, SPFNDecodingFailure)] = [
        ("{\"error\":{\"nothing\":\"an envelope declares\"}}", .notAnErrorEnvelope),
        (ExecuteFixtures.errorEnvelope(code: "NO_SUCH_CODE_IN_THIS_CONTRACT"), .unknownErrorCode),
    ]

    /// A client id the canonical proof input cannot carry: the C0 character would make
    /// the newline-separated form ambiguous, so the proof is refused before it is signed.
    private static let clientIDWithAControlCharacter = "client-test-\u{01}-0001"

    // MARK: - M6: SESSION_REVOKED wipes

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
        _ store: any SPFNKeyStore,
        key: SPFNCustodyKey,
        clientID: String,
        createdAt: Int64 = 1_750_000_000_000
    ) throws
    {
        try store.save(key.record(clientID: clientID, createdAtMillis: createdAt), slot: SPFNKeyLifecycle.activeSlot)
    }

    // MARK: - The rotation table's own assembly

    /// The install every `rotate()` cell starts from: `key-test-0001` enrolled, and
    /// `key-test-0002` queued as the key the rotation will generate.
    private func rotatingInstall(
        _ transport: any SPFNTransport,
        store: any SPFNKeyStore = InMemoryKeyStore(),
        clientID: String = "client-test-0001",
        proofClock: (any SPFNProofClock)? = nil
    ) throws -> (store: any SPFNKeyStore, lifecycle: SPFNKeyLifecycle)
    {
        try enrol(store, key: try testKey(), clientID: clientID)
        let lifecycle = try makeLifecycle(
            transport,
            store: store,
            keys: [try wrongKey()],
            keyIDs: ["key-test-0002"],
            proofClock: proofClock
        )
        return (store, lifecycle)
    }

    /// The install every `resumeRotation()` cell starts from: the same one, with the
    /// candidate already persisted, as a process death mid-rotation would have left it.
    private func pendingInstall(
        _ transport: any SPFNTransport,
        clientID: String = "client-test-0001",
        proofClock: (any SPFNProofClock)? = nil
    ) throws -> (store: any SPFNKeyStore, lifecycle: SPFNKeyLifecycle)
    {
        let store = InMemoryKeyStore()
        try enrol(store, key: try testKey(), clientID: clientID)
        try store.save(
            try wrongKey().record(clientID: clientID, createdAtMillis: 1_750_000_000_000),
            slot: SPFNKeyLifecycle.candidateSlot
        )
        let lifecycle = try makeLifecycle(
            transport,
            store: store,
            keys: [],
            keyIDs: [],
            proofClock: proofClock
        )
        return (store, lifecycle)
    }

    /// K2, K3, K9, K10: the outcome is unknown, so the candidate survives and the install
    /// answers `rotationPending` — with the OLD key still the only one that can sign.
    private func assertCandidateHeld(
        _ install: (store: any SPFNKeyStore, lifecycle: SPFNKeyLifecycle),
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws
    {
        XCTAssertEqual(
            try install.store.load(slot: SPFNKeyLifecycle.candidateSlot)?.keyID,
            "key-test-0002",
            "the server may hold this key; it is not this SDK's to delete",
            file: file,
            line: line
        )
        XCTAssertEqual(try install.store.load(slot: SPFNKeyLifecycle.activeSlot)?.keyID, "key-test-0001", file: file, line: line)
        let state = try await install.lifecycle.state()
        XCTAssertEqual(state, .rotationPending, file: file, line: line)
        let provider = try await install.lifecycle.activeProvider()
        XCTAssertEqual(provider?.keyID, "key-test-0001", "the candidate never becomes signable by existing", file: file, line: line)
    }

    /// K4, K6, K7, K8: the rotation was not applied, so the candidate is gone and the old
    /// key is still the one signer.
    private func assertCandidateRefused(
        _ install: (store: any SPFNKeyStore, lifecycle: SPFNKeyLifecycle),
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws
    {
        XCTAssertNil(try install.store.load(slot: SPFNKeyLifecycle.candidateSlot), file: file, line: line)
        XCTAssertEqual(try install.store.load(slot: SPFNKeyLifecycle.activeSlot)?.keyID, "key-test-0001", file: file, line: line)
        let state = try await install.lifecycle.state()
        XCTAssertEqual(state, .enrolled, file: file, line: line)
    }

    /// K5: the old key itself is dead, so nothing signs until a fresh enrollment.
    private func assertWiped(
        _ install: (store: any SPFNKeyStore, lifecycle: SPFNKeyLifecycle),
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws
    {
        XCTAssertNil(try install.store.load(slot: SPFNKeyLifecycle.activeSlot), file: file, line: line)
        XCTAssertNil(try install.store.load(slot: SPFNKeyLifecycle.candidateSlot), file: file, line: line)
        let state = try await install.lifecycle.state()
        XCTAssertEqual(state, .unenrolled, "the re-enrollment-required signal a caller reads", file: file, line: line)
    }

    /// - Parameter proofClock: the clock the proof's timestamp is derived from, which is
    ///   the injected `clock` unless a case needs it to fail on its own — the K8 row is
    ///   about a synchronization failure, and a clock that always answers cannot produce
    ///   one.
    private func makeLifecycle(
        _ transport: any SPFNTransport,
        store: any SPFNKeyStore,
        keys: [SPFNCustodyKey],
        keyIDs: [String],
        clock: FakeClock = FakeClock(1_750_000_000_000),
        proofClock: (any SPFNProofClock)? = nil,
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
            proofClock: proofClock ?? clock,
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

/// A store that reads and deletes like any other and refuses to write one named slot.
///
/// The K8 row is about a failure that is neither a client error nor an auth error, and a
/// store that refused every write could never be enrolled into in the first place — so
/// the refusal is narrowed to the slot the case is about.
final class SlotRefusingKeyStore: SPFNKeyStore, @unchecked Sendable
{
    /// Its own type, so a case can assert the lifecycle neither wrapped it nor replaced
    /// it with something from the client taxonomy.
    enum Refusal: Error, Equatable
    {
        case writeRefused
    }

    private let lock = NSLock()
    private let refusedSlot: String
    private var records: [String: SPFNStoredKey] = [:]

    init(refusing refusedSlot: String)
    {
        self.refusedSlot = refusedSlot
    }

    func load(slot: String) throws -> SPFNStoredKey?
    {
        lock.withLock { records[slot] }
    }

    func save(_ record: SPFNStoredKey, slot: String) throws
    {
        guard slot != refusedSlot
        else
        {
            throw Refusal.writeRefused
        }
        lock.withLock { records[slot] = record }
    }

    func delete(slot: String) throws
    {
        lock.withLock { records[slot] = nil }
    }
}

/// Counts the custody keys a flow asked the lifecycle's `makeKey` seam for.
final class GenerationCounter: @unchecked Sendable
{
    private let lock = NSLock()
    private var generated = 0

    var count: Int
    {
        lock.withLock { generated }
    }

    func record(_ key: SPFNCustodyKey) -> SPFNCustodyKey
    {
        lock.withLock { generated += 1 }
        return key
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
