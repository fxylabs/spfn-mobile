// SPFN Mobile — the E-table, cell by cell (docs/architecture/event-stream-design.md §4-1).
//
// Every test is named for its cell, and Tests/SPFNClientTests/SPFNEventStreamMachineTests.swift
// carries the same names; tools/validate/validate.sh compares the two lists. The machine is
// a pure function, so nothing here waits: an input goes in, a state and its effects come out.

package xyz.superfunction.spfn.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpfnEventStreamMachineTest
{
    private val configuration = SpfnEventStreamConfiguration(events = listOf("sessionUnread", "sessionActivity"))
    private val machine = SpfnEventStreamMachine(configuration) { 1.0 }
    private val token = SpfnEventStreamToken("a".repeat(64))

    /** A run of inputs from the initial state; `effects` are the last step's. */
    private inner class Run
    {
        var state = machine.initial();
        var effects: List<SpfnEventEffect> = emptyList();

        val generation: Long get() = state.generation;

        val public: SpfnEventStreamState get() = state.publicState;

        fun feed(input: SpfnEventInput): Run
        {
            val step = machine.step(state, input);
            state = step.state;
            effects = step.effects;
            return this;
        }

        fun issued(make: (Long) -> SpfnEventInput): Run = feed(make(generation))

        fun signedInForeground(): Run =
            feed(SpfnEventInput.SetSignedIn(CLIENT_A)).feed(SpfnEventInput.SetForeground(true));

        fun streaming(): Run = signedInForeground().issued { SpfnEventInput.TokenMinted(it, token) }

        fun headers(): Run = streaming().issued { SpfnEventInput.StreamAnswered(it, SpfnStreamAnswer.EventStream) }

        fun open(): Run = headers().issued { SpfnEventInput.FrameReceived(it, CONNECTED) }

        fun retrying(): Run = signedInForeground().issued { SpfnEventInput.TokenFailed(it, SpfnTokenFailure.Network) }

        fun offline(): Run = retrying().feed(SpfnEventInput.SetNetworkAvailable(false))

        fun closed(): Run = signedInForeground().issued { SpfnEventInput.TokenFailed(it, SpfnTokenFailure.Unauthorized) }
    }

    private fun run() = Run()

    private fun assertMints(run: Run)
    {
        assertTrue("expected a token call in ${run.effects}", run.effects.contains(SpfnEventEffect.MintToken(run.generation)));
    }

    private fun assertAbandons(run: Run)
    {
        assertTrue("expected every in-flight effect stopped in ${run.effects}", run.effects.containsAll(SpfnEventStreamMachine.ABANDON));
    }

    private fun retryDelay(run: Run): Long = (run.public as SpfnEventStreamState.Retrying).delayMillis

    @Test
    fun e1_signedInForeground_mintsToken()
    {
        val foregroundLast = run().signedInForeground();
        assertEquals(SpfnEventStreamState.Connecting(1), foregroundLast.public);
        assertMints(foregroundLast);

        val signedInLast = run().feed(SpfnEventInput.SetForeground(true)).feed(SpfnEventInput.SetSignedIn(CLIENT_A));
        assertEquals(SpfnEventStreamState.Connecting(1), signedInLast.public);
        assertMints(signedInLast);
    }

    @Test
    fun e3_token_opensStreamWithSortedEvents()
    {
        val run = run().streaming();
        assertEquals(SpfnEventStreamState.Connecting(1), run.public);
        assertEquals(listOf(SpfnEventEffect.OpenStream(run.generation, listOf("sessionActivity", "sessionUnread"), token)), run.effects);
    }

    @Test
    fun e4_tokenAuthRefusal_closesUnauthorized()
    {
        val run = run().closed();
        assertEquals(SpfnEventStreamState.Closed(SpfnCloseReason.Unauthorized), run.public);
        assertFalse(run.effects.any { it is SpfnEventEffect.StartRetryTimer });
    }

    @Test
    fun e5_token403_closesForbidden()
    {
        val run = run().signedInForeground().issued { SpfnEventInput.TokenFailed(it, SpfnTokenFailure.Forbidden) }
        assertEquals(SpfnEventStreamState.Closed(SpfnCloseReason.Forbidden), run.public);
    }

    @Test
    fun e6_token5xxOr429_retriesServerError()
    {
        for (status in listOf(500, 503, 429))
        {
            val run = run().signedInForeground().issued { SpfnEventInput.TokenFailed(it, SpfnTokenFailure.ServerError(status, null)) }
            assertEquals(SpfnEventStreamState.Retrying(2, 1_000, SpfnRetryReason.ServerError(status)), run.public);
            assertTrue(run.effects.contains(SpfnEventEffect.StartRetryTimer(run.generation, 1_000)));
        }
    }

    @Test
    fun e7_tokenTransport_retriesNetwork()
    {
        assertEquals(SpfnEventStreamState.Retrying(2, 1_000, SpfnRetryReason.Network), run().retrying().public);

        val offline = run().signedInForeground()
            .feed(SpfnEventInput.SetNetworkAvailable(false))
            .issued { SpfnEventInput.TokenFailed(it, SpfnTokenFailure.Network) }
        assertEquals(SpfnEventStreamState.Offline(2), offline.public);
        assertFalse(offline.effects.any { it is SpfnEventEffect.StartRetryTimer });
    }

    @Test
    fun e8_tokenNotAnEnvelope_retriesUnreadable()
    {
        val failure = SpfnEventStream.tokenFailure(SpfnClientError.Decoding(SpfnDecodingFailure.NOT_AN_ERROR_ENVELOPE, false), 0);
        assertEquals(SpfnTokenFailure.Unreadable, failure);
        val run = run().signedInForeground().issued { SpfnEventInput.TokenFailed(it, failure) }
        assertEquals(SpfnEventStreamState.Retrying(2, 1_000, SpfnRetryReason.Unreadable), run.public);
    }

    @Test
    fun e9_token2xxUnreadable_retriesUnreadable()
    {
        val failure = SpfnEventStream.tokenFailure(SpfnClientError.Decoding(SpfnDecodingFailure.NOT_THE_DECLARED_RESPONSE, true), 0);
        assertEquals(SpfnTokenFailure.Unreadable, failure);
        val run = run().signedInForeground().issued { SpfnEventInput.TokenFailed(it, failure) }
        assertEquals(SpfnEventStreamState.Retrying(2, 1_000, SpfnRetryReason.Unreadable), run.public);
    }

    @Test
    fun e10_signOutDuringToken_idlesSignedOut()
    {
        val run = run().signedInForeground().feed(SpfnEventInput.SetSignedIn(null));
        assertEquals(SpfnEventStreamState.Idle(SpfnIdleReason.SIGNED_OUT), run.public);
        assertTrue(run.effects.contains(SpfnEventEffect.CancelToken));
    }

    @Test
    fun e11_backgroundDuringToken_idlesBackground()
    {
        val run = run().signedInForeground().feed(SpfnEventInput.SetForeground(false));
        assertEquals(SpfnEventStreamState.Idle(SpfnIdleReason.BACKGROUND), run.public);
        assertTrue(run.effects.contains(SpfnEventEffect.CancelToken));
    }

    @Test
    fun e12_eventStreamHeaders_startWatchdog_notYetOpen()
    {
        val run = run().headers();
        assertEquals(SpfnEventStreamState.Connecting(1), run.public);
        assertEquals(listOf(SpfnEventEffect.StartSilenceTimer(run.generation, 25_000)), run.effects);
    }

    @Test
    fun e13_connected_opensNewEpoch_rereadsListeners()
    {
        val run = run().open();
        assertEquals(SpfnEventStreamState.Open(1), run.public);
        assertTrue(run.effects.contains(SpfnEventEffect.Reread(1)));
        assertTrue(run.effects.contains(SpfnEventEffect.StartStableTimer(run.generation, 30_000)));
        assertTrue(run.effects.contains(SpfnEventEffect.Publish(SpfnEventStreamState.Open(1))));
    }

    @Test
    fun e14_notEventStream_retriesUnreadable()
    {
        val answer = SpfnEventStream.streamAnswer(200, listOf("content-type" to "text/html"), ByteArray(0), 0);
        assertEquals(SpfnStreamAnswer.NotEventStream, answer);
        val run = run().streaming().issued { SpfnEventInput.StreamAnswered(it, answer) }
        assertEquals(SpfnEventStreamState.Retrying(2, 1_000, SpfnRetryReason.Unreadable), run.public);
        assertTrue(run.effects.contains(SpfnEventEffect.CloseStream));
    }

    @Test
    fun e15_stream401_retriesTokenOnce_thenBacksOff()
    {
        assertEquals(SpfnStreamAnswer.TokenRejected, SpfnEventStream.streamAnswer(401, emptyList(), ByteArray(0), 0));
        val first = run().streaming().issued { SpfnEventInput.StreamAnswered(it, SpfnStreamAnswer.TokenRejected) }
        assertEquals(SpfnEventStreamState.Connecting(1), first.public);
        assertMints(first);

        val second = first.issued { SpfnEventInput.TokenMinted(it, token) }
            .issued { SpfnEventInput.StreamAnswered(it, SpfnStreamAnswer.TokenRejected) }
        assertEquals(SpfnEventStreamState.Retrying(2, 1_000, SpfnRetryReason.TokenRejected), second.public);
    }

    @Test
    fun e16_invalidEvents_reconnectsWithValidEvents()
    {
        val body = """{"error":"Invalid event names","invalidEvents":["sessionUnread"],"validEvents":["other","sessionActivity"]}""";
        val answer = SpfnEventStream.streamAnswer(400, emptyList(), body.toByteArray(), 0);
        assertEquals(SpfnStreamAnswer.InvalidEvents(listOf("sessionUnread"), listOf("other", "sessionActivity")), answer);

        val narrowed = run().streaming().issued { SpfnEventInput.StreamAnswered(it, answer) }
        assertEquals(SpfnEventStreamState.Connecting(1), narrowed.public);
        assertMints(narrowed);
        assertTrue(narrowed.effects.contains(SpfnEventEffect.MarkUnavailable(listOf("sessionUnread"))));

        val reopened = narrowed.issued { SpfnEventInput.TokenMinted(it, token) }
        assertEquals(listOf(SpfnEventEffect.OpenStream(reopened.generation, listOf("sessionActivity"), token)), reopened.effects);

        val open = reopened.issued { SpfnEventInput.StreamAnswered(it, SpfnStreamAnswer.EventStream) }
            .issued { SpfnEventInput.FrameReceived(it, CONNECTED) }
        assertEquals(SpfnEventStreamState.Open(1, listOf("sessionUnread")), open.public);
    }

    @Test
    fun e17_otherBadRequest_closesUnknownEventsEmpty()
    {
        val answer = SpfnEventStream.streamAnswer(400, emptyList(), """{"error":"Missing events parameter"}""".toByteArray(), 0);
        assertEquals(SpfnStreamAnswer.BadRequest, answer);
        val run = run().streaming().issued { SpfnEventInput.StreamAnswered(it, answer) }
        assertEquals(SpfnEventStreamState.Closed(SpfnCloseReason.UnknownEvents(emptyList())), run.public);
    }

    @Test
    fun e18_stream403_closesForbidden()
    {
        val answer = SpfnEventStream.streamAnswer(403, emptyList(), ByteArray(0), 0);
        val run = run().streaming().issued { SpfnEventInput.StreamAnswered(it, answer) }
        assertEquals(SpfnEventStreamState.Closed(SpfnCloseReason.Forbidden), run.public);
    }

    @Test
    fun e19_stream5xxOr3xx_retriesServerError()
    {
        for (status in listOf(302, 500, 502, 429))
        {
            val answer = SpfnEventStream.streamAnswer(status, emptyList(), ByteArray(0), 0);
            val run = run().streaming().issued { SpfnEventInput.StreamAnswered(it, answer) }
            assertEquals(SpfnEventStreamState.Retrying(2, 1_000, SpfnRetryReason.ServerError(status)), run.public);
        }
    }

    @Test
    fun e20_streamTransportError_retriesNetwork()
    {
        val run = run().streaming().issued { SpfnEventInput.StreamFailed(it) }
        assertEquals(SpfnEventStreamState.Retrying(2, 1_000, SpfnRetryReason.Network), run.public);
    }

    @Test
    fun e21_silenceBeforeConnected_retriesSilence()
    {
        val run = run().headers().issued { SpfnEventInput.SilenceElapsed(it) }
        assertEquals(SpfnEventStreamState.Retrying(2, 1_000, SpfnRetryReason.Silence), run.public);
        assertTrue(run.effects.contains(SpfnEventEffect.CloseStream));
    }

    @Test
    fun e22_configuredFrame_deliversAndRestartsWatchdog()
    {
        val run = run().open().issued { SpfnEventInput.FrameReceived(it, SpfnSseEvent("sessionActivity", ENVELOPE)) }
        assertEquals(SpfnEventStreamState.Open(1), run.public);
        assertEquals(
            listOf(SpfnEventEffect.Deliver("sessionActivity", ENVELOPE), SpfnEventEffect.StartSilenceTimer(run.generation, 25_000)),
            run.effects
        );
    }

    @Test
    fun e23_unconfiguredFrame_countsUnexpected()
    {
        val run = run().open().issued { SpfnEventInput.FrameReceived(it, SpfnSseEvent("somethingElse", ENVELOPE)) }
        assertEquals(
            listOf(SpfnEventEffect.CountUnexpected("somethingElse"), SpfnEventEffect.StartSilenceTimer(run.generation, 25_000)),
            run.effects
        );

        // A configured name nobody listens to is delivered and dropped by the hub, uncounted.
        val hub = SpfnEventListenerHub(deliveryBuffer = 4);
        hub.deliver("sessionActivity", ENVELOPE);
        assertEquals(SpfnEventDiagnostics(), hub.diagnostics.value);
    }

    @Test
    fun e24_oneDecoderThrows_dropsForThatListenerOnly()
    {
        val hub = SpfnEventListenerHub(deliveryBuffer = 4);
        val failing = hub.attach<String>("sessionActivity", { throw IllegalArgumentException("unreadable") }, { true });
        val reading = hub.attach("sessionActivity", { it.toString() }, { true });
        hub.deliver("sessionActivity", ENVELOPE);
        assertEquals(1L, hub.diagnostics.value.droppedFrames);
        assertEquals(listOf(SpfnEventSignal.Reread(SpfnRereadCause.Attached)), failing.queue.toList());
        assertEquals(2, reading.queue.size);
    }

    @Test
    fun e25_ping_restartsWatchdogOnly()
    {
        val run = run().open().issued { SpfnEventInput.FrameReceived(it, SpfnSseEvent("ping", """{"timestamp":1}""")) }
        assertEquals(listOf(SpfnEventEffect.StartSilenceTimer(run.generation, 25_000)), run.effects);
    }

    @Test
    fun e26_commentOrBlankLine_restartsWatchdog()
    {
        assertEquals(emptyList<SpfnSseEvent>(), SpfnSseLineParser().feed(": keep-alive\n\nretry: 5\n\n".toByteArray()));
        val run = run().open().issued { SpfnEventInput.BytesReceived(it) }
        assertEquals(listOf(SpfnEventEffect.StartSilenceTimer(run.generation, 25_000)), run.effects);
    }

    @Test
    fun e27_silenceWhenOpen_retriesSilence_fromOneWhenStable()
    {
        val unstable = run().open().issued { SpfnEventInput.SilenceElapsed(it) }
        assertEquals(SpfnEventStreamState.Retrying(2, 1_000, SpfnRetryReason.Silence), unstable.public);
        assertTrue(unstable.effects.contains(SpfnEventEffect.CloseStream));

        val stable = run().open().issued { SpfnEventInput.StableElapsed(it) }.issued { SpfnEventInput.SilenceElapsed(it) }
        assertEquals(SpfnEventStreamState.Retrying(1, 1_000, SpfnRetryReason.Silence), stable.public);
    }

    @Test
    fun e28_cleanEnd_retriesServerClosed()
    {
        val run = run().open().issued { SpfnEventInput.StreamEnded(it) }
        assertEquals(SpfnEventStreamState.Retrying(2, 1_000, SpfnRetryReason.ServerClosed), run.public);
    }

    @Test
    fun e29_streamErrorWhenOpen_retriesNetwork()
    {
        val run = run().open().issued { SpfnEventInput.StreamFailed(it) }
        assertEquals(SpfnEventStreamState.Retrying(2, 1_000, SpfnRetryReason.Network), run.public);
    }

    @Test
    fun e33_retryTimer_mintsToken()
    {
        val run = run().retrying().issued { SpfnEventInput.RetryElapsed(it) }
        assertEquals(SpfnEventStreamState.Connecting(2), run.public);
        assertMints(run);
    }

    @Test
    fun e34_networkLostWhileRetrying_goesOffline()
    {
        val run = run().offline();
        assertEquals(SpfnEventStreamState.Offline(2), run.public);
        assertTrue(run.effects.contains(SpfnEventEffect.CancelTimers));
    }

    @Test
    fun e35_networkBackWhileOffline_mintsAtOnce()
    {
        val run = run().offline().feed(SpfnEventInput.SetNetworkAvailable(true));
        assertEquals(SpfnEventStreamState.Connecting(2), run.public);
        assertMints(run);
    }

    @Test
    fun e36_networkLostWhileOpen_staysOpen()
    {
        val run = run().open().feed(SpfnEventInput.SetNetworkAvailable(false));
        assertEquals(SpfnEventStreamState.Open(1), run.public);
        assertEquals(emptyList<SpfnEventEffect>(), run.effects);
    }

    @Test
    fun e37_background_closesEverything()
    {
        for (start in listOf(run().streaming(), run().open(), run().retrying(), run().offline()))
        {
            start.feed(SpfnEventInput.SetForeground(false));
            assertEquals(SpfnEventStreamState.Idle(SpfnIdleReason.BACKGROUND), start.public);
            assertAbandons(start);
        }
    }

    @Test
    fun e38_foregroundAgain_mintsWithoutBackoff()
    {
        val run = run().open().feed(SpfnEventInput.SetForeground(false)).feed(SpfnEventInput.SetForeground(true));
        assertEquals(SpfnEventStreamState.Connecting(1), run.public);
        assertMints(run);

        val offline = run().open().feed(SpfnEventInput.SetForeground(false))
            .feed(SpfnEventInput.SetNetworkAvailable(false))
            .feed(SpfnEventInput.SetForeground(true))
        assertEquals(SpfnEventStreamState.Offline(1), offline.public);
    }

    @Test
    fun e39_lateResults_ignoredInIdleAndClosed()
    {
        for (start in listOf(run().open().feed(SpfnEventInput.SetForeground(false)), run().closed()))
        {
            val stale = start.generation - 1;
            val before = start.state;
            for (late in lateInputs(stale))
            {
                start.feed(late);
                assertEquals(before, start.state);
                assertEquals(emptyList<SpfnEventEffect>(), start.effects);
            }
        }
    }

    @Test
    fun e40_signOut_closesOpenStream()
    {
        for (start in listOf(run().streaming(), run().open(), run().retrying(), run().offline(), run().closed()))
        {
            start.feed(SpfnEventInput.SetSignedIn(null));
            assertEquals(SpfnEventStreamState.Idle(SpfnIdleReason.SIGNED_OUT), start.public);
            assertAbandons(start);
        }
    }

    @Test
    fun e41_keyRotation_changesNothing()
    {
        // A rotation keeps the client id, so the lifecycle's value does not move and the
        // only input it could produce is the same id again.
        val run = run().open().feed(SpfnEventInput.SetSignedIn(CLIENT_A));
        assertEquals(SpfnEventStreamState.Open(1), run.public);
        assertEquals(emptyList<SpfnEventEffect>(), run.effects);
    }

    @Test
    fun e42_revokedSession_tokenThroughExecute()
    {
        // Sessionless (the default) the refusal is the answer; with a session-guarded token
        // route execute re-handshakes once first. A refusal closes, and the wipe that
        // `noteSessionRevoked` performs arrives next as a sign-out.
        val refused = run().closed().feed(SpfnEventInput.SetSignedIn(null));
        assertEquals(SpfnEventStreamState.Idle(SpfnIdleReason.SIGNED_OUT), refused.public);

        val wipedFirst = run().signedInForeground().feed(SpfnEventInput.SetSignedIn(null));
        assertEquals(SpfnEventStreamState.Idle(SpfnIdleReason.SIGNED_OUT), wipedFirst.public);
    }

    @Test
    fun e43_accountSwitch_reconnectsWithNewToken()
    {
        for (start in listOf(run().signedInForeground(), run().streaming(), run().open(), run().retrying(), run().offline(), run().closed()))
        {
            start.feed(SpfnEventInput.SetNetworkAvailable(true)).feed(SpfnEventInput.SetSignedIn(CLIENT_B));
            assertEquals(SpfnEventStreamState.Connecting(1), start.public);
            assertAbandons(start);
            assertMints(start);
        }
        val offline = run().open().feed(SpfnEventInput.SetNetworkAvailable(false)).feed(SpfnEventInput.SetSignedIn(CLIENT_B));
        assertEquals(SpfnEventStreamState.Offline(1), offline.public);
    }

    @Test
    fun e44_closed_leavesOnlyThroughBackground()
    {
        val run = run().closed().feed(SpfnEventInput.SetForeground(false));
        assertEquals(SpfnEventStreamState.Idle(SpfnIdleReason.BACKGROUND), run.public);
        run.feed(SpfnEventInput.SetForeground(true));
        assertEquals(SpfnEventStreamState.Connecting(1), run.public);
        assertMints(run);
    }

    @Test
    fun e45_closed_ignoresNetworkAndRepeatedInputs()
    {
        val run = run().closed();
        for (input in listOf(
            SpfnEventInput.SetNetworkAvailable(false),
            SpfnEventInput.SetNetworkAvailable(true),
            SpfnEventInput.SetForeground(true),
            SpfnEventInput.SetSignedIn(CLIENT_A)
        ))
        {
            run.feed(input);
            assertEquals(SpfnEventStreamState.Closed(SpfnCloseReason.Unauthorized), run.public);
            assertEquals(emptyList<SpfnEventEffect>(), run.effects);
        }
    }

    @Test
    fun e46_repeatedInputsWhenOpen_areIdempotent()
    {
        val run = run().open();
        for (input in listOf(SpfnEventInput.SetForeground(true), SpfnEventInput.SetSignedIn(CLIENT_A), SpfnEventInput.SetNetworkAvailable(true)))
        {
            run.feed(input);
            assertEquals(SpfnEventStreamState.Open(1), run.public);
            assertEquals(emptyList<SpfnEventEffect>(), run.effects);
        }
    }

    @Test
    fun e47_stableTimer_resetsAttempt()
    {
        val flaky = run().retrying().issued { SpfnEventInput.RetryElapsed(it) }
            .issued { SpfnEventInput.TokenMinted(it, token) }
            .issued { SpfnEventInput.StreamAnswered(it, SpfnStreamAnswer.EventStream) }
            .issued { SpfnEventInput.FrameReceived(it, CONNECTED) }
        assertEquals(2, flaky.state.attempt);
        flaky.issued { SpfnEventInput.StableElapsed(it) }
        assertEquals(SpfnEventStreamState.Open(1), flaky.public);
        assertEquals(emptyList<SpfnEventEffect>(), flaky.effects);
        assertEquals(1, flaky.state.attempt);
    }

    @Test
    fun e48_conditionsMetWithoutNetwork_goesOffline()
    {
        val run = run().feed(SpfnEventInput.SetNetworkAvailable(false)).signedInForeground();
        assertEquals(SpfnEventStreamState.Offline(1), run.public);
        assertFalse(run.effects.any { it is SpfnEventEffect.MintToken });
    }

    @Test
    fun e49_signedOutIdle_recordsConditions()
    {
        val run = run().feed(SpfnEventInput.SetForeground(true)).feed(SpfnEventInput.SetNetworkAvailable(false));
        assertEquals(SpfnEventStreamState.Idle(SpfnIdleReason.SIGNED_OUT), run.public);
        assertEquals(emptyList<SpfnEventEffect>(), run.effects);
        run.feed(SpfnEventInput.SetSignedIn(CLIENT_A));
        assertEquals(SpfnEventStreamState.Offline(1), run.public);
    }

    @Test
    fun e50_signOutInBackground_changesReasonOnly()
    {
        val background = run().open().feed(SpfnEventInput.SetForeground(false));
        background.feed(SpfnEventInput.SetSignedIn(CLIENT_B));
        assertEquals(SpfnEventStreamState.Idle(SpfnIdleReason.BACKGROUND), background.public);
        assertEquals(emptyList<SpfnEventEffect>(), background.effects);

        background.feed(SpfnEventInput.SetSignedIn(null));
        assertEquals(SpfnEventStreamState.Idle(SpfnIdleReason.SIGNED_OUT), background.public);
        assertEquals(listOf(SpfnEventEffect.Publish(SpfnEventStreamState.Idle(SpfnIdleReason.SIGNED_OUT))), background.effects);
    }

    @Test
    fun e51_networkLostDuringConnecting_letsCallFinish()
    {
        for (start in listOf(run().signedInForeground(), run().streaming()))
        {
            start.feed(SpfnEventInput.SetNetworkAvailable(false));
            assertEquals(SpfnEventStreamState.Connecting(1), start.public);
            assertEquals(emptyList<SpfnEventEffect>(), start.effects);
        }
        val failed = run().streaming().feed(SpfnEventInput.SetNetworkAvailable(false)).issued { SpfnEventInput.StreamFailed(it) }
        assertEquals(SpfnEventStreamState.Offline(2), failed.public);
    }

    @Test
    fun e52_invalidEventsWithEmptyIntersection_closesUnknownEvents()
    {
        val answer = SpfnStreamAnswer.InvalidEvents(listOf("sessionUnread", "sessionActivity"), listOf("other"));
        val run = run().streaming().issued { SpfnEventInput.StreamAnswered(it, answer) }
        assertEquals(SpfnEventStreamState.Closed(SpfnCloseReason.UnknownEvents(listOf("sessionActivity", "sessionUnread"))), run.public);

        // A server that refuses names it also calls valid cannot narrow anything: closed, no loop.
        val contradictory = SpfnStreamAnswer.InvalidEvents(listOf("sessionUnread"), listOf("sessionActivity", "sessionUnread"));
        val closed = run().streaming().issued { SpfnEventInput.StreamAnswered(it, contradictory) }
        assertEquals(SpfnEventStreamState.Closed(SpfnCloseReason.UnknownEvents(listOf("sessionUnread"))), closed.public);
    }

    @Test
    fun e53_retryAfterSeconds_extendsDelay()
    {
        val wait = SpfnRetryAfter.millis("7", NOW);
        assertEquals(7_000L, wait);
        assertEquals(7_000L, retryDelay(run().signedInForeground().issued { SpfnEventInput.TokenFailed(it, SpfnTokenFailure.ServerError(429, wait)) }));

        val answer = SpfnEventStream.streamAnswer(429, listOf("Retry-After" to "7"), ByteArray(0), NOW);
        assertEquals(7_000L, retryDelay(run().streaming().issued { SpfnEventInput.StreamAnswered(it, answer) }));

        // Shorter than the backoff: the backoff stands.
        assertEquals(1_000L, retryDelay(run().signedInForeground().issued { SpfnEventInput.TokenFailed(it, SpfnTokenFailure.ServerError(429, 200)) }));
    }

    @Test
    fun e54_retryAfterHttpDate_extendsDelay()
    {
        // NOW is Sun, 06 Nov 1994 08:49:37 GMT; the header asks for 90 seconds after it.
        val wait = SpfnRetryAfter.millis("Sun, 06 Nov 1994 08:51:07 GMT", NOW);
        assertEquals(90_000L, wait);
        assertEquals(0L, SpfnRetryAfter.millis("Sun, 06 Nov 1994 08:00:00 GMT", NOW));

        val answer = SpfnEventStream.streamAnswer(429, listOf("retry-after" to "Sun, 06 Nov 1994 08:51:07 GMT"), ByteArray(0), NOW);
        assertEquals(90_000L, retryDelay(run().streaming().issued { SpfnEventInput.StreamAnswered(it, answer) }));
    }

    @Test
    fun e55_retryAfterMalformed_isIgnored()
    {
        for (malformed in listOf("", "soon", "-5", "1.5", "tomorrow"))
        {
            assertEquals(null, SpfnRetryAfter.millis(malformed, NOW));
            val answer = SpfnEventStream.streamAnswer(429, listOf("Retry-After" to malformed), ByteArray(0), NOW);
            assertEquals(1_000L, retryDelay(run().streaming().issued { SpfnEventInput.StreamAnswered(it, answer) }));
        }
        assertEquals(null, SpfnRetryAfter.millis(null, NOW));
    }

    @Test
    fun e56_retryAfterAboveCap_isCapped()
    {
        assertEquals(300_000L, SpfnRetryAfter.millis("3600", NOW));
        assertEquals(300_000L, SpfnRetryAfter.millis("99999999999999999999", NOW));
        assertEquals(300_000L, SpfnRetryAfter.millis("Mon, 07 Nov 1994 08:49:37 GMT", NOW));
        assertEquals(300_000L, retryDelay(run().signedInForeground().issued { SpfnEventInput.TokenFailed(it, SpfnTokenFailure.ServerError(429, 9_000_000)) }));
    }

    private fun lateInputs(generation: Long): List<SpfnEventInput> = listOf(
        SpfnEventInput.TokenMinted(generation, token),
        SpfnEventInput.TokenFailed(generation, SpfnTokenFailure.Network),
        SpfnEventInput.StreamAnswered(generation, SpfnStreamAnswer.EventStream),
        SpfnEventInput.StreamFailed(generation),
        SpfnEventInput.StreamEnded(generation),
        SpfnEventInput.BytesReceived(generation),
        SpfnEventInput.FrameReceived(generation, CONNECTED),
        SpfnEventInput.SilenceElapsed(generation),
        SpfnEventInput.RetryElapsed(generation),
        SpfnEventInput.StableElapsed(generation)
    )

    private companion object
    {
        const val CLIENT_A = "client-a";
        const val CLIENT_B = "client-b";
        const val ENVELOPE = """{"event":"sessionActivity","data":{"sessionId":"s-1","wsId":"w-1"}}""";
        const val NOW = 784_111_777_000L;
        val CONNECTED = SpfnSseEvent("connected", """{"subscribedEvents":["sessionActivity"],"timestamp":1}""");
    }
}
