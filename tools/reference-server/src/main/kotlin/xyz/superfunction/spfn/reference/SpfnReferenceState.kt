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

import xyz.superfunction.spfn.auth.SpfnClientProof
import xyz.superfunction.spfn.auth.SpfnProofInput
import xyz.superfunction.spfn.core.SpfnDigest
import java.security.SecureRandom

/**
 * The keys this server will verify a proof against.
 *
 * TEST VECTOR ONLY. Every value here is synthetic: it authenticates nothing, was never
 * issued by anything, and must never be presented to a real endpoint. The values are the
 * ones `Contracts/fixtures/proof/proof-input.json` already uses, so a proof this server
 * accepts is a proof the conformance fixtures describe.
 */
object SpfnReferenceTestKeys
{
    const val CLIENT_ID: String = "client-test-0001"

    const val KEY_ID: String = "key-test-0001"

    /** TEST VECTOR ONLY. A synthetic string, not a credential. */
    const val KEY_UTF8: String = "spfn-test-key-not-a-secret-0001"

    val KEY_BYTES: ByteArray
        get() = KEY_UTF8.toByteArray(Charsets.UTF_8)

    val DIRECTORY: Map<String, ByteArray>
        get() = mapOf(KEY_ID to KEY_BYTES)
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

class SpfnReferenceState(
    private val clock: SpfnReferenceClock,
    sessionTtlMillis: Long = DEFAULT_SESSION_TTL_MILLIS,
    private val keys: Map<String, ByteArray> = SpfnReferenceTestKeys.DIRECTORY,
    /** The contract's `clientProofV1.replayWindowMillis`. */
    val replayWindowMillis: Long = DEFAULT_REPLAY_WINDOW_MILLIS
)
{
    private val lock = Any()
    private val random = SecureRandom()

    private val sessions = LinkedHashMap<String, SpfnReferenceSession>()

    /** The replay ledger: `replayKeyOf` to the `issuedAtMillis` it was spent at. */
    private val spentNonces = LinkedHashMap<String, Long>()

    private val revokedKeyIds = LinkedHashSet<String>()
    private val holds = LinkedHashMap<String, SpfnReferenceHold>()

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
        val key = keys[keyId] ?: return@synchronized SpfnReferenceRefusal.proofInvalid();
        if (!SpfnDigest.constantTimeEquals(SpfnClientProof.proof(proofInput, key), presentedProof))
        {
            return@synchronized SpfnReferenceRefusal.proofInvalid();
        }

        spentNonces[replayKey] = proofInput.issuedAtMillis;
        null;
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
