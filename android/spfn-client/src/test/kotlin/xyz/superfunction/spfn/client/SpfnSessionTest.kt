// SPFN Mobile — the session contract.
//
// Every rule the layers above are allowed to rely on is pinned here: one encoding of the
// body, a fresh nonce per proof, one handshake for many concurrent callers, expiry judged
// against the injected clock at an exact instant, and errors that stay the type they were.
// SPFNSessionTests.swift is the counterpart and uses corresponding case names.

package xyz.superfunction.spfn.client

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
import xyz.superfunction.spfn.auth.SpfnClientProof
import xyz.superfunction.spfn.auth.SpfnProofInput
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import java.io.PrintWriter
import java.io.StringWriter

class SpfnSessionTest
{
    private val baseUrl = "https://example.invalid"

    /**
     * A fresh software keypair per call. These tests assert session behaviour, not
     * signature bytes — the one test that verifies a proof keeps its provider so it can
     * verify against that provider's own public key.
     */
    private fun keyProvider(clientId: String = SessionFixtureValues.CLIENT_ID): SpfnSoftwareKeyProvider =
        SpfnSoftwareKeyProvider.generate(clientId = clientId, keyId = SessionFixtureValues.KEY_ID)

    private fun session(
        transport: ScriptedTransport,
        clock: FakeClock,
        nonces: List<String>,
        clientId: String = SessionFixtureValues.CLIENT_ID,
        keyProvider: SpfnSoftwareKeyProvider = this.keyProvider(clientId)
    ): SpfnSession = SpfnSession(
        transport = transport,
        keyProvider = keyProvider,
        baseUrl = baseUrl,
        clock = clock,
        nonceGenerator = ScriptedNonceGenerator(nonces)
    )

    private fun handshakeAnswer(expiringAt: Long = SessionFixtureValues.EXPIRES_AT_MILLIS): ScriptedTransport.Outcome =
        ScriptedTransport.Outcome.Answer(jsonResponse(200, SessionFixtureValues.handshakeResponse(expiringAt)))

    // ---- the handshake request ---------------------------------------------

