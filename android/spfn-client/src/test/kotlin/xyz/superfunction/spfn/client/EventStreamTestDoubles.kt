// SPFN Mobile — what the event stream suites run against.
//
// [SpfnFakeStreamTransport] is the stream boundary with a script: a test queues a response
// (status, headers), then pushes chunks into it, ends it quietly or fails it. [TokenServer]
// answers the token call behind `execute` — the handshake it needs first, then one token per
// call — and records what it was sent. No socket anywhere (design §9-2).
//
// EventStreamTestDoubles.swift is the counterpart.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations

class SpfnFakeStreamTransport : SpfnStreamTransport
{
    /** One scripted connection. Chunks go in with [send]; [finish] and [fail] end it. */
    class FakeStream(val statusCode: Int, val headers: List<Pair<String, String>>)
    {
        private val chunks = Channel<ByteArray>(Channel.UNLIMITED);

        var cancelled = false
            private set;

        fun send(text: String)
        {
            chunks.trySend(text.toByteArray(Charsets.UTF_8));
        }

        fun finish()
        {
            chunks.close();
        }

        fun fail()
        {
            chunks.close(SpfnTransportError.Connectivity("scripted drop"));
        }

        internal fun response(): SpfnStreamResponse =
            SpfnStreamResponse(statusCode, headers, chunks.consumeAsFlow()) {
                cancelled = true;
                chunks.cancel();
            };
    }

    private val scripted = ArrayDeque<FakeStream>();
    private val recorded = mutableListOf<SpfnTransportRequest>();

    val requests: List<SpfnTransportRequest> get() = synchronized(this) { recorded.toList() };

    /** Queues the next connection's answer and returns it for the test to drive. */
    fun enqueue(statusCode: Int = 200, headers: List<Pair<String, String>> = EVENT_STREAM_HEADERS): FakeStream
    {
        val stream = FakeStream(statusCode, headers);
        synchronized(this) { scripted.addLast(stream) };
        return stream;
    }

    /** With nothing queued the connection hangs, as a connect nobody answers does. */
    override suspend fun open(request: SpfnTransportRequest): SpfnStreamResponse
    {
        val next = synchronized(this) {
            recorded.add(request);
            scripted.removeFirstOrNull();
        } ?: awaitCancellation();
        return next.response();
    }

    companion object
    {
        val EVENT_STREAM_HEADERS: List<Pair<String, String>> = listOf("content-type" to "text/event-stream");
    }
}

/**
 * Answers the handshake the session opens first, then the token path with `token-1`,
 * `token-2`, … — or with whatever [tokenAnswers] scripts, in order, before falling back.
 */
class TokenServer(private val tokenPath: String = "/events/token") : SpfnTransport
{
    val tokenAnswers = ArrayDeque<SpfnTransportResponse>();
    private val recorded = mutableListOf<SpfnTransportRequest>();
    private var minted = 0;

    val tokenRequests: List<SpfnTransportRequest> get() = synchronized(this) { recorded.filter { it.url.endsWith(tokenPath) } };

    override suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse
    {
        val scripted = synchronized(this) {
            recorded.add(request);
            if (request.url.endsWith(tokenPath)) tokenAnswers.removeFirstOrNull() else null
        };
        return when
        {
            request.url.endsWith(SpfnGeneratedOperations.authClientProofHandshake.path) ->
                jsonResponse(200, SessionFixtureValues.HANDSHAKE_RESPONSE_BODY)
            scripted != null -> scripted
            request.url.endsWith(tokenPath) -> jsonResponse(200, "{\"token\":\"token-${synchronized(this) { ++minted }}\"}")
            else -> jsonResponse(404, ExecuteFixtures.errorEnvelope("Error"))
        };
    }
}

/** The SSE frames the server writes, spelled as `@spfn/core` 0.3.0-beta.13 spells them. */
object ServerFrames
{
    const val CONNECTED = "event: connected\ndata: {\"subscribedEvents\":[\"sessionActivity\",\"sessionUnread\"],\"timestamp\":1750000000000}\n\n";

    const val PING = "event: ping\ndata: {\"timestamp\":1750000010000}\n\n";

    fun activity(id: Int, sessionId: String, wsId: String): String =
        "id: $id\nevent: sessionActivity\ndata: {\"event\":\"sessionActivity\",\"data\":{\"sessionId\":\"$sessionId\",\"wsId\":\"$wsId\"}}\n\n";
}
