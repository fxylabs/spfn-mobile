// SPFN Mobile — an operation that declares no request body.
//
// The pinned bundle states the rule in `clientProofV1.proofInput.bodySha256`: "the literal
// string of 64 zero characters when an operation has no body", and an operation has no body
// when it names no requestType — `core.time` and `auth.mfa.status`, both GET. The expected
// digest is read from Contracts/fixtures/proof/proof-input.json (`handshake-no-body`), the
// vector the outside implementation derived, rather than restated here.
//
// Five cells. Three send no body: a proven GET, an unproven GET and a DELETE. One is the
// regression guard — a declared body that happens to be empty is still `{}` and is still
// digested. The last sends a GET through OkHttp itself, which refuses any body on a GET, so
// the old `{}` could never have left the device.
//
// SPFNBodylessRequestTests.swift is the counterpart and uses the same cell names.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.superfunction.spfn.auth.SpfnClientProof
import xyz.superfunction.spfn.auth.SpfnProofInput
import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnDigest
import xyz.superfunction.spfn.core.SpfnNoResponse
import xyz.superfunction.spfn.core.SpfnOperation
import xyz.superfunction.spfn.generated.SpfnGeneratedCalls
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import java.util.concurrent.TimeUnit

class SpfnBodylessRequestTest
{
    private val baseUrl = "https://example.invalid"

    /** B1. A proven GET: no bytes, no `content-type`, and a proof over the absent digest. */
    @Test
    fun b1ProvenGetSendsNoBodyAndSignsTheAbsentDigest() = runBlocking {
        val transport = ScriptedTransport(listOf(answer(MFA_STATUS_BODY)));

        client(transport).execute(SpfnGeneratedCalls.authMfaStatus, Unit);

        val sent = transport.received.single();
        assertEquals("GET", sent.method);
        assertNoBody(sent);
        assertProofSigns(sent, SpfnGeneratedOperations.authMfaStatus, absentBodyDigest());
    }

    /** B2. An unproven GET carries no proof, and no body or `content-type` either. */
    @Test
    fun b2UnprovenGetSendsNoBody() = runBlocking {
        val transport = ScriptedTransport(listOf(answer("{\"serverTimeMillis\":1750000000000}")));

        client(transport).execute(SpfnGeneratedCalls.coreTime, Unit);

        val sent = transport.received.single();
        assertEquals("GET", sent.method);
        assertNoBody(sent);
    }

    /** B3. The rule is the operation's missing request type, not the method: DELETE too. */
    @Test
    fun b3DeleteWithoutARequestTypeSendsNoBody() = runBlocking {
        val transport = ScriptedTransport(listOf(answer("", statusCode = 204)));

        client(transport).execute(BODYLESS_DELETE, Unit);

        val sent = transport.received.single();
        assertNoBody(sent);
        assertProofSigns(sent, BODYLESS_DELETE.operation, absentBodyDigest());
    }

    /** B4. A declared body that happens to be empty is not "no body": `{}` goes out and is digested. */
    @Test
    fun b4DeclaredEmptyBodyStillSendsAnEmptyObject() = runBlocking {
        val transport = ScriptedTransport(listOf(answer("", statusCode = 204)));

        client(transport).execute(EMPTY_OBJECT_POST, Unit);

        val sent = transport.received.single();
        assertEquals("{}", sent.body?.toString(Charsets.UTF_8));
        assertEquals(SpfnWireHeaders.REQUEST_CONTENT_TYPE, header(sent, SpfnWireHeaders.CONTENT_TYPE));
        assertProofSigns(sent, EMPTY_OBJECT_POST.operation, SpfnDigest.sha256Hex("{}"));
    }

    /**
     * B5. Through OkHttp, which refuses a body on a GET
     * (`SpfnOkHttpTransportTest.aBodyOnAMethodThatForbidsOneSurfacesAsConnectivity`). The
     * server records what actually crossed the socket.
     */
    @Test
    fun b5GetThroughOkHttpReachesTheServerWithoutABody() = runBlocking {
        MockWebServer().use { server ->
            server.start();
            server.enqueue(okHttpAnswer(MFA_STATUS_BODY));
            val transport = SpfnOkHttpTransport();
            val baseUrl = server.url("/").toString();

            client(transport, baseUrl).execute(SpfnGeneratedCalls.authMfaStatus, Unit);

            val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!;
            assertEquals("GET", recorded.method);
            assertEquals(0L, recorded.bodySize);
            assertNull(recorded.headers[SpfnWireHeaders.CONTENT_TYPE]);
        };
    }

