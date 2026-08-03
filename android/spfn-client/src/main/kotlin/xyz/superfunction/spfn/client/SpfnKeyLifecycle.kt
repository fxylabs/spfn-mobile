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
// Sources/SPFNClient/SPFNKeyLifecycle.swift is the same machine in Swift.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.superfunction.spfn.auth.SpfnAuthException
import xyz.superfunction.spfn.core.SpfnDigest
import xyz.superfunction.spfn.core.SpfnOperation
import xyz.superfunction.spfn.generated.SpfnGeneratedContract
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnOauthNativeRequest
import xyz.superfunction.spfn.generated.SpfnOauthNativeResponse
import xyz.superfunction.spfn.generated.SpfnRotateKeyRequest
import xyz.superfunction.spfn.generated.SpfnRotateKeyResponse
import java.util.Base64
import java.util.UUID

/** The lifecycle's answer to "what key does this install hold". */
enum class SpfnKeyLifecycleState
{
    /** No usable key: enrollment is required before any proven operation. */
    UNENROLLED,

    /** One active key, ready to sign. */
    ENROLLED,

    /** A rotation was started and its outcome is unknown; call [SpfnKeyLifecycle.resumeRotation]. */
    ROTATION_PENDING
}

/** What enrollment settled: the identity the server issued for the key it registered. */
class SpfnEnrollmentResult(
    /**
     * The key owner's identity — the response's `userId`, which is what every proof's
     * `clientId` must equal from now on (the contract's `clientIdRule`).
     */
    val clientId: String,
    val keyId: String,
    val isNewUser: Boolean
)
{
    override fun equals(other: Any?): Boolean =
        other is SpfnEnrollmentResult &&
            other.clientId == clientId &&
            other.keyId == keyId &&
            other.isNewUser == isNewUser

    override fun hashCode(): Int = (31 * clientId.hashCode() + keyId.hashCode()) * 31 + isNewUser.hashCode()

    override fun toString(): String =
        "SpfnEnrollmentResult(clientId=$clientId, keyId=$keyId, isNewUser=$isNewUser)"
}

/** Everything the lifecycle refuses on its own, before or instead of the network. */
sealed class SpfnKeyLifecycleException(message: String) : IllegalStateException(message)
{
    /**
     * Enrollment was asked for while a key exists. Wipe first — implicitly enrolling
     * over a live key would orphan a registration the server still honours.
     */
    class AlreadyEnrolled : SpfnKeyLifecycleException("a key is already enrolled; wipe before enrolling again")

    /** Rotation or signing was asked for with no active key. */
    class NotEnrolled : SpfnKeyLifecycleException("no active key; enroll first")

    /** A new enrollment or rotation was asked for while a rotation is unresolved. */
    class RotationUnresolved : SpfnKeyLifecycleException("a rotation is unresolved; call resumeRotation first")

    /**
     * The provider id cannot be a path segment. The id is substituted into the
     * operation path before signing, so anything but `[a-z0-9-]` would change the
     * route — or smuggle one — rather than name a provider.
     */
    class MalformedProviderId : SpfnKeyLifecycleException("a provider id is lowercase alphanumerics and hyphens")

    /**
     * The server's answer named a key other than the one this call sent. The message
     * carries this SDK's own identifiers, never server text.
     */
    class ServerNamedAnotherKey(val sent: String, val received: String) :
        SpfnKeyLifecycleException("the server confirmed key '$received' where '$sent' was registered")

    /**
     * A record exists but its key cannot be opened on this device. Re-enrollment is
     * the only way forward.
     */
    class KeyUnloadable : SpfnKeyLifecycleException("a stored key could not be opened on this device")
}

/**
 * Owns the key slots and drives enrollment and rotation over the execute path.
 *
 * A mutex serializes the mutating flows for the same reason the session holds one:
 * `rotate` reads, sends and swaps, and two of those interleaved would be two
 * candidates for one active key.
 *
 * @param newKeyId mints key identifiers; UUIDs by default. Injected so a suite can
 *   pin the wire bytes a flow produces against the fixtures.
 */
