package xyz.superfunction.spfn.harness

import xyz.superfunction.spfn.ui.Busy

/**
 * Every line the screen shows a flow, and nothing that draws one.
 *
 * A flow's selector is split by what it is looking at: a control it finds by id, and a
 * readout — whose whole point is that its value changes — it finds by TEXT, as a regex
 * (`text: "busy=ready"`, `text: "outcome=ok:rotated.*"`). So these strings are the
 * harness's wire protocol with tools/harness/flows/, and a screen that draws `busy=idle`
 * compiles, installs, and leaves thirteen flows waiting out their timeouts.
 *
 * They live here, apart from the composables, for exactly that reason: a composable is
 * proved on a phone and this is proved on a JVM, in the same run that builds it
 * (`HarnessReadoutTest`, docs/IMPLEMENTATION-PITFALLS.md P7).
 *
 * tools/harness/ios/Sources/HarnessView.swift draws the same lines on the other platform.
 */
object HarnessReadout
{
    /** `unenrolled`, `enrolled` or `rotationPending` — the SDK's state, in Swift's spelling. */
    fun state(value: String): String = "state=$value";

    /** `idle` before anything runs, then `ok:<detail>` or `err:<name>`. */
    fun outcome(value: String): String = "outcome=$value";

    /**
     * Whether an action is in flight, in the two words the flows wait on.
     *
     * A flow taps a button and then waits for `busy=ready` rather than sleeping for a
     * guessed number of seconds, so these two words are the whole synchronisation between
     * a runner and this app.
     *
     * [Busy.Error] reads as ready because it is over: the harness never constructs one —
     * a failed action is an `err:` in [outcome], which is the vocabulary the receipts and
     * the flows already share — and a state that means "not running" must not read as
     * running whichever way it got there.
     */
    fun busy(busy: Busy): String = when (busy)
    {
        is Busy.Idle -> "busy=ready"
        is Busy.Busy -> "busy=busy"
        is Busy.Error -> "busy=ready"
    };

    /**
     * Whether the transport is refusing to send, at all times rather than after a tap.
     *
     * A blocked switch mimics a real network drop exactly, which is what makes it worth
     * reading and impossible to notice: the first device run burned three attempts on a
     * transport left shut by an earlier case.
     */
    fun network(blocked: Boolean): String = if (blocked) "network=blocked" else "network=open";

    /** The custody a freshly generated key actually landed in, or `unread`. */
    fun custody(value: String): String = "custody=$value";

    /** The case the next device sign-in will be recorded as, in the shared spec's word. */
    fun case(case: HarnessSocialCase): String = "case=${case.wireName}";

    /**
     * Whether this build was configured, and never what it was configured with.
     *
     * A client id and a server address are what `local.properties` gave this build, and
     * neither belongs on a screen that ends up in a screenshot.
     */
    fun social(readout: String): String = "social=$readout";

    /** The file the last attempt left behind, or `none`. */
    fun receipt(value: String): String = "receipt=$value";

    /** The code this device is showing while it waits to be approved, or `none`. */
    fun deviceCode(value: String): String = "device-code=$value";

    /**
     * How deep the generated approval flow stands, and zero when it is closed.
     *
     * Spelled exactly as the generated screens spell it, because it is the same number
     * read off the same flow: a flow that is open draws this line twice — once here and
     * once on the screen covering this one — and the two agree by construction rather
     * than by anyone keeping them equal.
     */
    fun stack(depth: Int): String = "stack=$depth";

    /**
     * The status of the last response the transport received, or `none`.
     *
     * A record of the WIRE, not of an action: the generated screens send through the same
     * transport, and the refusal one of them carries is on its own `state=` readout. Both
     * are needed, and a run that read only the screen could not tell a refusal the server
     * sent from one the screen invented (docs/IMPLEMENTATION-PITFALLS.md P7).
     */
    fun http(statusCode: String): String = "http=$statusCode";

    /**
     * Whole seconds until the shown code expires, `expired` once it has, `-` when no code
     * is showing.
     *
     * [nowMillis] is passed in rather than read here so that the same instant can be
     * asserted twice: the screen holds the tick it last drew, and a test can name a
     * boundary. A countdown rather than an instant because what the person holding this
     * phone needs to know is how long they have to walk to the other one.
     */
    fun expiresIn(expiryMillis: Long?, nowMillis: Long): String
    {
        if (expiryMillis == null)
        {
            return "expires-in=-";
        }
        val remaining = expiryMillis - nowMillis;
        return if (remaining > 0) "expires-in=${remaining / 1_000}s" else "expires-in=expired";
    }
}
