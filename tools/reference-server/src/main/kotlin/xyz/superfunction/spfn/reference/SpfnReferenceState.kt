// SPFN Mobile — everything the reference server remembers between requests.
//
// One lock guards all of it. The server runs its handlers on a thread pool so a held
// request cannot stall the others, which means two requests really can present the same
// nonce at the same moment: the whole admission sequence — revocation, expiry, replay,
// proof — happens inside one critical section, so "a nonce is accepted at most once" is a
// property of the code rather than of the timing.
//
// The check order is the contract's, not this file's invention. The bundle's
// `clientProofV1.revocationRule` fixes revocation before proof verification so a revoked
// key stays distinguishable from a bad proof, and `SpfnProofAcceptance` in spfn-auth is
// the SDK-side statement of the same order. SpfnReferenceCheckOrderTest runs the two
// against the same inputs and fails if they ever disagree.

package xyz.superfunction.spfn.reference

import xyz.superfunction.spfn.auth.SpfnAuthException
import xyz.superfunction.spfn.auth.SpfnClientProof
import xyz.superfunction.spfn.auth.SpfnEcdsa
import xyz.superfunction.spfn.auth.SpfnProofInput
import xyz.superfunction.spfn.core.SpfnDigest
import xyz.superfunction.spfn.generated.SpfnKeyPlatform
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The keypair this server pre-registers, and the tests sign with.
 *
 * TEST KEYPAIR ONLY — NOT A SECRET. Restated byte for byte from SPFN primitives
 * packages/auth/src/server/client-proof/__tests__/test-keys.ts, which is also what
 * `Contracts/fixtures/proof/proof-input.json` pins as `testKeyPair` and what the
 * primitives dev server pre-registers — so a proof this server accepts is a proof the
 * fixtures describe and the upstream dev surface accepts too. Publishing the private
 * half is intentional: it authenticates nothing, was never issued by anything, and
 * must never be presented to a real endpoint.
 */
object SpfnReferenceTestKeys
{
    const val CLIENT_ID: String = "client-test-0001"

    const val KEY_ID: String = "key-test-0001"

    /** The public half in the contract's representation: SPKI DER, base64. */
    const val PUBLIC_KEY_SPKI_B64: String =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAES7xktjK+fMydT7UZcfuW/vzU9rU/" +
            "+RPVVQKKgxrB1sd9bh6N1bqiBwU/zuw9/LaQ91lWPeWSN9OlT8OlDYXIYg=="

    /** PKCS#8 DER, base64. TEST ONLY — deliberately published, not a secret. */
    const val PRIVATE_KEY_PKCS8_B64: String =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgMv3D4UvmGKjFeG3m" +
            "yLLfwlcOAQ9n8qoFmwrgGWBErsShRANCAARLvGS2Mr58zJ1PtRlx+5b+/NT2tT/5" +
            "E9VVAoqDGsHWx31uHo3VuqIHBT/O7D38tpD3WVY95ZI306VPw6UNhchi"

    val PUBLIC_KEY_SPKI_DER: ByteArray
        get() = Base64.getDecoder().decode(PUBLIC_KEY_SPKI_B64)

    /** The registration every launch starts from: the test keypair's public half. */
    val DIRECTORY: Map<String, ByteArray>
        get() = mapOf(KEY_ID to PUBLIC_KEY_SPKI_DER)

    /**
     * A proof over [input], signed with the test private key.
     *
     * The signer the server's own tests present proofs with. It lives beside the
     * keypair rather than in every test file so the DER→raw conversion happens in
     * exactly one place.
     */
    fun proofFor(input: SpfnProofInput): String = SpfnClientProof.proof(input) { message ->
        val signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(
            KeyFactory.getInstance("EC")
                .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(PRIVATE_KEY_PKCS8_B64)))
        );
        signer.update(message);
        SpfnEcdsa.derToRaw(signer.sign());
    }
}

/** What `/control/stats` reports. Counters only; nothing a request carried. */
class SpfnReferenceStats(
    val requestCount: Long,
    val handshakeCount: Long,
    val echoCount: Long,
    val itemsListCount: Long,
    val refusalCount: Long,
    val liveSessionCount: Long,
    val spentNonceCount: Long
)

/** One session the server issued. */
private class SpfnReferenceSession(
    val clientId: String,
    val keyId: String,
    val expiresAtMillis: Long
)

/** A configured delay for the next requests to one path. */
private class SpfnReferenceHold(val millis: Long, var remaining: Int)

