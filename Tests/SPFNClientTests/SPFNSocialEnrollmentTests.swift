// SPFN Mobile — native social enrollment, case table rows B1–B10 and D1–D6.
//
// The lifecycle's state guards were already closed by the M-series; what this file
// closes is the provider argument that arrived with the native social surface, and the
// answer the server gives back. Each test is one row of the design's case table, and
// the expected results are the table's own, copied by hand (P10).
//
// SpfnSocialEnrollmentTest.kt is the Kotlin counterpart and uses the same row names.

import Foundation
import XCTest
import SPFNClient
import SPFNCore
import SPFNGenerated

final class SPFNSocialEnrollmentTests: XCTestCase
{
    private let baseURL = "https://example.invalid"

    // MARK: - B: which provider argument the entry point admits

    /// B1: unenrolled, `apple` — the call proceeds and the request rides the provider's
    /// own path.
    func test_B1_unenrolledWithAppleProceeds() async throws
    {
        try await assertProviderProceeds("apple")
    }

    /// B2: unenrolled, `google` — proceeds.
    func test_B2_unenrolledWithGoogleProceeds() async throws
    {
        try await assertProviderProceeds("google")
    }

    /// B3: unenrolled, `kakao` — proceeds. The SDK does not hold the list of providers
    /// the server supports; a server that does not know this one refuses it (B10).
    func test_B3_unenrolledWithKakaoProceeds() async throws
    {
        try await assertProviderProceeds("kakao")
    }

    /// B4: unenrolled, `naver` — proceeds, for the same reason as B3.
    func test_B4_unenrolledWithNaverProceeds() async throws
    {
        try await assertProviderProceeds("naver")
    }

    /// B5: an empty provider is refused before anything is sent.
    func test_B5_anEmptyProviderIsMalformed() async throws
    {
        try await assertProviderRefused("")
    }

    /// B6: uppercase and whitespace are refused — the id is substituted into the path
    /// before signing, so anything but `[a-z0-9-]` would change the route.
    func test_B6_uppercaseOrWhitespaceProviderIsMalformed() async throws
    {
        try await assertProviderRefused("Google")
        try await assertProviderRefused("goo gle")
    }

