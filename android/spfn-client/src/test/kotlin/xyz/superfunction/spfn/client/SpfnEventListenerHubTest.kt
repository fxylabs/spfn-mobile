// SPFN Mobile — the L-table, cell by cell (docs/architecture/event-stream-design.md §4-2).
//
// The hub is plain code with a queue per listener, so most cells read that queue directly.
// The cells that are about the CONNECTION — attaching never calls the token path, leaving
// never reconnects — run the whole stream over the fakes and count what reached the
// server. Tests/SPFNClientTests/SPFNEventListenerHubTests.swift carries the same names but
// L-12, which Swift's signature makes uncompilable.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnCanonicalValue

@OptIn(ExperimentalCoroutinesApi::class)
class SpfnEventListenerHubTest
{
    data class Activity(val sessionId: String, val wsId: String)
    {
        companion object : SpfnEventPayload<Activity>
        {
            override val eventName = "sessionActivity";

            override fun decode(value: SpfnCanonicalValue): Activity
            {
                val members = (value as SpfnCanonicalValue.Obj).members;
                return Activity(text(members["sessionId"]), text(members["wsId"]));
            }

            private fun text(value: SpfnCanonicalValue?): String =
                (value as? SpfnCanonicalValue.Text)?.value ?: throw IllegalArgumentException("not text");
        }
    }

    private val attached = SpfnEventSignal.Reread(SpfnRereadCause.Attached);

    private fun hub(buffer: Int = 4) = SpfnEventListenerHub(deliveryBuffer = buffer);

    private fun SpfnEventListenerHub.activity(where: (Activity) -> Boolean = { true }) =
        attach(Activity.eventName, Activity::decode, where);

    private fun envelope(sessionId: String, wsId: String): String =
        "{\"event\":\"sessionActivity\",\"data\":{\"sessionId\":\"$sessionId\",\"wsId\":\"$wsId\"}}";

    /** A stream over the fakes, already open, with the token server's calls countable. */
    private class Harness(scope: TestScope)
    {
        val tokens = TokenServer();
        val streams = SpfnFakeStreamTransport();
        val connection = streams.enqueue();
        private val session = SpfnSession(
            transport = tokens,
            keyProvider = ExecuteFixtures.syntheticProvider(),
            baseUrl = "https://example.invalid",
            clock = FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS),
            nonceGenerator = ScriptedNonceGenerator(emptyList())
        );
        val events = SpfnEventStream(
            client = SpfnClient(tokens, session),
            session = session,
            configuration = SpfnEventStreamConfiguration(events = listOf("sessionActivity", "sessionUnread")),
            transport = streams,
            scope = scope.backgroundScope,
            jitter = { 1.0 }
        );

