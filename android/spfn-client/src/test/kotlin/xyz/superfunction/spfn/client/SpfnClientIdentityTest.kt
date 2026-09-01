// SPFN Mobile — the client says who it is, and reads what the server says back.
//
// Counterpart of Tests/SPFNClientTests/SPFNClientIdentityTests.swift, cell for cell. Ten
// cells closed before the code was written: three for the paths a request leaves by,
// seven for what a response's announcement earns. S3 exists because there are three exits
// and only two go through SpfnClient.execute — a change covering execute alone opens every
// session anonymously, and S3 is the cell that fails when it does.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.generated.SpfnGeneratedContract
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations

class SpfnClientIdentityTest
{
    private val baseUrl = "https://example.invalid";

    // `appVersion` is process-wide, so a cell that sets it puts it back.
    @After
    fun clearAppVersion()
    {
        SpfnClientIdentity.appVersion = null;
    }

    // ---- S: what a request says about itself -------------------------------

    /** S1. The unproven class — enrolment and login — carries the identity. */
    @Test
    fun s1UnprovenRequestCarriesTheIdentity() = runBlocking {
        val transport = ScriptedTransport(listOf(answer(ExecuteFixtures.REGISTER_RESPONSE_BODY)));

        client(transport).execute(ExecuteCalls.REGISTER, ExecuteFixtures.REGISTER_REQUEST);

        assertCarriesIdentity(transport.received.first());
    }

    /** S2. A proven operation carries it alongside the proof headers. */
    @Test
    fun s2ProvenRequestCarriesTheIdentity() = runBlocking {
        val transport = ScriptedTransport(
            listOf(handshakeAnswer(), answer(ExecuteFixtures.ECHO_RESPONSE_BODY))
        );

        client(transport, nonces = listOf("nonce-open", "nonce-echo"))
            .execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST);

