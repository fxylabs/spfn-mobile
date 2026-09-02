// SPFN Mobile — an operation that declares no response body.
//
// Contract 0.10.0 added `auth.device.deny`, which names no responseType, and stated in
// `restOperations.responseBody` what that means: "An operation that declares no
// responseType answers 204 with an empty body and there is nothing to decode".
//
// Six cells, closed from that sentence before the reader was written and named here one to
// one. Three ask what a bodyless operation accepts, one asks that it still has refusals,
// and two are the regression guard: the operations that do declare a response must not
// have acquired the new branch's tolerance. The expected values come from the contract
// text quoted above, not from what the reader happens to do.
//
// The cells run through `SpfnClient.execute`, not against the reader directly, because
// what the change set owes is that the one reader on this platform enforces the rule for
// every path into it.
//
// SPFNNoResponseOperationTests.swift is the counterpart and uses the same cell names.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnNoResponse
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations

class SpfnNoResponseOperationTest
{
    private val baseUrl = "https://example.invalid"

    /**
     * The descriptor is what the reader consults, so the suite states what the contract
     * says about these two operations before asking what the reader does with them.
     */
    @Test
    fun theGeneratedDescriptorsCarryWhatTheContractDeclares()
    {
        assertFalse(
            "auth.device.deny names no responseType in contract 0.10.0",
            SpfnGeneratedOperations.authDeviceDeny.declaresResponse
        );
        assertTrue(
            "auth.device.approve answers with DeviceAuthInfoResponse",
            SpfnGeneratedOperations.authDeviceApprove.declaresResponse
        );
    }

    // ---- N1 … N4: the operation declares no response -----------------------

    /** N1. 204 with an empty body is the contract's answer, and it succeeds. */
    @Test
    fun n1NoResponseOperationAcceptsNoContentWithAnEmptyBody() = runBlocking {
        val transport = ScriptedTransport(listOf(answer("", statusCode = 204)));

        val answered = client(transport, listOf("nonce-deny"))
            .execute(ExecuteCalls.DENY, ExecuteFixtures.DENY_REQUEST);

        assertEquals(SpfnNoResponse, answered);
        assertEquals(
            "the one request is the deny itself",
            baseUrl + SpfnGeneratedOperations.authDeviceDeny.path,
            transport.received.last().url
        );
    }

    /**
     * N2. 204 carrying a body. There is nothing to decode, so bytes here mean the two ends
     * disagree about the operation — refused, and the failure names the case.
     */
    @Test
    fun n2NoResponseOperationRefusesABodyOnTheNoContent() = runBlocking {
        val thrown = denyFailing(jsonResponse(204, "{}"));

        assertEquals(SpfnDecodingFailure.BODY_ON_NO_RESPONSE_OPERATION, decodingFailure(thrown));
    }

    /**
     * N3. 200 with a body. A 2xx that is not 204 is not the answer the contract describes,
     * and `{}` is not "empty" — accepting it would hide a server answering a different
     * operation than the one that was called.
     */
    @Test
    fun n3NoResponseOperationRefusesATwoHundred() = runBlocking {
        val thrown = denyFailing(jsonResponse(200, "{}"));

        assertEquals(
            SpfnDecodingFailure.NOT_NO_CONTENT_ON_NO_RESPONSE_OPERATION,
            decodingFailure(thrown)
        );
    }

    /**
     * N4. A refusal reaches a bodyless operation exactly as it reaches every other one:
     * the envelope is read and classified on the code it declares.
     */
    @Test
    fun n4NoResponseOperationStillReadsTheErrorEnvelope() = runBlocking {
        val thrown = denyFailing(
            jsonResponse(404, ExecuteFixtures.errorEnvelope(code = "DeviceAuthNotFoundError"))
        );

        assertTrue("expected a server refusal, got $thrown", thrown is SpfnClientError.Server);
        val failure = (thrown as SpfnClientError.Server).failure;
        assertEquals(SpfnGeneratedErrorCode.DeviceAuthNotFoundError, failure.code);
        assertEquals(404, failure.httpStatus);
    }

    // ---- N5, N6: the operation declares a response -------------------------

    /**
     * N5. The regression guard in the direction the new branch could leak into: an
     * operation that declares a response is not allowed to accept 204 with no body just
     * because the reader now knows what that means for a different operation.
     */
    @Test
    fun n5DeclaredResponseOperationStillRefusesAnEmptyNoContent() = runBlocking {
        val thrown = approveFailing(jsonResponse(204, ""));

        assertEquals(
            "an empty body is not the declared response; this is the pre-0.10.0 refusal, unchanged",
            SpfnDecodingFailure.NOT_CANONICAL_JSON,
            decodingFailure(thrown)
        );
    }

    /**
     * N6. And it still reads its declared response, which is the behaviour every operation
     * but one has.
     */
    @Test
    fun n6DeclaredResponseOperationReadsItsDeclaredBody() = runBlocking {
        val transport = ScriptedTransport(listOf(answer(ExecuteFixtures.APPROVE_RESPONSE_BODY)));

        val described = client(transport, listOf("nonce-approve"))
            .execute(ExecuteCalls.APPROVE, ExecuteFixtures.APPROVE_REQUEST);

        assertEquals(ExecuteFixtures.APPROVE_RESPONSE, described);
    }

    // ---- helpers -----------------------------------------------------------

    private fun decodingFailure(thrown: Throwable?): SpfnDecodingFailure
    {
        assertTrue("expected a decoding refusal, got $thrown", thrown is SpfnClientError.Decoding);
        return (thrown as SpfnClientError.Decoding).failure;
    }

    /**
     * The device operations are proven but session-free — `requiresSession` is false for
     * all five, as it is for `auth.keys.rotate` — so no handshake is sent and each script
     * holds exactly one answer. A script that led with a handshake would have that answer
     * consumed by the operation itself, and every cell would be about the wrong response.
     */
    private suspend fun denyFailing(response: SpfnTransportResponse): Throwable?
    {
        val transport = ScriptedTransport(listOf(ScriptedTransport.Outcome.Answer(response)));
        return runCatching {
            client(transport, listOf("nonce-deny"))
                .execute(ExecuteCalls.DENY, ExecuteFixtures.DENY_REQUEST);
        }.exceptionOrNull();
    }

    private suspend fun approveFailing(response: SpfnTransportResponse): Throwable?
    {
        val transport = ScriptedTransport(listOf(ScriptedTransport.Outcome.Answer(response)));
        return runCatching {
            client(transport, listOf("nonce-approve"))
                .execute(ExecuteCalls.APPROVE, ExecuteFixtures.APPROVE_REQUEST);
        }.exceptionOrNull();
    }

    private fun answer(text: String, statusCode: Int = 200): ScriptedTransport.Outcome =
        ScriptedTransport.Outcome.Answer(jsonResponse(statusCode, text))

    private fun client(
        transport: SpfnTransport,
        nonces: List<String> = emptyList()
    ): SpfnClient = SpfnClient(
        transport = transport,
        session = SpfnSession(
            transport = transport,
            keyProvider = ExecuteFixtures.syntheticProvider(SessionFixtureValues.CLIENT_ID),
            baseUrl = baseUrl,
            clock = FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS),
            nonceGenerator = ScriptedNonceGenerator(nonces)
        ),
        timeoutMillis = 15_000
    )
}
