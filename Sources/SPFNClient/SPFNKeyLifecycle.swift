// SPFN Mobile — the client key's life: enrollment, rotation, revocation, TTL.
//
// One rule shapes everything here: at every observable moment there is exactly one key
// a caller can sign with — the one in the active slot. Enrollment creates it, rotation
// replaces it, revocation wipes it, and no path exposes a second signer in between. A
// rotation candidate exists transiently in its own slot, persisted before the network
// call so a process death cannot lose track of a key the server may already know, and
// it becomes signable only by becoming the active key.
//
// The rotation state machine, spelled out because M5 tests every edge of it:
//
//   enrolled ──rotate(): persist candidate──▶ rotationPending ──success──▶ enrolled(new)
//     ▲                                            │
//     │◀──refusal in the same call: not applied────┘  (candidate destroyed, old kept)
//     │
//     │◀── resume: PROOF_INVALID means the old key is no longer registered, so the
//     │    earlier attempt WAS applied — the candidate is promoted, not discarded.
//     │    A transport failure leaves the machine where it was; SESSION_REVOKED
//     │    wipes everything, because the old key itself is dead.
//
// The asymmetry between rotate() and resumeRotation() on the same PROOF_INVALID is the
// point of having both: inside rotate() the request was sent exactly once and refused,
// so the server did not apply it; on resume the previous send's outcome is unknown, and
// a well-formed old-key proof failing verification means the old key is gone — which is
// what a completed rotation looks like from the outside.
//
// Device-code enrollment adds no state to that machine. The key it parks and the device
// code it polls with live in this call's own frame for as long as the call runs, and the
// install stays `unenrolled` until the approval is saved — so a process death, a
// cancellation or any refusal leaves nothing behind to resume, which is exactly the
// difference between it and a rotation.
//
// android/spfn-client/.../SpfnKeyLifecycle.kt is the same machine in Kotlin.

import Foundation
import SPFNAuth
import SPFNCore
import SPFNGenerated

/// The lifecycle's answer to "what key does this install hold".
public enum SPFNKeyLifecycleState: Equatable, Sendable
{
    /// No usable key: enrollment is required before any proven operation.
    case unenrolled

    /// One active key, ready to sign.
    case enrolled

    /// A rotation was started and its outcome is unknown; call `resumeRotation()`.
    case rotationPending
}

/// What enrollment settled: the identity the server issued for the key it registered.
public struct SPFNEnrollmentResult: Equatable, Sendable
{
    /// The key owner's identity — the response's `userId`, which is what every proof's
    /// `clientId` must equal from now on (the contract's `clientIdRule`).
    public let clientID: String

    public let keyID: String

    public let isNewUser: Bool

    public init(clientID: String, keyID: String, isNewUser: Bool)
    {
        self.clientID = clientID
        self.keyID = keyID
        self.isNewUser = isNewUser
    }
}

/// What a device-code enrollment settled.
///
/// Its own type rather than `SPFNEnrollmentResult`: the two flows answer with different
/// facts. A social enrollment learns whether the account was created just now; a device
/// approval learns whether the account it joined is owed a password change. Neither
/// question has an answer on the other path, and one type carrying both would be a type
/// where half the fields are always meaningless.
///
/// `publicId`, `email` and `phone` reach the client on the approved poll and are not
/// carried here: the lifecycle owns keys, and an account's profile is the app's to read
/// through its own operations.
public struct SPFNDeviceCodeEnrollmentResult: Equatable, Sendable
{
    /// The key owner's identity — the approved poll's `userId`, which is what every
    /// proof's `clientId` must equal from now on (the contract's `clientIdRule`).
    public let clientID: String

    /// The key this flow parked and the approval registered. This SDK's own identifier,
    /// minted before `auth.device.start` was sent.
    public let keyID: String

    /// The login rule the account carries, exactly as the approved poll stated it.
    public let passwordChangeRequired: Bool

    public init(clientID: String, keyID: String, passwordChangeRequired: Bool)
    {
        self.clientID = clientID
        self.keyID = keyID
        self.passwordChangeRequired = passwordChangeRequired
    }
}