/**
 * Where one device authorization is in the flow.
 *
 * `expired` is not a member: expiry is the clock's answer about a record, not a state a
 * record is moved into, exactly as upstream judges it. A record that passed its TTL is
 * refused whichever of these four it is sitting in.
 */
enum class SpfnDeviceAuthStatus
{
    PENDING,
    APPROVED,
    DENIED,
    CONSUMED
}

/** One parked device authorization. The device code is held only as its hash. */
private class SpfnReferenceDeviceAuthorization(
    val userCode: String,
    val publicKeySpkiDer: ByteArray,
    val keyId: String,
    val fingerprint: String,
    val deviceName: String?,
    val platform: SpfnKeyPlatform?,
    val requestedAtMillis: Long,
    val expiresAtMillis: Long,
    var status: SpfnDeviceAuthStatus,
    /** The approver, taken from the admitted proof. Set by approve and by nothing else. */
    var ownerId: String?
)

/** The codes a `start` handed out, and the two numbers the waiting device obeys. */
class SpfnStartedDeviceAuth(
    val deviceCode: String,
    val userCode: String,
    val expiresAtMillis: Long,
    val intervalMillis: Long
)
{
    /** The device code is the waiting device's only credential; nothing prints it. */
    override fun toString(): String = "SpfnStartedDeviceAuth(userCode=$userCode)"
}

/** What the approver is shown about the device asking to be let in. */
class SpfnDeviceAuthDescription(
    val deviceName: String?,
    val platform: SpfnKeyPlatform?,
    val fingerprintPrefix: String,
    val requestedAtMillis: Long,
    val expiresAtMillis: Long
)

/**
 * Every answer the four device operations can produce.
 *
 * One type for all four because the state table is one table: the row a record is in
 * decides the answer, and the operation only decides which of these shapes that answer
 * takes. A refusal is the same value whichever operation asked.
 */
sealed interface SpfnDeviceAuthOutcome
{
    class Refused(val refusal: SpfnReferenceRestRefusal) : SpfnDeviceAuthOutcome

    /** What `info` answers, and what `approve` answers with once it has bound the record. */
    class Described(val description: SpfnDeviceAuthDescription) : SpfnDeviceAuthOutcome

    /** `deny` applied. There is nothing to answer with, which is why it declares no response. */
    object Recorded : SpfnDeviceAuthOutcome

    class Pending(val intervalMillis: Long) : SpfnDeviceAuthOutcome

    /** The poll that spent the record: the parked key is registered and this is the login. */
    class Approved(val userId: String, val publicId: String) : SpfnDeviceAuthOutcome
}

