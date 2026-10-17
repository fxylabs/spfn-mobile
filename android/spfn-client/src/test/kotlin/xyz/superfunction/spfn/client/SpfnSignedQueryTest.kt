// SPFN Mobile — a proven operation with a query signs its path and sends path + query.
//
// The server verifies the path it routed — Hono's `c.req.path`, which carries no query —
// so the proof input is `operation.path` and the query rides only on the URL. The
// expected bytes are the `queryVectors` of Contracts/fixtures/request/wire.json, derived
// by Contracts/fixtures/derive-expected-values.py rather than by either SDK.
//
// SPFNSignedQueryTests.swift reads the same vectors and asserts the same things.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import xyz.superfunction.spfn.auth.SpfnClientProof
import xyz.superfunction.spfn.auth.SpfnProofInput
import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnOperation

class SpfnSignedQueryTest
{
    private val baseUrl = "https://example.invalid"

    @Test
    fun aProvenGetWithAQueryMatchesTheWireVector() = runBlocking {
        assertSentAsRecorded(queryVector("proven-get-with-query"));
    }

    /** `a b&c` is encoded on the URL and never signed; a null value leaves no `name=`. */
    @Test
    fun anEncodedQueryAndANullValueMatchTheWireVector() = runBlocking {
        assertSentAsRecorded(queryVector("proven-get-with-encoded-query"));
    }

    /**
     * The proof input of a call with a query is byte for byte the one its bare path would
     * sign, and a proof over it does not verify over path + query.
     */
    @Test
    fun theProofInputIsTheBarePath() = runBlocking {
        val vector = queryVector("proven-get-with-query");
        val sent = sendOnce(vector);
        val input = proofInput(vector, vector.text("path"), sent.headers);
        val withQuery = proofInput(vector, vector.text("pathAndQuery"), sent.headers);

        assertEquals(vector.text("canonicalString"), SpfnClientProof.canonicalString(input));
        assertThrows(Exception::class.java) {
            SpfnClientProof.verify(
                requireNotNull(header(SpfnWireHeaders.PROOF, sent)),
                withQuery,
                ExecuteFixtures.fixturePublicKeySpkiDer()
            );
        };
        Unit
    }

    /**
     * The one retry after PROOF_EXPIRED re-sends the same URL, query included, under a new
     * nonce and a new proof over the same bare path.
     */
    @Test
    fun theRetryAfterAnExpiredProofResendsTheQueryUnderAFreshProof() = runBlocking {
        val vector = queryVector("proven-get-with-query");
        val clock = AnchoringProofClock(SessionFixtureValues.ISSUED_AT_MILLIS);
        val transport = ScriptedTransport(
            listOf(
                answer(ExecuteFixtures.errorEnvelope("PROOF_EXPIRED"), statusCode = 401),
                answer("{}")
            )
        );

        client(transport, clock, emptyList()).execute(call(vector), Unit);

        assertEquals("the discarded anchor was fetched again", 2, clock.synchronizations);
        assertEquals(List(2) { baseUrl + vector.text("pathAndQuery") }, transport.received.map { it.url });
        assertNotEquals(header(SpfnWireHeaders.NONCE, transport.received[0]), header(SpfnWireHeaders.NONCE, transport.received[1]));
        for (sent in transport.received)
        {
            SpfnClientProof.verify(
                requireNotNull(header(SpfnWireHeaders.PROOF, sent)),
                proofInput(vector, vector.text("path"), sent.headers),
                ExecuteFixtures.fixturePublicKeySpkiDer()
            );
        }
    }

    @Test
    fun aCallWithoutAQueryIsSentToItsPathAlone()
    {
        val call = SpfnCall<Unit, SpfnCanonicalValue>(
            operation = SpfnOperation("getThing", "GET", "/v1/things", "clientProofV1", false, true),
            query = listOf("cursor" to null),
            encode = { _ -> null },
            decode = { value -> value }
        );

        assertEquals("/v1/things", call.pathAndQuery);
    }

    // ---- driving one vector ------------------------------------------------

    private suspend fun assertSentAsRecorded(vector: Map<String, SpfnCanonicalValue>)
    {
        val sent = sendOnce(vector);

        assertEquals(vector.text("method"), sent.method);
        assertEquals(baseUrl + vector.text("pathAndQuery"), sent.url);
        assertNull("a GET with no request type sends no body", sent.body);
        assertHeadersMatchWireVector(sent.headers, vector.headerPairs("headers"), vector);
    }

    private suspend fun sendOnce(vector: Map<String, SpfnCanonicalValue>): SpfnTransportRequest
    {
        val issued = vector.headerPairs("headers").toMap();
        val transport = ScriptedTransport(listOf(answer("{}")));

        client(
            transport,
            FakeClock(requireNotNull(issued[SpfnWireHeaders.ISSUED_AT_MILLIS]).toLong()),
            listOf(requireNotNull(issued[SpfnWireHeaders.NONCE]))
        ).execute(call(vector), Unit);

        assertEquals("no handshake: the operation requires no session", 1, transport.callCount);
        return transport.received.first();
    }

    /** The call a generated app-contract client would build for the vector's operation. */
    private fun call(vector: Map<String, SpfnCanonicalValue>): SpfnCall<Unit, SpfnCanonicalValue> = SpfnCall(
        operation = SpfnOperation(vector.text("operationId"), vector.text("method"), vector.text("path"), "clientProofV1", false, true),
        query = vector.list("query").map { pair ->
            val fields = pair.elements();
            fields[0].text() to fields[1].takeIf { it != SpfnCanonicalValue.Null }?.text()
        },
        encode = { _ -> null },
        decode = { value -> value }
    )

    private fun client(transport: ScriptedTransport, clock: SpfnProofClock, nonces: List<String>): SpfnClient = SpfnClient(
        transport = transport,
        session = SpfnSession(
            transport = transport,
            keyProvider = ExecuteFixtures.syntheticProvider(SessionFixtureValues.CLIENT_ID),
            baseUrl = baseUrl,
            clock = clock,
            nonceGenerator = ScriptedNonceGenerator(nonces)
        )
    )

    // ---- reading the vector ------------------------------------------------

    private fun queryVector(name: String): Map<String, SpfnCanonicalValue> =
        WireFixtures.wire().list("queryVectors").map { it.members() }.first { it.text("name") == name }

    private fun proofInput(vector: Map<String, SpfnCanonicalValue>, path: String, headers: List<Pair<String, String>>): SpfnProofInput
    {
        val byName = headers.toMap();
        return SpfnProofInput(
            method = vector.text("method"),
            path = path,
            clientId = requireNotNull(byName[SpfnWireHeaders.CLIENT_ID]),
            keyId = requireNotNull(byName[SpfnWireHeaders.KEY_ID]),
            nonce = requireNotNull(byName[SpfnWireHeaders.NONCE]),
            issuedAtMillis = requireNotNull(byName[SpfnWireHeaders.ISSUED_AT_MILLIS]).toLong(),
            bodySha256 = vector.text("bodySha256")
        );
    }

    private fun answer(text: String, statusCode: Int = 200): ScriptedTransport.Outcome =
        ScriptedTransport.Outcome.Answer(jsonResponse(statusCode, text))

    private fun header(name: String, request: SpfnTransportRequest): String? =
        request.headers.firstOrNull { it.first == name }?.second
}