        assertCarriesIdentity(transport.received.last());
    }

    /**
     * S3. The handshake leaves through the session, not through execute. This is the cell
     * that fails if the identity is added only where execute sends.
     */
    @Test
    fun s3HandshakeCarriesTheIdentity() = runBlocking {
        val transport = ScriptedTransport(
            listOf(handshakeAnswer(), answer(ExecuteFixtures.ECHO_RESPONSE_BODY))
        );

        client(transport, nonces = listOf("nonce-open", "nonce-echo"))
            .execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST);

        assertEquals(
            "the first request is the handshake",
            baseUrl + SpfnGeneratedOperations.authClientProofHandshake.path,
            transport.received.first().url
        );
        assertCarriesIdentity(transport.received.first());
    }

    // ---- R: what a response's announcement earns ---------------------------

    /** R1. No announcement is refused. */
    @Test
    fun r1AnUnannouncedResponseIsRefused() = runBlocking {
        val mismatch = registerFailing(
            response(200, ExecuteFixtures.REGISTER_RESPONSE_BODY, emptyList())
        );

        assertEquals(SpfnContractMismatch.Reason.UNANNOUNCED, mismatch.reason);
        assertNull(mismatch.serverVersion);
        assertEquals(SpfnGeneratedContract.BINDING.admittedRange, mismatch.admittedRange);
    }

    /** R2. The version this build was generated from is inside the window. */
    @Test
    fun r2AnAnnouncementInsideTheWindowIsRead() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                ScriptedTransport.Outcome.Answer(
                    response(
                        200,
                        ExecuteFixtures.REGISTER_RESPONSE_BODY,
                        announcementHeaders(SpfnGeneratedContract.BINDING.importedVersion)
                    )
                )
            )
        );

        val registered = client(transport).execute(ExecuteCalls.REGISTER, ExecuteFixtures.REGISTER_REQUEST);

        assertEquals(ExecuteFixtures.REGISTER_RESPONSE, registered);
    }

    /** R3. A server ahead of this build. Its version is carried, because it parsed. */
    @Test
    fun r3AServerAheadOfThisBuildIsRefusedAndNamed() = runBlocking {
        assertOutsideWindow(AHEAD_OF_THE_WINDOW);
    }

    /**
     * R4. A server behind this build. The case only this side can catch: the server's own
     * gate arrived in 0.8.0, so a server below that has no gate to refuse with.
     */
    @Test
    fun r4AServerBehindThisBuildIsRefusedAndNamed() = runBlocking {
        assertOutsideWindow("0.7.0");
    }

    /** R5. An announcement that is not a version carries nothing into the failure. */
    @Test
    fun r5AnUnreadableAnnouncementIsRefusedAndCarriesNothing() = runBlocking {
        val mismatch = registerFailing(
            response(
                200,
                ExecuteFixtures.REGISTER_RESPONSE_BODY,
                announcementHeaders("0.8; DROP TABLE users")
            )
        );

        assertEquals(SpfnContractMismatch.Reason.UNREADABLE, mismatch.reason);
        assertNull(mismatch.serverVersion);
    }

    /**
     * R6. The server refuses on contract grounds and announces its version on that
     * refusal. Classifying the refusal first would keep the refusal and lose the reason.
     */
    @Test
    fun r6AContractRefusalIsReportedAsAVersionGapNotAsAServerFailure() = runBlocking {
        val mismatch = registerFailing(
            response(
                409,
                ExecuteFixtures.errorEnvelope("CONTRACT_UNSUPPORTED"),
                announcementHeaders(AHEAD_OF_THE_WINDOW)
            )
        );

        assertEquals(SpfnContractMismatch.Reason.OUTSIDE_ADMITTED_RANGE, mismatch.reason);
        assertEquals(AHEAD_OF_THE_WINDOW, mismatch.serverVersion);
    }

    /** R7. The handshake is the second place a response is read, and it refuses alike. */
    @Test
    fun r7AnUnannouncedHandshakeResponseIsRefused() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                ScriptedTransport.Outcome.Answer(
                    response(200, SessionFixtureValues.HANDSHAKE_RESPONSE_BODY, emptyList())
                )
            )
        );

        val thrown = runCatching {
            client(transport, nonces = listOf("nonce-open"))
                .execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST);
        }.exceptionOrNull();

        assertTrue("expected a contract mismatch, got $thrown", thrown is SpfnClientError.Contract);
        assertEquals(
            SpfnContractMismatch.Reason.UNANNOUNCED,
            (thrown as SpfnClientError.Contract).mismatch.reason
        );
    }

    // ---- reading the identity itself ---------------------------------------

    /**
     * The kind and the contract version are what the server's gate judges, so neither is
     * allowed to go missing however the app version turns out.
     */
    @Test
    fun theIdentityAlwaysStatesAKindAndAContractVersion()
    {
        val byName = SpfnClientIdentity.headers.toMap();

        assertEquals("android", byName[SpfnWireHeaders.CLIENT_KIND]);
        assertEquals(
            SpfnGeneratedContract.BINDING.importedVersion,
            byName[SpfnWireHeaders.CLIENT_CONTRACT_VERSION]
        );
        assertNull(
            "unset by default, because a Context is what reads it",
            byName[SpfnWireHeaders.CLIENT_VERSION]
        );
    }

    /** Set, it rides along; the two the gate judges are unaffected either way. */
    @Test
    fun anAppVersionSuppliedByTheAppIsCarried()
    {
        SpfnClientIdentity.appVersion = "4.2.0";

        assertEquals("4.2.0", SpfnClientIdentity.headers.toMap()[SpfnWireHeaders.CLIENT_VERSION]);
    }

    // ---- helpers -----------------------------------------------------------

    companion object
    {
        /**
         * The first version above the admitted window, computed from the pin rather than
         * written down. The rule is the contract's own — `upstream.lock.json`'s
         * `rangeRule`: on a 0.x line the breaking axis is the minor, above it the major —
         * so this is the smallest version the window cannot admit.
         *
         * A literal here rots silently: these cells were written at the 0.4.1 pin, where
         * "0.9.0" meant ahead; at the 0.9.0 pin the same literal sits inside the window
         * and the cell asserts nothing. Derived, it moves with the pin.
         */
        val AHEAD_OF_THE_WINDOW: String =
            with(SpfnGeneratedContract.BINDING) {
                if (supportedMajor == 0) "0.${supportedMinor + 1}.0" else "${supportedMajor + 1}.0.0"
            }
    }

    private suspend fun assertOutsideWindow(announced: String)
    {
        val mismatch = registerFailing(
            response(200, ExecuteFixtures.REGISTER_RESPONSE_BODY, announcementHeaders(announced))
        );

        assertEquals(SpfnContractMismatch.Reason.OUTSIDE_ADMITTED_RANGE, mismatch.reason);
        assertEquals(announced, mismatch.serverVersion);
        assertEquals(SpfnGeneratedContract.BINDING.admittedRange, mismatch.admittedRange);
    }

    /**
     * The unproven path is used for the response cells because it sends exactly one
     * request, so what comes back is judged without a handshake in the way.
     */
    private suspend fun registerFailing(answer: SpfnTransportResponse): SpfnContractMismatch
    {
        val transport = ScriptedTransport(listOf(ScriptedTransport.Outcome.Answer(answer)));

        val thrown = runCatching {
            client(transport).execute(ExecuteCalls.REGISTER, ExecuteFixtures.REGISTER_REQUEST);
        }.exceptionOrNull();

        assertTrue("expected a contract mismatch, got $thrown", thrown is SpfnClientError.Contract);
        return (thrown as SpfnClientError.Contract).mismatch;
    }

    private fun assertCarriesIdentity(sent: SpfnTransportRequest)
    {
        val byName = sent.headers.toMap();

        assertEquals("android", byName[SpfnWireHeaders.CLIENT_KIND]);
        assertEquals(
            SpfnGeneratedContract.BINDING.importedVersion,
            byName[SpfnWireHeaders.CLIENT_CONTRACT_VERSION]
        );
    }

    /** A response built here rather than through [jsonResponse], which always announces. */
    private fun response(
        statusCode: Int,
        body: String,
        announcing: List<Pair<String, String>>
    ): SpfnTransportResponse = SpfnTransportResponse(
        statusCode = statusCode,
        headers = listOf(SpfnWireHeaders.CONTENT_TYPE to SpfnWireHeaders.REQUEST_CONTENT_TYPE) + announcing,
        body = body.toByteArray(Charsets.UTF_8)
    )

    private fun answer(text: String, statusCode: Int = 200): ScriptedTransport.Outcome =
        ScriptedTransport.Outcome.Answer(jsonResponse(statusCode, text))

    private fun handshakeAnswer(): ScriptedTransport.Outcome =
        answer(SessionFixtureValues.HANDSHAKE_RESPONSE_BODY)

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
