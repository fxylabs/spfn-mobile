// SPFN Mobile — the reference server's clock.
//
// The server judges session expiry and the proof replay window against a clock, so a
// server that read the wall clock directly would make "the session expired" something a
// test can only reach by sleeping. Sleeping is how a suite becomes both slow and flaky,
// so the clock is injected and the deterministic tests move it by hand.
//
// A real launch uses the system clock. `SpfnReferenceMain` never installs a test clock.

package xyz.superfunction.spfn.reference

/** Milliseconds since the Unix epoch, as the server reads them. */
fun interface SpfnReferenceClock
{
    fun nowMillis(): Long

    companion object
    {
        /** The wall clock. What a launched server always runs on. */
        fun system(): SpfnReferenceClock = SpfnReferenceClock { System.currentTimeMillis() }
    }
}

/**
 * A clock a test moves by hand.
 *
 * Starts at an ordinary epoch instant rather than at zero, because a client proof is
 * refused when its `issuedAtMillis` is outside the replay window of the server's clock:
 * a server sitting at zero would refuse every proof a real client clock ever produced,
 * and the test would be measuring the gap between two epochs instead of the rule.
 */
class SpfnReferenceTestClock(startMillis: Long = DEFAULT_START_MILLIS) : SpfnReferenceClock
{
    private val lock = Any()
    private var current: Long = startMillis

    override fun nowMillis(): Long = synchronized(lock) { current }

    /** Moves the clock forward. A test that needs an expiry calls this instead of sleeping. */
    fun advance(millis: Long)
    {
        synchronized(lock) { current += millis };
    }

    companion object
    {
        /** 2025-06-15T14:26:40Z, the instant the conformance fixtures are written around. */
        const val DEFAULT_START_MILLIS: Long = 1_750_000_000_000
    }
}