        fun open(scope: TestScope)
        {
            events.setSignedIn("client-test-0001");
            events.setForeground(true);
            scope.runCurrent();
            connection.send(ServerFrames.CONNECTED);
            scope.runCurrent();
        }
    }

    @Test
    fun l1_attach_sendsAttachedReread_noMachineInput() = runTest {
        val harness = Harness(this);
        harness.open(this);
        val signals = mutableListOf<SpfnEventSignal<Activity>>();
        val job = backgroundScope.launch { harness.events.listen(Activity, where = { it.wsId == "w-1" }).collect { signals.add(it) } };
        runCurrent();
        assertEquals(listOf(attached), signals);
        assertEquals(1, harness.tokens.tokenRequests.size);
        assertEquals(1, harness.streams.requests.size);
        assertEquals(SpfnEventStreamState.Open(1), harness.events.state.value);
        job.cancel();
    }

    @Test
    fun l2_detach_removesQueue_connectionStays() = runTest {
        val harness = Harness(this);
        harness.open(this);
        val job = backgroundScope.launch { harness.events.listen(Activity).collect { } };
        runCurrent();
        job.cancel();
        runCurrent();
        harness.connection.send(ServerFrames.activity(1, "s-1", "w-1"));
        runCurrent();
        assertEquals(SpfnEventStreamState.Open(1), harness.events.state.value);
        assertEquals(1, harness.tokens.tokenRequests.size);
        assertTrue(!harness.connection.cancelled);
    }

    @Test
    fun l3_unknownName_isRefused()
    {
        val configuration = SpfnEventStreamConfiguration(events = listOf("sessionActivity"));
        assertTrue(configuration.contains("sessionActivity"));
        assertTrue(!configuration.contains("sessionUnread"));
        runTest {
            val harness = Harness(this);
            assertThrows(IllegalArgumentException::class.java) { harness.events.listen("somethingElse", { it }) };
        };
    }

    @Test
    fun l4_overflow_replacesQueueWithOneReread()
    {
        val hub = hub(buffer = 2);
        val slow = hub.activity();
        val other = hub.activity();
        repeat(3) { hub.deliver("sessionActivity", envelope("s-$it", "w-1")) };
        assertEquals(listOf(SpfnEventSignal.Reread(SpfnRereadCause.Overflow)), slow.queue.toList());
        repeat(2) { hub.deliver("sessionActivity", envelope("s-next-$it", "w-1")) };
        assertEquals(3, slow.queue.size);
        assertEquals(SpfnEventSignal.Reread(SpfnRereadCause.Overflow), other.queue.first());
    }

    @Test
    fun l5_attachWhileNotOpen_getsAttachedOnly() = runTest {
        val harness = Harness(this);
        val signals = mutableListOf<SpfnEventSignal<Activity>>();
        val job = backgroundScope.launch { harness.events.listen(Activity).collect { signals.add(it) } };
        runCurrent();
        assertEquals(listOf(attached), signals);
        assertEquals(SpfnEventStreamState.Idle(SpfnIdleReason.SIGNED_OUT), harness.events.state.value);

        harness.open(this);
        assertEquals(listOf(attached, SpfnEventSignal.Reread(SpfnRereadCause.Opened(1))), signals);
        job.cancel();
    }

    @Test
    fun l6_navigation_doesNotReconnect() = runTest {
        val harness = Harness(this);
        harness.open(this);
        repeat(10)
        {
            val screenA = backgroundScope.launch { harness.events.listen(Activity).collect { } };
            runCurrent();
            screenA.cancel();
            val screenB = backgroundScope.launch { harness.events.listen("sessionUnread", { it }).collect { } };
            runCurrent();
            screenB.cancel();
            runCurrent();
        };
        assertEquals(1, harness.tokens.tokenRequests.size);
        assertEquals(1, harness.streams.requests.size);
        assertEquals(SpfnEventStreamState.Open(1), harness.events.state.value);
    }

    @Test
    fun l7_twoListenersNoCondition_bothGetFrame()
    {
        val hub = hub();
        val first = hub.activity();
        val second = hub.activity();
        hub.deliver("sessionActivity", envelope("s-1", "w-1"));
        val frame = SpfnEventSignal.Frame(Activity("s-1", "w-1"));
        assertEquals(listOf(attached, frame), first.queue.toList());
        assertEquals(listOf(attached, frame), second.queue.toList());
    }

    @Test
    fun l9_conditionTrue_enqueuesFrame()
    {
        val hub = hub();
        val listener = hub.activity { it.wsId == "w-1" };
        hub.deliver("sessionActivity", envelope("s-1", "w-1"));
        assertEquals(listOf(attached, SpfnEventSignal.Frame(Activity("s-1", "w-1"))), listener.queue.toList());
    }

    @Test
    fun l10_conditionFalse_countsFilteredAndSendsNothing()
    {
        val hub = hub();
        val listener = hub.activity { it.wsId == "w-1" };
        hub.deliver("sessionActivity", envelope("s-1", "w-2"));
        assertEquals(listOf(attached), listener.queue.toList());
        assertEquals(SpfnEventDiagnostics(filteredFrames = 1), hub.diagnostics.value);
    }

    @Test
    fun l10_filteredFrames_doNotOverflow()
    {
        val hub = hub(buffer = 3);
        val listener = hub.activity { it.wsId == "w-1" };
        repeat(6) { hub.deliver("sessionActivity", envelope("s-$it", "w-2")) };
        assertEquals(listOf(attached), listener.queue.toList());
        assertEquals(6L, hub.diagnostics.value.filteredFrames);
    }

    @Test
    fun l11_decodeFailure_skipsCondition_countsDropped()
    {
        val hub = hub();
        var conditionCalls = 0;
        val listener = hub.attach<Activity>("sessionActivity", { throw IllegalArgumentException("unreadable") }, { conditionCalls += 1; true });
        hub.deliver("sessionActivity", envelope("s-1", "w-1"));
        hub.deliver("sessionActivity", "not json");
        assertEquals(0, conditionCalls);
        assertEquals(listOf(attached), listener.queue.toList());
        assertEquals(SpfnEventDiagnostics(droppedFrames = 2), hub.diagnostics.value);
    }

    @Test
    fun l12_conditionThrows_endsOnlyThatListener() = runTest {
        val harness = Harness(this);
        harness.open(this);
        val failure = IllegalStateException("condition bug");
        var ended: Throwable? = null;
        val healthy = mutableListOf<SpfnEventSignal<Activity>>();
        backgroundScope.launch {
            harness.events.listen(Activity, where = { throw failure }).catch { ended = it }.collect { };
        };
        backgroundScope.launch { harness.events.listen(Activity).collect { healthy.add(it) } };
        runCurrent();
        harness.connection.send(ServerFrames.activity(1, "s-1", "w-1"));
        runCurrent();
        harness.connection.send(ServerFrames.activity(2, "s-2", "w-1"));
        runCurrent();
        assertSame(failure, ended);
        assertEquals(listOf(attached, SpfnEventSignal.Frame(Activity("s-1", "w-1")), SpfnEventSignal.Frame(Activity("s-2", "w-1"))), healthy);
        assertEquals(SpfnEventStreamState.Open(1), harness.events.state.value);
        assertEquals(1, harness.tokens.tokenRequests.size);
    }

    @Test
    fun l13_reread_ignoresCondition()
    {
        val hub = hub(buffer = 1);
        val listener = hub.activity { false };
        hub.reread(3);
        assertEquals(listOf(SpfnEventSignal.Reread(SpfnRereadCause.Opened(3))), listener.queue.toList());

        val overflowing = hub(buffer = 1);
        val never = overflowing.attach("sessionActivity", Activity::decode) { false };
        val always = overflowing.activity();
        repeat(2) { overflowing.deliver("sessionActivity", envelope("s-$it", "w-1")) };
        assertEquals(listOf(attached), never.queue.toList());
        assertEquals(listOf(SpfnEventSignal.Reread(SpfnRereadCause.Overflow)), always.queue.toList());
        assertEquals(attached, overflowing.attach("sessionActivity", Activity::decode) { false }.queue.first());
    }

    @Test
    fun l14_twoConditions_sameName_eachGetsOwnMatches()
    {
        val hub = hub();
        val home = hub.activity { it.wsId == "w-a" };
        val detail = hub.activity { it.sessionId == "s-x" };
        hub.deliver("sessionActivity", envelope("s-x", "w-a"));
        hub.deliver("sessionActivity", envelope("s-y", "w-a"));
        hub.deliver("sessionActivity", envelope("s-x", "w-b"));
        hub.deliver("sessionActivity", envelope("s-z", "w-c"));
        assertEquals(listOf("s-x/w-a", "s-y/w-a"), frames(home));
        assertEquals(listOf("s-x/w-a", "s-x/w-b"), frames(detail));
        assertEquals(4L, hub.diagnostics.value.filteredFrames);
    }

    @Test
    fun l15_conditionChange_reattaches_withoutReconnect() = runTest {
        val harness = Harness(this);
        harness.open(this);
        val seen = mutableListOf<SpfnEventSignal<Activity>>();
        val first = backgroundScope.launch { harness.events.listen(Activity, where = { it.wsId == "w-a" }).collect { seen.add(it) } };
        runCurrent();
        first.cancel();
        val second = backgroundScope.launch { harness.events.listen(Activity, where = { it.wsId == "w-b" }).collect { seen.add(it) } };
        runCurrent();
        harness.connection.send(ServerFrames.activity(1, "s-1", "w-a"));
        harness.connection.send(ServerFrames.activity(2, "s-2", "w-b"));
        runCurrent();
        assertEquals(listOf(attached, attached, SpfnEventSignal.Frame(Activity("s-2", "w-b"))), seen);
        assertEquals(1, harness.tokens.tokenRequests.size);
        second.cancel();
    }

    @Test
    fun l16_unavailableName_signalsItsListenersOnce()
    {
        val hub = hub();
        val missing = hub.attach("sessionUnread", { it }, { true });
        val present = hub.activity();
        hub.markUnavailable(listOf("sessionUnread"));
        hub.markUnavailable(listOf("sessionUnread"));
        hub.reread(1);
        assertEquals(listOf(SpfnEventSignal.Unavailable, SpfnEventSignal.Reread(SpfnRereadCause.Opened(1))), missing.queue.toList());
        assertEquals(listOf(SpfnEventSignal.Reread(SpfnRereadCause.Opened(1))), present.queue.toList());
    }

    @Test
    fun l17_attachToUnavailableName_getsAttachedThenUnavailable()
    {
        val hub = hub();
        hub.markUnavailable(listOf("sessionUnread"));
        val late = hub.attach("sessionUnread", { it }, { true });
        assertEquals(listOf(attached, SpfnEventSignal.Unavailable), late.queue.toList());
    }

    @Test
    fun l18_unavailableNames_leaveOtherListenersAndConnectionAlone() = runTest {
        val body = "{\"error\":\"Invalid event names\",\"invalidEvents\":[\"sessionUnread\"],\"validEvents\":[\"sessionActivity\"]}";
        val refusal = SpfnFakeStreamTransport();
        val tokens = TokenServer();
        val session = SpfnSession(tokens, ExecuteFixtures.syntheticProvider(), "https://example.invalid", FakeClock(SessionFixtureValues.ISSUED_AT_MILLIS), ScriptedNonceGenerator(emptyList()));
        val events = SpfnEventStream(
            SpfnClient(tokens, session), session,
            SpfnEventStreamConfiguration(events = listOf("sessionActivity", "sessionUnread")),
            refusal, backgroundScope, jitter = { 1.0 }
        );
        val rejected = refusal.enqueue(400, listOf("content-type" to "application/json"));
        rejected.send(body);
        rejected.finish();
        val narrowed = refusal.enqueue();
        val unread = mutableListOf<SpfnEventSignal<SpfnCanonicalValue>>();
        val activity = mutableListOf<SpfnEventSignal<Activity>>();
        backgroundScope.launch { events.listen("sessionUnread", { it }).collect { unread.add(it) } };
        backgroundScope.launch { events.listen(Activity).collect { activity.add(it) } };
        events.setSignedIn("client-test-0001");
        events.setForeground(true);
        runCurrent();
        narrowed.send(ServerFrames.CONNECTED + ServerFrames.activity(1, "s-1", "w-1"));
        runCurrent();

        assertEquals(SpfnEventStreamState.Open(1, listOf("sessionUnread")), events.state.value);
        assertTrue(refusal.requests.last().url.endsWith("&events=sessionActivity"));
        assertEquals(listOf(attached, SpfnEventSignal.Unavailable, SpfnEventSignal.Reread(SpfnRereadCause.Opened(1))), unread);
        assertEquals(
            listOf(attached, SpfnEventSignal.Reread(SpfnRereadCause.Opened(1)), SpfnEventSignal.Frame(Activity("s-1", "w-1"))),
            activity
        );
    }

    @Test
    fun payload_listen_equalsNameListen() = runTest {
        val harness = Harness(this);
        harness.open(this);
        val byPayload = mutableListOf<SpfnEventSignal<Activity>>();
        val byName = mutableListOf<SpfnEventSignal<Activity>>();
        backgroundScope.launch { harness.events.listen(Activity, where = { it.wsId == "w-1" }).collect { byPayload.add(it) } };
        backgroundScope.launch { harness.events.listen("sessionActivity", Activity::decode, where = { it.wsId == "w-1" }).collect { byName.add(it) } };
        runCurrent();
        harness.connection.send(ServerFrames.activity(1, "s-1", "w-1") + ServerFrames.activity(2, "s-2", "w-2"));
        runCurrent();
        assertEquals(byName, byPayload);
        assertEquals(2, byPayload.size);
    }

    private fun frames(listener: SpfnEventListenerHub.Listener<Activity>): List<String> =
        listener.queue.filterIsInstance<SpfnEventSignal.Frame<Activity>>().map { "${it.value.sessionId}/${it.value.wsId}" };
}
