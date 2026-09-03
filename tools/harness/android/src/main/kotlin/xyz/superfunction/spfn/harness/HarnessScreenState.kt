package xyz.superfunction.spfn.harness

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import xyz.superfunction.spfn.ui.Busy

/**
 * What the screen is currently drawing, as Compose state.
 *
 * [HarnessModel] holds plain fields and answers a callback when one of them changes; a
 * composable redraws when a snapshot state it read changes. This class is the whole of
 * the join between those two, and it is deliberately thin: it copies, it does not decide.
 * The model is the harness's behaviour and stays the one place that has any.
 *
 * [readFrom] is this screen's `render()`. The old view tree called that method after every
 * tap, after every model callback and once a second while a code was on screen; the same
 * calls are here, and what they now do is write these fields rather than assign text to
 * fifteen views.
 *
 * **Written on the main thread only.** Snapshot state is not a thread-safe channel, and
 * the model runs its actions on the IO dispatcher. Every path that reaches [readFrom]
 * arrives on the main thread already: an action's completion through the Activity's
 * main-dispatcher scope, and the device-code callback through the model's own
 * `Handler(Looper.getMainLooper())` post, which is why that post is left exactly as it was.
 */
class HarnessScreenState
{
    /** The SDK's lifecycle state, as the model reports it. */
    var state: String by mutableStateOf("unread");

    /** What the last action did. */
    var outcome: String by mutableStateOf("idle");

    /**
     * Whether an action is running.
     *
     * The screen's own, not the model's, and the reason is a bug this screen already had:
     * it was a parameter to `render`, and a case button repainted with `busy=false` while
     * another action was still in flight — a readout that told a flow to stop waiting for
     * something that had not finished. One piece of state, written where it changes.
     */
    var busy: Busy by mutableStateOf(Busy.Idle);

    /** Whether the transport is refusing to send. */
    var networkBlocked: Boolean by mutableStateOf(false);

    /** The custody a probe found, or `unread`. */
    var custody: String by mutableStateOf("unread");

    /** The case the next device sign-in will be recorded as. */
    var socialCase: HarnessSocialCase by mutableStateOf(HarnessSocialCase.FIRST_ENROLL);

    /** The receipt the last attempt wrote, or `none`. */
    var receipt: String by mutableStateOf("none");

    /** The code this device is showing while it waits to be approved, or `none`. */
    var deviceCode: String by mutableStateOf("none");

    /** When that code stops being usable, or null when none is showing. */
    var deviceCodeExpiresAtMillis: Long? by mutableStateOf(null);

    /**
     * The instant the countdown was last drawn at.
     *
     * A composable cannot read the clock and be redrawn when it moves, so the clock is a
     * state like any other and the ticker writes it. Nothing else on this screen changes
     * without somebody tapping.
     *
     * The one specialised state here, because it is the one that is written every second:
     * a plain `mutableStateOf` boxes each `Long` it is given, which lint reports and which
     * would be a piece of garbage per tick for a value nothing else reads.
     */
    var nowMillis: Long by mutableLongStateOf(0L);

    /**
     * Whether the device-mode attempt is running, which is not the same as [busy].
     *
     * The device-mode attempt is the only action on this screen that takes long enough for
     * a person to wonder whether the tap landed: it wipes, puts a provider sheet up, waits
     * for an account to be picked, enrols, and writes a file. It gets a marker beside the
     * button. The ten lifecycle actions do not — their work is one request and it is over
     * before a marker would have finished appearing, and a marker that flashes is noise.
     */
    var attemptRunning: Boolean by mutableStateOf(false);

    /** What this build may say about its configuration. Never the values themselves. */
    val social: String = HarnessSocialConfiguration.readout;

    /** Whether a device sign-in can run at all. Fixed at build time, so not a state. */
    val socialConfigured: Boolean = HarnessSocialConfiguration.isConfigured;

    /**
     * Copies everything the model owns, and touches nothing the screen owns.
     *
     * [busy] and [attemptRunning] are absent on purpose: they are the screen's answer to a
     * tap, and a copy that reset them would put `busy=ready` on screen in the middle of an
     * action the flows are still waiting on.
     */
    fun readFrom(model: HarnessModel)
    {
        state = model.state;
        outcome = model.outcome;
        networkBlocked = model.networkBlocked;
        custody = model.custody;
        socialCase = model.socialCase;
        receipt = model.receipt;
        deviceCode = model.deviceCode;
        deviceCodeExpiresAtMillis = model.deviceCodeExpiresAtMillis;
        nowMillis = System.currentTimeMillis();
    }
}
