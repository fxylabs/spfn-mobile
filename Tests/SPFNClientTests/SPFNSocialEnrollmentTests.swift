// SPFN Mobile — native social enrollment, the case table's enroll cells.
//
// The lifecycle's state guards were already closed by the M-series; what this file
// closes is the entry point that generates the key, runs the app's sign-in and registers
// the result — one call, because the nonce is the key's fingerprint and the key has to
// exist before the provider is asked. Each test is one cell of the design's case table,
// and the expected results are the table's own, written here by hand (P10).
//
// SpfnKeyLifecycleTest.kt carries the same cells in Kotlin, under the same numbers.

import Foundation
import XCTest
import SPFNClient
import SPFNCore
import SPFNGenerated

final class SPFNSocialEnrollmentTests: XCTestCase
{
    private let baseURL = "https://example.invalid"

    // MARK: - Cells 1–3: what the entry point refuses before generating anything

    /// Cell 1: an empty provider is refused before anything is sent.
    func test_1_anEmptyProviderIsMalformed() async throws
    {
        try await assertProviderRefused("")
    }

    /// Cell 1: uppercase and whitespace are refused — the id is substituted into the
    /// path before signing, so anything but `[a-z0-9-]` would change the route.
    func test_1_uppercaseOrWhitespaceProviderIsMalformed() async throws
    {
        try await assertProviderRefused("Google")
        try await assertProviderRefused("goo gle")
    }

