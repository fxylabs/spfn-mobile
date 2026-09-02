// SPFN Mobile — the two clocks a test can move, and the difference between them.
//
// The difference is the bug this file exists to keep fixed. A launched server on a frozen
// clock passed every test here that asked whether `advance` works, and still refused every
// proof the Swift suite sent: its client anchors to `core.time` once and then runs on its
// own monotonic source, so a server that does not move is behind that client a millisecond
// later. So "it ticks" is asserted against real elapsed time, with a strict inequality —
// a frozen clock reads equal, and `>=` would have let it through.

package xyz.superfunction.spfn.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import java.util.concurrent.atomic.AtomicLong

class SpfnReferenceClockTest
{
    /**
     * Two reads far enough apart that the answer cannot be a rounding coincidence: the
     * clock is asserted to have moved by at least the time that really passed, which a
     * clock only `advance` moves cannot do. Sleeping is what the rest of this suite avoids
     * on purpose, and is unavoidable in the one test whose subject is time passing.
     */
    @Test
    fun `a ticking clock moves on its own between two reads`()
    {
        val clock = SpfnReferenceTickingClock(START_MILLIS);

        val first = clock.nowMillis();
        Thread.sleep(SLEEP_MILLIS);
        val second = clock.nowMillis();

        assertTrue("$second did not move past $first", second > first);
        assertTrue("$second - $first is less than the $SLEEP_MILLIS ms that passed", second - first >= SLEEP_MILLIS);
        assertTrue("$first is not the instant the clock was started at", first >= START_MILLIS);
    }

    /**
     * And an advance adds to the ticking rather than replacing it, which is only sayable
     * exactly against a monotonic source the test owns. The real source is what the case
     * above uses; here it is injected so the arithmetic is an equality and not a range.
     */
    @Test
    fun `an advance moves a ticking clock on top of the time that passed`()
    {
        val monotonic = AtomicLong(SOME_NANOS);
        val clock = SpfnReferenceTickingClock(START_MILLIS, monotonicNanos = { monotonic.get() });

        monotonic.addAndGet(7 * NANOS_PER_MILLISECOND);
        assertEquals(START_MILLIS + 7, clock.nowMillis());

        clock.advance(60_000);
        assertEquals(START_MILLIS + 7 + 60_000, clock.nowMillis());

        monotonic.addAndGet(3 * NANOS_PER_MILLISECOND);
        assertEquals("the advance is kept, not restarted from", START_MILLIS + 10 + 60_000, clock.nowMillis());
    }

    /**
     * The frozen clock stays frozen. The unit suites read the instant a request was judged
     * at and assert it exactly, so a clock that drifted a millisecond between the advance
     * and the assertion would make those tests fail for a reason none of them names.
     */
    @Test
    fun `the frozen clock moves only when a test moves it`()
    {
        val clock = SpfnReferenceTestClock(START_MILLIS);

        Thread.sleep(SLEEP_MILLIS);
        assertEquals("time passing must not move the frozen clock", START_MILLIS, clock.nowMillis());

        clock.advance(60_000);
        assertEquals(START_MILLIS + 60_000, clock.nowMillis());
    }

    /**
     * `/control/advance-clock` takes both kinds. The ticking one and the refusal on the
     * wall clock are covered in `SpfnReferenceMainTest`, where a launch is what builds
     * them; the frozen one is only reachable through a harness, which is here.
     */
    @Test
    fun `the control route moves the frozen clock a unit harness runs on`()
    {
        SpfnReferenceHarness().use { harness ->
            val before = harness.clock.nowMillis();

            val answer = harness.send(
                "POST",
                SpfnReferenceControl.ADVANCE_CLOCK,
                SpfnCanonicalJson.encode(
                    SpfnCanonicalValue.Obj(mapOf("millis" to SpfnCanonicalValue.Integer(60_000)))
                ),
                listOf(SpfnReferenceControl.TOKEN_HEADER to harness.server.controlToken)
            );

            assertEquals(String(answer.body), 200, answer.statusCode);
            assertEquals(before + 60_000, harness.clock.nowMillis());
        }
    }

    private companion object
    {
        const val START_MILLIS = SpfnReferenceTestClock.DEFAULT_START_MILLIS

        /** Long enough that a clock which really ticks cannot read the same twice. */
        const val SLEEP_MILLIS = 25L

        const val NANOS_PER_MILLISECOND = 1_000_000L

        /** An arbitrary monotonic reading: the source's origin is not the epoch. */
        const val SOME_NANOS = 987_654_321_000L
    }
}
