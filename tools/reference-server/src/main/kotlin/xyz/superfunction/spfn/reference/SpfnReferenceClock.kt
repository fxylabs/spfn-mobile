// SPFN Mobile — the reference server's clock.
//
// The server judges session expiry and the proof replay window against a clock, so a
// server that read the wall clock directly would make "the session expired" something a
// test can only reach by sleeping. Sleeping is how a suite becomes both slow and flaky,
// so the clock is injected and the deterministic tests move it by hand.
//
// There are two clocks a test can move, and the difference between them is the whole
// point of this file. The unit suites run in the same process as the server and hold both
// ends of the timeline, so their clock is FROZEN: it moves only when a test says so, and
// an expiry is exactly the advance that caused it. A launched server has a client in
// another process whose proof clock anchors to `core.time` and then runs on that
// machine's monotonic source, so a frozen server would be overtaken within a millisecond
// and refuse every proof as future-dated. Its clock TICKS, and `advance` moves it on top
// of the ticking.
//
// A launch uses the system clock unless `SpfnReferenceMain` is given `--test-clock`,
// which the integration runner passes exactly when it tells the suites the clock moves.

package xyz.superfunction.spfn.reference

import java.util.concurrent.atomic.AtomicLong

/** Milliseconds since the Unix epoch, as the server reads them. */
fun interface SpfnReferenceClock
{
    fun nowMillis(): Long

    companion object
    {
        /** The wall clock. What a launched server runs on unless `--test-clock` says otherwise. */
        fun system(): SpfnReferenceClock = SpfnReferenceClock { System.currentTimeMillis() }
    }
}

/**
 * A clock `/control/advance-clock` can move.
 *
 * The route asks for this and not for a concrete class, because the two clocks a test may
 * be given differ in whether they also move on their own — which is a fact about the
 * server that route has no reason to know.
 */
interface SpfnReferenceMovableClock : SpfnReferenceClock
{
    /** Moves the clock forward. A test that needs an expiry calls this instead of sleeping. */
    fun advance(millis: Long)
}

/**
 * A clock that moves only by hand. What the in-process unit suites run on.
 *
 * Starts at an ordinary epoch instant rather than at zero, because a client proof is
 * refused when its `issuedAtMillis` is outside the replay window of the server's clock:
 * a server sitting at zero would refuse every proof a real client clock ever produced,
 * and the test would be measuring the gap between two epochs instead of the rule.
 */
class SpfnReferenceTestClock(startMillis: Long = DEFAULT_START_MILLIS) : SpfnReferenceMovableClock
{
    private val lock = Any()
    private var current: Long = startMillis

    override fun nowMillis(): Long = synchronized(lock) { current }

    override fun advance(millis: Long)
    {
        synchronized(lock) { current += millis };
    }

    companion object
    {
        /** 2025-06-15T14:26:40Z, the instant the conformance fixtures are written around. */
        const val DEFAULT_START_MILLIS: Long = 1_750_000_000_000
    }
}

/**
 * A clock that starts at a chosen instant, then runs at the rate of real time — and can
 * still be moved forward by hand. What a launched server runs on under `--test-clock`.
 *
 * Elapsed time comes from [monotonicNanos] rather than from the wall clock, so an
 * operator changing the machine's date mid-run cannot move this clock backwards and
 * cannot make a live session look expired.
 */
class SpfnReferenceTickingClock(
    private val startMillis: Long = SpfnReferenceTestClock.DEFAULT_START_MILLIS,
    private val monotonicNanos: () -> Long = System::nanoTime
) : SpfnReferenceMovableClock
{
    private val monotonicAtStart: Long = monotonicNanos()
    private val advanced = AtomicLong(0)

    override fun nowMillis(): Long =
        startMillis + (monotonicNanos() - monotonicAtStart) / NANOS_PER_MILLISECOND + advanced.get()

    override fun advance(millis: Long)
    {
        advanced.addAndGet(millis);
    }

    private companion object
    {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