    /// Cell 2: a key already exists — `alreadyEnrolled`, and the sign-in never runs.
    func test_2_enrolledRefusesAndRunsNoSignIn() async throws
    {
        let transport = ScriptedTransport([])
        let store = InMemoryKeyStore()
        let existing = SPFNCustodyKey.generate(keyID: "key-existing-0001", preferSecureEnclave: false)
        try store.save(
            existing.record(clientID: "user-existing-0001", createdAtMillis: 1_750_000_000_000),
            slot: SPFNKeyLifecycle.activeSlot
        )
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])
        let signIn = SignInRecorder()

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple", idToken: signIn.answering("t"))
        }

        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .alreadyEnrolled)
        let ran = await signIn.callCount
        XCTAssertEqual(ran, 0, "an already-enrolled install must not put a sign-in sheet up")
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "an already-enrolled install must not reach the network")
    }

    /// Cell 3: a rotation is unresolved — `rotationUnresolved`, and the sign-in never runs.
    func test_3_rotationPendingRefusesAndRunsNoSignIn() async throws
    {
        let transport = ScriptedTransport([])
        let store = InMemoryKeyStore()
        let candidate = SPFNCustodyKey.generate(keyID: "key-candidate-0001", preferSecureEnclave: false)
        try store.save(
            candidate.record(clientID: "user-existing-0001", createdAtMillis: 1_750_000_000_000),
            slot: SPFNKeyLifecycle.candidateSlot
        )
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])
        let signIn = SignInRecorder()

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple", idToken: signIn.answering("t"))
        }

        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .rotationUnresolved)
        let ran = await signIn.callCount
        XCTAssertEqual(ran, 0)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "an unresolved rotation must not reach the network")
    }

    // MARK: - Cells 4 and 20: what else may run while a sign-in is up

    /// Cell 4: a second enrollment during the first one's sign-in is refused.
    ///
    /// The state checks cannot catch this on their own — an enrollment in progress has
    /// saved nothing, so both calls read `unenrolled`. Without the in-flight claim both
    /// would generate a key and register it, and the second save would bury the first
    /// registration while the server kept honouring it.
    func test_4_aSecondEnrollmentDuringTheSignInIsRefused() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, Self.enrollResponse(keyID: "key-new-0001", userID: "user-new-0001", isNewUser: true))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001", "key-new-0002"])
        let gate = ClosureGate()

        let first = Task
        {
            try await lifecycle.enroll(provider: "apple")
            { _ in
                await gate.enterAndWait()
                return "idtoken-first"
            }
        }
        await gate.waitForArrival()

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple") { _ in "idtoken-second" }
        }
        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .enrollmentInFlight)

        await gate.open()
        let result = try await first.value

        XCTAssertEqual(result.keyID, "key-new-0001", "the first enrollment is the one that settled")
        let calls = await transport.callCount
        XCTAssertEqual(calls, 1, "exactly one registration reached the server")
    }

    /// Cell 4: the claim is released however the call leaves, so a failed enrollment does
    /// not lock the install out of enrolling again.
    func test_4_aFailedEnrollmentReleasesTheClaim() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, Self.enrollResponse(keyID: "key-new-0002", userID: "user-new-0001", isNewUser: true))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001", "key-new-0002"])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple") { _ in throw SignInRefused.dismissed }
        }
        XCTAssertNotNil(thrown)

        let result = try await lifecycle.enroll(provider: "apple") { _ in "idtoken-retry" }
        XCTAssertEqual(result.keyID, "key-new-0002")
    }

    /// Cell 20: a rotation started during a sign-in answers `notEnrolled`, because at
    /// that moment the install genuinely holds no key.
    func test_20_rotateDuringTheSignInAnswersNotEnrolled() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, Self.enrollResponse(keyID: "key-new-0001", userID: "user-new-0001", isNewUser: true))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])
        let gate = ClosureGate()

        let first = Task
        {
            try await lifecycle.enroll(provider: "apple")
            { _ in
                await gate.enterAndWait()
                return "idtoken-first"
            }
        }
        await gate.waitForArrival()

        let thrown = await failure { _ = try await lifecycle.rotate() }
        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .notEnrolled)

        await gate.open()
        _ = try await first.value
    }

    // MARK: - Cells 5–7: what the sign-in closure answers with

    /// Cell 5: a cancelled sign-in reaches the caller as the cancellation it was, and
    /// nothing is stored. Classifying it would answer a caller who cancelled the task
    /// with a failure it did not have.
    func test_5_aCancelledSignInPropagatesUnchangedAndStoresNothing() async throws
    {
        let transport = ScriptedTransport([])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple") { _ in throw CancellationError() }
        }

        XCTAssertTrue(thrown is CancellationError, "the cancellation was reshaped into \(String(describing: thrown))")
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0, "a cancelled sign-in must not reach the network")
        try await assertNothingSurvived(store, lifecycle)
    }

    /// Cell 6: any other refusal from the sign-in reaches the caller unchanged. The
    /// provider's own error is the app's to read; reshaping it here would lose it.
    func test_6_aRefusedSignInPropagatesUnchangedAndStoresNothing() async throws
    {
        let transport = ScriptedTransport([])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple") { _ in throw SignInRefused.dismissed }
        }

        XCTAssertEqual(thrown as? SignInRefused, .dismissed)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0)
        try await assertNothingSurvived(store, lifecycle)
    }

    /// Cell 7: an empty token is refused here rather than sent. The server can only
    /// refuse it, and its refusal for this is outside the contract's error codes.
    func test_7_anEmptyTokenIsRefusedBeforeTheNetwork() async throws
    {
        let transport = ScriptedTransport([])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple") { _ in "" }
        }

        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .idTokenMissing)
        let calls = await transport.callCount
        XCTAssertEqual(calls, 0)
        try await assertNothingSurvived(store, lifecycle)
    }

    // MARK: - Cells 8–12: what the server's answer settles

    /// Cell 8: success with the key id this call sent — the identity is persisted and
    /// returned.
    func test_8_successWithAMatchingKeyIdStoresTheIdentity() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, Self.enrollResponse(keyID: "key-new-0001", userID: "user-new-0001", isNewUser: true))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

        let result = try await lifecycle.enroll(provider: "apple") { _ in "idtoken-apple" }

        XCTAssertEqual(result, SPFNEnrollmentResult(clientID: "user-new-0001", keyID: "key-new-0001", isNewUser: true))
        let active = try XCTUnwrap(store.loadSync(SPFNKeyLifecycle.activeSlot))
        XCTAssertEqual(active.clientID, "user-new-0001")
        XCTAssertEqual(active.keyID, "key-new-0001")
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .enrolled)
    }

    /// Cell 8: `isNewUser` reaches the caller as it arrived, either way.
    func test_8_isNewUserIsPassedThroughBothWays() async throws
    {
        let newUser = try await enrollWith(isNewUser: true)
        XCTAssertTrue(newUser.isNewUser)

        let returning = try await enrollWith(isNewUser: false)
        XCTAssertFalse(returning.isNewUser)
    }

    /// Cell 9: success naming a different key — refused, and nothing is stored. A server
    /// that confirms another key has not registered the one this device holds.
    func test_9_successNamingAnotherKeyIsRefusedAndStoresNothing() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, Self.enrollResponse(keyID: "key-other-9999", userID: "user-new-0001", isNewUser: true))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple") { _ in "idtoken-apple" }
        }

        XCTAssertEqual(
            thrown as? SPFNKeyLifecycleError,
            .serverNamedAnotherKey(sent: "key-new-0001", received: "key-other-9999")
        )
        try await assertNothingSurvived(store, lifecycle)
    }

    /// Cell 10: a refusal destroys the key and hands the error on. Both an auth-family
    /// code and a contract code, because the SDK classifies neither — a provider the
    /// server does not support arrives as one of these and is the caller's to read.
    func test_10_aRefusalDestroysTheKeyAndPropagates() async throws
    {
        for code in ["CONTRACT_UNSUPPORTED", "PROOF_INVALID"]
        {
            let transport = ScriptedTransport([
                .success(.json(400, ExecuteFixtures.errorEnvelope(code: code))),
            ])
            let store = InMemoryKeyStore()
            let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

            let thrown = await failure
            {
                _ = try await lifecycle.enroll(provider: "kakao") { _ in "idtoken-kakao" }
            }

            XCTAssertNotNil(thrown, "\(code): the refusal is the caller's to classify, not the SDK's to swallow")
            let calls = await transport.callCount
            XCTAssertEqual(calls, 1)
            try await assertNothingSurvived(store, lifecycle)
        }
    }

    /// Cell 11: a transport failure destroys the key and hands the error on. Enrollment
    /// is the one flow where a lost answer needs no resume: no key was persisted, so the
    /// next attempt starts from the same state as this one did.
    func test_11_aTransportFailureDestroysTheKeyAndPropagates() async throws
    {
        let transport = ScriptedTransport([
            .failure(SPFNTransportError.connectivity("offline")),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple") { _ in "idtoken-apple" }
        }

        XCTAssertNotNil(thrown)
        try await assertNothingSurvived(store, lifecycle)
    }

    /// Cell 12: a save that throws leaves no key and no state. The server may have
    /// registered it, and this device still cannot use a key it did not persist — so the
    /// honest answer is the throw, and the next attempt mints a new key.
    func test_12_aFailedSaveLeavesNothingBehind() async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, Self.enrollResponse(keyID: "key-new-0001", userID: "user-new-0001", isNewUser: true))),
        ])
        let store = RefusingKeyStore()
        let lifecycle = SPFNKeyLifecycle(
            transport: transport,
            store: store,
            baseURL: baseURL,
            clock: FakeClock(1_750_000_000_000),
            newKeyID: { "key-new-0001" },
            makeKey: { SPFNCustodyKey.generate(keyID: $0, preferSecureEnclave: false) }
        )

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: "apple") { _ in "idtoken-apple" }
        }

        XCTAssertEqual(thrown as? RefusingKeyStore.Refusal, .saveRefused)
        let state = try await lifecycle.state()
        XCTAssertEqual(state, .unenrolled)
    }

    // MARK: - Cell 15: what the body carries

    /// Cell 15: the body's nonce is the fingerprint, for every provider — including the
    /// one whose authorization request carried something else.
    func test_15_theBodyCarriesTheFingerprintForEveryProvider() async throws
    {
        for provider in ["apple", "google", "kakao", "naver"]
        {
            try await assertProviderProceeds(provider)
        }
    }

    // MARK: - Assembly

    /// One enrollment through `provider`, asserting the path it rode and the two body
    /// fields the contract binds to each other.
    private func assertProviderProceeds(
        _ provider: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws
    {
        let transport = ScriptedTransport([
            .success(.json(200, Self.enrollResponse(keyID: "key-new-0001", userID: "user-new-0001", isNewUser: true))),
        ])
        let store = InMemoryKeyStore()
        let lifecycle = makeLifecycle(transport, store: store, keyIDs: ["key-new-0001"])
        let seen = NonceBox()

        let result = try await lifecycle.enroll(provider: provider)
        { nonce in
            await seen.set(nonce)
            return "idtoken-\(provider)"
        }

        XCTAssertEqual(result.keyID, "key-new-0001", file: file, line: line)
        let handed = await seen.value
        let nonce = try XCTUnwrap(handed, file: file, line: line)
        XCTAssertEqual(nonce.provider, provider, file: file, line: line)

        let received = await transport.received
        let sent = try XCTUnwrap(received.first, file: file, line: line)
        // The path template is the contract's, read from the generated listing rather
        // than restated here: a restatement would be this suite's own invention.
        let expectedPath = SPFNGeneratedOperations.authEnrollOauthNative.path
            .replacingOccurrences(of: "{provider}", with: provider)
        XCTAssertEqual(sent.url, baseURL + expectedPath, file: file, line: line)

        let body = String(decoding: sent.body ?? [], as: UTF8.self)
        XCTAssertTrue(
            body.contains("\"nonce\":\"\(nonce.fingerprint)\""),
            "\(provider): the body's nonce must be the fingerprint",
            file: file, line: line
        )
        XCTAssertTrue(
            body.contains("\"fingerprint\":\"\(nonce.fingerprint)\""),
            "\(provider): the body's fingerprint must be the same value",
            file: file, line: line
        )
        if provider == "apple"
        {
            XCTAssertFalse(
                body.contains(nonce.requestValue),
                "apple: the body must not carry the value the authorization request took",
                file: file, line: line
            )
        }
    }

    private func assertProviderRefused(
        _ provider: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async throws
    {
        let transport = ScriptedTransport([])
        let lifecycle = makeLifecycle(transport, store: InMemoryKeyStore(), keyIDs: [])
        let signIn = SignInRecorder()

        let thrown = await failure
        {
            _ = try await lifecycle.enroll(provider: provider, idToken: signIn.answering("t"))
        }

        XCTAssertEqual(thrown as? SPFNKeyLifecycleError, .malformedProviderID, "'\(provider)' was accepted",
                       file: file, line: line)
        let ran = await signIn.callCount
        XCTAssertEqual(ran, 0, file: file, line: line)
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
        return try await lifecycle.enroll(provider: "apple") { _ in "idtoken-apple" }
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
        store: any SPFNKeyStore,
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

// MARK: - Doubles the enroll cells need

/// What a sign-in that refuses looks like to the lifecycle: an error of the app's own
/// making, which cell 6 asserts arrives unchanged.
enum SignInRefused: Error, Equatable
{
    case dismissed
}

/// Counts whether the sign-in closure ran at all — the cells that refuse before the
/// sheet goes up assert zero.
actor SignInRecorder
{
    private(set) var callCount = 0

    private func record()
    {
        callCount += 1
    }

    /// A closure that records the call and answers with `token`.
    nonisolated func answering(_ token: String) -> @Sendable (SPFNSocialNonce) async throws -> String
    {
        { _ in
            await self.record()
            return token
        }
    }
}

/// Holds the nonce the lifecycle handed the closure, so a cell can assert what the body
/// carried against what the sign-in was given.
actor NonceBox
{
    private(set) var value: SPFNSocialNonce?

    func set(_ nonce: SPFNSocialNonce)
    {
        value = nonce
    }
}

/// Suspends the sign-in closure until the test lets it go, and tells the test when the
/// closure got there. Cells 4 and 20 need both halves: without the arrival signal the
/// test would race the enrollment it is trying to interleave with.
actor ClosureGate
{
    private var hasArrived = false
    private var arrivalWaiters: [CheckedContinuation<Void, Never>] = []
    private var isOpen = false
    private var openWaiters: [CheckedContinuation<Void, Never>] = []

    /// Called from inside the sign-in closure.
    func enterAndWait() async
    {
        hasArrived = true
        let waiting = arrivalWaiters
        arrivalWaiters = []
        for waiter in waiting
        {
            waiter.resume()
        }
        guard !isOpen
        else
        {
            return
        }
        await withCheckedContinuation
        { (continuation: CheckedContinuation<Void, Never>) in
            openWaiters.append(continuation)
        }
    }

    func waitForArrival() async
    {
        guard !hasArrived
        else
        {
            return
        }
        await withCheckedContinuation
        { (continuation: CheckedContinuation<Void, Never>) in
            arrivalWaiters.append(continuation)
        }
    }

    func open()
    {
        isOpen = true
        let waiting = openWaiters
        openWaiters = []
        for waiter in waiting
        {
            waiter.resume()
        }
    }
}

/// A store whose save always throws — cell 12's whole subject. Loads answer nil, so the
/// lifecycle reads the install as unenrolled both before and after.
final class RefusingKeyStore: SPFNKeyStore, @unchecked Sendable
{
    enum Refusal: Error, Equatable
    {
        case saveRefused
    }

    func load(slot: String) throws -> SPFNStoredKey?
    {
        nil
    }

    func save(_ record: SPFNStoredKey, slot: String) throws
    {
        throw Refusal.saveRefused
    }

    func delete(slot: String) throws
    {
    }
}
