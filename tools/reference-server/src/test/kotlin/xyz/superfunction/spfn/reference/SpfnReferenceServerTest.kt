// SPFN Mobile — the reference server answers the contract, and refuses what is not it.
//
// Raw HTTP, deliberately. Every case here is a request neither SDK can produce: a body
// whose keys are out of order, a repeated header field, a session header on the handshake.
// A server is only worth integrating against if it refuses those, and only a test that
// can send them can show that it does.

package xyz.superfunction.spfn.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.generated.SpfnEchoRequest
import xyz.superfunction.spfn.generated.SpfnEchoResponse
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnHandshakeRequest
import xyz.superfunction.spfn.generated.SpfnListItemsRequest
import xyz.superfunction.spfn.generated.SpfnListItemsResponse

class SpfnReferenceServerTest
{
    // ---- the happy path ----------------------------------------------------

    @Test
    fun `an authenticated echo answers the contract response type`()
    {
        SpfnReferenceHarness().use { harness ->
            val response = echo(harness, harness.openSession(), "hello", 7);

            assertEquals(200, response.statusCode);
            val decoded = SpfnEchoResponse.decode(response.value());
            assertEquals("hello", decoded.message);
            assertEquals(7L, decoded.sequence);
            assertEquals(harness.clock.nowMillis(), decoded.serverTimeMillis);
        }
    }

    @Test
    fun `items list pages by cursor and stops advertising one at the end`()
    {
        SpfnReferenceHarness().use { harness ->
            val session = harness.openSession();

            val first = SpfnListItemsResponse.decode(listItems(harness, session, limit = 2).value());
            assertEquals(listOf("item-0001", "item-0002"), first.items.map { it.id });
            assertEquals("item-0002", first.nextCursor);

            val last = SpfnListItemsResponse.decode(listItems(harness, session, limit = 10, cursor = "item-0002").value());
            assertEquals(listOf("item-0003", "item-0004", "item-0005"), last.items.map { it.id });
            assertNull(last.nextCursor);
        }
    }

    @Test
    fun `an unknown cursor and an out-of-range limit are refused`()
    {
        SpfnReferenceHarness().use { harness ->
            val session = harness.openSession();

            assertEquals("CONTRACT_UNSUPPORTED", listItems(harness, session, limit = 0).errorCode());
            assertEquals("CONTRACT_UNSUPPORTED", listItems(harness, session, limit = 2, cursor = "item-9999").errorCode());
        }
    }

    // ---- shape refusals ----------------------------------------------------

    @Test
    fun `a body that is not canonical is refused even though its proof verifies`()
    {
        SpfnReferenceHarness().use { harness ->
            val operation = SpfnGeneratedOperations.echoSend;
            val session = harness.openSession();

            // Valid JSON, correct values, keys in the wrong order — and the proof is taken
            // over exactly these bytes, so nothing but the canonical check can refuse it.
            val body = "{\"sequence\":1,\"message\":\"out of order\"}".toByteArray(Charsets.UTF_8);
            val response = harness.send(operation, body, harness.proofHeaders(operation, body, sessionId = session));

            assertEquals("CONTRACT_UNSUPPORTED", response.errorCode());
            assertEquals(409, response.statusCode);
        }
    }

    @Test
    fun `insignificant whitespace is not canonical either`()
    {
        SpfnReferenceHarness().use { harness ->
            val operation = SpfnGeneratedOperations.echoSend;
            val session = harness.openSession();

            val body = "{\"message\": \"spaced\", \"sequence\": 1}".toByteArray(Charsets.UTF_8);
            val response = harness.send(operation, body, harness.proofHeaders(operation, body, sessionId = session));

            assertEquals("CONTRACT_UNSUPPORTED", response.errorCode());
        }
    }

    @Test
    fun `the canonical form of the same value is accepted`()
    {
        SpfnReferenceHarness().use { harness ->
            val operation = SpfnGeneratedOperations.echoSend;
            val session = harness.openSession();

            val body = SpfnCanonicalJson.encode(SpfnEchoRequest("out of order", 1).canonicalValue());
            val response = harness.send(operation, body, harness.proofHeaders(operation, body, sessionId = session));

            assertEquals(200, response.statusCode);
        }
    }

    @Test
    fun `a repeated contract header is refused`()
    {
        SpfnReferenceHarness().use { harness ->
            val operation = SpfnGeneratedOperations.echoSend;
            val session = harness.openSession();
            val body = SpfnCanonicalJson.encode(SpfnEchoRequest("twice", 1).canonicalValue());

            val headers = harness.proofHeaders(operation, body, sessionId = session);
            headers.add(SpfnReferenceWire.NONCE to "nonce-second-value");

            assertEquals("CONTRACT_UNSUPPORTED", harness.send(operation, body, headers).errorCode());
        }
    }

    @Test
    fun `an unknown path is refused without naming a neighbouring operation`()
    {
        SpfnReferenceHarness().use { harness ->
            val response = harness.send("POST", "/v1/nope", ByteArray(0), emptyList());

            assertEquals("CONTRACT_UNSUPPORTED", response.errorCode());
        }
    }