    @Test
    fun handshakeSendsOneRequestToTheContractPath() = runBlocking {
        val transport = ScriptedTransport(listOf(handshakeAnswer()))
        val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), listOf("nonce-000000000001"))

        val opened = subject.handshake()

        val sent = transport.received.single()
        assertEquals(1, transport.callCount)
        assertEquals("POST", sent.method)
        assertEquals("https://example.invalid/v1/auth/client-proof/handshake", sent.url)
        assertEquals(SessionFixtureValues.SESSION_ID, opened.sessionId)
        assertEquals(SessionFixtureValues.EXPIRES_AT_MILLIS, opened.expiresAtMillis)
    }

    @Test
    fun handshakeCarriesNoSessionHeader() = runBlocking {
        val transport = ScriptedTransport(listOf(handshakeAnswer()))
        val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), listOf("nonce-000000000001"))

        subject.handshake()

        assertFalse(transport.received.single().headers.any { it.first == SpfnWireHeaders.SESSION })
    }

    /**
     * The value the proof was taken over and the value that was sent have to be the same
     * bytes. Recomputing the proof from the body the transport actually received is the
     * only check that catches a second, independent encoding.
     */
    @Test
    fun proofIsTakenOverTheExactBodyThatWasSent() = runBlocking {
        val transport = ScriptedTransport(listOf(handshakeAnswer()))
        val provider = keyProvider()
        val subject = session(
            transport,
            FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS),
            listOf("nonce-000000000001"),
            keyProvider = provider
        )

        subject.handshake()

        val sent = transport.received.single()
        val input = SpfnProofInput.forRequest(
            method = "POST",
            path = "/v1/auth/client-proof/handshake",
            clientId = SessionFixtureValues.CLIENT_ID,
            keyId = SessionFixtureValues.KEY_ID,
            nonce = "nonce-000000000001",
            issuedAtMillis = SessionFixtureValues.ISSUED_AT_MILLIS,
            canonicalBody = sent.body
        )

        // The proof cannot be recomputed for byte equality — the signer draws a random
        // nonce — so it is verified over an input rebuilt from the bytes the transport
        // actually received, which is still the check that catches a second encoding.
        SpfnClientProof.verify(
            requireNotNull(sent.headers.toMap()[SpfnWireHeaders.PROOF]),
            input,
            provider.publicKeySpkiDer
        )
    }

    @Test
    fun everyProofCarriesAFreshNonce() = runBlocking {
        val transport = ScriptedTransport(listOf(handshakeAnswer()))
        // No script: the generator falls back to distinct values, and a session that
        // cached one nonce would still repeat itself.
        val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), emptyList())

        val first = subject.proofHeaders(SpfnGeneratedOperations.authClientProofHandshake, null).toMap()
        val second = subject.proofHeaders(SpfnGeneratedOperations.authClientProofHandshake, null).toMap()

        assertNotEquals(first[SpfnWireHeaders.NONCE], second[SpfnWireHeaders.NONCE])
        assertEquals("the handshake operation requires no session", 0, transport.callCount)
    }

    @Test
    fun theRealNonceGeneratorDoesNotRepeat()
    {
        val generator = SpfnRandomNonceGenerator();
        val seen = mutableSetOf<String>();
        repeat(256) {
            val nonce = generator.nextNonce();
            assertEquals(32, nonce.length);
            assertTrue(nonce.all { it in "0123456789abcdef" });
            seen.add(nonce);
        }
        assertEquals(256, seen.size);
    }

    // ---- ensureSession -----------------------------------------------------

    @Test
    fun validSessionCostsNoNetworkCall() = runBlocking {
        val transport = ScriptedTransport(listOf(handshakeAnswer()))
        val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), listOf("n1", "n2"))

        subject.ensureSession()
        subject.ensureSession()
        subject.ensureSession()

        assertEquals(1, transport.callCount)
    }

    @Test
    fun expiredSessionIsReopenedExactlyOnce() = runBlocking {
        val renewed = SessionFixtureValues.EXPIRES_AT_MILLIS + 300_000
        val transport = ScriptedTransport(listOf(handshakeAnswer(), handshakeAnswer(renewed)))
        val clock = FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS)
        val subject = session(transport, clock, listOf("n1", "n2"))

        subject.ensureSession()
        clock.set(SessionFixtureValues.EXPIRES_AT_MILLIS)
        val reopened = subject.ensureSession()
        subject.ensureSession()

        assertEquals(renewed, reopened.expiresAtMillis)
        assertEquals("one handshake to open, one to reopen, and no more", 2, transport.callCount)
    }

    /**
     * The boundary itself, in both directions. A session is usable strictly before its
     * expiry instant, so `now == expiresAtMillis` already counts as expired.
     */
    @Test
    fun expiryBoundaryIsExclusive() = runBlocking {
        val transport = ScriptedTransport(
            listOf(handshakeAnswer(), handshakeAnswer(SessionFixtureValues.EXPIRES_AT_MILLIS + 300_000))
        )
        val clock = FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS)
        val subject = session(transport, clock, listOf("n1", "n2"))

        subject.ensureSession()

        clock.set(SessionFixtureValues.EXPIRES_AT_MILLIS - 1)
        subject.ensureSession()
        assertEquals("one millisecond before expiry the session is still usable", 1, transport.callCount)

        clock.set(SessionFixtureValues.EXPIRES_AT_MILLIS)
        subject.ensureSession()
        assertEquals("at the expiry instant the session is gone", 2, transport.callCount)
    }

    @Test
    fun concurrentCallersShareOneHandshake() = runBlocking {
        val transport = ScriptedTransport(listOf(handshakeAnswer()), holdMillis = 40)
        val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), emptyList())

        val opened = mutableListOf<SpfnSessionState>()
        coroutineScope {
            repeat(16) {
                launch {
                    val state = subject.ensureSession();
                    synchronized(opened) { opened.add(state) };
                }
            }
        }

        assertEquals(16, opened.size)
        assertEquals(1, opened.map { it.sessionId }.toSet().size)
        assertEquals("sixteen callers must not open sixteen sessions", 1, transport.callCount)
    }

    @Test
    fun invalidateForcesTheNextCallToHandshakeAgain() = runBlocking {
        val transport = ScriptedTransport(listOf(handshakeAnswer(), handshakeAnswer()))
        val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), listOf("n1", "n2"))

        subject.ensureSession()
        subject.invalidate()
        val after = subject.currentState()
        subject.ensureSession()

        assertNull(after)
        assertEquals(2, transport.callCount)
    }

    // ---- header assembly ---------------------------------------------------

    @Test
    fun sessionOperationCarriesTheSessionHeaderLast() = runBlocking {
        val transport = ScriptedTransport(listOf(handshakeAnswer()))
        val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), listOf("n1", "n2"))

        val headers = subject.proofHeaders(
            SpfnGeneratedOperations.echoSend,
            """{"message":"hello","sequence":7}""".toByteArray(Charsets.UTF_8)
        )

        assertEquals(SpfnWireHeaders.SESSION to SessionFixtureValues.SESSION_ID, headers.last())
        assertEquals(SpfnWireHeaders.CONTENT_TYPE, headers.first().first)
    }

    @Test
    fun aBodylessRequestCarriesNoContentType() = runBlocking {
        val transport = ScriptedTransport(listOf(handshakeAnswer()))
        val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), listOf("n1"))

        val headers = subject.proofHeaders(SpfnGeneratedOperations.authClientProofHandshake, null)

        assertFalse(headers.any { it.first == SpfnWireHeaders.CONTENT_TYPE })
        assertEquals(SpfnWireHeaders.PROFILE, headers.first().first)
    }

    @Test
    fun noHeaderNameIsAssembledTwice() = runBlocking {
        val transport = ScriptedTransport(listOf(handshakeAnswer()))
        val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), listOf("n1", "n2"))

        val headers = subject.proofHeaders(
            SpfnGeneratedOperations.itemsList,
            """{"limit":25}""".toByteArray(Charsets.UTF_8)
        )

        val names = headers.map { it.first.lowercase() }
        assertEquals("the transport refuses a repeated header name", names.size, names.toSet().size)
    }

    // ---- failures keep their type ------------------------------------------

    @Test
    fun aRejectedHandshakeSurfacesTheServerEnvelope() = runBlocking {
        val body = """{"error":{"code":"PROOF_INVALID","message":"test vector for PROOF_INVALID","requestId":"req-proof-invalid"}}"""
        val transport = ScriptedTransport(listOf(ScriptedTransport.Outcome.Answer(jsonResponse(401, body))))
        val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), listOf("n1"))

        val failure = assertThrowsSession { subject.handshake() }

        assertTrue(failure is SpfnSessionError.HandshakeRejected)
        assertEquals("PROOF_INVALID", (failure as SpfnSessionError.HandshakeRejected).envelope.code)
        assertEquals("req-proof-invalid", failure.envelope.requestId)
        assertNull(subject.currentState())
    }

    /**
     * A server writes `message` and `requestId`, so it can put a session identifier in
     * either and this SDK would be the one printing it. A `Throwable` publishes its
     * message through `toString` and through every stack trace, so all of those paths
     * are checked here, on both the failure and the envelope it carries.
     */
    @Test
    fun aRejectedHandshakeNeverPrintsTheServerEnvelope() = runBlocking {
        val markers = listOf("MARKER_CODE_7f31", "session-marker-message-a4c2", "req-marker-b8e5")
        val body = """{"error":{"code":"MARKER_CODE_7f31","message":"session-marker-message-a4c2","requestId":"req-marker-b8e5"}}"""
        val transport = ScriptedTransport(listOf(ScriptedTransport.Outcome.Answer(jsonResponse(401, body))))
        val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), listOf("n1"))

        val failure = assertThrowsSession { subject.handshake() } as SpfnSessionError.HandshakeRejected

        // Classification still works: the fields are readable, they just do not print.
        assertEquals("MARKER_CODE_7f31", failure.envelope.code)
        assertEquals("session-marker-message-a4c2", failure.envelope.message)
        assertEquals("req-marker-b8e5", failure.envelope.requestId)

        val stackTrace = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }.toString()
        val renderings = listOf(
            "failure.toString()" to failure.toString(),
            "failure.message" to failure.message.orEmpty(),
            "failure.localizedMessage" to failure.localizedMessage.orEmpty(),
            "failure stack trace" to stackTrace,
            "envelope.toString()" to failure.envelope.toString()
        )

        for ((path, rendered) in renderings)
        {
            for (marker in markers)
            {
                assertFalse("$path exposed server-controlled text", rendered.contains(marker))
            }
        }

        // Marker absence alone would pass even if this failure named its envelope's code,
        // because the envelope redacts itself. These fix what the failure's own message
        // and the envelope's own toString are, so the two layers are provable separately.
        assertEquals("the server refused the handshake", failure.message)
        assertEquals(
            "SpfnErrorEnvelope(code=redacted, message=redacted, requestId=redacted)",
            failure.envelope.toString()
        )
    }

    /**
     * The reason of a malformed response is this module's own constant, so it stays
     * visible — a redaction that hid it would cost debuggability for nothing.
     */
    @Test
    fun aMalformedResponseStillNamesTheShapeItExpected()
    {
        val failure = SpfnSessionError.MalformedResponse("response body is not a HandshakeResponse");

        assertEquals(
            "malformed handshake response: response body is not a HandshakeResponse",
            failure.message
        );
    }

    @Test
    fun aSuccessBodyThatIsNotAHandshakeResponseIsMalformed() = runBlocking {
        for (body in listOf("""{"sessionId":"s"}""", """{"nope":1}""", "not json at all", ""))
        {
            val transport = ScriptedTransport(listOf(ScriptedTransport.Outcome.Answer(jsonResponse(200, body))))
            val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), listOf("n1"))

            val failure = assertThrowsSession { subject.handshake() }

            assertTrue("expected $body to be refused", failure is SpfnSessionError.MalformedResponse)
            assertEquals(
                "response body is not a HandshakeResponse",
                (failure as SpfnSessionError.MalformedResponse).reason
            )
        }
    }

    @Test
    fun aFailureBodyThatIsNotAnEnvelopeIsMalformed() = runBlocking {
        for (body in listOf("""{"error":{"code":"X"}}""", "<html>gateway</html>", ""))
        {
            val transport = ScriptedTransport(listOf(ScriptedTransport.Outcome.Answer(jsonResponse(502, body))))
            val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), listOf("n1"))

            val failure = assertThrowsSession { subject.handshake() }

            assertTrue("expected $body to be refused", failure is SpfnSessionError.MalformedResponse)
            assertEquals(
                "response body is not an SPFN error envelope",
                (failure as SpfnSessionError.MalformedResponse).reason
            )
        }
    }

    /**
     * The reason string is fixed, so a server that answers with a secret-bearing body
     * cannot get that body copied into an error and from there into a log.
     */
    @Test
    fun aMalformedReasonQuotesNothingFromTheBody() = runBlocking {
        val marker = "super-secret-value-9f2a"
        val bodies = listOf(
            // Parses, but is not a HandshakeResponse: the decoder is what refuses it.
            """{"leak":"$marker"}""",
            // Does not parse at all. The parser's own exception quotes the offending key
            // — `duplicate key '<key>'` — so a cause attached here would put the body
            // back into every stack trace by the back door.
            """{"$marker":1,"$marker":2}"""
        )

        for (body in bodies)
        {
            val transport = ScriptedTransport(listOf(ScriptedTransport.Outcome.Answer(jsonResponse(200, body))))
            val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), listOf("n1"))

            val failure = assertThrowsSession { subject.handshake() }
            val stackTrace = StringWriter().also { failure.printStackTrace(PrintWriter(it)) }.toString()

            assertNull("a cause would republish the body", failure.cause)
            assertFalse(failure.toString().contains(marker))
            assertFalse(failure.toString().contains("leak"))
            assertFalse("the stack trace exposed the body", stackTrace.contains(marker))
        }
    }

    @Test
    fun aControlCharacterInAProofFieldStaysAnAuthFailure() = runBlocking {
        val transport = ScriptedTransport(listOf(handshakeAnswer()))
        val subject = session(
            transport,
            FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS),
            listOf("n1"),
            clientId = "client\n-injected"
        )

        val failure = try
        {
            subject.handshake();
            null;
        }
        catch (thrown: SpfnAuthException)
        {
            thrown;
        }

        assertEquals("PROOF_INPUT_INVALID", failure?.code)
        assertEquals("nothing may be sent once the proof input is invalid", 0, transport.callCount)
    }

    @Test
    fun aTransportFailureStaysATransportError() = runBlocking {
        val transport = ScriptedTransport(listOf(ScriptedTransport.Outcome.Failure(SpfnTransportError.TimedOut())))
        val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), listOf("n1"))

        val failure = try
        {
            subject.ensureSession();
            null;
        }
        catch (thrown: SpfnTransportError)
        {
            thrown;
        }

        assertTrue(failure is SpfnTransportError.TimedOut)
    }

    /**
     * A failed handshake must not leave the in-flight slot occupied, or every later
     * caller would wait forever on a result that never arrives.
     */
    @Test
    fun aFailedHandshakeDoesNotStrandLaterCallers() = runBlocking {
        val transport = ScriptedTransport(
            listOf(ScriptedTransport.Outcome.Failure(SpfnTransportError.TimedOut()), handshakeAnswer())
        )
        val subject = session(transport, FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), listOf("n1", "n2"))

        try
        {
            subject.ensureSession();
        }
        catch (_: SpfnTransportError)
        {
            // expected; the point is what the next caller sees
        }
        val opened = subject.ensureSession()

        assertEquals(SessionFixtureValues.SESSION_ID, opened.sessionId)
        assertEquals(2, transport.callCount)
    }

    // ---- nothing secret reaches a toString ---------------------------------

    @Test
    fun toStringCarriesNoKeyAndNoSessionIdentifier()
    {
        val provider = keyProvider();
        // The private key has no one canonical spelling, so the assertion is the
        // contract itself: the only value-bearing fields printed are the two public
        // identifiers, and the key slot prints the redaction marker.
        assertEquals(
            "SpfnSoftwareKeyProvider(clientId=${provider.clientId}, keyId=${provider.keyId}, privateKey=redacted)",
            provider.toString()
        );

        val state = SpfnSessionState(SessionFixtureValues.SESSION_ID, SessionFixtureValues.EXPIRES_AT_MILLIS);
        assertFalse(state.toString().contains(SessionFixtureValues.SESSION_ID));
    }

    private suspend fun assertThrowsSession(body: suspend () -> Unit): SpfnSessionError =
        try
        {
            body();
            throw AssertionError("expected a session failure");
        }
        catch (thrown: SpfnSessionError)
        {
            thrown;
        }
}