class SpfnKeyLifecycle(
    private val transport: SpfnTransport,
    private val store: SpfnKeyMetadataStore,
    private val engine: SpfnKeystoreEngine,
    private val baseUrl: String,
    private val clock: SpfnClock = SpfnSystemClock(),
    private val nonceGenerator: SpfnNonceGenerator = SpfnRandomNonceGenerator(),
    private val timeoutMillis: Long = 15_000,
    private val preferStrongBox: Boolean = true,
    private val newKeyId: () -> String = { UUID.randomUUID().toString().lowercase() }
)
{
    private val mutex = Mutex()

    // ---- observation -------------------------------------------------------

    fun state(): SpfnKeyLifecycleState
    {
        if (store.load(CANDIDATE_SLOT) != null)
        {
            return SpfnKeyLifecycleState.ROTATION_PENDING;
        }
        val active = store.load(ACTIVE_SLOT);
        return if (active?.clientId != null)
        {
            SpfnKeyLifecycleState.ENROLLED
        }
        else
        {
            SpfnKeyLifecycleState.UNENROLLED
        };
    }

    /**
     * The one signer this install holds, or null before enrollment. A rotation
     * candidate is never returned here — it becomes signable by becoming active.
     */
    fun activeProvider(): SpfnKeystoreKeyProvider? = SpfnKeystoreKeyProvider.load(store, ACTIVE_SLOT, engine)

    // ---- M7: the TTL judgment ----------------------------------------------

    /**
     * Milliseconds until the active key reaches `keyPolicy.ttlDays`, negative once it
     * has, or null with no active key. Foreground arithmetic only: nothing here
     * schedules anything, because background execution is outside this SDK's scope.
     */
    fun keyRemainingMillis(): Long?
    {
        val active = store.load(ACTIVE_SLOT) ?: return null;
        if (active.clientId == null)
        {
            return null;
        }
        val ttlMillis = SpfnGeneratedContract.KEY_POLICY_TTL_DAYS * 24 * 60 * 60 * 1_000;
        return active.createdAtMillis + ttlMillis - clock.nowMillis();
    }

    /**
     * True when the active key is inside [leadTimeMillis] of its TTL — the moment a
     * foregrounded app should start a rotation.
     */
    fun rotationDue(leadTimeMillis: Long = 0): Boolean
    {
        val remaining = keyRemainingMillis() ?: return false;
        return remaining <= leadTimeMillis;
    }

    // ---- M1–M3: enrollment -------------------------------------------------

    /**
     * Generates a key and enrolls it through the native social operation.
     *
     * The request body is exact (M1): the public key as SPKI DER base64, the minted
     * keyId, the fingerprint as the SHA-256 of the SPKI DER in lowercase base16, and
     * the literal algorithm name. On success the response's `userId` is persisted as
     * the clientId every future proof carries (M2). On any failure the generated key —
     * a Keystore entry by then — is destroyed, so no orphan outlives the throw (M3).
     */
    suspend fun enroll(provider: String, idToken: String, nonce: String): SpfnEnrollmentResult = mutex.withLock {
        if (!isProviderId(provider))
        {
            throw SpfnKeyLifecycleException.MalformedProviderId();
        }
        when (state())
        {
            SpfnKeyLifecycleState.ENROLLED -> throw SpfnKeyLifecycleException.AlreadyEnrolled()
            SpfnKeyLifecycleState.ROTATION_PENDING -> throw SpfnKeyLifecycleException.RotationUnresolved()
            SpfnKeyLifecycleState.UNENROLLED -> Unit
        }

        val key = SpfnKeystoreCustodyKey.generate(newKeyId(), engine, preferStrongBox);
        val response: SpfnOauthNativeResponse;
        try
        {
            response = client(signer = null).execute(
                oauthNativeCall(provider),
                SpfnOauthNativeRequest(
                    idToken = idToken,
                    nonce = nonce,
                    publicKey = Base64.getEncoder().encodeToString(key.publicKeySpkiDer),
                    keyId = key.keyId,
                    fingerprint = SpfnDigest.sha256Hex(key.publicKeySpkiDer),
                    algorithm = ALGORITHM_NAME
                )
            );
            if (response.keyId != key.keyId)
            {
                throw SpfnKeyLifecycleException.ServerNamedAnotherKey(sent = key.keyId, received = response.keyId);
            }
        }
        catch (failure: Throwable)
        {
            // The Keystore entry already exists, so a failed enrollment must delete
            // it here — the Swift counterpart only has a value to drop at this point.
            key.destroy();
            throw failure;
        }

        store.save(ACTIVE_SLOT, key.metadata(clientId = response.userId, createdAtMillis = clock.nowMillis()));
        SpfnEnrollmentResult(clientId = response.userId, keyId = key.keyId, isNewUser = response.isNewUser);
    }

    // ---- M4–M5: rotation ---------------------------------------------------

    /**
     * Replaces the active key: a fresh key is generated, persisted as the candidate,
     * and registered through `auth.keys.rotate` under the old key's proof. Success
     * swaps the candidate in; a refusal destroys the candidate and keeps the old key,
     * because a refused request was never applied. Only a transport failure leaves
     * the machine in ROTATION_PENDING — the one case where the server's state is
     * genuinely unknown — and [resumeRotation] resolves it.
     */
    suspend fun rotate(): SpfnEnrollmentResult = mutex.withLock {
        when (state())
        {
            SpfnKeyLifecycleState.UNENROLLED -> throw SpfnKeyLifecycleException.NotEnrolled()
            SpfnKeyLifecycleState.ROTATION_PENDING -> throw SpfnKeyLifecycleException.RotationUnresolved()
            SpfnKeyLifecycleState.ENROLLED -> Unit
        }
        val old = activeProvider() ?: throw SpfnKeyLifecycleException.KeyUnloadable();

        val candidate = SpfnKeystoreCustodyKey.generate(newKeyId(), engine, preferStrongBox);
        store.save(CANDIDATE_SLOT, candidate.metadata(clientId = old.clientId, createdAtMillis = clock.nowMillis()));

        try
        {
            val response = send(candidate, old);
            return@withLock promote(candidate, old.clientId, response.keyId);
        }
        catch (failure: SpfnClientError)
        {
            when
            {
                failure is SpfnClientError.Transport ->
                    // No response: the server may or may not have applied it. The
                    // candidate stays persisted and the state answers ROTATION_PENDING.
                    throw failure

                failure is SpfnClientError.Auth &&
                    failure.failure.code == SpfnGeneratedErrorCode.SESSION_REVOKED ->
                {
                    // The old key itself is dead; nothing here can sign anymore (M6).
                    wipeLocked();
                    throw failure;
                }

                else ->
                {
                    // A refusal in the same call that sent the one request: not applied.
                    discardCandidate(candidate);
                    throw failure;
                }
            }
        }
        catch (failure: SpfnAuthException)
        {
            // Proof assembly failed before anything was sent, so the server cannot
            // have applied a request that never existed: the candidate is discarded.
            discardCandidate(candidate);
            throw failure;
        }
    }

    /**
     * Resolves a rotation whose outcome was lost to a transport failure.
     *
     * Re-sends the same candidate under the old key's proof. Success completes the
     * rotation. `PROOF_INVALID` also completes it: this SDK signed a well-formed
     * proof, so the only reading is that the old key is no longer registered — which
     * is what the earlier attempt having been applied looks like. `SESSION_REVOKED`
     * wipes. Any other refusal discards the candidate and keeps the old key.
     */
    suspend fun resumeRotation(): SpfnEnrollmentResult = mutex.withLock {
        val record = store.load(CANDIDATE_SLOT) ?: throw SpfnKeyLifecycleException.NotEnrolled();
        val clientId = record.clientId ?: throw SpfnKeyLifecycleException.KeyUnloadable();
        val candidate = SpfnKeystoreCustodyKey.reload(record, engine)
            ?: throw SpfnKeyLifecycleException.KeyUnloadable();

        // A death between the swap and the candidate cleanup leaves both slots naming
        // one key; the resume is then only the cleanup.
        val active = store.load(ACTIVE_SLOT);
        if (active?.keyId == record.keyId)
        {
            store.delete(CANDIDATE_SLOT);
            return@withLock SpfnEnrollmentResult(clientId = clientId, keyId = record.keyId, isNewUser = false);
        }

        val old = activeProvider() ?: throw SpfnKeyLifecycleException.KeyUnloadable();

        try
        {
            val response = send(candidate, old);
            return@withLock promote(candidate, clientId, response.keyId);
        }
        catch (failure: SpfnClientError)
        {
            when
            {
                failure is SpfnClientError.Transport -> throw failure

                failure is SpfnClientError.Auth &&
                    failure.failure.code == SpfnGeneratedErrorCode.PROOF_INVALID ->
                    return@withLock promote(candidate, clientId, candidate.keyId)

                failure is SpfnClientError.Auth &&
                    failure.failure.code == SpfnGeneratedErrorCode.SESSION_REVOKED ->
                {
                    wipeLocked();
                    throw failure;
                }

                else ->
                {
                    discardCandidate(candidate);
                    throw failure;
                }
            }
        }
    }

    // ---- M6: revocation ----------------------------------------------------

    /**
     * The reaction to `SESSION_REVOKED`: every slot is cleared, and the state answers
     * UNENROLLED — the "re-enrollment required" signal a caller reads.
     */
    suspend fun noteSessionRevoked() = mutex.withLock { wipeLocked() }

    /** Deletes both slots and their Keystore entries. Nothing can sign until a new enrollment. */
    suspend fun wipe() = mutex.withLock { wipeLocked() }

    private fun wipeLocked()
    {
        for (slot in listOf(ACTIVE_SLOT, CANDIDATE_SLOT))
        {
            val record = store.load(slot);
            if (record != null)
            {
                engine.delete(record.alias);
            }
            store.delete(slot);
        }
    }

    // ---- assembly ----------------------------------------------------------

    private fun discardCandidate(candidate: SpfnKeystoreCustodyKey)
    {
        candidate.destroy();
        store.delete(CANDIDATE_SLOT);
    }

    private suspend fun send(candidate: SpfnKeystoreCustodyKey, old: SpfnKeystoreKeyProvider): SpfnRotateKeyResponse =
        client(signer = old).execute(
            rotateCall(),
            SpfnRotateKeyRequest(
                publicKey = Base64.getEncoder().encodeToString(candidate.publicKeySpkiDer),
                keyId = candidate.keyId,
                fingerprint = SpfnDigest.sha256Hex(candidate.publicKeySpkiDer),
                algorithm = ALGORITHM_NAME
            )
        )

    /**
     * Swaps the candidate into the active slot, in the order a death cannot corrupt:
     * active first, candidate cleanup second — the resume path reads that overlap.
     * The replaced key's Keystore entry is deleted last; the server no longer honours
     * it, so keeping it would be a second signer in name only.
     */
    private fun promote(
        candidate: SpfnKeystoreCustodyKey,
        clientId: String,
        confirmedKeyId: String
    ): SpfnEnrollmentResult
    {
        if (confirmedKeyId != candidate.keyId)
        {
            throw SpfnKeyLifecycleException.ServerNamedAnotherKey(sent = candidate.keyId, received = confirmedKeyId);
        }
        val replaced = store.load(ACTIVE_SLOT);
        store.save(ACTIVE_SLOT, candidate.metadata(clientId = clientId, createdAtMillis = clock.nowMillis()));
        store.delete(CANDIDATE_SLOT);
        if (replaced != null && replaced.alias != candidate.alias)
        {
            engine.delete(replaced.alias);
        }
        return SpfnEnrollmentResult(clientId = clientId, keyId = candidate.keyId, isNewUser = false);
    }

    /**
     * One client per call, over one session. For the unproven enrollment the signer
     * is never consulted — the unproven path touches no session state — so the
     * enrollment client carries a placeholder that throws if anything ever asks it
     * to sign, which nothing on that path can.
     */
    private fun client(signer: SpfnKeystoreKeyProvider?): SpfnClient
    {
        val provider: SpfnKeyProvider = signer ?: UnenrolledKeyProvider;
        return SpfnClient(
            transport = transport,
            session = SpfnSession(
                transport = transport,
                keyProvider = provider,
                baseUrl = baseUrl,
                clock = clock,
                nonceGenerator = nonceGenerator,
                timeoutMillis = timeoutMillis
            ),
            timeoutMillis = timeoutMillis
        );
    }

    private object UnenrolledKeyProvider : SpfnKeyProvider
    {
        override val clientId: String = ""

        override val keyId: String = ""

        override fun sign(message: ByteArray): ByteArray =
            throw IllegalStateException("the unproven path never signs; nothing may ask this provider to");
    }

    private fun rotateCall(): SpfnCall<SpfnRotateKeyRequest, SpfnRotateKeyResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.authKeysRotate,
        encode = { it.canonicalValue() },
        decode = { SpfnRotateKeyResponse.decode(it) }
    )

    private fun oauthNativeCall(provider: String): SpfnCall<SpfnOauthNativeRequest, SpfnOauthNativeResponse>
    {
        val template = SpfnGeneratedOperations.authEnrollOauthNative;
        return SpfnCall(
            operation = SpfnOperation(
                id = template.id,
                method = template.method,
                path = template.path.replace("{provider}", provider),
                authProfile = template.authProfile,
                requiresSession = template.requiresSession
            ),
            encode = { it.canonicalValue() },
            decode = { SpfnOauthNativeResponse.decode(it) }
        );
    }

    companion object
    {
        /** The slot names this lifecycle owns inside the injected store. */
        const val ACTIVE_SLOT: String = "active"
        const val CANDIDATE_SLOT: String = "rotation-candidate"

        private const val ALGORITHM_NAME = "ES256"

        /**
         * The set the validator's own path exemption names: lowercase alphanumerics
         * and hyphens, non-empty. Everything else would rewrite the route it rides in.
         * ASCII-explicit ranges, not character classes, so the two platforms cannot
         * disagree over non-ASCII digits (the P9 lesson).
         */
        fun isProviderId(provider: String): Boolean =
            provider.isNotEmpty() && provider.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
    }
}