class SpfnReferenceState(
    private val clock: SpfnReferenceClock,
    sessionTtlMillis: Long = DEFAULT_SESSION_TTL_MILLIS,
    publicKeys: Map<String, ByteArray> = SpfnReferenceTestKeys.DIRECTORY,
    /** The contract's `clientProofV1.replayWindowMillis`. */
    val replayWindowMillis: Long = DEFAULT_REPLAY_WINDOW_MILLIS
)
{
    private val lock = Any()
    private val random = SecureRandom()

    /**
     * One registered key: the parsed public half and, when the REST surface enrolled
     * it, the owner it belongs to.
     *
     * `ownerId` is the contract's `clientIdRule` made concrete: a proof whose clientId
     * is not the key's owner is refused at the proof step with the same PROOF_INVALID
     * an unregistered keyId or a failed signature answers, so the refusal leaks
     * nothing about the key's existence or owner (the G9 mirror). A key registered
     * through `/control/register-key` has no owner — that surface mirrors the
     * primitives dev server, which has no userId concept — and is exempt.
     */
    class RegisteredKey(val publicKey: PublicKey, val ownerId: String?)

    /**
     * The registered public keys, parsed once at registration. `keyId` names one of
     * these; a keyId with no entry lands in the proof check as PROOF_INVALID, never in
     * revocation — it was never issued, so it was never revoked, and disclosing the
     * difference would say which keyIds exist.
     */
    private val registeredKeys = LinkedHashMap<String, RegisteredKey>()

    /**
     * What [reset] restores: the registrations this server was constructed with. The
     * pre-registered test key is owned by the test client id, so the G9 rule holds for
     * it exactly as it does for an enrolled key.
     */
    private val constructedKeys: Map<String, RegisteredKey> = publicKeys.mapValues { (_, spkiDer) ->
        RegisteredKey(SpfnEcdsa.publicKeyFromSpki(spkiDer), SpfnReferenceTestKeys.CLIENT_ID)
    }

    /** Every userId the REST surface has enrolled a key for, driving `isNewUser`. */
    private val knownUserIds = LinkedHashSet<String>()

    private val sessions = LinkedHashMap<String, SpfnReferenceSession>()

    /** The replay ledger: `replayKeyOf` to the `issuedAtMillis` it was spent at. */
    private val spentNonces = LinkedHashMap<String, Long>()

    private val revokedKeyIds = LinkedHashSet<String>()
    private val holds = LinkedHashMap<String, SpfnReferenceHold>()

    /**
     * The device authorizations, keyed by the SHA-256 of the device code.
     *
     * The hash rather than the code, as upstream stores it: the device code is the
     * waiting device's only credential, and a fixture that kept it in the clear would be
     * teaching the wrong shape to whoever reads this server to learn the flow. The user
     * code is held in the record because it authorizes nothing on its own — only an
     * already admitted caller can act on one — and is looked up by scanning, which is
     * what a table this size is for.
     */
    private val deviceAuthorizations = LinkedHashMap<String, SpfnReferenceDeviceAuthorization>()

    init
    {
        registeredKeys.putAll(constructedKeys);
    }

    private var ttlMillis: Long = sessionTtlMillis
    private var requestCount: Long = 0
    private var handshakeCount: Long = 0
    private var echoCount: Long = 0
    private var itemsListCount: Long = 0
    private var refusalCount: Long = 0

    // ---- admission ---------------------------------------------------------

    /**
     * Runs the contract's checks in the contract's order and returns the refusal, or
     * null when the request is admitted.
     *
     * A nonce is recorded as spent only on admission, matching `SpfnProofAcceptance`: a
     * request refused for any earlier reason has not spent anything, so a client that
     * fixes the reason and retries with the same nonce is not punished twice for one
     * mistake.
     */
    fun admit(
        clientId: String,
        keyId: String,
        presentedSessionId: String?,
        requiresSession: Boolean,
        proofInput: SpfnProofInput,
        presentedProof: String
    ): SpfnReferenceRefusal? = synchronized(lock)
    {
        val now = clock.nowMillis();
        prune(now);

        // 1. Revocation, before anything the proof could explain. A revoked key and a
        //    dropped session are the same answer on purpose: both are cleared by opening
        //    a new session, and neither is a statement about the proof.
        if (revokedKeyIds.contains(keyId))
        {
            return@synchronized SpfnReferenceRefusal.sessionRevoked();
        }
        if (requiresSession)
        {
            val session = presentedSessionId?.let { sessions[it] };
            if (session == null || session.expiresAtMillis <= now ||
                session.keyId != keyId || session.clientId != clientId
            )
            {
                return@synchronized SpfnReferenceRefusal.sessionRevoked();
            }
        }

        // 2. The replay window, judged against this server's clock.
        val age = now - proofInput.issuedAtMillis;
        if (age < 0 || age > replayWindowMillis)
        {
            return@synchronized SpfnReferenceRefusal.proofExpired();
        }

        // 3. One acceptance per (clientId, nonce) inside that window.
        val replayKey = replayKeyOf(clientId, proofInput.nonce);
        if (spentNonces.containsKey(replayKey))
        {
            return@synchronized SpfnReferenceRefusal.proofReplayed();
        }

        // 4. The proof itself, last, so the three answers above stay distinguishable.
        //    An unrecognised keyId lands here rather than in step 1: it was never issued,
        //    so it was never revoked, and there is nothing for a new session to fix.
        //    The ownership rule shares this step and this answer on purpose (G9): a
        //    clientId that is not the key's owner, an unregistered keyId and a failed
        //    signature are one indistinguishable PROOF_INVALID.
        val registered = registeredKeys[keyId] ?: return@synchronized SpfnReferenceRefusal.proofInvalid();
        if (registered.ownerId != null && registered.ownerId != clientId)
        {
            return@synchronized SpfnReferenceRefusal.proofInvalid();
        }
        try
        {
            SpfnClientProof.verify(presentedProof, proofInput, registered.publicKey);
        }
        catch (_: SpfnAuthException)
        {
            return@synchronized SpfnReferenceRefusal.proofInvalid();
        }

        spentNonces[replayKey] = proofInput.issuedAtMillis;
        null;
    }

    // ---- keys --------------------------------------------------------------

    /**
     * Registers a public key (SPKI DER) under [keyId], replacing any earlier one.
     *
     * Throws [IllegalArgumentException] when the bytes are not a parseable EC public
     * key, so the control surface can answer a bad registration as a bad request
     * rather than storing something every later proof would fail against.
     */
    fun registerPublicKey(keyId: String, publicKeySpkiDer: ByteArray)
    {
        val parsed = parseSpki(publicKeySpkiDer);
        // Ownerless: the control surface mirrors the primitives dev server, which has
        // no userId concept, so the ownership rule does not bind these keys.
        synchronized(lock) { registeredKeys[keyId] = RegisteredKey(parsed, ownerId = null) };
    }

    /**
     * The REST enrollment: registers [keyId] under [ownerId] and answers whether this
     * owner was seen before. Refuses a keyId that already exists — an enrollment that
     * silently replaced a live key would be a rotation nobody proved.
     */
    fun enrollKey(keyId: String, publicKeySpkiDer: ByteArray, ownerId: String): Boolean
    {
        val parsed = parseSpki(publicKeySpkiDer);
        synchronized(lock)
        {
            require(!registeredKeys.containsKey(keyId)) { "keyId is already registered" };
            registeredKeys[keyId] = RegisteredKey(parsed, ownerId);
            val isNewUser = !knownUserIds.contains(ownerId);
            knownUserIds.add(ownerId);
            return isNewUser;
        }
    }

    /**
     * The proven rotation: replaces [oldKeyId] with [newKeyId] under the same owner.
     * The caller has already admitted a proof by the old key. Sessions the old key
     * opened are dropped with it, so only the new key can prove anything afterwards.
     *
     * The replaced key enters the revocation ledger rather than merely vanishing.
     * The real server records rotation as a revocation of the old key, so a proof by
     * it is refused at the revocation step — SESSION_REVOKED, never PROOF_INVALID,
     * exactly as `clientProofV1.revocationRule` states for any revoked keyId. The
     * first real-server run against `@spfn/auth@0.2.0-beta.91` caught this state
     * answering PROOF_INVALID instead, which is the self-verification gap that run
     * exists to close.
     *
     * A false return means the request was not one this state can apply — the old key
     * vanished between admission and here, the new keyId already exists, or the two
     * are the same — and the caller answers with a shape refusal.
     */
    fun rotateKey(oldKeyId: String, newKeyId: String, publicKeySpkiDer: ByteArray): Boolean
    {
        val parsed = parseSpki(publicKeySpkiDer);
        synchronized(lock)
        {
            val old = registeredKeys[oldKeyId] ?: return false;
            if (newKeyId == oldKeyId || registeredKeys.containsKey(newKeyId))
            {
                return false;
            }
            registeredKeys.remove(oldKeyId);
            revokedKeyIds.add(oldKeyId);
            registeredKeys[newKeyId] = RegisteredKey(parsed, old.ownerId);
            sessions.entries.removeIf { it.value.keyId == oldKeyId };
            return true;
        }
    }

    private fun parseSpki(publicKeySpkiDer: ByteArray): PublicKey = try
    {
        SpfnEcdsa.publicKeyFromSpki(publicKeySpkiDer)
    }
    catch (failure: Exception)
    {
        throw IllegalArgumentException("not an SPKI DER public key", failure);
    }

    // ---- device authorization ----------------------------------------------

    // The table, restated from the upstream service's own header comment
    // (spfn packages/auth/src/server/services/device-auth.service.ts at 77fe6246):
    //
    //   | state ↓ op → | info           | approve        | deny           | poll                          |
    //   | pending      | device details | → approved     | → denied       | pending                       |
    //   | approved     | AlreadyHandled | AlreadyHandled | AlreadyHandled | key registered, → consumed    |
    //   | denied       | AlreadyHandled | AlreadyHandled | AlreadyHandled | Denied                        |
    //   | consumed     | NotFound       | NotFound       | NotFound       | NotFound                      |
    //   | expired      | Expired        | Expired        | Expired        | Expired                       |
    //   | unknown      | NotFound       | NotFound       | NotFound       | NotFound                      |
    //
    // Two rules decide the last three rows and they are ordered, not independent. A spent
    // record answers as unknown, and it has to keep answering that way after its TTL runs
    // out — which it always eventually does — so `consumed` is judged before the clock.
    // Everything else is judged by the clock next, which is why an approved record nobody
    // collected in time is expired rather than approved and registers nothing.
    //
    // Every transition below happens inside the one lock this file owns, so two approvals
    // arriving together cannot both move a record out of `pending`: the loser reads the
    // row the winner left and is told AlreadyHandled, which is the table's own answer.

    /**
     * Parks a device's public key and hands back the codes it shows and polls with.
     *
     * Nothing is attributed to an account here — the caller is unauthenticated by
     * definition, since obtaining a key is what the flow is for. The record gains an
     * owner only when somebody approves it.
     */
    fun startDeviceAuth(
        publicKeySpkiDer: ByteArray,
        keyId: String,
        fingerprint: String,
        deviceName: String?,
        platform: SpfnKeyPlatform?
    ): SpfnStartedDeviceAuth = synchronized(lock)
    {
        val now = clock.nowMillis();
        val expiresAtMillis = now + DEVICE_AUTH_TTL_MILLIS;

        for (attempt in 0 until USER_CODE_ATTEMPTS)
        {
            val deviceCode = randomHex(DEVICE_CODE_BYTES);
            val userCode = newUserCode();
            val deviceCodeHash = hashDeviceCode(deviceCode);
            val taken = deviceAuthorizations.containsKey(deviceCodeHash) ||
                deviceAuthorizations.values.any { it.userCode == userCode };
            if (taken)
            {
                continue;
            }
            deviceAuthorizations[deviceCodeHash] = SpfnReferenceDeviceAuthorization(
                userCode = userCode,
                publicKeySpkiDer = publicKeySpkiDer.copyOf(),
                keyId = keyId,
                fingerprint = fingerprint,
                deviceName = deviceName,
                platform = platform,
                requestedAtMillis = now,
                expiresAtMillis = expiresAtMillis,
                status = SpfnDeviceAuthStatus.PENDING,
                ownerId = null
            );
            return@synchronized SpfnStartedDeviceAuth(
                deviceCode = deviceCode,
                userCode = formatUserCode(userCode),
                expiresAtMillis = expiresAtMillis,
                intervalMillis = DEVICE_AUTH_INTERVAL_MILLIS
            );
        }
        // Two live rows out of 31^8 landing on one code three times running is not
        // coincidence, it is the generator or this table not being what this code thinks.
        throw IllegalStateException("could not allocate a unique user code in $USER_CODE_ATTEMPTS attempts");
    }

    /** The `info` column: the waiting device described, for a pending record only. */
    fun deviceAuthInfo(userCode: String): SpfnDeviceAuthOutcome = synchronized(lock)
    {
        val record = actionableByUserCode(userCode)
            ?: return@synchronized notFoundOrExpired(byUserCode(userCode));
        if (record.status != SpfnDeviceAuthStatus.PENDING)
        {
            return@synchronized SpfnDeviceAuthOutcome.Refused(SpfnReferenceRestRefusal.deviceAuthAlreadyHandled());
        }
        SpfnDeviceAuthOutcome.Described(describe(record));
    }

    /**
     * The `approve` column: binds the record to [approverId] and answers the same
     * description `info` gives.
     *
     * The key is not registered here. The waiting device may never come back, and a key
     * registered for a device that stopped listening is a signing credential nobody asked
     * for — so approval records the decision and the poll acts on it.
     */
    fun approveDeviceAuth(userCode: String, approverId: String): SpfnDeviceAuthOutcome = synchronized(lock)
    {
        val record = actionableByUserCode(userCode)
            ?: return@synchronized notFoundOrExpired(byUserCode(userCode));
        if (record.status != SpfnDeviceAuthStatus.PENDING)
        {
            return@synchronized SpfnDeviceAuthOutcome.Refused(SpfnReferenceRestRefusal.deviceAuthAlreadyHandled());
        }
        record.status = SpfnDeviceAuthStatus.APPROVED;
        record.ownerId = approverId;
        SpfnDeviceAuthOutcome.Described(describe(record));
    }

    /**
     * The `deny` column. Denying binds nobody: the point of refusing is that the account
     * owner wants nothing to do with the request, so the refusal records no approver.
     */
    fun denyDeviceAuth(userCode: String): SpfnDeviceAuthOutcome = synchronized(lock)
    {
        val record = actionableByUserCode(userCode)
            ?: return@synchronized notFoundOrExpired(byUserCode(userCode));
        if (record.status != SpfnDeviceAuthStatus.PENDING)
        {
            return@synchronized SpfnDeviceAuthOutcome.Refused(SpfnReferenceRestRefusal.deviceAuthAlreadyHandled());
        }
        record.status = SpfnDeviceAuthStatus.DENIED;
        SpfnDeviceAuthOutcome.Recorded;
    }

    /**
     * The `poll` column: the waiting device asking whether anyone has answered.
     *
     * Approved is the one branch with a side effect and it is a one-shot — the record is
     * marked consumed in the same critical section that registers the key, so of two
     * polls arriving together exactly one registers it and the loser is answered as if
     * the code were unknown, which by then it is.
     */
    fun pollDeviceAuth(deviceCode: String): SpfnDeviceAuthOutcome = synchronized(lock)
    {
        val hash = hashDeviceCode(deviceCode);
        val record = actionable(deviceAuthorizations[hash])
            ?: return@synchronized notFoundOrExpired(deviceAuthorizations[hash]);

        when (record.status)
        {
            SpfnDeviceAuthStatus.DENIED ->
                return@synchronized SpfnDeviceAuthOutcome.Refused(SpfnReferenceRestRefusal.deviceAuthDenied())
            SpfnDeviceAuthStatus.PENDING ->
                return@synchronized SpfnDeviceAuthOutcome.Pending(DEVICE_AUTH_INTERVAL_MILLIS)
            else -> Unit
        }

        val ownerId = record.ownerId
            ?: return@synchronized SpfnDeviceAuthOutcome.Refused(SpfnReferenceRestRefusal.deviceAuthNotFound());
        record.status = SpfnDeviceAuthStatus.CONSUMED;
        try
        {
            enrollKey(record.keyId, record.publicKeySpkiDer, ownerId);
        }
        catch (_: IllegalArgumentException)
        {
            return@synchronized SpfnDeviceAuthOutcome.Refused(SpfnReferenceRestRefusal.keyIdAlreadyRegistered());
        }
        SpfnDeviceAuthOutcome.Approved(userId = ownerId, publicId = publicIdOf(ownerId));
    }

    /** The record this user code names, or null when there is none this operation can act on. */
    private fun actionableByUserCode(userCode: String): SpfnReferenceDeviceAuthorization? =
        actionable(byUserCode(userCode))

    private fun byUserCode(userCode: String): SpfnReferenceDeviceAuthorization?
    {
        val normalised = normalizeUserCode(userCode);
        return deviceAuthorizations.values.firstOrNull { it.userCode == normalised };
    }

    /**
     * The record, or null when it is one no operation can act on. Caller holds [lock].
     *
     * The two checks are in this order deliberately: a spent record answers as unknown
     * and has to keep answering that way once its TTL runs out too, and testing expiry
     * first would let a consumed code say "expired" while a code that was never issued
     * says "not found" — the enumeration oracle these two errors exist to close.
     */
    private fun actionable(record: SpfnReferenceDeviceAuthorization?): SpfnReferenceDeviceAuthorization?
    {
        if (record == null || record.status == SpfnDeviceAuthStatus.CONSUMED)
        {
            return null;
        }
        return if (record.expiresAtMillis < clock.nowMillis()) null else record;
    }

    /** Which of the two refusals a record [actionable] rejected is owed. */
    private fun notFoundOrExpired(record: SpfnReferenceDeviceAuthorization?): SpfnDeviceAuthOutcome
    {
        if (record == null || record.status == SpfnDeviceAuthStatus.CONSUMED)
        {
            return SpfnDeviceAuthOutcome.Refused(SpfnReferenceRestRefusal.deviceAuthNotFound());
        }
        return SpfnDeviceAuthOutcome.Refused(SpfnReferenceRestRefusal.deviceAuthExpired());
    }

    private fun describe(record: SpfnReferenceDeviceAuthorization): SpfnDeviceAuthDescription =
        SpfnDeviceAuthDescription(
            deviceName = record.deviceName,
            platform = record.platform,
            fingerprintPrefix = record.fingerprint.take(KEY_FINGERPRINT_PREFIX_LENGTH),
            requestedAtMillis = record.requestedAtMillis,
            expiresAtMillis = record.expiresAtMillis
        )

    private fun newUserCode(): String
    {
        val code = StringBuilder(USER_CODE_LENGTH);
        for (position in 0 until USER_CODE_LENGTH)
        {
            code.append(USER_CODE_ALPHABET[random.nextInt(USER_CODE_ALPHABET.length)]);
        }
        return code.toString();
    }

    // ---- sessions ----------------------------------------------------------

    /** Opens a session and returns its identifier and the expiry the server advertises. */
    fun openSession(clientId: String, keyId: String): Pair<String, Long> = synchronized(lock)
    {
        val now = clock.nowMillis();
        prune(now);

        val sessionId = randomHex(SESSION_ID_BYTES);
        val expiresAtMillis = now + ttlMillis;
        sessions[sessionId] = SpfnReferenceSession(clientId, keyId, expiresAtMillis);
        sessionId to expiresAtMillis;
    }

    /**
     * Drops every session the server holds, as a restart would.
     *
     * The expiry each client was told stays what it was told, which is the point: a client
     * that still believes its session is alive presents it and is refused, so the refusal
     * path is reached without waiting for any wall clock.
     */
    fun expireSessions()
    {
        synchronized(lock) { sessions.clear() };
    }

    /** Revokes a key and drops the sessions it opened. */
    fun revokeKey(keyId: String)
    {
        synchronized(lock)
        {
            revokedKeyIds.add(keyId);
            sessions.entries.removeIf { it.value.keyId == keyId };
        }
    }

    fun sessionTtlMillis(millis: Long)
    {
        synchronized(lock) { ttlMillis = millis };
    }

    /** Returns the server to the state it started in, counters included. */
    fun reset()
    {
        synchronized(lock)
        {
            sessions.clear();
            spentNonces.clear();
            revokedKeyIds.clear();
            holds.clear();
            knownUserIds.clear();
            deviceAuthorizations.clear();
            registeredKeys.clear();
            registeredKeys.putAll(constructedKeys);
            ttlMillis = DEFAULT_SESSION_TTL_MILLIS;
            requestCount = 0;
            handshakeCount = 0;
            echoCount = 0;
            itemsListCount = 0;
            refusalCount = 0;
        }
    }

    // ---- delays ------------------------------------------------------------

    /** Makes the next [count] requests to [path] wait [millis] before being processed. */
    fun holdPath(path: String, millis: Long, count: Int)
    {
        synchronized(lock) { holds[path] = SpfnReferenceHold(millis, count) };
    }

    /**
     * Consumes one configured delay for [path] and returns how long to wait, or zero.
     *
     * The waiting happens in the caller, outside this lock. A handler that slept while
     * holding it would stall every other request, which is the opposite of what a
     * timeout test is trying to observe.
     */
    fun takeHoldMillis(path: String): Long = synchronized(lock)
    {
        val hold = holds[path] ?: return@synchronized 0L;
        hold.remaining -= 1;
        if (hold.remaining <= 0)
        {
            holds.remove(path);
        }
        hold.millis;
    }

    // ---- counters ----------------------------------------------------------

    fun recordRequest()
    {
        synchronized(lock) { requestCount += 1 };
    }

    fun recordOperation(operationId: String)
    {
        synchronized(lock)
        {
            when (operationId)
            {
                "auth.clientProof.handshake" -> handshakeCount += 1
                "echo.send" -> echoCount += 1
                "items.list" -> itemsListCount += 1
                else -> Unit
            }
        }
    }

    fun recordRefusal()
    {
        synchronized(lock) { refusalCount += 1 };
    }

    fun stats(): SpfnReferenceStats = synchronized(lock)
    {
        prune(clock.nowMillis());
        SpfnReferenceStats(
            requestCount = requestCount,
            handshakeCount = handshakeCount,
            echoCount = echoCount,
            itemsListCount = itemsListCount,
            refusalCount = refusalCount,
            liveSessionCount = sessions.size.toLong(),
            spentNonceCount = spentNonces.size.toLong()
        );
    }

    // ---- housekeeping ------------------------------------------------------

    /**
     * Drops what can no longer affect an answer. Caller holds [lock].
     *
     * The nonce predicate is the exact negation of the window check in [admit]: an entry
     * is dropped only once a proof carrying that `issuedAtMillis` would be refused as
     * expired anyway. Dropping one moment earlier would let a nonce inside the window be
     * spent twice, which is the failure this ledger exists to prevent.
     */
    private fun prune(nowMillis: Long)
    {
        sessions.entries.removeIf { it.value.expiresAtMillis <= nowMillis };
        spentNonces.entries.removeIf { nowMillis - it.value > replayWindowMillis };
    }

    private fun randomHex(byteCount: Int): String
    {
        val bytes = ByteArray(byteCount);
        random.nextBytes(bytes);
        val out = StringBuilder(byteCount * 2);
        for (byte in bytes)
        {
            val value = byte.toInt() and 0xFF;
            out.append(HEX_DIGITS[value shr 4]);
            out.append(HEX_DIGITS[value and 0x0F]);
        }
        return out.toString();
    }

    companion object
    {
        /** Long enough that a session never expires mid-test unless a test asks it to. */
        const val DEFAULT_SESSION_TTL_MILLIS: Long = 600_000

        /** The contract's `clientProofV1.replayWindowMillis`. */
        const val DEFAULT_REPLAY_WINDOW_MILLIS: Long = 300_000

        /** Ten minutes, the upstream `DEFAULT_DEVICE_AUTH_TTL_MS`. */
        const val DEVICE_AUTH_TTL_MILLIS: Long = 600_000

        /**
         * What this server asks a waiting device to wait between polls.
         *
         * Deliberately shorter than upstream's five-second default. The interval is
         * server configuration rather than contract — upstream refuses anything but a
         * positive whole number of milliseconds and states no other rule — and what the
         * matrix proves is that the client obeys whatever it is told, which it proves
         * exactly as well at 200ms while spending fifteen fewer seconds asleep.
         */
        const val DEVICE_AUTH_INTERVAL_MILLIS: Long = 200

        /**
         * How many hex characters of a fingerprint the approver is shown, restated from
         * upstream `KEY_FINGERPRINT_PREFIX_LENGTH` in key.service.ts at 77fe6246. The
         * same number the key list truncates to, so the two views of one key agree.
         */
        const val KEY_FINGERPRINT_PREFIX_LENGTH: Int = 8

        /**
         * The alphabet a user code is drawn from, restated from upstream
         * `USER_CODE_ALPHABET`. 0/O and 1/I/L are the pairs a person mistypes copying a
         * code between two screens, so neither member of either pair is in the set.
         */
        const val USER_CODE_ALPHABET: String = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

        /** Characters in a user code, not counting the dash it is displayed with. */
        const val USER_CODE_LENGTH: Int = 8

        private const val USER_CODE_GROUP_SIZE = 4

        /** Redraws before a run of collisions is called what it is: a broken generator. */
        private const val USER_CODE_ATTEMPTS = 3

        /** Entropy behind a device code. 256 bits, so it is never guessed. */
        private const val DEVICE_CODE_BYTES = 32

        /** The stored form as a person reads it: `XXXX-XXXX`. */
        fun formatUserCode(userCode: String): String =
            userCode.take(USER_CODE_GROUP_SIZE) + "-" + userCode.drop(USER_CODE_GROUP_SIZE)

        /**
         * Folds a typed user code back to the stored form, as upstream's
         * `normalizeUserCode` does: someone reading `WXYZ-2345` off a screen types the
         * dash, or spaces, or lower case, and all of those name the same code.
         *
         * Only the user code is folded. The device code is a credential and is matched
         * byte for byte — accepting variant spellings of a credential is how one token
         * becomes several strings and a one-shot record is spent twice.
         *
         * ASCII-explicit rather than a Unicode-aware class, so this and the SDKs cannot
         * disagree over a full-width digit (P9).
         */
        fun normalizeUserCode(input: String): String =
            input.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }.uppercase()

        /**
         * What the table holds instead of the device code: unsalted SHA-256, as upstream
         * `hashDeviceCode` does. The input is 256 bits of CSPRNG output, so there is no
         * dictionary to defend against and a per-row salt would only stop the exact-match
         * lookup this has to support.
         */
        fun hashDeviceCode(deviceCode: String): String =
            SpfnDigest.sha256Hex(deviceCode.toByteArray(Charsets.UTF_8))

        /**
         * The public identifier this fixture issues for an owner.
         *
         * A real server stores one per account. This one has no account table beyond the
         * owner ids the REST surface has enrolled keys for, so it derives the value —
         * which is enough for the contract shape and is never a fact about a person.
         */
        fun publicIdOf(ownerId: String): String = "public-$ownerId"

        private const val SESSION_ID_BYTES = 16

        private val HEX_DIGITS = "0123456789abcdef".toCharArray()

        /**
         * The ledger key. Joined with a C0 control character, which no proof field may
         * contain, so two distinct (clientId, nonce) pairs can never collide into one.
         */
        fun replayKeyOf(clientId: String, nonce: String): String = "$clientId\u001F$nonce"

        /**
         * 128 random bits as lowercase base16.
         *
         * Used for the per-launch control token and for the `requestId` an error envelope
         * carries. Both are opaque and neither is derived from anything a request sent.
         */
        fun newHexId(): String
        {
            val bytes = ByteArray(SESSION_ID_BYTES);
            SecureRandom().nextBytes(bytes);
            val out = StringBuilder(bytes.size * 2);
            for (byte in bytes)
            {
                val value = byte.toInt() and 0xFF;
                out.append(HEX_DIGITS[value shr 4]);
                out.append(HEX_DIGITS[value and 0x0F]);
            }
            return out.toString();
        }
    }
}