    // ---- helpers -----------------------------------------------------------

    private fun assertNoBody(sent: SpfnTransportRequest)
    {
        assertNull("a bodyless operation sent body bytes", sent.body);
        assertFalse(
            "a bodyless operation sent a content-type",
            sent.headers.any { it.first.equals(SpfnWireHeaders.CONTENT_TYPE, ignoreCase = true) }
        );
    }

    /** The signer draws a random nonce, so the proof is verified rather than compared. */
    private fun assertProofSigns(sent: SpfnTransportRequest, operation: SpfnOperation, bodySha256: String)
    {
        val input = SpfnProofInput(
            method = operation.method,
            path = operation.path,
            clientId = header(sent, SpfnWireHeaders.CLIENT_ID),
            keyId = header(sent, SpfnWireHeaders.KEY_ID),
            nonce = header(sent, SpfnWireHeaders.NONCE),
            issuedAtMillis = header(sent, SpfnWireHeaders.ISSUED_AT_MILLIS).toLong(),
            bodySha256 = bodySha256
        );
        SpfnClientProof.verify(header(sent, SpfnWireHeaders.PROOF), input, ExecuteFixtures.fixturePublicKeySpkiDer());
    }

    private fun header(sent: SpfnTransportRequest, name: String): String =
        sent.headers.single { it.first == name }.second

    /** The digest the outside implementation signed for an operation with no body. */
    private fun absentBodyDigest(): String =
        WireFixtures.load("Contracts/fixtures/proof/proof-input.json").members()
            .list("vectors").map { it.members() }
            .single { it.text("name") == "handshake-no-body" }
            .obj("input").text("bodySha256")

    private fun answer(text: String, statusCode: Int = 200): ScriptedTransport.Outcome =
        ScriptedTransport.Outcome.Answer(jsonResponse(statusCode, text))

    private fun okHttpAnswer(text: String): MockResponse
    {
        val builder = MockResponse.Builder().code(200).body(text);
        for ((name, value) in jsonResponse(200, text).headers)
        {
            builder.addHeader(name, value);
        }
        return builder.build();
    }

    private fun client(transport: SpfnTransport, baseUrl: String = this.baseUrl): SpfnClient = SpfnClient(
        transport = transport,
        session = SpfnSession(
            transport = transport,
            keyProvider = ExecuteFixtures.syntheticProvider(SessionFixtureValues.CLIENT_ID),
            baseUrl = baseUrl,
            clock = FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS),
            nonceGenerator = ScriptedNonceGenerator(emptyList())
        ),
        timeoutMillis = 15_000
    )

    private companion object
    {
        const val MFA_STATUS_BODY = "{\"enrolled\":false,\"methods\":[],\"recoveryCodesRemaining\":0}"

        /** Hand-built: the contract has no bodyless DELETE yet, and an app contract may. */
        val BODYLESS_DELETE: SpfnCall<Unit, SpfnNoResponse> = SpfnCall.noResponse(
            operation = SpfnOperation(
                id = "test.bodylessDelete",
                method = "DELETE",
                path = "/v1/things/t1",
                authProfile = "clientProofV1",
                requiresSession = false,
                declaresResponse = false
            ),
            encode = { _ -> null }
        )

        /** A POST whose declared body is an object with no members. */
        val EMPTY_OBJECT_POST: SpfnCall<Unit, SpfnNoResponse> = SpfnCall.noResponse(
            operation = SpfnOperation(
                id = "test.emptyObjectPost",
                method = "POST",
                path = "/v1/things",
                authProfile = "clientProofV1",
                requiresSession = false,
                declaresResponse = false
            ),
            encode = { _ -> SpfnCanonicalValue.Obj(emptyMap()) }
        )
    }
}