/// How the wait between two polls is spent.
///
/// A seam for the same reason the clock is one: the device-code flow's only observable
/// timing rule is "wait exactly what the server asked for", and a suite that really
/// waited five seconds per poll could not assert it in a unit test.
public protocol SPFNSleeper: Sendable
{
    func sleep(millis: Int64) async throws
}

/// The default sleeper. `Task.sleep` is cancellation-aware, which is what makes a
/// cancelled wait stop at the wait rather than at the next request.
public struct SPFNTaskSleeper: SPFNSleeper
{
    public init() {}

    public func sleep(millis: Int64) async throws
    {
        try await Task.sleep(nanoseconds: UInt64(max(0, millis)) * 1_000_000)
    }
}

/// Everything the lifecycle refuses on its own, before or instead of the network.
public enum SPFNKeyLifecycleError: Error, Equatable, Sendable
{
    /// Enrollment was asked for while a key exists. Wipe first — implicitly enrolling
    /// over a live key would orphan a registration the server still honours.
    case alreadyEnrolled

    /// Rotation or signing was asked for with no active key.
    case notEnrolled

    /// A new enrollment or rotation was asked for while a rotation is unresolved.
    case rotationUnresolved

    /// A second enrollment was asked for while the first one's sign-in is still running.
    ///
    /// The state checks above cannot see this: an enrollment in progress has saved
    /// nothing yet, so both calls would read `unenrolled` and both would register a key.
    case enrollmentInFlight

    /// The sign-in closure answered with an empty token. Sending it would spend a key
    /// generation on a request the server can only refuse.
    case idTokenMissing

    /// The provider id cannot be a path segment. The id is substituted into the
    /// operation path before signing, so anything but `[a-z0-9-]` would change the
    /// route — or smuggle one — rather than name a provider.
    case malformedProviderID

    /// The server's answer named a key other than the one this call sent. The
    /// associated values are this SDK's own identifiers, never server text.
    case serverNamedAnotherKey(sent: String, received: String)

    /// A record exists but its key cannot be opened on this device. Re-enrollment is
    /// the only way forward.
    case keyUnloadable

    /// The device code reached the `expiresAtMillis` the `start` answer named before
    /// anyone approved it, judged on the proof clock. The wait ends here rather than at
    /// the server's own refusal: a client that polled past the expiry it was told would
    /// be asking about a code it already knows is dead.
    case deviceCodeExpired
}

