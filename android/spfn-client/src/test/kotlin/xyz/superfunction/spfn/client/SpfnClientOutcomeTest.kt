// SPFN Mobile — the outcome observer (spfn-mobile#97).
//
// One case per row of the issue's table, O1–O8; row L9, the lifecycle handing its observer
// to every client it builds, is in SpfnKeyLifecycleTest.kt beside the installs it needs.
// Every case asserts the whole history the observer was handed, not its last entry,
// because the defect this surface invites is an outcome reported once per attempt rather
// than once per call.
//
// SPFNClientOutcomeTests.swift is the counterpart and uses corresponding case names.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations

class SpfnClientOutcomeTest
{
    private val baseUrl = "https://example.invalid"

    /** O1. An answer the call accepted is a success, and the response is returned as before. */
    @Test
    fun onOutcome_O1_anAcceptedAnswerReportsSuccess() = runBlocking {
        val recorder = OutcomeRecorder();
        val subject = client(ScriptedTransport(listOf(handshakeAnswer(), answer(ExecuteFixtures.ECHO_RESPONSE_BODY))), recorder.observer);

        val echoed = subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST);

        assertEquals(ExecuteFixtures.ECHO_RESPONSE, echoed);
        assertReportedSuccessOnce(recorder);
    }

    /** O2. No connection is reported as the transport said it, and thrown the same. */
    @Test
    fun onOutcome_O2_aConnectivityFailureReportsTheErrorItThrows() = runBlocking {
        assertReportedAsThrown(SpfnTransportError.Connectivity("offline"));
    }

    /** O3. No answer in time. */
    @Test
    fun onOutcome_O3_aTimeoutReportsTheErrorItThrows() = runBlocking {
        assertReportedAsThrown(SpfnTransportError.TimedOut());
    }

    /** O4. A transport that observed the cancellation itself. */
    @Test
    fun onOutcome_O4_aCancelledTransportCallReportsTheErrorItThrows() = runBlocking {
        assertReportedAsThrown(SpfnTransportError.Cancelled());
    }

    /**
     * O4. The calling coroutine cancelled between the two attempts: still reported, once,
     * and still rethrown as itself — the observer is told in the frame that rethrows it, so
     * the report never outlives the scope that made the call.
     */
    @Test
    fun onOutcome_O4_aCancelledTaskReportsCancellationAndStillThrows() = runBlocking {
        val recorder = OutcomeRecorder();
        val running = CompletableDeferred<Job>();
        val transport = ScriptedTransport(
            listOf(handshakeAnswer(), answer(ExecuteFixtures.errorEnvelope("SESSION_REVOKED"), statusCode = 401)),
            onCall = { call -> if (call == 2) running.await().cancel() }
        );
        val subject = client(transport, recorder.observer);

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
        assertReportedOnce(recorder, thrown);
    }

    /** O5. A refusal the server decided on is reported as the envelope classified. */
    @Test
    fun onOutcome_O5_aServerRefusalReportsTheErrorItThrows() = runBlocking {
        val recorder = OutcomeRecorder();
        val transport = ScriptedTransport(
            listOf(handshakeAnswer(), answer(ExecuteFixtures.errorEnvelope("PROFILE_REJECTED"), statusCode = 400))
        );
        val subject = client(transport, recorder.observer);

        val thrown = failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };

        assertTrue("got $thrown", thrown is SpfnClientError.Server);
        assertEquals(SpfnGeneratedErrorCode.PROFILE_REJECTED, (thrown as SpfnClientError.Server).failure.code);
        assertReportedOnce(recorder, thrown);
    }

    /**
     * O5. Refused as expired, retried once, refused again: two attempts and one outcome, the
     * one the second attempt ended with.
     */
    @Test
    fun onOutcome_O5_aRefusalAfterTheOneRetryIsReportedOnce() = runBlocking {
        val recorder = OutcomeRecorder();
        val transport = ScriptedTransport(
            listOf(
                handshakeAnswer(),
                answer(ExecuteFixtures.errorEnvelope("PROOF_EXPIRED"), statusCode = 401),
                handshakeAnswer(),
                answer(ExecuteFixtures.errorEnvelope("PROOF_EXPIRED"), statusCode = 401)
            )
        );
        val subject = client(transport, recorder.observer);

        val thrown = failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };

        assertTrue("got $thrown", thrown is SpfnClientError.Auth);
        assertEquals(SpfnGeneratedErrorCode.PROOF_EXPIRED, (thrown as SpfnClientError.Auth).failure.code);
        assertReportedOnce(recorder, thrown);
        assertEquals(2, transport.received.count { it.url.endsWith(SpfnGeneratedOperations.echoSend.path) });
    }

    /** O6. The retry is the SDK's own handling: a call it rescued is a success, once. */
    @Test
    fun onOutcome_O6_aProofRetryThatSucceedsReportsOneSuccess() = runBlocking {
        val recorder = OutcomeRecorder();
        val transport = ScriptedTransport(
            listOf(
                handshakeAnswer(),
                answer(ExecuteFixtures.errorEnvelope("PROOF_EXPIRED"), statusCode = 401),
                handshakeAnswer(),
                answer(ExecuteFixtures.ECHO_RESPONSE_BODY)
            )
        );
        val subject = client(transport, recorder.observer);

        val echoed = subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST);

        assertEquals(ExecuteFixtures.ECHO_RESPONSE, echoed);
        assertReportedSuccessOnce(recorder);
    }

    /** O7. An answer this SDK could not read. */
    @Test
    fun onOutcome_O7_aDecodingFailureReportsTheErrorItThrows() = runBlocking {
        val recorder = OutcomeRecorder();
        val subject = client(ScriptedTransport(listOf(handshakeAnswer(), answer("{}"))), recorder.observer);

        val thrown = failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };

        assertTrue("got $thrown", thrown is SpfnClientError.Decoding);
        assertEquals(SpfnDecodingFailure.NOT_THE_DECLARED_RESPONSE, (thrown as SpfnClientError.Decoding).failure);
        assertTrue(thrown.onSuccessStatus);
        assertReportedOnce(recorder, thrown);
    }

    /** O8. Without an observer the call answers and fails exactly as it did before. */
    @Test
    fun onOutcome_O8_noObserverLeavesTheCallUnchanged() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                handshakeAnswer(),
                answer(ExecuteFixtures.ECHO_RESPONSE_BODY),
                ScriptedTransport.Outcome.Failure(SpfnTransportError.TimedOut())
            )
        );
        val subject = client(transport, onOutcome = null);

        val echoed = subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST);
        val thrown = failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };

        assertEquals(ExecuteFixtures.ECHO_RESPONSE, echoed);
        assertTrue("got $thrown", thrown is SpfnClientError.Transport && thrown.error is SpfnTransportError.TimedOut);
        assertEquals(3, transport.callCount);
    }

    // ---- assembly ----------------------------------------------------------

    /** Opens a session, then fails the one request with [error] in the transport. */
    private suspend fun assertReportedAsThrown(error: SpfnTransportError)
    {
        val recorder = OutcomeRecorder();
        val transport = ScriptedTransport(listOf(handshakeAnswer(), ScriptedTransport.Outcome.Failure(error)));
        val subject = client(transport, recorder.observer);

        val thrown = failureOf { subject.execute(ExecuteCalls.ECHO, ExecuteFixtures.ECHO_REQUEST) };

        assertTrue("got $thrown", thrown is SpfnClientError.Transport);
        assertSame(error, (thrown as SpfnClientError.Transport).error);
        assertReportedOnce(recorder, thrown);
    }

    /** The one outcome reported is the very error the call threw. */
    private fun assertReportedOnce(recorder: OutcomeRecorder, thrown: Throwable?)
    {
        assertEquals("one call, one outcome", 1, recorder.reported.size);
        assertSame(thrown, recorder.reported.single().exceptionOrNull());
    }

    private fun assertReportedSuccessOnce(recorder: OutcomeRecorder)
    {
        assertEquals("one call, one outcome", 1, recorder.reported.size);
        assertTrue(recorder.reported.single().isSuccess);
    }

    /**
     * The clock re-anchors like the real one, so the expired-proof rows take the path they
     * take in production.
     */
    private fun client(transport: SpfnTransport, onOutcome: SpfnClientOutcome?): SpfnClient = SpfnClient(
        transport = transport,
        session = SpfnSession(
            transport = transport,
            keyProvider = ExecuteFixtures.syntheticProvider(),
            baseUrl = baseUrl,
            clock = AnchoringProofClock(SessionFixtureValues.ISSUED_AT_MILLIS),
            nonceGenerator = ScriptedNonceGenerator(emptyList())
        ),
        onOutcome = onOutcome
    )

    private fun answer(text: String, statusCode: Int = 200): ScriptedTransport.Outcome =
        ScriptedTransport.Outcome.Answer(jsonResponse(statusCode, text))

    private fun handshakeAnswer(): ScriptedTransport.Outcome =
        answer(SessionFixtureValues.HANDSHAKE_RESPONSE_BODY)

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
}
