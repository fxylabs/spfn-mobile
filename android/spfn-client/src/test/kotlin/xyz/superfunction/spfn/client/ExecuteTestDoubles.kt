// SPFN Mobile — what the execute suite sends requests at.
//
// Two doubles, because the suite asks two different kinds of question. [ScriptedTransport]
// answers by position and settles what one call does. [RevokingServer] answers by what the
// request presented, which is the only way to ask what several concurrent calls do to each
// other: under a positional script, whichever coroutine happened to run first would consume
// an answer meant for another one and the test would pass or fail by scheduling.
//
// ExecuteTestDoubles.swift is the counterpart.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.CompletableDeferred
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.generated.SpfnEchoRequest
import xyz.superfunction.spfn.generated.SpfnEchoResponse
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnHandshakeRequest
import xyz.superfunction.spfn.generated.SpfnHandshakeResponse
import xyz.superfunction.spfn.generated.SpfnItem
import xyz.superfunction.spfn.generated.SpfnListItemsRequest
import xyz.superfunction.spfn.generated.SpfnListItemsResponse

// ---- contract bodies, spelled as a server would put them on the wire --------

object ExecuteFixtures
{
    /**
     * The synthetic key the wire vectors are signed with. Not a credential; see
     * Contracts/fixtures/MANIFEST.json.
     */
    fun syntheticProvider(clientId: String = SessionFixtureValues.CLIENT_ID): SpfnInMemoryKeyProvider =
        SpfnInMemoryKeyProvider(
            clientId = clientId,
            keyId = SessionFixtureValues.KEY_ID,
            key = WireFixtures.wire().obj("syntheticKey").text("keyUtf8").toByteArray(Charsets.UTF_8)
        )

    fun handshakeResponse(sessionId: String, expiringAt: Long): String =
        "{\"expiresAtMillis\":$expiringAt,\"sessionId\":\"$sessionId\"}"

    /** An error envelope in canonical form. Keys sort as the canonical encoder sorts them. */
    fun errorEnvelope(
        code: String,
        message: String = "refused",
        requestId: String = "req-test-0001"
    ): String = "{\"error\":{\"code\":\"$code\",\"message\":\"$message\",\"requestId\":\"$requestId\"}}"

    val ECHO_REQUEST = SpfnEchoRequest(message = "hello", sequence = 7)

    val ECHO_RESPONSE = SpfnEchoResponse(message = "hello", sequence = 7, serverTimeMillis = 1_750_000_000_500)

    val ECHO_RESPONSE_BODY: String =
        SpfnCanonicalJson.encode(ECHO_RESPONSE.canonicalValue()).toString(Charsets.UTF_8)

    val LIST_REQUEST = SpfnListItemsRequest(limit = 2, cursor = "cursor-1")

    val LIST_RESPONSE = SpfnListItemsResponse(
        items = listOf(SpfnItem(id = "item-1", name = "first", updatedAtMillis = 1_750_000_000_100)),
        nextCursor = "cursor-2"
    )

    val LIST_RESPONSE_BODY: String =
        SpfnCanonicalJson.encode(LIST_RESPONSE.canonicalValue()).toString(Charsets.UTF_8)
}

// ---- the three calls --------------------------------------------------------

// Hand-written here rather than shipped: what the library owes is one execute path, and the
// per-operation descriptors that ride on it are the generator's job, not this change set's.
// Writing them in the suite is what keeps that boundary visible.

object ExecuteCalls
{
    val ECHO = SpfnCall<SpfnEchoRequest, SpfnEchoResponse>(
        operation = SpfnGeneratedOperations.echoSend,
        encode = { it.canonicalValue() },
        decode = { SpfnEchoResponse.decode(it) }
    )

    val LIST = SpfnCall<SpfnListItemsRequest, SpfnListItemsResponse>(
        operation = SpfnGeneratedOperations.itemsList,
        encode = { it.canonicalValue() },
        decode = { SpfnListItemsResponse.decode(it) }
    )

    /**
     * The handshake, described the same way as the others so the suite can show that
     * `execute` refuses it on the operation rather than on how it was described.
     */
    val HANDSHAKE = SpfnCall<SpfnHandshakeRequest, SpfnHandshakeResponse>(
        operation = SpfnGeneratedOperations.authClientProofHandshake,
        encode = { it.canonicalValue() },
        decode = { SpfnHandshakeResponse.decode(it) }
    )
}

// ---- a server that answers what it was shown --------------------------------

/**
 * Issues a new session per handshake and refuses any request presenting a revoked one.
 *
 * Deliberately not a script. The question it exists to answer — do N concurrent calls
 * meeting one revocation share one re-handshake — is a question about what the calls do to
 * each other, and any answer chosen by position would be an answer about scheduling.
 *
 * @param holdingFirst how many operation requests are held until that many have arrived.
 *   Without it a concurrency test proves nothing: one coroutine can finish its whole retry
 *   before another has sent anything, and then the calls never met.
 */
class RevokingServer(
    private val revoked: Set<String>,
    private val expiresAtMillis: Long = SessionFixtureValues.EXPIRES_AT_MILLIS,
    holdingFirst: Int = 0
) : SpfnTransport
{
    private val firstRound: Barrier? = if (holdingFirst > 0) Barrier(holdingFirst) else null
    private val lock = Any()
    private val recorded = mutableListOf<SpfnTransportRequest>()
    private var issued = 0
    private var operations = 0

    val received: List<SpfnTransportRequest> get() = synchronized(lock) { recorded.toList() }

    val callCount: Int get() = synchronized(lock) { recorded.size }

    /** How many of the recorded calls opened a session. */
    val handshakes: Int
        get() = received.count { it.url.endsWith(SpfnGeneratedOperations.authClientProofHandshake.path) }

    override suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse
    {
        synchronized(lock) { recorded.add(request) };

        if (request.url.endsWith(SpfnGeneratedOperations.authClientProofHandshake.path))
        {
            val next = synchronized(lock) { ++issued };
            return jsonResponse(200, ExecuteFixtures.handshakeResponse("session-$next", expiresAtMillis));
        }

        val arrival = synchronized(lock) { ++operations };
        if (firstRound != null && arrival <= firstRound.width)
        {
            firstRound.arriveAndWait();
        }

        val presented = request.headers.firstOrNull { it.first == SpfnWireHeaders.SESSION }?.second ?: "";
        if (revoked.contains(presented))
        {
            return jsonResponse(401, ExecuteFixtures.errorEnvelope("SESSION_REVOKED"));
        }
        return jsonResponse(200, ExecuteFixtures.ECHO_RESPONSE_BODY);
    }
}

/** Holds every arrival until [width] of them have arrived, then releases all of them. */
class Barrier(val width: Int)
{
    private val released = CompletableDeferred<Unit>()
    private val lock = Any()
    private var arrived = 0

    suspend fun arriveAndWait()
    {
        val full = synchronized(lock) { ++arrived >= width };
        if (full)
        {
            released.complete(Unit);
            return;
        }
        released.await();
    }
}
