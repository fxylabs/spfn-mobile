// SPFN Mobile — the fakes the session suite injects.
//
// A session reads a clock and a random source, so every one of its observable rules —
// a fresh nonce per request, expiry judged at an exact instant, one handshake for many
// callers — is only assertable if both are supplied rather than read from the system.
// SessionTestDoubles.swift is the counterpart.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.delay

/** A clock a test moves by hand. */
class FakeClock(millis: Long) : SpfnClock
{
    @Volatile
    private var millis: Long = millis

    override fun nowMillis(): Long = millis

    fun set(value: Long)
    {
        millis = value;
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

/** A JSON answer, spelled the way a server would put it on the wire. */
fun jsonResponse(statusCode: Int, text: String): SpfnTransportResponse =
    SpfnTransportResponse(
        statusCode = statusCode,
        headers = listOf("content-type" to "application/json"),
        body = text.toByteArray(Charsets.UTF_8)
    )

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
