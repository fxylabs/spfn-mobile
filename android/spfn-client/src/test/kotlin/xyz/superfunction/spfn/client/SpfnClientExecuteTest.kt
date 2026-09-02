// SPFN Mobile — the execute contract.
//
// Every rule a caller is allowed to rely on is pinned here, and each is written so that
// removing the code that implements it fails a named case rather than degrading quietly:
//
//   - one path: every operation that carries a session goes through `execute`, and the
//     handshake is refused there rather than sent;
//   - the bytes a request puts on the wire are the ones Contracts/fixtures/request/wire.json
//     records, assembled by the same session the previous change set pinned;
//   - a refusal is classified on the code the contract declares, never on the status;
//   - an auth refusal buys exactly one re-handshake and one re-send, with a new nonce and a
//     new proof over the same body;
//   - nothing else buys a retry, and neither does a second auth refusal;
//   - concurrent calls meeting one revocation share one re-handshake;
//   - no default output path prints anything the server wrote.
//
// SPFNClientExecuteTests.swift is the counterpart and uses corresponding case names.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.auth.SpfnAuthException
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorSurface
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnHandshakeRequest
import java.io.PrintWriter
import java.io.StringWriter

class SpfnClientExecuteTest
{
    private val baseUrl = "https://example.invalid"

