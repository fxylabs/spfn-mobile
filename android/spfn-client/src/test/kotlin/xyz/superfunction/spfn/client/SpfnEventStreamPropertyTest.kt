// SPFN Mobile — the event stream's configuration, backoff and invariants
// (docs/architecture/event-stream-design.md §9-1, "추가로").
//
// The invariants run a thousand random inputs through the machine under a fixed seed, so a
// failure replays exactly. SPFNEventStreamPropertyTests.swift runs the same properties
// under the same seed; the two generators are not the same sequence, the properties are.

package xyz.superfunction.spfn.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpfnEventStreamPropertyTest
{
    private val configuration = SpfnEventStreamConfiguration(events = listOf("sessionActivity", "sessionUnread"));
    private val token = SpfnEventStreamToken("t".repeat(64));

    @Test
    fun backoff_sequence()
    {
        val backoff = SpfnEventStreamBackoff.Standard;
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..7).map { backoff.delayMillis(it, 1.0) });
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L), (1..6).map { backoff.delayMillis(it, 0.5) });
        assertEquals(500L, backoff.delayMillis(1, 0.0));
    }

    @Test
    fun tokenPath_derived()
    {
        assertEquals("/events/token", SpfnEventStreamConfiguration(events = listOf("a")).tokenPath);
        assertEquals("/token", SpfnEventStreamConfiguration(events = listOf("a"), streamPath = "/sse").tokenPath);
        assertEquals("/a/b/token", SpfnEventStreamConfiguration(events = listOf("a"), streamPath = "/a/b/c").tokenPath);
        assertEquals("/custom/mint", SpfnEventStreamConfiguration(events = listOf("a"), tokenPath = "/custom/mint").tokenPath);
        for (path in listOf("/events/stream?x=1", "events/stream", "/events#frag"))
        {
            assertThrows(IllegalArgumentException::class.java) { SpfnEventStreamConfiguration(events = listOf("a"), streamPath = path) };
        }
        assertThrows(IllegalArgumentException::class.java) { SpfnEventStreamConfiguration(events = listOf("a"), tokenPath = "/t?x") };
    }

    @Test
    fun configuration_rejectsEmptyEvents()
    {
        for (events in listOf(emptyList(), listOf(""), listOf("a,b"), listOf("ok", "")))
        {
            assertThrows(IllegalArgumentException::class.java) { SpfnEventStreamConfiguration(events = events) };
        }
        val deduplicated = SpfnEventStreamConfiguration(events = listOf("sessionUnread", "sessionActivity", "sessionUnread"));
        assertEquals(listOf("sessionActivity", "sessionUnread"), deduplicated.events);
        assertEquals(25_000L, deduplicated.silenceMillis);
    }

    @Test
    fun staleResult_ignored()
    {
        val machine = SpfnEventStreamMachine(configuration) { 1.0 };
        var state = machine.initial();
        for (input in listOf(SpfnEventInput.SetSignedIn("a"), SpfnEventInput.SetForeground(true)))
        {
            state = machine.step(state, input).state;
        }
        val issued = state.generation;
        state = machine.step(state, SpfnEventInput.TokenFailed(issued, SpfnTokenFailure.Network)).state;
        for (late in listOf(
            SpfnEventInput.TokenMinted(issued, token),
            SpfnEventInput.StreamAnswered(issued, SpfnStreamAnswer.EventStream),
            SpfnEventInput.StreamFailed(issued),
            SpfnEventInput.SilenceElapsed(issued),
            SpfnEventInput.RetryElapsed(issued),
            SpfnEventInput.StableElapsed(issued)
        ))
        {
            val step = machine.step(state, late);
            assertEquals(state, step.state);
            assertEquals(emptyList<SpfnEventEffect>(), step.effects);
        }
    }

    @Test
    fun epoch_monotonic()
    {
        walk(SEED) { before, after, _ -> assertTrue("epoch went from ${before.epoch} to ${after.epoch}", after.epoch >= before.epoch) };
    }

    @Test
    fun connection_iffForegroundAndSignedIn()
    {
        walk(SEED) { _, after, input ->
            val idle = after.phase is SpfnEventPhase.Idle;
            assertEquals("after $input the state was ${after.publicState} with F=${after.foreground} A=${after.clientId}", !after.wantsConnection, idle);
        };
    }

    @Test
    fun closed_leavesOnlyThroughCondition()
    {
        walk(SEED) { before, after, input ->
            if (before.phase is SpfnEventPhase.Closed && after.phase !is SpfnEventPhase.Closed)
            {
                val conditionChanged = before.foreground != after.foreground || before.clientId != after.clientId;
                assertTrue("closed was left by $input", conditionChanged);
            }
        };
    }

    /**
     * A thousand inputs chosen from the whole input vocabulary, each asynchronous result
     * carrying either the current generation or a stale one. `check` sees every step.
     */
    private fun walk(seed: Int, check: (SpfnEventMachineState, SpfnEventMachineState, SpfnEventInput) -> Unit)
    {
        val random = Random(seed);
        val machine = SpfnEventStreamMachine(configuration) { random.nextDouble(0.5, 1.0) };
        var state = machine.initial();
        var closedSeen = false;
        repeat(1_000)
        {
            val input = randomInput(random, state);
            val after = machine.step(state, input).state;
            check(state, after, input);
            closedSeen = closedSeen || after.phase is SpfnEventPhase.Closed;
            state = after;
        };
        assertTrue("the walk never reached closed; widen the generator", closedSeen);
    }

    private fun randomInput(random: Random, state: SpfnEventMachineState): SpfnEventInput
    {
        val generation = if (random.nextInt(4) == 0) state.generation - 1 else state.generation;
        return when (random.nextInt(16))
        {
            // Weighted toward the connection condition, or the walk spends itself in idle.
            0 -> SpfnEventInput.SetForeground(random.nextInt(5) != 0)
            1 -> SpfnEventInput.SetSignedIn(listOf(null, "a", "a", "b", "b")[random.nextInt(5)])
            2 -> SpfnEventInput.SetNetworkAvailable(random.nextInt(5) != 0)
            3 -> SpfnEventInput.TokenMinted(generation, token)
            4 -> SpfnEventInput.TokenFailed(generation, TOKEN_FAILURES[random.nextInt(TOKEN_FAILURES.size)])
            5 -> SpfnEventInput.StreamAnswered(generation, STREAM_ANSWERS[random.nextInt(STREAM_ANSWERS.size)])
            6, 7 -> SpfnEventInput.StreamAnswered(generation, SpfnStreamAnswer.EventStream)
            8, 9 -> SpfnEventInput.FrameReceived(generation, SpfnSseEvent("connected", "{}"))
            10 -> SpfnEventInput.FrameReceived(generation, SpfnSseEvent("sessionActivity", "{}"))
            11 -> SpfnEventInput.StreamFailed(generation)
            12 -> SpfnEventInput.StreamEnded(generation)
            13 -> SpfnEventInput.SilenceElapsed(generation)
            14 -> SpfnEventInput.RetryElapsed(generation)
            else -> SpfnEventInput.StableElapsed(generation)
        };
    }

    private companion object
    {
        const val SEED = 20_260_927;

        val TOKEN_FAILURES: List<SpfnTokenFailure> = listOf(
            SpfnTokenFailure.Unauthorized,
            SpfnTokenFailure.Forbidden,
            SpfnTokenFailure.ServerError(503, null),
            SpfnTokenFailure.ServerError(429, 5_000),
            SpfnTokenFailure.Network,
            SpfnTokenFailure.Unreadable
        );

        val STREAM_ANSWERS: List<SpfnStreamAnswer> = listOf(
            SpfnStreamAnswer.NotEventStream,
            SpfnStreamAnswer.TokenRejected,
            SpfnStreamAnswer.InvalidEvents(listOf("sessionUnread"), listOf("sessionActivity")),
            SpfnStreamAnswer.BadRequest,
            SpfnStreamAnswer.Forbidden,
            SpfnStreamAnswer.ServerError(502, null)
        );
    }
}
