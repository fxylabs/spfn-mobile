package xyz.superfunction.spfn.harness

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.superfunction.spfn.ui.Busy

/**
 * What owns the harness: one model, one screen state, and the scope every tap runs in.
 *
 * The screen itself is [HarnessScreen]. This half is the three things a composable cannot
 * hold — the model, a coroutine scope that dies with the Activity, and the Toast — and the
 * wiring between them. [HarnessScreenState] is the join: the model answers with plain
 * fields and a callback, and [HarnessScreenState.readFrom] copies them into snapshot state
 * on the main thread, which is what the old view tree's `render()` did with fifteen
 * `setText` calls.
 *
 * A tap answers in three ways, and the readouts are none of them. The readouts are the
 * machine-readable truth and they are written for a flow: a person who taps a button and
 * watches one word two lines up change from `ready` to `busy` and back inside a second has
 * watched nothing happen. So every control changes under a finger, the one action that
 * outlives its tap by seconds shows a marker while it runs, and every action ends in a
 * Toast naming what it did ([announce]). None of the three is a readout and no flow may
 * read one — see [announce] for what keeps a flow's selector off the Toast.
 */
class HarnessActivity : ComponentActivity()
{
    /**
     * Where every tap's work runs, owned here and cancelled in [onDestroy].
     *
     * The Activity's rather than a composition's: an action that survives a
     * recomposition — a provider sheet, a device code somebody is walking to another phone
     * with — must not be cancelled by one, and `rememberCoroutineScope` dies with the
     * composable that remembered it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main);

    private lateinit var model: HarnessModel;

    private val screen = HarnessScreenState();

    /**
     * The Toast currently on screen, held only so the next one can replace it.
     *
     * Toasts QUEUE. A Maestro run taps ten buttons in a few seconds, and ten queued
     * `Toast.LENGTH_LONG` signals would still be arriving half a minute after the run that
     * produced them, each naming a result that had already been superseded.
     */
    private var signal: Toast? = null;

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState);
        model = HarnessModel(this, HarnessConfiguration.fromLaunch(intent));
        // The model posts the callback onto the main thread and then calls this, so the
        // code reaches the screen the moment the server answers rather than when the whole
        // sign-in finishes — which is the point of showing a code at all.
        model.onDeviceCodeShown = { screen.readFrom(model) };

        val actions = actions();
        setContent { HarnessScreen(screen, actions) };

        model.refresh();
        screen.readFrom(model);
    }

    override fun onDestroy()
    {
        scope.cancel();
        // A Toast outlives the Activity that showed it. One left running would go on
        // reporting a result over whatever screen replaced this one.
        signal?.cancel();
        signal = null;
        super.onDestroy();
    }

    /**
     * Every tappable thing on the screen, with the tag a flow finds it by.
     *
     * Built once and handed to the composition, because these lambdas close over the model
     * and the scope, and neither of those is a thing a recomposition should be able to
     * replace.
     */
    private fun actions(): HarnessActions = HarnessActions(
        // Selecting is instant work, so it does not go through [perform]: showing
        // `busy=busy` for a field assignment would teach a flow to wait for nothing.
        selectCase = { case -> model.selectSocialCase(case); screen.readFrom(model) },
        socialSignIn = HarnessAction("btn_social_google", "sign-in-google")
        {
            // The signal names the outcome and the receipt's FILE NAME, and nothing else
            // the receipt holds. Everything else in that file is evidence about an account,
            // and a Toast is the one part of this screen that survives into a photograph.
            perform(attempt = true, signal = { "${model.outcome}\n${model.receipt}" })
            {
                model.signInWithGoogle(this@HarnessActivity);
            }
        },
        deviceSignIn = action("btn_device_sign_in", "sign-in-with-a-code") { model.signInWithACode() },
        setApproverCode = { code -> model.setApproverCode(code) },
        approver = listOf(
            action("btn_device_info", "device-info") { model.describeWaitingDevice() },
            action("btn_device_approve", "device-approve") { model.approveWaitingDevice() },
            action("btn_device_deny", "device-deny") { model.denyWaitingDevice() }
        ),
        lifecycle = lifecycleActions()
    );

    /**
     * The ten buttons the flows tap, in the order the flows expect to find them.
     *
     * The title is lowercase and uncapitalised, so what a reader sees on the phone is the
     * same word the flow file names.
     */
    private fun lifecycleActions(): List<HarnessAction> = listOf(
        action("btn_enroll", "enroll") { model.enroll() },
        action("btn_rotate", "rotate") { model.rotate() },
        action("btn_resume", "resume") { model.resumeRotation() },
        action("btn_revoke", "revoke") { model.revokeActiveKey() },
        action("btn_proven_call", "proven-call") { model.provenCall() },
        action("btn_note_revoked", "note-revoked") { model.noteSessionRevoked() },
        action("btn_wipe", "wipe") { model.wipe() },
        action("btn_custody_probe", "custody-probe") { model.probeCustody() },
        action("btn_block_network", "block-network") { model.setNetworkBlocked(true) },
        action("btn_open_network", "open-network") { model.setNetworkBlocked(false) }
    );

    /**
     * One action whose signal is the `outcome=` value it already produces and nothing more.
     *
     * These buttons write no receipt, and inventing a second vocabulary for the Toast would
     * give a reader two names for one result.
     */
    private fun action(tag: String, title: String, run: suspend () -> Unit): HarnessAction =
        HarnessAction(tag, title) { perform(attempt = false, signal = { model.outcome }, action = run) };

    /**
     * The network work leaves the main thread; the screen state is written back on it. A
     * flow waits for `busy=ready` rather than sleeping for a guessed number of seconds, and
     * the flag is set HERE, synchronously inside the tap, so there is no window where a
     * started action still reads as finished.
     *
     * [attempt] shows the running marker for exactly as long as the action runs, and
     * [signal] is read AFTER it finishes — it is a lambda rather than a string for that
     * reason alone, since the outcome it names does not exist yet at the moment of the tap.
     *
     * The restore is a `finally` and the announcement is not. A cancelled coroutine is the
     * screen going away rather than an action finishing, so the marker and the busy flag
     * are put back and nothing is announced: a Toast is a claim that something completed
     * (P16), and one shown here would appear over whatever screen replaced this one.
     */
    private fun perform(attempt: Boolean, signal: () -> String, action: suspend () -> Unit)
    {
        screen.busy = Busy.Busy;
        screen.attemptRunning = attempt;
        screen.readFrom(model);
        scope.launch {
            try
            {
                withContext(Dispatchers.IO) { action() };
            }
            finally
            {
                screen.busy = Busy.Idle;
                screen.attemptRunning = false;
                screen.readFrom(model);
            }
            announce(signal());
        };
    }

    /**
     * The completion signal a tap owes the person who made it.
     *
     * A Toast and not a label, because the point is that it ARRIVES: a value that changes
     * in place is only noticed by someone already looking at it, and the operator's eyes
     * are on the button they just pressed.
     *
     * The text carries NO readout prefix — `ok:wiped`, not `outcome=ok:wiped`. Every flow
     * selector in tools/harness/flows/ matches either a control's id or a readout's text
     * (`outcome=…`, `state=…`, `busy=…`), and dropping the prefix is what makes it
     * impossible for one of them to match this window instead of the label it means. A
     * transient signal a flow could assert on is a flow that passes because a Toast was
     * still up (docs/IMPLEMENTATION-PITFALLS.md P7).
     *
     * The text is ALL that keeps a flow off this window, and the iOS half has a second
     * lock the Android half cannot have. A SwiftUI banner is marked `accessibilityHidden`,
     * which deletes it from the hierarchy a flow searches; a Toast announces itself to the
     * accessibility layer by design, because being heard is what a Toast is for. Same
     * rule, different strength, and the strength is the platform's rather than this
     * file's (P15).
     */
    private fun announce(text: String)
    {
        signal?.cancel();
        val toast = Toast.makeText(this, text, Toast.LENGTH_LONG);
        signal = toast;
        toast.show();
    }
}