    @Test
    fun `a session header is required exactly where the contract puts one`()
    {
        SpfnReferenceHarness().use { harness ->
            val echo = SpfnGeneratedOperations.echoSend;
            val body = SpfnCanonicalJson.encode(SpfnEchoRequest("sessionless", 1).canonicalValue());
            assertEquals(
                "CONTRACT_UNSUPPORTED",
                harness.send(echo, body, harness.proofHeaders(echo, body)).errorCode()
            );

            val handshake = SpfnGeneratedOperations.authClientProofHandshake;
            val session = harness.openSession();
            val nonce = harness.nextNonce();
            val handshakeBody = SpfnCanonicalJson.encode(
                SpfnHandshakeRequest(
                    SpfnReferenceTestKeys.CLIENT_ID,
                    SpfnReferenceTestKeys.KEY_ID,
                    nonce,
                    harness.clock.nowMillis()
                ).canonicalValue()
            );
            assertEquals(
                "CONTRACT_UNSUPPORTED",
                harness.send(
                    handshake,
                    handshakeBody,
                    harness.proofHeaders(handshake, handshakeBody, sessionId = session, nonce = nonce)
                ).errorCode()
            );
        }
    }

    @Test
    fun `an auth profile outside the allowlist is refused as a profile, not as a proof`()
    {
        SpfnReferenceHarness().use { harness ->
            val operation = SpfnGeneratedOperations.echoSend;
            val session = harness.openSession();
            val body = SpfnCanonicalJson.encode(SpfnEchoRequest("profile", 1).canonicalValue());

            val headers = harness.proofHeaders(operation, body, sessionId = session)
                .map { if (it.first == SpfnReferenceWire.PROFILE) it.first to "somethingElseV1" else it };

            val response = harness.send(operation, body, headers);
            assertEquals("PROFILE_REJECTED", response.errorCode());
            assertEquals(400, response.statusCode);
        }
    }

    @Test
    fun `a request without the contract content type is refused`()
    {
        SpfnReferenceHarness().use { harness ->
            val operation = SpfnGeneratedOperations.echoSend;
            val session = harness.openSession();
            val body = SpfnCanonicalJson.encode(SpfnEchoRequest("typeless", 1).canonicalValue());

            val headers = harness.proofHeaders(operation, body, sessionId = session)
                .filterNot { it.first == SpfnReferenceWire.CONTENT_TYPE } + (SpfnReferenceWire.CONTENT_TYPE to "text/plain");

            assertEquals("CONTRACT_UNSUPPORTED", harness.send(operation, body, headers).errorCode());
        }
    }

    // ---- auth refusals -----------------------------------------------------

    @Test
    fun `an unknown session is refused as revoked`()
    {
        SpfnReferenceHarness().use { harness ->
            val operation = SpfnGeneratedOperations.echoSend;
            val body = SpfnCanonicalJson.encode(SpfnEchoRequest("ghost", 1).canonicalValue());

            val response = harness.send(
                operation,
                body,
                harness.proofHeaders(operation, body, sessionId = "session-that-was-never-issued")
            );
            assertEquals("SESSION_REVOKED", response.errorCode());
            assertEquals(401, response.statusCode);
        }
    }

    @Test
    fun `a session the server dropped is refused as revoked`()
    {
        SpfnReferenceHarness().use { harness ->
            val session = harness.openSession();
            assertEquals(200, echo(harness, session, "before", 1).statusCode);

            harness.server.state.expireSessions();

            assertEquals("SESSION_REVOKED", echo(harness, session, "after", 2).errorCode());
        }
    }

    @Test
    fun `a tampered proof is refused as invalid`()
    {
        SpfnReferenceHarness().use { harness ->
            val operation = SpfnGeneratedOperations.echoSend;
            val session = harness.openSession();
            val body = SpfnCanonicalJson.encode(SpfnEchoRequest("tampered", 1).canonicalValue());

            val headers = harness.proofHeaders(operation, body, sessionId = session)
                .map { if (it.first == SpfnReferenceWire.PROOF) it.first to it.second.reversed() else it };

            assertEquals("PROOF_INVALID", harness.send(operation, body, headers).errorCode());
        }
    }

    // ---- what a log is allowed to hold -------------------------------------

    @Test
    fun `nothing a request carried reaches the log`()
    {
        SpfnReferenceHarness().use { harness ->
            val operation = SpfnGeneratedOperations.echoSend;
            val session = harness.openSession();
            val body = SpfnCanonicalJson.encode(SpfnEchoRequest("secret message", 99).canonicalValue());
            val headers = harness.proofHeaders(operation, body, sessionId = session);
            harness.send(operation, body, headers);

            val logged = harness.logLines.joinToString("\n");
            assertTrue("the server logged nothing at all", harness.logLines.isNotEmpty());

            val forbidden = headers.map { it.second } +
                listOf(session, harness.controlTokenForTest(), "secret message", SpfnReferenceTestKeys.KEY_UTF8);
            for (value in forbidden)
            {
                assertFalse("a request value reached the log: $value", logged.contains(value));
            }
        }
    }

    // ---- helpers -----------------------------------------------------------

    private fun echo(harness: SpfnReferenceHarness, session: String, message: String, sequence: Long): SpfnRawResponse
    {
        val operation = SpfnGeneratedOperations.echoSend;
        val body = SpfnCanonicalJson.encode(SpfnEchoRequest(message, sequence).canonicalValue());
        return harness.send(operation, body, harness.proofHeaders(operation, body, sessionId = session));
    }

    private fun listItems(
        harness: SpfnReferenceHarness,
        session: String,
        limit: Long,
        cursor: String? = null
    ): SpfnRawResponse
    {
        val operation = SpfnGeneratedOperations.itemsList;
        val body = SpfnCanonicalJson.encode(SpfnListItemsRequest(limit, cursor).canonicalValue());
        return harness.send(operation, body, harness.proofHeaders(operation, body, sessionId = session));
    }

    private fun SpfnReferenceHarness.controlTokenForTest(): String = server.controlToken
}
