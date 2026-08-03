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
    private let nonceGenerator: any SPFNNonceGenerator
    private let timeoutMillis: Int64
    private let newKeyID: @Sendable () -> String
    private let makeKey: @Sendable (String) -> SPFNCustodyKey

    /// - Parameters:
    ///   - newKeyID: mints key identifiers; UUIDs by default. Injected so a suite can
    ///     pin the wire bytes a flow produces against the fixtures.
    ///   - makeKey: generates custody keys; the platform decision by default.
    public init(
        transport: any SPFNTransport,
        store: any SPFNKeyStore,
        baseURL: String,
        clock: any SPFNClock = SPFNSystemClock(),
        nonceGenerator: any SPFNNonceGenerator = SPFNRandomNonceGenerator(),
        timeoutMillis: Int64 = 15_000,
        newKeyID: @escaping @Sendable () -> String = { UUID().uuidString.lowercased() },
        makeKey: @escaping @Sendable (String) -> SPFNCustodyKey = { SPFNCustodyKey.generate(keyID: $0) }
    )
    {
        self.transport = transport
        self.store = store
        self.baseURL = baseURL
        self.clock = clock
        self.nonceGenerator = nonceGenerator
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

    /// Generates a key and enrolls it through the native social operation.
    ///
    /// The request body is exact (M1): the public key as SPKI DER base64, the minted
    /// keyId, the fingerprint as the SHA-256 of the SPKI DER in lowercase base16, and
    /// the literal algorithm name. On success the response's `userId` is persisted as
    /// the clientID every future proof carries (M2). On any failure the generated key
    /// is destroyed — nothing was persisted, so no orphan outlives the throw (M3).
    public func enroll(provider: String, idToken: String, nonce: String) async throws -> SPFNEnrollmentResult
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

        // On any failure from here to the save, the key was never persisted, so
        // dropping the value destroys it and no orphan outlives the throw. The Android
        // counterpart has a keystore entry to delete at the same point; the two files
        // tell one story with different amounts of work.
        let key = makeKey(newKeyID())
        let response = try await client(signingWith: nil).execute(
            Self.oauthNativeCall(provider: provider),
            request: SPFNOauthNativeRequest(
                idToken: idToken,
                nonce: nonce,
                publicKey: Data(key.publicKeySpkiDer).base64EncodedString(),
                keyId: key.keyID,
                fingerprint: SPFNDigest.sha256Hex(key.publicKeySpkiDer),
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
                clock: clock,
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

    private static let algorithmName = "ES256"

    private static var rotateCall: SPFNCall<SPFNRotateKeyRequest, SPFNRotateKeyResponse>
    {
        SPFNCall(
            operation: SPFNGeneratedOperations.authKeysRotate,
            encode: { $0.canonicalValue },
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
                requiresSession: template.requiresSession
            ),
            encode: { $0.canonicalValue },
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
