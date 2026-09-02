// SPFN Mobile — the fakes the session suite injects.
//
// A session reads a clock and a random source, so every one of its observable rules —
// a fresh nonce per request, expiry judged at an exact instant, one handshake for many
// callers — is only assertable if both are supplied rather than read from the system.
// SessionTestDoubles.swift is the counterpart.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import xyz.superfunction.spfn.generated.SpfnGeneratedContract
import kotlin.coroutines.coroutineContext

/** A clock a test moves by hand. */
class FakeClock(millis: Long) : SpfnClock, SpfnProofClock
{
    @Volatile
    private var millis: Long = millis

    override fun nowMillis(): Long = millis

    override suspend fun nowMillis(transport: SpfnTransport, baseUrl: String, timeoutMillis: Long): Long = millis

    fun set(value: Long)
    {
        millis = value;
    }
}

/**
 * A proof clock whose reads are scripted, so a case can lose a `core.time` fetch.
 *
 * The device-code wait reads the proof clock once per iteration, and what the SDK owes a
 * failed read differs by failure — a lost fetch is asked again, a refusal to synchronize
 * ends the wait. Neither is reachable through [FakeClock], which always answers, or
 * through the scripted transport, which the injected clock never consults.
 *
 * [script] is read in order: an entry that holds a throwable is a read that throws it, and
 * `null` — including every read past the end — answers [millis].
 */
class ScriptedProofClock(
    private val millis: Long,
    script: List<Throwable?> = emptyList()
) : SpfnProofClock
{
    private val remaining = ArrayDeque(script)
    private var recorded = 0

    /** How many times the wait asked what time it is. */
    val reads: Int get() = synchronized(this) { recorded }

    override suspend fun nowMillis(transport: SpfnTransport, baseUrl: String, timeoutMillis: Long): Long
    {
        val next = synchronized(this) {
            recorded += 1;
            if (remaining.isEmpty()) null else remaining.removeFirst();
        };
        if (next != null)
        {
            throw next;
        }
        return millis;
    }
}

/**
 * A sleeper that records what it was asked to wait and never really waits.
 *
 * The device-code suite asserts the interval the SDK obeyed, which is only observable if
 * the wait is a value rather than elapsed time. [onSleep] runs with the 1-based wait
 * number, so a case can make something happen at an exact point in the poll loop.
 *
 * Cancellation is honoured the way `delay` honours it: a wait entered by a coroutine
 * that is already cancelled throws instead of returning, so a cancelled poll loop stops
 * at the wait and never sends the next request.
 */
class ScriptedSleeper(private val onSleep: (suspend (Int) -> Unit)? = null) : SpfnSleeper
{
    private val recorded = mutableListOf<Long>()

    val waits: List<Long> get() = synchronized(this) { recorded.toList() }

    override suspend fun sleep(millis: Long)
    {
        val count = synchronized(this) { recorded.add(millis); recorded.size };
        onSleep?.invoke(count);
        coroutineContext.ensureActive();
    }
}

/** Hands out a fixed list of nonces in order, so a fixture's exact nonce can be replayed. */
class ScriptedNonceGenerator(nonces: List<String>) : SpfnNonceGenerator
{
    private val remaining = ArrayDeque(nonces)
    private var issued = 0

    override fun nextNonce(): String = synchronized(this) {
        issued += 1;
        if (remaining.isEmpty()) "nonce-exhausted-$issued" else remaining.removeFirst();
    }
}

/**
 * Answers from a script, records every request, and can hold a call open long enough for
 * other callers to arrive while it is still in flight.
 */
class ScriptedTransport(
    outcomes: List<Outcome>,
    private val holdMillis: Long = 0,
    /**
     * Runs after the request is recorded and before its answer is produced, with the
     * 1-based call number. The execute suite uses it to make something happen at an exact
     * point in a call — cancelling between two attempts, for instance — rather than racing
     * a timer against the code under test.
     */
    private val onCall: (suspend (Int) -> Unit)? = null
) : SpfnTransport
{
    sealed interface Outcome
    {
        class Answer(val response: SpfnTransportResponse) : Outcome

        class Failure(val error: Throwable) : Outcome
    }

    private val lock = Any()
    private val outcomes = ArrayDeque(outcomes)
    private val recorded = mutableListOf<SpfnTransportRequest>()

    val received: List<SpfnTransportRequest> get() = synchronized(lock) { recorded.toList() }

    val callCount: Int get() = synchronized(lock) { recorded.size }

    override suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse
    {
        val call = synchronized(lock) { recorded.add(request); recorded.size };
        onCall?.invoke(call);
        if (holdMillis > 0)
        {
            delay(holdMillis);
        }
        val next = synchronized(lock) { outcomes.removeFirstOrNull() }
            ?: throw SpfnTransportError.Connectivity("scripted transport ran out of answers");
        return when (next)
        {
            is Outcome.Answer -> next.response
            is Outcome.Failure -> throw next.error
        };
    }
}

/**
 * A JSON answer, spelled the way a server would put it on the wire.
 *
 * The contract announcement is part of "the way a server would put it": contract 0.8.0
 * puts it on every response including a refusal, and this SDK refuses a response without
 * it. A double that omitted it would make every test in this directory assert the refusal
 * rather than what it was written to assert.
 */
fun jsonResponse(statusCode: Int, text: String): SpfnTransportResponse =
    SpfnTransportResponse(
        statusCode = statusCode,
        headers = listOf("content-type" to "application/json") + announcementHeaders(),
        body = text.toByteArray(Charsets.UTF_8)
    )

/**
 * The server's own announcement, defaulting to the version this build was generated from.
 * A test that is about the announcement passes its own.
 */
fun announcementHeaders(
    version: String = SpfnGeneratedContract.BINDING.importedVersion,
    range: String = SpfnGeneratedContract.BINDING.supportedRange
): List<Pair<String, String>> =
    listOf(
        SpfnWireHeaders.SERVER_CONTRACT_VERSION to version,
        SpfnWireHeaders.SUPPORTED_CONTRACT_RANGE to range
    )

/**
 * What a signer that cannot sign throws. Its own type, so a test can assert the
 * session neither wrapped it nor replaced it.
 */
class SignerFailure : IllegalStateException("the signing key is unavailable")

/** A provider whose key is gone: every sign attempt fails. */
class ThrowingKeyProvider : SpfnKeyProvider
{
    override val clientId: String = SessionFixtureValues.CLIENT_ID
    override val keyId: String = SessionFixtureValues.KEY_ID

    override fun sign(message: ByteArray): ByteArray = throw SignerFailure()
}

/**
 * The synthetic identities every fixture vector uses. Not credentials; see
 * Contracts/fixtures/MANIFEST.json.
 */
object SessionFixtureValues
{
    const val CLIENT_ID = "client-test-0001"
    const val KEY_ID = "key-test-0001"
    const val SESSION_ID = "session-test-0001"
    const val ISSUED_AT_MILLIS = 1_750_000_000_000L
    const val EXPIRES_AT_MILLIS = 1_750_000_300_000L

    /** A handshake answer in canonical form, as the server would write it. */
    fun handshakeResponse(expiringAt: Long): String =
        "{\"expiresAtMillis\":$expiringAt,\"sessionId\":\"$SESSION_ID\"}"

    val HANDSHAKE_RESPONSE_BODY: String = handshakeResponse(EXPIRES_AT_MILLIS)
}