/// Owns the key slots and drives enrollment and rotation over the execute path.
///
/// An actor for the same reason the session is one: `rotate` reads, sends and swaps,
/// and two of those interleaved would be two candidates for one active key.
public actor SPFNKeyLifecycle
{
    /// The slot names this lifecycle owns inside the injected store.
    public static let activeSlot = "active"
    public static let candidateSlot = "rotation-candidate"

    private let transport: any SPFNTransport
    private let store: any SPFNKeyStore
    private let baseURL: String
    private let clock: any SPFNClock
    private let proofClock: any SPFNProofClock
    private let nonceGenerator: any SPFNNonceGenerator
    private let sleeper: any SPFNSleeper
    private let timeoutMillis: Int64
    private let newKeyID: @Sendable () -> String
    private let makeKey: @Sendable (String) -> SPFNCustodyKey

    /// True from the moment `enroll` claims the flow to the moment it leaves, however it
    /// leaves. An actor does not serialise across an `await`, and `enroll` now awaits the
    /// app's sign-in — which lasts as long as a person takes — so this is what stands
    /// between two concurrent calls and two registered keys.
    private var enrollmentInFlight = false

    /// - Parameters:
    ///   - newKeyID: mints key identifiers; UUIDs by default. Injected so a suite can
    ///     pin the wire bytes a flow produces against the fixtures.
    ///   - makeKey: generates custody keys; the platform decision by default.
    public init(
        transport: any SPFNTransport,
        store: any SPFNKeyStore,
        baseURL: String,
        clock: any SPFNClock = SPFNSystemClock(),
        proofClock: any SPFNProofClock = SPFNProcessServerClock.shared,
        nonceGenerator: any SPFNNonceGenerator = SPFNRandomNonceGenerator(),
        sleeper: any SPFNSleeper = SPFNTaskSleeper(),
        timeoutMillis: Int64 = 15_000,
        newKeyID: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() },
        makeKey: @escaping @Sendable (String) -> SPFNCustodyKey = { SPFNCustodyKey.generate(keyID: $0) }
    )
    {
        self.transport = transport
        self.store = store
        self.baseURL = baseURL
        self.clock = clock
        self.proofClock = proofClock
        self.nonceGenerator = nonceGenerator
        self.sleeper = sleeper
        self.timeoutMillis = timeoutMillis
        self.newKeyID = newKeyID
        self.makeKey = makeKey
    }

    // MARK: - Observation

    public func state() throws -> SPFNKeyLifecycleState
    {
        if try store.load(slot: Self.candidateSlot) != nil
        {
            return .rotationPending
        }
        guard let active = try store.load(slot: Self.activeSlot), active.clientID != nil
        else
        {
            return .unenrolled
        }
        return .enrolled
    }

    /// The one signer this install holds, or nil before enrollment. A rotation
    /// candidate is never returned here — it becomes signable by becoming active.
    public func activeProvider() throws -> SPFNSecureEnclaveKeyProvider?
    {
        try SPFNSecureEnclaveKeyProvider.load(from: store, slot: Self.activeSlot)
    }

    // MARK: - M7: the TTL judgment

    /// Milliseconds until the active key reaches `keyPolicy.ttlDays`, negative once it
    /// has, or nil with no active key. Foreground arithmetic only: nothing here
    /// schedules anything, because background execution is outside this SDK's scope.
    public func keyRemainingMillis() throws -> Int64?
    {
        guard let active = try store.load(slot: Self.activeSlot), active.clientID != nil
        else
        {
            return nil
        }
        let ttlMillis = SPFNGeneratedContract.keyPolicyTtlDays * 24 * 60 * 60 * 1_000
        return active.createdAtMillis + ttlMillis - clock.nowMillis()
    }

    /// True when the active key is inside `leadTimeMillis` of its TTL — the moment a
    /// foregrounded app should start a rotation.
    public func rotationDue(leadTimeMillis: Int64 = 0) throws -> Bool
    {
        guard let remaining = try keyRemainingMillis()
        else
        {
            return false
        }
        return remaining <= leadTimeMillis
    }

    // MARK: - M1–M3: enrollment

    /// Generates a key, signs in with the provider, and enrolls the key — one call.
    ///
    /// The three steps are one call because the nonce is the key's fingerprint (the
    /// contract's `nativeEnrollment.nonceRule`). The key therefore has to exist before
    /// the provider is asked for a token, and a sign-in the user abandons would strand a
    /// key nobody registered. Owning the whole flow is what lets this destroy it.
    ///
    /// `idToken` is handed the nonce and returns the provider's token. Everything the
    /// closure needs to reach a provider is on the nonce: `requestValue` is already the
    /// shape that provider expects, so a caller driving kakao or naver directly puts
    /// that value in the request and nothing else.
    ///
    /// The request body is exact (M1): the public key as SPKI DER base64, the minted
    /// keyId, the fingerprint as the SHA-256 of the SPKI DER in lowercase base16, the
    /// nonce equal to that fingerprint, and the literal algorithm name. On success the
    /// response's `userId` is persisted as the clientID every future proof carries (M2).
    /// On any failure the generated key is destroyed (M3).
    public func enroll(
        provider: String,
        idToken: @Sendable (SPFNSocialNonce) async throws -> String
    ) async throws -> SPFNEnrollmentResult
    {
        guard Self.isProviderID(provider)
        else
        {
            throw SPFNKeyLifecycleError.malformedProviderID
        }
        switch try state()
        {
        case .enrolled:
            throw SPFNKeyLifecycleError.alreadyEnrolled
        case .rotationPending:
            throw SPFNKeyLifecycleError.rotationUnresolved
        case .unenrolled:
            break
        }
        // Claimed before the first `await`, so the two checks above and this claim are
        // one indivisible step from any other call's point of view.
        guard !enrollmentInFlight
        else
        {
            throw SPFNKeyLifecycleError.enrollmentInFlight
        }
        enrollmentInFlight = true
        defer { enrollmentInFlight = false }

        // On any failure from here to the save, the key was never persisted, so
        // dropping the value destroys it and no orphan outlives the throw. The Android
        // counterpart has a keystore entry to delete at the same point; the two files
        // tell one story with different amounts of work.
        let key = makeKey(newKeyID())
        let fingerprint = SPFNDigest.sha256Hex(key.publicKeySpkiDer)
        let token = try await idToken(SPFNSocialNonce(fingerprint: fingerprint, provider: provider))
        guard !token.isEmpty
        else
        {
            throw SPFNKeyLifecycleError.idTokenMissing
        }

        let response = try await client(signingWith: nil).execute(
            Self.oauthNativeCall(provider: provider),
            request: SPFNOauthNativeRequest(
                idToken: token,
                // The same value twice, by the contract's rule. Reading it from one
                // local rather than recomputing it means the two fields cannot drift.
                nonce: fingerprint,
                publicKey: Data(key.publicKeySpkiDer).base64EncodedString(),
                keyId: key.keyID,
                fingerprint: fingerprint,
                algorithm: Self.algorithmName
            )
        )

        guard response.keyId == key.keyID
        else
        {
            throw SPFNKeyLifecycleError.serverNamedAnotherKey(sent: key.keyID, received: response.keyId)
        }

        try store.save(
            key.record(clientID: response.userId, createdAtMillis: clock.nowMillis()),
            slot: Self.activeSlot
        )
        return SPFNEnrollmentResult(clientID: response.userId, keyID: key.keyID, isNewUser: response.isNewUser)
    }

    // MARK: - M8: enrollment by device code

    /// Enrolls this device by showing a code somebody approves on a device already
    /// signed in — the contract's `deviceAuthorization` flow, from the waiting side.
    ///
    /// One call, for the same reason `enroll` is one: the key has to exist before
    /// `auth.device.start` can park it, the code the user reads names that parked key,
    /// and an approval nobody comes back to collect would strand a key nobody
    /// registered. Owning the whole wait is what lets this destroy it.
    ///
    /// `showCode` is called exactly once, immediately after `start` answers, with the
    /// code as the server spelled it (`XXXX-XXXX` — the server folds case, spaces and
    /// dashes on the way back in, so nothing here reformats it) and the instant it
    /// expires. It is called on whatever executor the caller's task is running on; this
    /// SDK switches to no thread of its own, so an app that must draw from the main
    /// thread hops there itself.
    ///
    /// The rules, in the order they are enforced (M8):
    ///
    ///   1. The state checks and the in-flight claim are `enroll`'s, and the claim is
    ///      the same flag: a device-code enrollment and a social one cannot both be
    ///      running, because both would register a key while the store still reads
    ///      `unenrolled`.
    ///   2. The `start` body is exact: the public key as SPKI DER base64, the minted
    ///      keyId, the fingerprint as the SHA-256 of the SPKI DER in lowercase base16,
    ///      the literal algorithm name, this build's client kind as the platform, and
    ///      the caller's `deviceName` when it gave one. Nothing is read off the OS.
    ///   3. The wait obeys the server: `intervalMillis` from `start`, then from each
    ///      `pending`. There is no client-side default and no backoff. A `pending`
    ///      answer is not a failure; every refusal the contract marks retryable — one
    ///      today, `TooManyRequestsError` — and every lost response are asked again
    ///      after that same interval, and everything else ends the wait.
    ///   4. The deadline is `start`'s `expiresAtMillis` judged on the proof clock, the
    ///      one `core.time` synchronised. The device's own wall clock never enters it,
    ///      and a lost `core.time` fetch is a lost poll: it costs the same interval and
    ///      is asked again, so the deadline is judged when the clock answers.
    ///   5. Every exit that is not an approval destroys the key, cancellation included.
    ///      No fourth lifecycle state exists: until the approval is saved this install
    ///      is `unenrolled`, and a process death leaves it that way.
    public func enrollByDeviceCode(
        deviceName: String? = nil,
        showCode: @Sendable (_ userCode: String, _ expiresAtMillis: Int64) -> Void
    ) async throws -> SPFNDeviceCodeEnrollmentResult
    {
        switch try state()
        {
        case .enrolled:
            throw SPFNKeyLifecycleError.alreadyEnrolled
        case .rotationPending:
            throw SPFNKeyLifecycleError.rotationUnresolved
        case .unenrolled:
            break
        }
        // Claimed before the first `await`, so the check above and this claim are one
        // indivisible step from any other call's point of view — including `enroll`'s.
        guard !enrollmentInFlight
        else
        {
            throw SPFNKeyLifecycleError.enrollmentInFlight
        }
        enrollmentInFlight = true
        defer { enrollmentInFlight = false }

        // The key exists only as this local until the approval is saved. Every throw
        // below therefore destroys it by dropping it, and the install stays unenrolled;
        // the Android counterpart deletes a Keystore alias at the same points.
        let key = makeKey(newKeyID())
        let fingerprint = SPFNDigest.sha256Hex(key.publicKeySpkiDer)

        let started = try await client(signingWith: nil).execute(
            Self.deviceStartCall,
            request: SPFNStartDeviceAuthRequest(
                publicKey: Data(key.publicKeySpkiDer).base64EncodedString(),
                keyId: key.keyID,
                fingerprint: fingerprint,
                algorithm: Self.algorithmName,
                deviceName: deviceName,
                platform: Self.platform
            )
        )
        showCode(started.userCode, started.expiresAtMillis)

        let approved = try await awaitApproval(
            deviceCode: started.deviceCode,
            expiresAtMillis: started.expiresAtMillis,
            intervalMillis: try Self.waitMillis(started.intervalMillis)
        )

        // Saved exactly as `enroll` saves it, so a key this flow enrolled is a key
        // `rotate` can replace and `activeProvider` can sign with.
        try store.save(
            key.record(clientID: approved.clientID, createdAtMillis: clock.nowMillis()),
            slot: Self.activeSlot
        )
        return SPFNDeviceCodeEnrollmentResult(
            clientID: approved.clientID,
            keyID: key.keyID,
            passwordChangeRequired: approved.passwordChangeRequired
        )
    }

    /// What an approved poll settled, before the key it belongs to is saved.
    private struct SPFNDeviceApproval
    {
        let clientID: String
        let passwordChangeRequired: Bool
    }

    /// The wait: sleep the interval, judge the deadline, poll, read the answer.
    ///
    /// The deadline is checked between the sleep and the request rather than after it,
    /// so a code that expired while this device was waiting costs no request at all.
    ///
    /// Two things can be lost inside one iteration and both cost the same interval: the
    /// clock read and the poll. On a fresh install the first iteration's clock read is a
    /// real `core.time` request, and a network that dropped it says exactly as much about
    /// the code as a network that dropped the poll one line below — nothing.
    private func awaitApproval(
        deviceCode: String,
        expiresAtMillis: Int64,
        intervalMillis: Int64
    ) async throws -> SPFNDeviceApproval
    {
        var waitMillis = intervalMillis
        while true
        {
            try await sleeper.sleep(millis: waitMillis)

            guard let now = try await clockNow()
            else
            {
                continue
            }
            guard now < expiresAtMillis
            else
            {
                throw SPFNKeyLifecycleError.deviceCodeExpired
            }

            guard let answer = try await pollOnce(deviceCode: deviceCode)
            else
            {
                continue
            }

            // The branch is read from `status` and never from which fields arrived: the
            // contract's `pollStatusRule` states that every field but the discriminant
            // is optional because it belongs to one branch, so guessing from presence
            // would be reading a shape nothing declared.
            switch answer.status
            {
            case .pending:
                waitMillis = try Self.waitMillis(answer.intervalMillis)
            case .approved:
                guard let clientID = answer.userId, let passwordChangeRequired = answer.passwordChangeRequired
                else
                {
                    throw SPFNClientError.decoding(.notTheDeclaredResponse)
                }
                return SPFNDeviceApproval(clientID: clientID, passwordChangeRequired: passwordChangeRequired)
            }
        }
    }

    /// The proof clock, or nil for the one failure that means "ask again after the
    /// interval".
    ///
    /// A lost `core.time` fetch is not an answer about the device code, so it does not
    /// end the wait and destroy the key: it waits and reads again, and the deadline is
    /// judged when the clock finally answers. Only the transport failure is retried.
    /// A refusal to synchronize at all — an untrusted base URL, a contract with no usable
    /// clock operation — is the same on every retry and ends the wait, and cancellation
    /// is the caller withdrawing and is rethrown as itself.
    private func clockNow() async throws -> Int64?
    {
        do
        {
            return try await proofClock.nowMillis(
                transport: transport,
                baseURL: baseURL,
                timeoutMillis: timeoutMillis
            )
        }
        catch SPFNClockSynchronizationError.requestFailed
        {
            return nil
        }
    }

    /// One poll, or nil for the two answers that mean "ask again after the interval".
    ///
    /// A lost response is one of them: the poll applies nothing, so re-sending it cannot
    /// apply anything twice — which is why this operation may be retried where the
    /// execute path retries nothing. A cancelled call is not a lost one and is rethrown
    /// as itself, because the caller withdrawing is not a network failure.
    private func pollOnce(deviceCode: String) async throws -> SPFNPollDeviceAuthResponse?
    {
        do
        {
            return try await client(signingWith: nil).execute(
                Self.devicePollCall,
                request: SPFNPollDeviceAuthRequest(deviceCode: deviceCode)
            )
        }
        catch SPFNClientError.transport(let failure) where failure != .cancelled
        {
            return nil
        }
        catch SPFNClientError.server(let failure) where failure.code.isRetryable
        {
            // `TooManyRequestsError` today, and whatever the contract marks retryable
            // tomorrow: the code is still live, this device only asked too fast.
            return nil
        }
    }

    /// The interval the server asked this device to wait, or a decoding refusal.
    ///
    /// Absent, zero and negative are one answer: one this client cannot obey. The
    /// contract declares an integer and the server's own configuration refuses anything
    /// but a positive whole number of milliseconds, so a value outside that is a server
    /// this client does not understand — and waiting zero would spin the poll straight
    /// into the rate limit the interval exists to stay under.
    private static func waitMillis(_ intervalMillis: Int64?) throws -> Int64
    {
        guard let intervalMillis, intervalMillis > 0
        else
        {
            throw SPFNClientError.decoding(.notTheDeclaredResponse)
        }
        return intervalMillis
    }

    // MARK: - M4–M5: rotation

    /// Replaces the active key: a fresh key is generated, persisted as the candidate,
    /// and registered through `auth.keys.rotate` under the old key's proof. Success
    /// swaps the candidate in; a refusal destroys the candidate and keeps the old key,
    /// because a refused request was never applied. Only a transport failure leaves
    /// the machine in `rotationPending` — the one case where the server's state is
    /// genuinely unknown — and `resumeRotation()` resolves it.
    @discardableResult
    public func rotate() async throws -> SPFNEnrollmentResult
    {
        switch try state()
        {
        case .unenrolled:
            throw SPFNKeyLifecycleError.notEnrolled
        case .rotationPending:
            throw SPFNKeyLifecycleError.rotationUnresolved
        case .enrolled:
            break
        }
        guard let old = try activeProvider()
        else
        {
            throw SPFNKeyLifecycleError.keyUnloadable
        }

        let candidate = makeKey(newKeyID())
        try store.save(
            candidate.record(clientID: old.clientID, createdAtMillis: clock.nowMillis()),
            slot: Self.candidateSlot
        )

        do
        {
            let response = try await send(candidate: candidate, provedBy: old)
            return try promote(candidate: candidate, clientID: old.clientID, confirmedKeyID: response.keyId)
        }
        catch let error as SPFNClientError
        {
            switch error
            {
            case .transport:
                // No response: the server may or may not have applied it. The
                // candidate stays persisted and the state answers rotationPending.
                throw error
            case .auth(let failure) where failure.code == .sessionRevoked:
                // The old key itself is dead; nothing here can sign anymore (M6).
                try wipe()
                throw error
            default:
                // A refusal in the same call that sent the one request: not applied.
                try store.delete(slot: Self.candidateSlot)
                throw error
            }
        }
        catch let error as SPFNAuthError
        {
            // Proof assembly failed before anything was sent, so the server cannot
            // have applied a request that never existed: the candidate is discarded.
            try store.delete(slot: Self.candidateSlot)
            throw error
        }
    }

    /// Resolves a rotation whose outcome was lost to a transport failure.
    ///
    /// Re-sends the same candidate under the old key's proof. Success completes the
    /// rotation. `PROOF_INVALID` also completes it: this SDK signed a well-formed
    /// proof, so the only reading is that the old key is no longer registered — which
    /// is what the earlier attempt having been applied looks like. `SESSION_REVOKED`
    /// wipes. Any other refusal discards the candidate and keeps the old key.
    @discardableResult
    public func resumeRotation() async throws -> SPFNEnrollmentResult
    {
        guard let record = try store.load(slot: Self.candidateSlot)
        else
        {
            throw SPFNKeyLifecycleError.notEnrolled
        }
        guard let clientID = record.clientID, let candidate = SPFNCustodyKey.reload(from: record)
        else
        {
            throw SPFNKeyLifecycleError.keyUnloadable
        }

        // A death between the swap and the candidate cleanup leaves both slots naming
        // one key; the resume is then only the cleanup.
        if let active = try store.load(slot: Self.activeSlot), active.keyID == record.keyID
        {
            try store.delete(slot: Self.candidateSlot)
            return SPFNEnrollmentResult(clientID: clientID, keyID: record.keyID, isNewUser: false)
        }

        guard let old = try activeProvider()
        else
        {
            throw SPFNKeyLifecycleError.keyUnloadable
        }

        do
        {
            let response = try await send(candidate: candidate, provedBy: old)
            return try promote(candidate: candidate, clientID: clientID, confirmedKeyID: response.keyId)
        }
        catch let error as SPFNClientError
        {
            switch error
            {
            case .transport:
                throw error
            case .auth(let failure) where failure.code == .proofInvalid:
                return try promote(candidate: candidate, clientID: clientID, confirmedKeyID: candidate.keyID)
            case .auth(let failure) where failure.code == .sessionRevoked:
                try wipe()
                throw error
            default:
                try store.delete(slot: Self.candidateSlot)
                throw error
            }
        }
    }

    // MARK: - M6: revocation

    /// The reaction to `SESSION_REVOKED`: every slot is cleared, and the state answers
    /// `unenrolled` — the "re-enrollment required" signal a caller reads.
    public func noteSessionRevoked() throws
    {
        try wipe()
    }

    /// Deletes both slots. After this nothing can sign until a new enrollment.
    public func wipe() throws
    {
        try store.delete(slot: Self.activeSlot)
        try store.delete(slot: Self.candidateSlot)
    }

    // MARK: - Assembly

    private func send(
        candidate: SPFNCustodyKey,
        provedBy old: SPFNSecureEnclaveKeyProvider
    ) async throws -> SPFNRotateKeyResponse
    {
        try await client(signingWith: old).execute(
            Self.rotateCall,
            request: SPFNRotateKeyRequest(
                publicKey: Data(candidate.publicKeySpkiDer).base64EncodedString(),
                keyId: candidate.keyID,
                fingerprint: SPFNDigest.sha256Hex(candidate.publicKeySpkiDer),
                algorithm: Self.algorithmName
            )
        )
    }

    /// Swaps the candidate into the active slot, in the order a death cannot corrupt:
    /// active first, candidate cleanup second — the resume path reads that overlap.
    private func promote(
        candidate: SPFNCustodyKey,
        clientID: String,
        confirmedKeyID: String
    ) throws -> SPFNEnrollmentResult
    {
        guard confirmedKeyID == candidate.keyID
        else
        {
            throw SPFNKeyLifecycleError.serverNamedAnotherKey(sent: candidate.keyID, received: confirmedKeyID)
        }
        try store.save(
            candidate.record(clientID: clientID, createdAtMillis: clock.nowMillis()),
            slot: Self.activeSlot
        )
        try store.delete(slot: Self.candidateSlot)
        return SPFNEnrollmentResult(clientID: clientID, keyID: candidate.keyID, isNewUser: false)
    }

    /// One client per call, over one session. For the unproven enrollment the signer
    /// is never consulted — the unproven path touches no session state — so the
    /// enrollment client carries the candidate key under an empty identity rather
    /// than a second provider type that exists only to throw.
    private func client(signingWith provider: SPFNSecureEnclaveKeyProvider?) -> SPFNClient
    {
        let keyProvider: any SPFNKeyProvider = provider
            ?? SPFNSecureEnclaveKeyProvider(clientID: "", key: makeKeyPlaceholder)
        return SPFNClient(
            transport: transport,
            session: SPFNSession(
                transport: transport,
                keyProvider: keyProvider,
                baseURL: baseURL,
                clock: proofClock,
                nonceGenerator: nonceGenerator,
                timeoutMillis: timeoutMillis
            ),
            timeoutMillis: timeoutMillis
        )
    }

    /// A throwaway key backing the never-consulted placeholder above.
    private var makeKeyPlaceholder: SPFNCustodyKey
    {
        SPFNCustodyKey.generate(keyID: "unenrolled", preferSecureEnclave: false)
    }

    /// The signature algorithm every key this lifecycle generates is signed with.
    ///
    /// A generated enum case since contract 0.6.0 rather than the string it used to be.
    /// The contract declares the set, so a value outside it is now a compile error here
    /// instead of a refusal the server has to raise.
    private static let algorithmName: SPFNKeyAlgorithm = .es256

    /// The platform a parked key is registered under, and it is the identity header's
    /// own value rather than a second constant: `x-spfn-client-kind` is what the server
    /// already judges this build by, and two spellings of one fact are two facts as soon
    /// as somebody edits one. Nil would mean this build reports a kind the contract's
    /// `KeyPlatform` set does not name, which is a mismatch the field cannot state.
    private static var platform: SPFNKeyPlatform?
    {
        SPFNKeyPlatform(rawValue: SPFNClientIdentity.kind)
    }

    private static var deviceStartCall: SPFNCall<SPFNStartDeviceAuthRequest, SPFNStartDeviceAuthResponse>
    {
        SPFNCall(
            operation: SPFNGeneratedOperations.authDeviceStart,
            encode: { try $0.canonicalValue() },
            decode: { try SPFNStartDeviceAuthResponse(canonical: $0) }
        )
    }

    private static var devicePollCall: SPFNCall<SPFNPollDeviceAuthRequest, SPFNPollDeviceAuthResponse>
    {
        SPFNCall(
            operation: SPFNGeneratedOperations.authDevicePoll,
            encode: { try $0.canonicalValue() },
            decode: { try SPFNPollDeviceAuthResponse(canonical: $0) }
        )
    }

    private static var rotateCall: SPFNCall<SPFNRotateKeyRequest, SPFNRotateKeyResponse>
    {
        SPFNCall(
            operation: SPFNGeneratedOperations.authKeysRotate,
            encode: { try $0.canonicalValue() },
            decode: { try SPFNRotateKeyResponse(canonical: $0) }
        )
    }

    private static func oauthNativeCall(provider: String) -> SPFNCall<SPFNOauthNativeRequest, SPFNOauthNativeResponse>
    {
        let template = SPFNGeneratedOperations.authEnrollOauthNative
        return SPFNCall(
            operation: SPFNOperation(
                id: template.id,
                method: template.method,
                path: template.path.replacingOccurrences(of: "{provider}", with: provider),
                authProfile: template.authProfile,
                requiresSession: template.requiresSession,
                declaresResponse: template.declaresResponse
            ),
            encode: { try $0.canonicalValue() },
            decode: { try SPFNOauthNativeResponse(canonical: $0) }
        )
    }

    /// The set the validator's own path exemption names: lowercase alphanumerics and
    /// hyphens, non-empty. Everything else would rewrite the route it rides in.
    static func isProviderID(_ provider: String) -> Bool
    {
        !provider.isEmpty && provider.unicodeScalars.allSatisfy
        { scalar in
            (scalar >= "a" && scalar <= "z") || (scalar >= "0" && scalar <= "9") || scalar == "-"
        }
    }
}