    private fun session(
        transport: SpfnTransport,
        clock: FakeClock = FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS),
        nonces: List<String> = emptyList(),
        clientId: String = SessionFixtureValues.CLIENT_ID
    ): SpfnSession = SpfnSession(
        transport = transport,
        keyProvider = ExecuteFixtures.syntheticProvider(clientId),
        baseUrl = baseUrl,
        clock = clock,
        nonceGenerator = ScriptedNonceGenerator(nonces)
    )

    private fun client(
        transport: SpfnTransport,
        clock: FakeClock = FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS),
        nonces: List<String> = emptyList(),
        clientId: String = SessionFixtureValues.CLIENT_ID,
        timeoutMillis: Long = 15_000
    ): SpfnClient = SpfnClient(
        transport = transport,
        session = session(transport, clock, nonces, clientId),
        timeoutMillis = timeoutMillis
    )

    private fun answer(text: String, statusCode: Int = 200): ScriptedTransport.Outcome =
        ScriptedTransport.Outcome.Answer(jsonResponse(statusCode, text))

    private fun handshakeAnswer(): ScriptedTransport.Outcome =
        answer(SessionFixtureValues.HANDSHAKE_RESPONSE_BODY)

    private fun header(name: String, request: SpfnTransportRequest): String? =
        request.headers.firstOrNull { it.first == name }?.second

    // ---- one path ----------------------------------------------------------

    @Test
    fun everyOperationWithASessionGoesThroughTheSamePath() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                handshakeAnswer(),
                answer(ExecuteFixtures.ECHO_RESPONSE_BODY),
                answer(ExecuteFixtures.LIST_RESPONSE_BODY)
            )
        );
        val subject = client(transport);

        val echoed = subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST);
        val listed = subject.execute(ExecuteCalls.LIST, ExecuteFixtures.LIST_REQUEST);

        assertEquals(ExecuteFixtures.ECHO_RESPONSE, echoed);
        assertEquals(ExecuteFixtures.LIST_RESPONSE, listed);
        assertEquals(
            listOf(
                baseUrl + SpfnGeneratedOperations.authClientProofHandshake.path,
                baseUrl + SpfnGeneratedOperations.echoSend.path,
                baseUrl + SpfnGeneratedOperations.itemsList.path
            ),
            transport.received.map { it.url }
        );
    }

    /**
     * The handshake is what opens the session every other operation presents, so sending it
     * through here would send it without the bookkeeping that gives it its point.
     */
    @Test
    fun theHandshakeOperationIsRefusedRatherThanSent() = runBlocking {
        val transport = ScriptedTransport(emptyList());
        val subject = client(transport);

        val thrown = failureOf {
            subject.execute(
                ExecuteCalls.HANDSHAKE,
                SpfnHandshakeRequest(
                    clientId = SessionFixtureValues.CLIENT_ID,
                    keyId = SessionFixtureValues.KEY_ID,
                    nonce = "nonce-000000000001",
                    issuedAtMillis = SessionFixtureValues.ISSUED_AT_MILLIS
                )
            );
        };

        assertTrue("got $thrown", thrown is SpfnClientError.UnsupportedOperation);
        assertEquals(
            SpfnGeneratedOperations.authClientProofHandshake.id,
            (thrown as SpfnClientError.UnsupportedOperation).operationId
        );
        assertEquals("a refused operation costs no network call", 0, transport.callCount);
    }

    // ---- the unproven class ------------------------------------------------

    /**
     * K1/K2. An unproven operation is sent with the content type alone: no proof, no
     * identity, no nonce and no session header — and no handshake happens first,
     * because the operation exists to run before any key does.
     */
    @Test
    fun anUnprovenOperationCarriesNoProofIdentityNonceOrSessionHeader() = runBlocking {
        val transport = ScriptedTransport(listOf(answer(ExecuteFixtures.REGISTER_RESPONSE_BODY)));
        val subject = client(transport);

        val registered = subject.execute(ExecuteCalls.REGISTER, ExecuteFixtures.REGISTER_REQUEST);

        assertEquals(ExecuteFixtures.REGISTER_RESPONSE, registered);
        assertEquals("no handshake preceded the unproven request", 1, transport.callCount);
        val sent = transport.received.first();
        assertEquals("POST", sent.method);
        assertEquals(baseUrl + SpfnGeneratedOperations.authEnrollRegister.path, sent.url);
        assertEquals(
            "an unproven request carries the content type and the identity, and no proof",
            listOf(SpfnWireHeaders.CONTENT_TYPE to SpfnWireHeaders.REQUEST_CONTENT_TYPE)
                + SpfnClientIdentity.headers,
            sent.headers
        );
        assertEquals(
            SpfnCanonicalJson.encode(ExecuteFixtures.REGISTER_REQUEST.canonicalValue()).toString(Charsets.UTF_8),
            sent.body?.toString(Charsets.UTF_8)
        );
    }

    /**
     * K2. The session is not consulted, not opened and not stored around an unproven
     * call — the request goes out immediately with no state on either side of it.
     */
    @Test
    fun anUnprovenOperationTouchesNoSessionState() = runBlocking {
        val transport = ScriptedTransport(listOf(answer(ExecuteFixtures.REGISTER_RESPONSE_BODY)));
        val shared = session(transport);
        val subject = SpfnClient(transport = transport, session = shared);

        subject.execute(ExecuteCalls.REGISTER, ExecuteFixtures.REGISTER_REQUEST);

        assertNull("an unproven call opened a session it has no use for", shared.currentState());
        assertEquals(1, transport.callCount);
    }

    /**
     * The unproven class owns no retry: the one retry `execute` has exists to replace
     * a stale session, and an unproven request never presented one.
     */
    @Test
    fun anUnprovenOperationIsNotRetriedOnAnAuthRefusal() = runBlocking {
        val transport = ScriptedTransport(listOf(answer(ExecuteFixtures.errorEnvelope("PROOF_INVALID"), 401)));
        val subject = client(transport);

        val thrown = failureOf { subject.execute(ExecuteCalls.REGISTER, ExecuteFixtures.REGISTER_REQUEST) };

        assertTrue("got $thrown", thrown is SpfnClientError.Auth);
        assertEquals(SpfnGeneratedErrorCode.PROOF_INVALID, (thrown as SpfnClientError.Auth).failure.code);
        assertEquals("no re-handshake and no re-send for a session nobody presented", 1, transport.callCount);
    }

    /**
     * K3. A proven operation that requires no session — the rotation operation — still
     * carries every proof header, and never a session header or a handshake.
     */
    @Test
    fun aProvenSessionFreeOperationCarriesProofHeadersAndNoSession() = runBlocking {
        val transport = ScriptedTransport(listOf(answer(ExecuteFixtures.ROTATE_RESPONSE_BODY)));
        val subject = client(transport);

        val rotated = subject.execute(ExecuteCalls.ROTATE, ExecuteFixtures.ROTATE_REQUEST);

        assertEquals(ExecuteFixtures.ROTATE_RESPONSE, rotated);
        assertEquals("no handshake: the proof alone authenticates this operation", 1, transport.callCount);
        val sent = transport.received.first();
        assertEquals(baseUrl + SpfnGeneratedOperations.authKeysRotate.path, sent.url);
        assertEquals(
            listOf(
                SpfnWireHeaders.CONTENT_TYPE,
                SpfnWireHeaders.PROFILE,
                SpfnWireHeaders.CLIENT_ID,
                SpfnWireHeaders.KEY_ID,
                SpfnWireHeaders.NONCE,
                SpfnWireHeaders.ISSUED_AT_MILLIS,
                SpfnWireHeaders.PROOF
            ) + SpfnClientIdentity.headers.map { it.first },
            sent.headers.map { it.first }
        );
    }

    /**
     * K4. An operation naming an auth class outside the generated enum is refused
     * before anything is sent. Fail-closed: unknown is never downgraded to unproven.
     */
    @Test
    fun anOperationNamingAnUndeclaredAuthClassIsRefusedUnsent() = runBlocking {
        val transport = ScriptedTransport(emptyList());
        val subject = client(transport);

        val thrown = failureOf { subject.execute(ExecuteCalls.UNDECLARED, ExecuteFixtures.ECHO_REQUEST) };

        assertTrue("got $thrown", thrown is SpfnClientError.UndeclaredAuthClass);
        assertEquals("mysteryV9", (thrown as SpfnClientError.UndeclaredAuthClass).authProfile);
        assertEquals("a refused auth class costs no network call", 0, transport.callCount);
    }

    // ---- the bytes on the wire ---------------------------------------------

    @Test
    fun anExecutedRequestMatchesTheWireVector() = runBlocking {
        val vector = WireFixtures.vector("echo-with-session");
        val expected = vector.headerPairs("headers");
        val byName = expected.toMap();
        val openingNonce = WireFixtures.vector("handshake").headerPairs("headers")
            .toMap()
            .getValue(SpfnWireHeaders.NONCE);

        val transport = ScriptedTransport(listOf(handshakeAnswer(), answer(ExecuteFixtures.ECHO_RESPONSE_BODY)));
        val subject = client(
            transport,
            clock = FakeClock(byName.getValue(SpfnWireHeaders.ISSUED_AT_MILLIS).toLong()),
            nonces = listOf(openingNonce, byName.getValue(SpfnWireHeaders.NONCE))
        );

        subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST);

        val sent = transport.received.last();
        assertEquals(vector.text("method"), sent.method);
        assertEquals(baseUrl + vector.text("path"), sent.url);
        assertHeadersMatchWireVector(sent.headers, expected, vector);
        assertEquals(vector.text("canonicalBody"), sent.body?.toString(Charsets.UTF_8));
    }

    /**
     * `items.list` has no recorded vector, so what is pinned for it is the rule rather than
     * a byte string: the body is the canonical encoding of the request value and the headers
     * are the ones the wire mapping names, in the order it fixes.
     */
    @Test
    fun anOperationWithoutAVectorStillCarriesTheContractShape() = runBlocking {
        val transport = ScriptedTransport(listOf(handshakeAnswer(), answer(ExecuteFixtures.LIST_RESPONSE_BODY)));
        val subject = client(transport);

        subject.execute(ExecuteCalls.LIST, ExecuteFixtures.LIST_REQUEST);

        val sent = transport.received.last();
        assertEquals(
            SpfnCanonicalJson.encode(ExecuteFixtures.LIST_REQUEST.canonicalValue()).toString(Charsets.UTF_8),
            sent.body?.toString(Charsets.UTF_8)
        );
        assertEquals(
            listOf(
                SpfnWireHeaders.CONTENT_TYPE,
                SpfnWireHeaders.PROFILE,
                SpfnWireHeaders.CLIENT_ID,
                SpfnWireHeaders.KEY_ID,
                SpfnWireHeaders.NONCE,
                SpfnWireHeaders.ISSUED_AT_MILLIS,
                SpfnWireHeaders.PROOF,
                SpfnWireHeaders.SESSION
            ) + SpfnClientIdentity.headers.map { it.first },
            sent.headers.map { it.first }
        );
    }

    // ---- reading the answer ------------------------------------------------

    @Test
    fun a2xxThatIsNotTheDeclaredResponseIsADecodingFailure() = runBlocking {
        val thrown = echoFailing(answer("{\"message\":\"hello\"}"));
        assertDecoding(SpfnDecodingFailure.NOT_THE_DECLARED_RESPONSE, thrown);
    }

    @Test
    fun aBodyThatIsNotCanonicalJsonIsADecodingFailure() = runBlocking {
        val thrown = echoFailing(answer("not json at all"));
        assertDecoding(SpfnDecodingFailure.NOT_CANONICAL_JSON, thrown);
    }

    @Test
    fun aContractErrorCodeOutsideTheAuthFamilyIsAServerFailure() = runBlocking {
        val thrown = echoFailing(answer(ExecuteFixtures.errorEnvelope("PROFILE_REJECTED"), statusCode = 400));

        assertTrue("got $thrown", thrown is SpfnClientError.Server);
        assertEquals(
            SpfnServerFailure(
                SpfnGeneratedErrorCode.PROFILE_REJECTED,
                400,
                SpfnErrorEnvelope("PROFILE_REJECTED", "refused", "req-test-0001")
            ),
            (thrown as SpfnClientError.Server).failure
        );
    }

    @Test
    fun anUnknownErrorCodeIsADecodingFailure() = runBlocking {
        val thrown = echoFailing(answer(ExecuteFixtures.errorEnvelope("TEAPOT"), statusCode = 409));
        assertDecoding(SpfnDecodingFailure.UNKNOWN_ERROR_CODE, thrown);
    }

    /**
     * The classification is on the code, not the status. A 401 an intermediary wrote carries
     * no envelope, and reading it as an auth failure would make the client re-handshake
     * against something that never refused a proof.
     */
    @Test
    fun a401WithoutAnEnvelopeIsADecodingFailureAndNotAnAuthFailure() = runBlocking {
        val transport = ScriptedTransport(
            listOf(handshakeAnswer(), answer("{\"detail\":\"gateway says no\"}", statusCode = 401))
        );
        val subject = client(transport);

        val thrown = failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };

        assertDecoding(SpfnDecodingFailure.NOT_AN_ERROR_ENVELOPE, thrown);
        assertEquals("a status alone never provokes a re-handshake", 2, transport.callCount);
    }

    @Test
    fun everyContractErrorCodeIsClassifiedOnPurpose()
    {
        assertEquals(
            listOf("PROOF_INVALID", "PROOF_REPLAYED", "PROOF_EXPIRED", "SESSION_REVOKED"),
            SpfnGeneratedErrorCode.entries.filter { it.isAuthFailure() }.map { it.wireCode }
        );
        assertEquals(
            listOf(
                "PROFILE_REJECTED", "CONTRACT_UNSUPPORTED",
                "ValidationError", "NativeSignInUnsupportedError", "NonceKeyBindingError",
                "InvalidKeyFingerprintError", "UnverifiedEmailLinkError", "InvalidSocialTokenError",
                "AccountDisabledError", "AccountPendingDeletionError", "RegistrationRejectedError",
                "KeyIdAlreadyRegisteredError", "TooManyRequestsError", "Error",
                "DeviceAuthExpiredError", "DeviceAuthDeniedError", "DeviceAuthNotFoundError",
                "DeviceAuthAlreadyHandledError"
            ),
            SpfnGeneratedErrorCode.entries.filterNot { it.isAuthFailure() }.map { it.wireCode }
        );
    }

    /**
     * A re-handshake re-establishes a clientProofV1 session, and the /_auth operations
     * carry no proof and open no session — so no code from that surface can be cleared by
     * one, whatever the list above happens to say today.
     *
     * Stated against the surface rather than against a spelled-out list because the two
     * fail differently: the list catches a code nobody classified, and this catches a code
     * somebody classified wrongly. A rate limit routed into the re-handshake path would
     * re-open a session, resend, and be rate-limited again.
     */
    @Test
    fun noRestSurfaceCodeIsTreatedAsAnAuthFailure()
    {
        val rest = SpfnGeneratedErrorCode.entries.filter { it.surface == SpfnGeneratedErrorSurface.REST };
        assertTrue("the contract declares a rest surface, so this must have something to check", rest.isNotEmpty());

        for (code in rest)
        {
            assertFalse(
                "${code.wireCode} is answered by the /_auth surface, and a re-handshake cannot clear one",
                code.isAuthFailure()
            );
        }
    }

    // ---- the one retry -----------------------------------------------------

    @Test
    fun anAuthRefusalReopensTheSessionAndResendsOnce() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                handshakeAnswer(),
                answer(ExecuteFixtures.errorEnvelope("SESSION_REVOKED"), statusCode = 401),
                handshakeAnswer(),
                answer(ExecuteFixtures.ECHO_RESPONSE_BODY)
            )
        );
        val subject = client(transport);

        val echoed = subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST);

        assertEquals(ExecuteFixtures.ECHO_RESPONSE, echoed);
        assertEquals(
            listOf(
                baseUrl + SpfnGeneratedOperations.authClientProofHandshake.path,
                baseUrl + SpfnGeneratedOperations.echoSend.path,
                baseUrl + SpfnGeneratedOperations.authClientProofHandshake.path,
                baseUrl + SpfnGeneratedOperations.echoSend.path
            ),
            transport.received.map { it.url }
        );
    }

    /**
     * The re-sent request is the same request, proved again. Same bytes, new nonce, new
     * proof — a replayed nonce is one of the things the server refuses requests for.
     */
    @Test
    fun theResentRequestCarriesTheSameBodyUnderAFreshProof() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                handshakeAnswer(),
                answer(ExecuteFixtures.errorEnvelope("PROOF_EXPIRED"), statusCode = 401),
                handshakeAnswer(),
                answer(ExecuteFixtures.ECHO_RESPONSE_BODY)
            )
        );
        val subject = client(transport);

        subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST);

        val first = transport.received[1];
        val second = transport.received[3];

        assertEquals(
            "one encoding of the body, sent twice",
            first.body?.toString(Charsets.UTF_8),
            second.body?.toString(Charsets.UTF_8)
        );
        assertNotEquals(header(SpfnWireHeaders.NONCE, first), header(SpfnWireHeaders.NONCE, second));
        assertNotEquals(header(SpfnWireHeaders.PROOF, first), header(SpfnWireHeaders.PROOF, second));
    }

    @Test
    fun aSecondAuthRefusalSurfacesInsteadOfRetryingAgain() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                handshakeAnswer(),
                answer(ExecuteFixtures.errorEnvelope("PROOF_INVALID"), statusCode = 401),
                handshakeAnswer(),
                answer(ExecuteFixtures.errorEnvelope("PROOF_INVALID"), statusCode = 401)
            )
        );
        val subject = client(transport);

        val thrown = failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };

        assertTrue("got $thrown", thrown is SpfnClientError.Auth);
        assertEquals(SpfnGeneratedErrorCode.PROOF_INVALID, (thrown as SpfnClientError.Auth).failure.code);
        assertEquals(401, thrown.failure.httpStatus);
        assertEquals("one re-handshake and one re-send, and then it stops", 4, transport.callCount);
    }

    /**
     * A handshake that is itself refused is surfaced. Re-opening a session in answer to a
     * refused attempt to open one is the loop this policy exists to not have.
     */
    @Test
    fun aRefusedHandshakeIsSurfacedWithoutAnotherAttempt() = runBlocking {
        val transport = ScriptedTransport(
            listOf(answer(ExecuteFixtures.errorEnvelope("PROOF_INVALID"), statusCode = 401))
        );
        val subject = client(transport);

        val thrown = failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };

        assertTrue("got $thrown", thrown is SpfnClientError.Auth);
        assertEquals(SpfnGeneratedErrorCode.PROOF_INVALID, (thrown as SpfnClientError.Auth).failure.code);
        assertEquals(1, transport.callCount);
    }

    @Test
    fun aMalformedHandshakeAnswerIsADecodingFailure() = runBlocking {
        val transport = ScriptedTransport(listOf(answer("{\"sessionId\":\"only-half-of-it\"}")));
        val subject = client(transport);

        val thrown = failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };

        assertDecoding(SpfnDecodingFailure.NOT_THE_DECLARED_RESPONSE, thrown);
    }

    @Test
    fun aRefusedHandshakeWithoutAnEnvelopeIsADecodingFailure() = runBlocking {
        val transport = ScriptedTransport(listOf(answer("{\"oops\":true}", statusCode = 500)));
        val subject = client(transport);

        val thrown = failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };

        assertDecoding(SpfnDecodingFailure.NOT_AN_ERROR_ENVELOPE, thrown);
    }

    // ---- nothing else is retried -------------------------------------------

    @Test
    fun aTransportFailureIsNotRetried() = runBlocking {
        val transport = ScriptedTransport(
            listOf(handshakeAnswer(), ScriptedTransport.Outcome.Failure(SpfnTransportError.TimedOut()))
        );
        val subject = client(transport);

        val thrown = failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };

        assertTrue("got $thrown", thrown is SpfnClientError.Transport);
        assertTrue((thrown as SpfnClientError.Transport).error is SpfnTransportError.TimedOut);
        assertEquals(2, transport.callCount);
    }

    @Test
    fun aServerFailureIsNotRetried() = runBlocking {
        val transport = ScriptedTransport(
            listOf(handshakeAnswer(), answer(ExecuteFixtures.errorEnvelope("CONTRACT_UNSUPPORTED"), statusCode = 409))
        );
        val subject = client(transport);

        failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };

        assertEquals(2, transport.callCount);
    }

    @Test
    fun aDecodingFailureIsNotRetried() = runBlocking {
        val transport = ScriptedTransport(listOf(handshakeAnswer(), answer("{}")));
        val subject = client(transport);

        failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };

        assertEquals(2, transport.callCount);
    }

    /**
     * An error this path did not produce keeps its own type. A proof that could not be
     * assembled is a client-side fault, and dressing it as one of the four would have it
     * read as something the server did.
     */
    @Test
    fun anErrorThePathDidNotProducePassesThroughUnchanged() = runBlocking {
        val transport = ScriptedTransport(emptyList());
        val subject = client(transport, clientId = "client\u0001test");

        val thrown = failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };

        assertTrue("got $thrown", thrown is SpfnAuthException);
        assertEquals("PROOF_INPUT_INVALID", (thrown as SpfnAuthException).code);
        assertEquals(0, transport.callCount);
    }

    // ---- deadlines and cancellation ----------------------------------------

    @Test
    fun eachAttemptCarriesItsOwnDeadline() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                handshakeAnswer(),
                answer(ExecuteFixtures.errorEnvelope("SESSION_REVOKED"), statusCode = 401),
                handshakeAnswer(),
                answer(ExecuteFixtures.ECHO_RESPONSE_BODY)
            )
        );
        val subject = client(transport, timeoutMillis = 1_234);

        subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST);

        assertEquals(listOf(15_000L, 1_234L, 15_000L, 1_234L), transport.received.map { it.timeoutMillis });
    }

    /**
     * Cancellation that lands after the refusal and before the re-handshake costs no further
     * request, and stays a `CancellationException` so the enclosing scope unwinds as one.
     */
    @Test
    fun cancellationBetweenTheTwoAttemptsSendsNothingFurther() = runBlocking {
        val running = CompletableDeferred<Job>();
        val transport = ScriptedTransport(
            listOf(
                handshakeAnswer(),
                answer(ExecuteFixtures.errorEnvelope("SESSION_REVOKED"), statusCode = 401),
                handshakeAnswer(),
                answer(ExecuteFixtures.ECHO_RESPONSE_BODY)
            ),
            onCall = { call -> if (call == 2) running.await().cancel() }
        );
        val subject = client(transport);

        var thrown: Throwable? = null;
        val job = launch {
            try
            {
                subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST);
            }
            catch (failure: Throwable)
            {
                thrown = failure;
            }
        };
        running.complete(job);
        job.join();

        assertTrue("got $thrown", thrown is CancellationException);
        assertEquals("the re-handshake never happened", 2, transport.callCount);
    }

    // ---- concurrency -------------------------------------------------------

    /**
     * Every call refused on one session discards that session, and only that one. If they
     * discarded whatever the session happened to be by then, the first re-opened session
     * would be thrown away by the second call, which would open another, and so on.
     */
    @Test
    fun concurrentCallsMeetingOneRevocationShareOneReHandshake() = runBlocking {
        val server = RevokingServer(revoked = setOf("session-1"), holdingFirst = 3);
        val shared = session(server);
        val subject = SpfnClient(transport = server, session = shared);

        shared.handshake();

        coroutineScope {
            val calls = (0 until 3).map {
                async { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) }
            };
            for (call in calls)
            {
                assertEquals(ExecuteFixtures.ECHO_RESPONSE, call.await());
            }
        };

        assertEquals("the opening one, and one shared by all three refusals", 2, server.handshakes);
        assertEquals("two handshakes, three refused requests, three re-sent", 8, server.callCount);
    }

    // ---- redaction ---------------------------------------------------------

    /**
     * A `Throwable`'s message is printed by `toString` and by every stack trace, so a
     * refusal that carried server text in either would publish it into any log.
     */
    @Test
    fun noDefaultOutputPathPrintsWhatTheServerWrote()
    {
        val markers = listOf("0e33f", "client-9", "revoked");
        val envelope = SpfnErrorEnvelope(
            code = "SESSION_REVOKED",
            message = "session 0e33f belonging to client-9 was revoked",
            requestId = "req-0e33f"
        );
        val auth = SpfnAuthFailure(SpfnGeneratedErrorCode.SESSION_REVOKED, 401, envelope);
        val server = SpfnServerFailure(SpfnGeneratedErrorCode.PROFILE_REJECTED, 400, envelope);
        val refusals = listOf(
            SpfnClientError.Auth(auth) to auth.toString(),
            SpfnClientError.Server(server) to server.toString()
        );

        for ((refusal, carried) in refusals)
        {
            val trace = StringWriter();
            refusal.printStackTrace(PrintWriter(trace));

            for (rendering in listOf(refusal.toString(), refusal.message ?: "", trace.toString(), carried))
            {
                for (marker in markers)
                {
                    assertFalse("$rendering exposed '$marker'", rendering.contains(marker));
                }
            }
        }
    }

    /**
     * Marker absence alone would pass even if these types printed their envelope, because
     * the envelope redacts itself. Fixing what each type's own rendering is makes the two
     * layers provable separately rather than as one — the same split the session suite makes.
     */
    @Test
    fun aRefusalStillNamesTheCodeAndTheStatus()
    {
        val envelope = SpfnErrorEnvelope("SESSION_REVOKED", "gone", "req-1");

        assertEquals(
            "SpfnAuthFailure(code=SESSION_REVOKED, httpStatus=401, envelope=redacted)",
            SpfnAuthFailure(SpfnGeneratedErrorCode.SESSION_REVOKED, 401, envelope).toString()
        );
        assertEquals(
            "SpfnServerFailure(code=PROFILE_REJECTED, httpStatus=400, envelope=redacted)",
            SpfnServerFailure(SpfnGeneratedErrorCode.PROFILE_REJECTED, 400, envelope).toString()
        );
        assertEquals(
            "the response was not what the contract describes: UNKNOWN_ERROR_CODE",
            SpfnClientError.Decoding(SpfnDecodingFailure.UNKNOWN_ERROR_CODE).message
        );
    }

    // ---- assembly ----------------------------------------------------------

    /** Opens a session, then answers the one request with [response]. */
    private suspend fun echoFailing(response: ScriptedTransport.Outcome): Throwable?
    {
        val transport = ScriptedTransport(listOf(handshakeAnswer(), response));
        val subject = client(transport);
        return failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };
    }

    private suspend fun failureOf(body: suspend () -> Unit): Throwable?
    {
        try
        {
            body();
        }
        catch (failure: Throwable)
        {
            return failure;
        }
        throw AssertionError("expected a failure");
    }

    private fun assertDecoding(expected: SpfnDecodingFailure, thrown: Throwable?)
    {
        assertTrue("got $thrown", thrown is SpfnClientError.Decoding);
        assertEquals(expected, (thrown as SpfnClientError.Decoding).failure);
    }
}
