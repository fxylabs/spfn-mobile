// SPFN Mobile — the wire mapping, checked against the contract and against the fixture.
//
// Two separate claims are made here, and neither rests on the other:
//
//   - the header names this SDK compiles against are the ones the pinned bundle's
//     `wireMapping` section states, so a renamed header fails a test run rather than
//     surfacing as a 401 against a real server;
//   - a session assembles exactly the bytes Contracts/fixtures/request/wire.json
//     records, and those expected bytes were produced by
//     Contracts/fixtures/derive-expected-values.py rather than by either SDK.
//
// SPFNWireConformanceTests.swift reads the same two files and asserts the same things.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations

class SpfnWireConformanceTest
{
    private val baseUrl = "https://example.invalid"

    // ---- the constants agree with the contract -----------------------------

    @Test
    fun headerNamesMatchTheBundleWireMapping()
    {
        val fromBundle = WireFixtures.bundleWireMapping()
            .obj("headers")
            .mapValues { (_, value) -> value.text() };

        assertEquals(fromBundle, SpfnWireHeaders.BY_CONTRACT_FIELD);
    }

    @Test
    fun headerOrderMatchesTheBundleWireMapping()
    {
        val order = WireFixtures.bundleWireMapping().list("headerOrder").map { it.text() };

        assertEquals(order, SpfnWireHeaders.CONTRACT_FIELD_ORDER);
    }

    @Test
    fun requestContentTypeMatchesTheBundleWireMapping()
    {
        assertEquals(
            WireFixtures.bundleWireMapping().text("requestContentType"),
            SpfnWireHeaders.REQUEST_CONTENT_TYPE
        );
    }

    /**
     * The fixture and the generated operations have to agree about which operations carry
     * a session, or the vectors would pin a rule the SDK never applies.
     */
    @Test
    fun everyWireVectorAgreesWithItsGeneratedOperation()
    {
        for (vector in WireFixtures.wire().list("vectors").map { it.members() })
        {
            val operation = requireNotNull(SpfnGeneratedOperations.operation(vector.text("operationId")));
            assertEquals(operation.method, vector.text("method"));
            assertEquals(operation.path, vector.text("path"));

            val carriesSession = vector.headerPairs("headers").any { it.first == SpfnWireHeaders.SESSION };
            assertEquals("wireMapping.sessionRule", operation.requiresSession, carriesSession);
        }
    }

    // ---- a session assembles the recorded bytes ----------------------------

    @Test
    fun handshakeMatchesTheWireVector() = runBlocking {
        val vector = WireFixtures.vector("handshake")
        val expected = vector.headerPairs("headers")
        val issued = issuedValues(expected)

        val transport = ScriptedTransport(
            listOf(
                ScriptedTransport.Outcome.Answer(
                    jsonResponse(200, SessionFixtureValues.HANDSHAKE_RESPONSE_BODY)
                )
            )
        )
        val session = SpfnSession(
            transport = transport,
            keyProvider = syntheticProvider(),
            baseUrl = baseUrl,
            clock = FakeClock(issued.second),
            nonceGenerator = ScriptedNonceGenerator(listOf(issued.first))
        )

        session.handshake()

        val sent = transport.received.single()
        assertEquals(vector.text("method"), sent.method)
        assertEquals(baseUrl + vector.text("path"), sent.url)
        assertHeadersMatchWireVector(sent.headers, expected, vector)
        assertEquals(vector.text("canonicalBody"), String(requireNotNull(sent.body), Charsets.UTF_8))
    }

    @Test
    fun aSessionOperationMatchesTheWireVector() = runBlocking {
        val handshakeVector = WireFixtures.vector("handshake")
        val vector = WireFixtures.vector("echo-with-session")
        val expected = vector.headerPairs("headers")
        val issued = issuedValues(expected)
        val openingNonce = issuedValues(handshakeVector.headerPairs("headers")).first

        val transport = ScriptedTransport(
            listOf(
                ScriptedTransport.Outcome.Answer(
                    jsonResponse(200, SessionFixtureValues.HANDSHAKE_RESPONSE_BODY)
                )
            )
        )
        val session = SpfnSession(
            transport = transport,
            keyProvider = syntheticProvider(),
            baseUrl = baseUrl,
            clock = FakeClock(issued.second),
            // The handshake that opens the session consumes the first nonce; the request
            // the vector describes consumes the second.
            nonceGenerator = ScriptedNonceGenerator(listOf(openingNonce, issued.first))
        )

        val headers = session.proofHeaders(
            SpfnGeneratedOperations.echoSend,
            vector.text("canonicalBody").toByteArray(Charsets.UTF_8)
        )

        // `proofHeaders` on its own, so no identity: the identity is appended where the
        // request is handed to the transport, not where the proof is assembled.
        assertHeadersMatchWireVector(headers, expected, vector, identity = emptyList())
        assertEquals("one handshake, then the request itself", 1, transport.callCount)
        assertEquals(SessionFixtureValues.SESSION_ID, vector.text("sessionId"))
    }

    // ---- reading the vector ------------------------------------------------

    private fun issuedValues(headers: List<Pair<String, String>>): Pair<String, Long>
    {
        val byName = headers.toMap();
        return requireNotNull(byName[SpfnWireHeaders.NONCE]) to
            requireNotNull(byName[SpfnWireHeaders.ISSUED_AT_MILLIS]).toLong();
    }

    /**
     * The fixture test keypair the vectors name. Read from the fixture rather than
     * restated, so the suite cannot silently sign with something else.
     */
    private fun syntheticProvider(): SpfnSoftwareKeyProvider = ExecuteFixtures.syntheticProvider()
}
