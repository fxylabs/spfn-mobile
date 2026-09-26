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
// TWO outcomes park a rotation, not one: a send with no answer, and an answer with a 2xx
// status this SDK could not read. The second is not a failure — the server said yes, and
// may well have applied the rotation — so destroying the candidate on it would leave an
// install whose only registered key it has just deleted, stuck until a wipe and a fresh
// enrollment. `classifyRotationOutcome` is where every one of those rows is decided,
// once, for both entry points; docs/architecture/README.md carries the same table in
// prose.
//
// The asymmetry between rotate() and resumeRotation() on the same PROOF_INVALID is the
// point of having both: inside rotate() the request was sent exactly once and refused,
// so the server did not apply it; on resume the previous send's outcome is unknown, and
// a well-formed old-key proof failing verification means the old key is gone — which is
// what a completed rotation looks like from the outside.
//
// Device-code and link-code enrollment add no state to that machine. The key each parks
// and the device code it polls with live in this call's own frame for as long as the
// call runs, and the install stays `unenrolled` until the approval is saved — so a
// process death, a cancellation or any refusal leaves nothing behind to resume, which is
// exactly the difference between them and a rotation.
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
public enum SPFNKeyLifecycleError: LocalizedError, Equatable, Sendable
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

    /// This SDK version does not finish a second-factor sign-in. The generated key
    /// is discarded; the challenge is never exposed by this error.
    case secondFactorRequired

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

    /// The link code handed to `enrollByLinkCode` is not eight characters of the
    /// device-code alphabet once spaces, dashes and case are folded away. Refused before
    /// a key is generated or anything is sent; the code itself is never carried here.
    case malformedLinkCode

    /// The link reached the `expiresAtMillis` the `redeem` answer named before the
    /// signed-in device picked the number, judged on the proof clock — the local twin of
    /// `DeviceLinkExpiredError`, for the reason `deviceCodeExpired` gives.
    case linkCodeExpired

    public var errorDescription: String?
    {
        switch self
        {
        case .secondFactorRequired:
            return "this SDK version does not finish a second-factor sign-in"
        default:
            return nil
        }
    }
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
        try claimEnrollment()
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

        // The discriminant alone decides the branch. A challenge is not evidence of
        // enrollment, and this SDK version cannot activate its pending server key.
        guard !response.mfaRequired
        else
        {
            throw SPFNKeyLifecycleError.secondFactorRequired
        }
        guard let userID = response.userId, let keyID = response.keyId
        else
        {
            throw SPFNClientError.decoding(.notTheDeclaredResponse, onSuccessStatus: true)
        }
        guard keyID == key.keyID
        else
        {
            throw SPFNKeyLifecycleError.serverNamedAnotherKey(sent: key.keyID, received: keyID)
        }

        try store.save(
            key.record(clientID: userID, createdAtMillis: clock.nowMillis()),
            slot: Self.activeSlot
        )
        return SPFNEnrollmentResult(clientID: userID, keyID: key.keyID, isNewUser: response.isNewUser ?? false)
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
    ///      `pending`. There is no client-side default and no backoff. Every poll asks
    ///      the server to hold it for `waitMillis`, so a `pending` that was held that
    ///      long answers an interval of 0 and is asked again at once. A `pending` answer
    ///      is not a failure; every refusal the contract marks retryable — one today,
    ///      `TooManyRequestsError` — and every lost response are asked again after the
    ///      last interval the server named above zero, and everything else ends the
    ///      wait.
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
        try claimEnrollment()
        defer { enrollmentInFlight = false }

        // The key exists only as this local until the approval is saved. Every throw
        // below therefore destroys it by dropping it, and the install stays unenrolled;
        // the Android counterpart deletes a Keystore alias at the same points.
        let key = makeKey(newKeyID())
        let fingerprint = SPFNDigest.sha256Hex(key.publicKeySpkiDer)

        let started = try await client(signingWith: nil).execute(
            SPFNGeneratedCalls.authDeviceStart,
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
            polling: SPFNGeneratedCalls.authDevicePoll,
            with: SPFNPollDeviceAuthRequest(deviceCode: started.deviceCode, waitMillis: Self.longPollMillis),
            expiresAtMillis: started.expiresAtMillis,
            intervalMillis: try Self.waitMillis(started.intervalMillis),
            expired: .deviceCodeExpired
        )
        return try save(key, approvedBy: approved)
    }

    // MARK: - M9: enrollment by a link code

    /// Enrolls this device by a code a device already signed in shows — the contract's
    /// `deviceLink` flow, from the new device's side. `enrollByDeviceCode` turned round:
    /// there this device shows the code, here it reads one.
    ///
    /// `code` is what a person typed or what `SPFNLinkCode.parse` read off a scan.
    /// Spaces, dashes and case fold away; anything that is not then eight characters of
    /// the device-code alphabet is refused with `malformedLinkCode` before a key is
    /// generated or anything is sent.
    ///
    /// `showMatch` is called exactly once, immediately after `redeem` answers, with the
    /// number this device shows (10–99) and the instant the link expires. The person
    /// picks that number on the signed-in device, so the app shows it large and nothing
    /// else about the link. It is called on the caller's executor, as `showCode` is.
    ///
    /// The rules are `enrollByDeviceCode`'s, one for one: the same in-flight claim, so
    /// no two enrollments of any kind run at once; the `redeem` body carries exactly the
    /// fields `start` does, plus the code; the same wait, long poll and deadline on the
    /// proof clock; every non-approved exit destroys the key, cancellation included; and
    /// an approval is saved exactly as `enroll` saves one.
    ///
    /// What ends the call, and what an app shows for it:
    ///
    ///   - `redeem` refused with `DeviceLinkNotFoundError` (404): the code was never
    ///     issued, or another device already used it — "code not found".
    ///   - `redeem` or a poll refused with `DeviceLinkExpiredError` (400), or
    ///     `linkCodeExpired` from this SDK: the code died — "code expired".
    ///   - a poll refused with `DeviceLinkDeniedError` (403): the signed-in device
    ///     refused, or picked another number — "not approved".
    ///   - a poll refused with `DeviceLinkNotFoundError`: the approval was collected by
    ///     another poll, which ends the wait as `DeviceAuthNotFoundError` ends a
    ///     device-code wait.
    ///
    /// The refusals arrive as `SPFNClientError.server` carrying the generated code. The
    /// code, the device code, the match number and the key are never logged.
    public func enrollByLinkCode(
        code: String,
        deviceName: String? = nil,
        showMatch: @Sendable (_ matchNumber: Int64, _ expiresAtMillis: Int64) -> Void
    ) async throws -> SPFNDeviceCodeEnrollmentResult
    {
        guard let userCode = SPFNLinkCode.normalized(code)
        else
        {
            throw SPFNKeyLifecycleError.malformedLinkCode
        }
        try claimEnrollment()
        defer { enrollmentInFlight = false }

        // As in `enrollByDeviceCode`: the key lives only in this frame until the
        // approval is saved, so every throw below destroys it by dropping it.
        let key = makeKey(newKeyID())
        let fingerprint = SPFNDigest.sha256Hex(key.publicKeySpkiDer)

        let redeemed = try await client(signingWith: nil).execute(
            SPFNGeneratedCalls.authDeviceLinkRedeem,
            request: SPFNRedeemDeviceLinkRequest(
                userCode: userCode,
                publicKey: Data(key.publicKeySpkiDer).base64EncodedString(),
                keyId: key.keyID,
                fingerprint: fingerprint,
                algorithm: Self.algorithmName,
                deviceName: deviceName,
                platform: Self.platform
            )
        )
        guard Self.matchNumbers.contains(redeemed.matchNumber)
        else
        {
            throw SPFNClientError.decoding(.notTheDeclaredResponse, onSuccessStatus: true)
        }
        showMatch(redeemed.matchNumber, redeemed.expiresAtMillis)

        let approved = try await awaitApproval(
            polling: SPFNGeneratedCalls.authDeviceLinkPoll,
            with: SPFNPollDeviceLinkRequest(deviceCode: redeemed.deviceCode, waitMillis: Self.longPollMillis),
            expiresAtMillis: redeemed.expiresAtMillis,
            intervalMillis: try Self.waitMillis(redeemed.intervalMillis),
            expired: .linkCodeExpired
        )
        return try save(key, approvedBy: approved)
    }

    /// The state checks and the in-flight claim every enrollment starts with.
    ///
    /// Synchronous, so a caller runs it before its first `await` and the checks and the
    /// claim are one indivisible step from any other call's point of view — whichever
    /// entry point that call came in by. The caller releases the claim in a `defer`.
    private func claimEnrollment() throws
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
        guard !enrollmentInFlight
        else
        {
            throw SPFNKeyLifecycleError.enrollmentInFlight
        }
        enrollmentInFlight = true
    }

    /// What an approved poll settled, before the key it belongs to is saved.
    private struct SPFNDeviceApproval
    {
        let clientID: String
        let passwordChangeRequired: Bool
    }

    /// Saves an approved key exactly as `enroll` saves one, so a key either device flow
    /// enrolled is a key `rotate` can replace and `activeProvider` can sign with.
    private func save(_ key: SPFNCustodyKey, approvedBy approval: SPFNDeviceApproval) throws -> SPFNDeviceCodeEnrollmentResult
    {
        try store.save(
            key.record(clientID: approval.clientID, createdAtMillis: clock.nowMillis()),
            slot: Self.activeSlot
        )
        return SPFNDeviceCodeEnrollmentResult(
            clientID: approval.clientID,
            keyID: key.keyID,
            passwordChangeRequired: approval.passwordChangeRequired
        )
    }

    /// The wait: sleep the interval, judge the deadline, poll, read the answer. Both
    /// device flows run it; they differ only in the poll they send and in the error a
    /// passed deadline raises.
    ///
    /// The deadline is checked between the sleep and the request rather than after it,
    /// so a code that expired while this device was waiting costs no request at all.
    ///
    /// Two things can be lost inside one iteration and both cost the same interval: the
    /// clock read and the poll. On a fresh install the first iteration's clock read is a
    /// real `core.time` request, and a network that dropped it says exactly as much about
    /// the code as a network that dropped the poll one line below — nothing.
    ///
    /// Two numbers, because a long poll made them differ. `waitMillis` is what the next
    /// iteration sleeps, and a held `pending` sets it to the 0 the server answered.
    /// `intervalMillis` is the last interval the server named above zero, and it is what
    /// a lost answer or a rate limit costs: re-asking those at once would spin a dead
    /// network, or walk straight back into the limit.
    private func awaitApproval<Request>(
        polling call: SPFNCall<Request, SPFNPollDeviceAuthResponse>,
        with request: Request,
        expiresAtMillis: Int64,
        intervalMillis: Int64,
        expired: SPFNKeyLifecycleError
    ) async throws -> SPFNDeviceApproval
    {
        var intervalMillis = intervalMillis
        var waitMillis = intervalMillis
        while true
        {
            if waitMillis > 0
            {
                try await sleeper.sleep(millis: waitMillis)
            }
            waitMillis = intervalMillis

            guard let now = try await clockNow()
            else
            {
                continue
            }
            guard now < expiresAtMillis
            else
            {
                throw expired
            }

            guard let answer = try await pollOnce(call, request: request)
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
                waitMillis = try Self.pendingWaitMillis(answer.intervalMillis)
                intervalMillis = waitMillis > 0 ? waitMillis : intervalMillis
            case .approved:
                guard let clientID = answer.userId, let passwordChangeRequired = answer.passwordChangeRequired
                else
                {
                    throw SPFNClientError.decoding(.notTheDeclaredResponse, onSuccessStatus: true)
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
    ///
    /// The request asks the server to hold it for `longPollMillis`, so its transport
    /// deadline is that hold plus the ordinary one: a deadline at or under the hold would
    /// cut every held poll off as a lost answer.
    private func pollOnce<Request>(
        _ call: SPFNCall<Request, SPFNPollDeviceAuthResponse>,
        request: Request
    ) async throws -> SPFNPollDeviceAuthResponse?
    {
        do
        {
            return try await client(signingWith: nil, timeoutMillis: timeoutMillis + Self.longPollMillis)
                .execute(call, request: request)
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
            throw SPFNClientError.decoding(.notTheDeclaredResponse, onSuccessStatus: true)
        }
        return intervalMillis
    }

    /// The wait a `pending` answer asks for, or a decoding refusal.
    ///
    /// `waitMillis` with 0 admitted. The contract's `pendingRule` takes the time a held
    /// poll already waited off the interval, so after a hold at least that long the
    /// answer is 0 and the next poll goes at once. Absent and negative are still a
    /// server this client does not understand.
    private static func pendingWaitMillis(_ intervalMillis: Int64?) throws -> Int64
    {
        guard let intervalMillis, intervalMillis >= 0
        else
        {
            throw SPFNClientError.decoding(.notTheDeclaredResponse, onSuccessStatus: true)
        }
        return intervalMillis
    }

    // MARK: - M4–M5: rotation

    /// What one failed rotation attempt says about the key the server now honours.
    ///
    /// The whole rotation table is decided by this one type, and `rotate()` and
    /// `resumeRotation()` both read it, so the two paths cannot drift apart into two
    /// tables that disagree — which is what a second catch list written beside the first
    /// always becomes.
    private enum RotationOutcome
    {
        /// The outcome is unknown: the candidate stays persisted and the state answers
        /// `rotationPending`, because the server may already hold the new key.
        case candidateHeld

        /// The rotation was not applied — refused, or never sent at all — so the
        /// candidate is destroyed and the old key stays the one signer.
        case candidateRefused

        /// The old key itself is dead. Every slot goes and the install is unenrolled.
        case keyDead

        /// The old key is no longer registered, which from here is what an earlier
        /// attempt having been applied looks like. Reachable on the resume path only.
        case candidateRegistered
    }

    /// Which row of the rotation table an error falls on.
    ///
    /// Total on purpose. Every error the send can raise lands on a row, including the ones
    /// neither `SPFNClientError` nor `SPFNAuthError` — a clock that would not synchronize,
    /// a key store that would not write. Those fail before any request exists, so the
    /// server cannot have applied anything; an error slipping past instead would leave a
    /// candidate behind and make `state()` answer `rotationPending` for a rotation nobody
    /// ever sent.
    ///
    /// The one asymmetry between the two callers is `PROOF_INVALID`, and it is the point
    /// of having both. Inside `rotate()` the request was sent once and refused, so the
    /// server did not apply it. On a resume the earlier send's outcome is unknown, and a
    /// well-formed old-key proof failing verification means the old key is gone — which is
    /// what a completed rotation looks like from the outside.
    private static func classifyRotationOutcome(_ error: any Error, resuming: Bool) -> RotationOutcome
    {
        guard let failure = error as? SPFNClientError
        else
        {
            // K7 and K8: an error this SDK raised before the request went out — proof
            // assembly, the proof clock, the key store. Nothing was sent.
            return .candidateRefused
        }
        switch failure
        {
        // K2 and K10: no response, cancellation included. The server may or may not
        // have applied it, and a cancelled send is no more settled than a lost one.
        case .transport:
            return .candidateHeld

        // K3: the server answered 2xx and this SDK could not read the answer. It said
        // yes; destroying the candidate here is how an install loses the key the server
        // moved to and stops until a wipe and a re-enrollment.
        case .decoding(_, let onSuccessStatus):
            return onSuccessStatus ? .candidateHeld : .candidateRefused

        case .auth(let refusal) where refusal.code == .sessionRevoked:
            return .keyDead

        case .auth(let refusal) where refusal.code == .proofInvalid && resuming:
            return .candidateRegistered

        // K6: a refusal the server authenticated, decided on, or would not answer at
        // all — and the two refusals this SDK raises without sending anything.
        case .auth, .server, .contract, .unsupportedOperation, .undeclaredAuthClass:
            return .candidateRefused
        }
    }

    /// Applies a classification to the slots.
    ///
    /// `candidateRegistered` is absent from the work rather than from the switch: it is
    /// the one outcome that answers with a value instead of rethrowing, so the resume path
    /// reads it before it gets here.
    private func settle(_ outcome: RotationOutcome) throws
    {
        switch outcome
        {
        case .candidateHeld, .candidateRegistered:
            return
        case .candidateRefused:
            try store.delete(slot: Self.candidateSlot)
        case .keyDead:
            try wipe()
        }
    }

    /// Replaces the active key: a fresh key is generated, persisted as the candidate,
    /// and registered through `auth.keys.rotate` under the old key's proof. Success
    /// swaps the candidate in; a refusal destroys the candidate and keeps the old key,
    /// because a refused request was never applied. A transport failure and a 2xx this
    /// SDK could not read are the two outcomes where the server's state is genuinely
    /// unknown — those leave the machine in `rotationPending` for `resumeRotation()`.
    ///
    /// The send is guarded and the promotion is not, and that is the second half of the
    /// rule. Once a 2xx answer has been read the server has applied the rotation, so
    /// every way the bookkeeping after it can fail — a key id that is not the one sent,
    /// a store that will not write — leaves the candidate exactly where it is.
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
        let response: SPFNRotateKeyResponse
        do
        {
            try store.save(
                candidate.record(clientID: old.clientID, createdAtMillis: clock.nowMillis()),
                slot: Self.candidateSlot
            )
            response = try await send(candidate: candidate, provedBy: old)
        }
        catch
        {
            try settle(Self.classifyRotationOutcome(error, resuming: false))
            throw error
        }
        return try promote(candidate: candidate, clientID: old.clientID, confirmedKeyID: response.keyId)
    }

    /// Resolves a rotation whose outcome was left unknown.
    ///
    /// Re-sends the same candidate under the old key's proof, and reads the answer
    /// through the same table `rotate()` reads — with the one documented asymmetry on
    /// `PROOF_INVALID`, which completes the rotation here rather than discarding it.
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

        let response: SPFNRotateKeyResponse
        do
        {
            response = try await send(candidate: candidate, provedBy: old)
        }
        catch
        {
            let outcome = Self.classifyRotationOutcome(error, resuming: true)
            guard case .candidateRegistered = outcome
            else
            {
                try settle(outcome)
                throw error
            }
            return try promote(candidate: candidate, clientID: clientID, confirmedKeyID: candidate.keyID)
        }
        return try promote(candidate: candidate, clientID: clientID, confirmedKeyID: response.keyId)
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
            SPFNGeneratedCalls.authKeysRotate,
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

    /// One client per call, over one session. For the unproven enrollment the signer is
    /// never consulted — the unproven path touches no session state — so it is handed a
    /// provider that refuses to sign rather than a key.
    private func client(
        signingWith provider: SPFNSecureEnclaveKeyProvider?,
        timeoutMillis: Int64? = nil
    ) throws -> SPFNClient
    {
        let keyProvider: any SPFNKeyProvider = provider ?? UnenrolledKeyProvider()
        return SPFNClient(
            transport: transport,
            session: try SPFNSession(
                transport: transport,
                keyProvider: keyProvider,
                baseURL: baseURL,
                clock: proofClock,
                nonceGenerator: nonceGenerator,
                timeoutMillis: self.timeoutMillis
            ),
            timeoutMillis: timeoutMillis ?? self.timeoutMillis
        )
    }

    /// The signer the unproven path is given: it names nothing and refuses to sign.
    ///
    /// It replaces a throwaway P-256 key that used to be generated per unproven call — on
    /// every enrollment, every `auth.device.start`, and every poll of a device-code wait,
    /// which is one keypair per interval for as long as somebody takes to approve. It was
    /// never consulted, so the cost bought nothing; and had anything ever consulted it,
    /// the request would have gone out signed by a key no server has ever heard of, which
    /// is a refusal nobody could read. Refusing outright is what turns that into a
    /// failure at the line that asked.
    ///
    /// `SpfnKeyLifecycle.UnenrolledKeyProvider` is the same object on the other platform.
    private struct UnenrolledKeyProvider: SPFNKeyProvider
    {
        /// What a signature asked for on the unproven path is: a bug in this file. Its
        /// own type rather than an `SPFNKeyLifecycleError`, which is the vocabulary of
        /// things a caller can do something about.
        enum Refusal: Error, Equatable
        {
            case theUnprovenPathNeverSigns
        }

        let clientID = ""
        let keyID = ""

        func sign(_ message: [UInt8]) throws -> [UInt8]
        {
            throw Refusal.theUnprovenPathNeverSigns
        }
    }

    /// The signature algorithm every key this lifecycle generates is signed with.
    ///
    /// A generated enum case since contract 0.6.0 rather than the string it used to be.
    /// The contract declares the set, so a value outside it is now a compile error here
    /// instead of a refusal the server has to raise.
    private static let algorithmName: SPFNKeyAlgorithm = .es256

    /// How long each device-flow poll asks the server to hold it while nothing has been
    /// decided — the `waitMillis` `auth.device.poll` declares since contract 0.13.1 and
    /// `auth.deviceLink.poll` since it arrived in 0.13.2. The server caps a hold at its
    /// own maximum, 20 seconds by default, so asking for more would buy nothing and only
    /// lengthen the transport deadline built on it.
    static let longPollMillis: Int64 = 20_000

    /// The numbers `redeem` may answer: the contract's `matchRule`. Anything else is a
    /// number the signed-in device will never offer, so showing it would strand the
    /// person in front of a pick that cannot succeed.
    private static let matchNumbers: ClosedRange<Int64> = 10...99

    /// The platform a parked key is registered under, and it is the identity header's
    /// own value rather than a second constant: `x-spfn-client-kind` is what the server
    /// already judges this build by, and two spellings of one fact are two facts as soon
    /// as somebody edits one. Nil would mean this build reports a kind the contract's
    /// `KeyPlatform` set does not name, which is a mismatch the field cannot state.
    private static var platform: SPFNKeyPlatform?
    {
        SPFNKeyPlatform(rawValue: SPFNClientIdentity.kind)
    }

    /// The one descriptor this file still builds by hand.
    ///
    /// Every other operation it sends is a value in `SPFNGeneratedCalls`. This one cannot
    /// be: the contract's path carries a `{provider}` segment, and the descriptor a request
    /// actually rides on has to name the route it goes to. So the generated operation is
    /// the template and the substitution happens here, on a provider id `isProviderID` has
    /// already judged.
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