    /// B7: a key already exists — `alreadyEnrolled`, and no request is sent.
    func test_B7_enrolledRefusesAndSendsNothing() async throws
    {
        let transport = ScriptedTransport([])
        let store = InMemoryKeyStore()
        let existing = SPFNCustodyKey.generate(keyID: "key-existing-0001", preferSecureEnclave: false)
        try store.save(
            existing.record(clientID: "user-existing-0001", createdAtMillis: 1_750_000_000_000),
            slot: SPFNKeyLifecycle.activeSlot
        )
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple", idToken: "t", nonce: SPFNSocialNonce.make())
        }

        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .alreadyEnrolled)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "an already-enrolled install must not reach the network")
    }

    /// B8: a rotation is unresolved — `rotationUnresolved`, and no request is sent.
    func test_B8_rotationPendingRefusesAndSendsNothing() async throws
    {
        let transport = ScriptedTransport([])
        let store = InMemoryKeyStore()
        let candidate = SPFNCustodyKey.generate(keyID: "key-candidate-0001", preferSecureEnclave: false)
        try store.save(
            candidate.record(clientID: "user-existing-0001", createdAtMillis: 1_750_000_000_000),
            slot: SPFNKeyLifecycle.candidateSlot
        )
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple", idToken: "t", nonce: SPFNSocialNonce.make())
        }

        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .rotationUnresolved)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "an unresolved rotation must not reach the network")
    }

    /// B9: a refused enrollment persists no key. The M3 rule, re-asserted through the
    /// provider entry point, because that is the argument this change set added.
    func test_B9_aFailedEnrollmentStoresNoKey() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(409, ExecuteFixtures.errorEnvelope(code: "CONTRACT_UNSUPPORTED"))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "google", idToken: "t", nonce: SPFNSocialNonce.make())
        }

        XCTAssertNotNil(thrown)
        try await assertNothingSurvived(store, lifecycle)
    }

    /// B10: the server refuses a provider it does not support — the key is destroyed
    /// and the server's refusal reaches the caller unchanged.
    func test_B10_aProviderTheServerRefusesDestroysTheKeyAndPropagates() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(400, ExecuteFixtures.errorEnvelope(code: "CONTRACT_UNSUPPORTED"))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "kakao", idToken: "t", nonce: SPFNSocialNonce.make())
        }

        XCTAssertNotNil(thrown, "the refusal is the caller's to classify, not the SDK's to swallow")
        let calls = await transport.callCount
        XCTAssertEqual(calls, 1)
        try await assertNothingSurvived(store, lifecycle)
    }

    // MARK: - D: what the server's answer settles

    /// D1: success with the key id this call sent — the identity is persisted and
    /// returned.
    func test_D1_successWithAMatchingKeyIdStoresTheIdentity() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, Self.enrollResponse(keyID: "key-new-0001", userID: "user-new-0001", isNewUser: true))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

        let result = try await lifecycle.enroll(provider: "apple", idToken: "t", nonce: SPFNSocialNonce.make())

        XCTAssertEqual(result, SPFNEnrollmentResult(clientID: "user-new-0001", keyID: "key-new-0001", isNewUser: true))
        let active = try XCTUnwrap(store.loadSync(SPFNKeyLifecycle.activeSlot))
        XCTAssertEqual(active.clientID, "user-new-0001")
        XCTAssertEqual(active.keyID, "key-new-0001")
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .enrolled)
    }

    /// D2: success naming a different key — refused, and nothing is stored. A server
    /// that confirms another key has not registered the one this device holds.
    func test_D2_successNamingAnotherKeyIsRefusedAndStoresNothing() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, Self.enrollResponse(keyID: "key-other-9999", userID: "user-new-0001", isNewUser: true))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple", idToken: "t", nonce: SPFNSocialNonce.make())
        }

        XCTAssertEqual(
            thrown as? SPFNKeyLifecycleError,
            .serverNamedAnotherKey(sent: "key-new-0001", received: "key-other-9999")
        )
        try await assertNothingSurvived(store, lifecycle)
    }

    /// D3: `isNewUser: true` reaches the caller as it arrived.
    func test_D3_isNewUserTrueIsPassedThrough() async throws
    {
        let result = try await enrollWith(isNewUser: true)
        XCTAssertTrue(result.isNewUser)
    }

    /// D4: `isNewUser: false` reaches the caller as it arrived.
    func test_D4_isNewUserFalseIsPassedThrough() async throws
    {
        let result = try await enrollWith(isNewUser: false)
        XCTAssertFalse(result.isNewUser)
    }

    /// D5: a failure answer destroys the key and hands the error on.
    func test_D5_aFailureResponseDestroysTheKeyAndPropagates() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(401, ExecuteFixtures.errorEnvelope(code: "PROOF_INVALID"))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple", idToken: "t", nonce: SPFNSocialNonce.make())
        }

        XCTAssertNotNil(thrown)
        try await assertNothingSurvived(store, lifecycle)
    }

    /// D6: a transport failure destroys the key and hands the error on. Enrollment is
    /// the one flow where a lost answer needs no resume: no key was persisted, so the
    /// next attempt starts from the same state as this one did.
    func test_D6_aTransportFailureDestroysTheKeyAndPropagates() async throws
    {
        let transport = ScriptedTransport([
            .failure(SPFNTransportError.connectivity("offline")),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple", idToken: "t", nonce: SPFNSocialNonce.make())
        }

        XCTAssertNotNil(thrown)
        try await assertNothingSurvived(store, lifecycle)
    }

    // MARK: - Assembly

    /// The nonce the enrollment body carries is the raw value, never the Apple request
    /// value — asserted here rather than in its own row because every "proceeds" row
    /// rides through it.
    private func assertProviderProceeds(_ provider: String) async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, Self.enrollResponse(keyID: "key-new-0001", userID: "user-new-0001", isNewUser: true))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])
        let nonce = SPFNSocialNonce.make()

        let result = try await lifecycle.enroll(provider: provider, idToken: "idtoken-\(provider)", nonce: nonce)

        XCTAssertEqual(result.keyID, "key-new-0001")
        let received = await transport.received
        let sent = try XCTUnwrap(received.first)
        // The path template is the contract's, read from the generated listing rather
        // than restated here: a restatement would be this suite's own invention.
        let expectedPath = SPFNGeneratedOperations.authEnrollOauthNative.path
            .replacingOccurrences(of: "{provider}", with: provider)
        XCTAssertEqual(sent.url, baseURL + expectedPath)

        let body = String(decoding: sent.body ?? [], as: UTF8.self)
        XCTAssertTrue(body.contains("\"nonce\":\"\(nonce.rawValue)\""), "the body must carry the raw value")
        XCTAssertFalse(body.contains(nonce.appleRequestValue), "the body must not carry the hashed value")
    }

    private func assertProviderRefused(
        _ provider: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws
    {
        let transport = ScriptedTransport([])
        let lifecycle = makeLifecycle(transport, store: InMemoryKeyStore(), keyIDs: [])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: provider, idToken: "t", nonce: SPFNSocialNonce.make())
        }

        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .malformedProviderID, "'\(provider)' was accepted",
                       file: file, line: line)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, file: file, line: line)
    }

    private func enrollWith(isNewUser: Bool) async throws -> SPFNEnrollmentResult
    {
        let transport = ScriptedTransport([
            .success(.json(200, Self.enrollResponse(
                keyID: "key-new-0001",
                userID: "user-new-0001",
                isNewUser: isNewUser
            ))),
        ])
        let lifecycle = makeLifecycle(transport, store: InMemoryKeyStore(), keyIDs: ["key-new-0001"])
        return try await lifecycle.enroll(provider: "apple", idToken: "t", nonce: SPFNSocialNonce.make())
    }

    private func assertNothingSurvived(
        _ store: InMemoryKeyStore,
        _ lifecycle: SPFNKeyLifecycle,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws
    {
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.activeSlot), "a refused enrollment persisted a key",
                     file: file, line: line)
        XCTAssertNil(store.loadSync(SPFNKeyLifecycle.candidateSlot), file: file, line: line)
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .unenrolled, file: file, line: line)
    }

    private static func enrollResponse(keyID: String, userID: String, isNewUser: Bool) -> String
    {
        "{\"isNewUser\":\(isNewUser),\"keyId\":\"\(keyID)\",\"userId\":\"\(userID)\"}"
    }

    private func makeLifecycle(
        _ transport: any SPFNTransport,
        store: InMemoryKeyStore,
        keyIDs: [String]
    ) -> SPFNKeyLifecycle
    {
        let idQueue = ScriptedQueue(keyIDs)
        return SPFNKeyLifecycle(
            transport: transport,
            store: store,
            baseURL: baseURL,
            clock: FakeClock(1_750_000_000_000),
            newKeyID: { idQueue.next() ?? "key-unexpected" },
            makeKey: { SPFNCustodyKey.generate(keyID: $0, preferSecureEnclave: false) }
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
