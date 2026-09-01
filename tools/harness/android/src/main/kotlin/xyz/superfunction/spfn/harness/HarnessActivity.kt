package xyz.superfunction.spfn.harness

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The harness screen.
 *
 * Readouts, then the half a person drives, then the half the flows drive — all built in
 * code. This is not a sample app and not a design: every view exists because a flow needs
 * to tap it or a person needs to read it.
 *
 * The order is what a device run bought. The device-mode controls sit on top because that
 * is what a phone is for here, and the ten lifecycle buttons sit under a divider that says
 * so — with every resource id and every title they always had, because a flow finds them by
 * those strings and a rearranged screen must not be a renamed one.
 *
 * How a flow finds them is split, and the split is forced by the platforms rather than
 * chosen. A flow's `id:` matches an accessibility identifier on iOS and a RESOURCE id on
 * Android, and a resource id is fixed at build time — so a button, whose identity never
 * changes, is found by id on both, while a readout, whose whole point is that its value
 * changes, is found by its text. The label rides in that text (`state=unenrolled`) so the
 * match names which readout it means.
 *
 * The first run on a real phone is what settled this: all nine cases failed with
 * "Element not found: Id matching regex: wipe" while the screen was on and correct,
 * because content descriptions are not resource ids.
 *
 * A tap answers in three ways, and the readouts are none of them. The readouts are the
 * machine-readable truth and they are written for a flow: a person who taps a button and
 * watches one word two lines up change from `ready` to `busy` and back inside a second has
 * watched nothing happen. So every tappable view here changes under a finger
 * ([pressFeedback]), the one action that outlives its tap by seconds shows a spinner while
 * it runs ([attemptSpinner]), and every action ends in a Toast naming what it did
 * ([announce]). None of the three is a readout and no flow may read one — see [announce]
 * for what keeps a flow's selector off the Toast.
 *
 * tools/harness/ios/Sources/HarnessView.swift is the same screen in SwiftUI, with the
 * same button ids and the same readout text.
 */
class HarnessActivity : Activity()
{
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main);

    private lateinit var model: HarnessModel;
    private lateinit var stateLabel: TextView;
    private lateinit var outcomeLabel: TextView;
    private lateinit var busyLabel: TextView;
    private lateinit var networkLabel: TextView;
    private lateinit var custodyLabel: TextView;
    private lateinit var caseLabel: TextView;
    private lateinit var socialLabel: TextView;
    private lateinit var receiptLabel: TextView;

    /** What the selected case asks a person to do at the sheet. Not a readout a flow reads. */
    private lateinit var preconditionLabel: TextView;

    /**
     * The spinner beside `sign-in-google`, shown for as long as that attempt runs.
     *
     * The device-mode attempt is the only action on this screen that takes long enough for
     * a person to wonder whether the tap landed: it wipes, puts a provider sheet up, waits
     * for an account to be picked, enrols, and writes a file. `busy=busy` says all of that
     * in one word two lines above the button, which is a fact for a flow rather than an
     * answer to a finger.
     *
     * The ten lifecycle buttons get no spinner. Their work is one request and it is over
     * before a spinner would have finished appearing; a spinner that flashes is noise, and
     * the Toast that follows is the reaction those taps needed.
     */
    private lateinit var attemptSpinner: ProgressBar;

    /**
     * The Toast currently on screen, held only so the next one can replace it.
     *
     * Toasts QUEUE. A Maestro run taps ten buttons in a few seconds, and ten queued
     * `Toast.LENGTH_LONG` signals would still be arriving half a minute after the run that
     * produced them, each naming a result that had already been superseded.
     */
    private var signal: Toast? = null;

    /**
     * Whether an action is running, held here rather than passed to each `render` call.
     *
     * It was a parameter, and a case button repainted the screen with `busy=false` while
     * another action was still in flight — a readout that told a flow to stop waiting for
     * something that had not finished. One piece of state, written where it changes.
     */
    private var busy: Boolean = false;

    /** The views a running action must not be able to be interrupted through. */
    private val socialViews = mutableListOf<View>();

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState);
        model = HarnessModel(this, HarnessConfiguration.fromLaunch(intent));
        setContentView(buildScreen());
        model.refresh();
        render();
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

    private fun buildScreen(): ViewGroup
    {
        val column = LinearLayout(this);
        column.orientation = LinearLayout.VERTICAL;
        column.setPadding(32, 32, 32, 32);

        stateLabel = label(column);
        outcomeLabel = label(column);
        busyLabel = label(column);
        networkLabel = label(column);
        custodyLabel = label(column);
        caseLabel = label(column);
        socialLabel = label(column);
        receiptLabel = label(column);

        column.addView(deviceMode());
        column.addView(lifecycleDivider());

        for (action in actions())
        {
            column.addView(button(action));
        }

        val scroll = ScrollView(this);
        scroll.addView(column);
        // The system bars' insets become this view's padding. Without it the screen is
        // edge-to-edge — which every app targeting API 35 and later now is, whether it asks
        // or not — and on a real phone on 2026-09-01 the last button of the column sat under
        // the navigation bar, reachable only by scrolling past the end of the content.
        scroll.fitsSystemWindows = true;
        return scroll;
    }

    /**
     * The half of the screen a person drives and no flow does, above the half that no
     * person drives.
     *
     * Two things to tap and one thing to choose. The five cases used to be buttons in the
     * same column as the sign-in and the ten lifecycle actions — seventeen views that looked
     * alike, of which two did anything — and the operator was expected to remember a wipe
     * before each attempt as well.
     */
    private fun deviceMode(): ViewGroup
    {
        val block = LinearLayout(this);
        block.orientation = LinearLayout.VERTICAL;
        block.setPadding(0, 0, 0, 24);

        block.addView(heading("device verification"));
        block.addView(caseSelector());

        preconditionLabel = TextView(this);
        preconditionLabel.textSize = 14f;
        preconditionLabel.setPadding(0, 12, 0, 4);
        block.addView(preconditionLabel);

        block.addView(signInRow());
        return block;
    }

    /**
     * The one action button and the spinner that says it is still running.
     *
     * The spinner is [View.INVISIBLE] rather than `GONE` when idle so that showing it moves
     * nothing: a control that changes the layout of the screen while an attempt runs is a
     * control that can move another one out from under a finger.
     */
    private fun signInRow(): ViewGroup
    {
        val row = LinearLayout(this);
        row.orientation = LinearLayout.HORIZONTAL;
        row.gravity = Gravity.CENTER_VERTICAL;

        attemptSpinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        attemptSpinner.visibility = View.INVISIBLE;
        val spinnerLayout = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        spinnerLayout.leftMargin = 24;
        attemptSpinner.layoutParams = spinnerLayout;

        val signIn = signInButton();
        // A vertical LinearLayout gives its children MATCH_PARENT width and a horizontal
        // one gives them WRAP_CONTENT, so moving this button into a row would have shrunk
        // the screen's main action to the width of its own title without anything saying
        // so. The weight puts it back: the button takes the row less the spinner.
        signIn.layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        );
        socialViews.add(signIn);
        row.addView(signIn);
        row.addView(attemptSpinner);
        return row;
    }

    /**
     * The five cases as one single-choice control, boxed so it cannot be mistaken for a
     * stack of actions.
     *
     * A [RadioGroup] is the platform's own answer to "exactly one of these": it keeps the
     * invariant itself rather than leaving the screen to remember it, and each row is still
     * a view with a resource id, which is the only kind of element a Maestro selector can
     * find. The ids are the ones this screen always used, so anything that could already
     * find a case still finds it.
     *
     * The listener is attached AFTER the initial selection is checked. A [RadioGroup.check]
     * fires the listener, and a listener that repainted the screen during construction would
     * reach labels that do not exist yet.
     */
    private fun caseSelector(): ViewGroup
    {
        val box = LinearLayout(this);
        box.orientation = LinearLayout.VERTICAL;
        box.background = selectorBorder();
        box.setPadding(24, 16, 24, 16);

        val header = TextView(this);
        header.text = "case (pick one)";
        header.textSize = 14f;
        box.addView(header);

        val group = RadioGroup(this);
        group.orientation = RadioGroup.VERTICAL;
        for (case in HarnessSocialCase.entries)
        {
            val option = caseOption(case);
            socialViews.add(option);
            group.addView(option);
        }
        group.check(caseId(model.socialCase));
        group.setOnCheckedChangeListener { _, checked ->
            model.selectSocialCase(caseOf(checked));
            render();
        };
        box.addView(group);
        return box;
    }

    /**
     * One case, spelled exactly as the shared spec spells it and as iOS spells it.
     *
     * The label is the wire name and nothing else. It used to be `case-first-enroll` here
     * against `[first-enroll]` on iOS, and a device run spent a round trip working out that
     * the three spellings were one case. Selection rides in the radio button's own mark,
     * where a selection belongs, and never in the text.
     *
     * Selecting is instant work, so it does not go through [perform]: showing `busy=busy`
     * for a field assignment would teach a flow to wait for nothing.
     */
    private fun caseOption(case: HarnessSocialCase): RadioButton
    {
        val option = RadioButton(this);
        option.id = caseId(case);
        option.text = case.wireName;
        option.textSize = 16f;
        option.stateListAnimator = pressFeedback();
        return option;
    }

    private fun caseId(case: HarnessSocialCase): Int = when (case)
    {
        HarnessSocialCase.FIRST_ENROLL -> R.id.btn_case_first_enroll
        HarnessSocialCase.RE_LOGIN -> R.id.btn_case_re_login
        HarnessSocialCase.USER_CANCEL -> R.id.btn_case_user_cancel
        HarnessSocialCase.NETWORK_FAILURE -> R.id.btn_case_network_failure
        HarnessSocialCase.SERVER_REJECT -> R.id.btn_case_server_reject
    };

    /**
     * The case a checked resource id names.
     *
     * Exhaustive over the same five ids [caseId] writes, and it throws rather than falling
     * back to a default: an id this cannot name is a row nothing here put in the group, and
     * silently reading it as `first-enroll` would file the receipt under a case the person
     * did not pick.
     */
    private fun caseOf(id: Int): HarnessSocialCase =
        HarnessSocialCase.entries.first { caseId(it) == id };

    /**
     * The one button that opens a real provider sheet.
     *
     * Disabled — visibly, with the reason on the `social=` label — when this build carries
     * no client id or no server address. A checkout of this repository is exactly that
     * build, and it installs and runs: the configuration is missing, so the action that
     * needs it is unavailable, which is a state rather than a crash.
     *
     * The title is `sign-in-google`, the same word the iOS harness puts on the same action.
     * The RESOURCE id stays `btn_social_google`, which is what it has always been: a
     * resource id is what a selector matches, and renaming one to tidy a title would break
     * every selector that already names it for nothing.
     */
    private fun signInButton(): Button
    {
        val view = Button(this);
        view.id = R.id.btn_social_google;
        view.text = "sign-in-google";
        view.isAllCaps = false;
        view.stateListAnimator = pressFeedback();
        // The signal names the outcome and the receipt's FILE NAME, and nothing else the
        // receipt holds. Everything else in that file is evidence about an account, and a
        // Toast is the one part of this screen that survives into a photograph of it.
        view.setOnClickListener {
            perform(attemptSpinner, { "${model.outcome}\n${model.receipt}" })
            {
                model.signInWithGoogle(this@HarnessActivity);
            }
        };
        return view;
    }

    /** The rule and the caption that say which half of the screen is below them. */
    private fun lifecycleDivider(): ViewGroup
    {
        val block = LinearLayout(this);
        block.orientation = LinearLayout.VERTICAL;
        block.setPadding(0, 8, 0, 8);

        val rule = View(this);
        rule.setBackgroundColor(Color.GRAY);
        rule.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2);
        block.addView(rule);
        block.addView(heading("sdk lifecycle (flows)"));
        return block;
    }

    private fun heading(text: String): TextView
    {
        val view = TextView(this);
        view.text = text;
        view.textSize = 16f;
        view.setTypeface(null, Typeface.BOLD);
        view.setPadding(0, 8, 0, 8);
        return view;
    }

    /**
     * The box that makes the case selector read as one control.
     *
     * A stroke rather than a fill: the harness declares no theme, so it takes the platform's,
     * and a background colour chosen here would be a guess about what the text colour will be
     * on someone's phone. A grey outline is legible against a light one and a dark one.
     */
    private fun selectorBorder(): GradientDrawable
    {
        val border = GradientDrawable();
        border.shape = GradientDrawable.RECTANGLE;
        border.cornerRadius = 12f;
        border.setStroke(2, Color.GRAY);
        return border;
    }

    /**
     * Every button the flows tap: its resource id, its visible text, and what it does.
     *
     * The text is lowercase and `isAllCaps` is turned off, so what a reader sees on the
     * phone is the same word the flow file names.
     */
    private fun actions(): List<Triple<Int, String, suspend () -> Unit>> = listOf(
        Triple(R.id.btn_enroll, "enroll") { model.enroll() },
        Triple(R.id.btn_rotate, "rotate") { model.rotate() },
        Triple(R.id.btn_resume, "resume") { model.resumeRotation() },
        Triple(R.id.btn_revoke, "revoke") { model.revokeActiveKey() },
        Triple(R.id.btn_proven_call, "proven-call") { model.provenCall() },
        Triple(R.id.btn_note_revoked, "note-revoked") { model.noteSessionRevoked() },
        Triple(R.id.btn_wipe, "wipe") { model.wipe() },
        Triple(R.id.btn_custody_probe, "custody-probe") { model.probeCustody() },
        Triple(R.id.btn_block_network, "block-network") { model.setNetworkBlocked(true) },
        Triple(R.id.btn_open_network, "open-network") { model.setNetworkBlocked(false) }
    );

    private fun label(parent: ViewGroup): TextView
    {
        val view = TextView(this);
        view.textSize = 16f;
        parent.addView(view);
        return view;
    }

    private fun button(action: Triple<Int, String, suspend () -> Unit>): Button
    {
        val view = Button(this);
        view.id = action.first;
        view.text = action.second;
        view.isAllCaps = false;
        view.stateListAnimator = pressFeedback();
        // A lifecycle action's signal is the `outcome=` value it already produces and
        // nothing more. These ten buttons write no receipt, and inventing a second
        // vocabulary for the Toast would give a reader two names for one result.
        view.setOnClickListener { perform(null, { model.outcome }, action.third) };
        return view;
    }

    /**
     * What a view does under a finger, given to every button and every case row on this
     * screen.
     *
     * Not decoration and not a theme. These views are built in code against whatever theme
     * the device supplies, so what a press looks like — a ripple, a lift, or nothing at all
     * — is the device's answer rather than this screen's, and on a phone held at arm's
     * length across a desk the honest answer to "did that tap land?" was often nothing.
     * Alpha and scale are visible on any theme, light or dark, and need no colour chosen
     * here: the same reason [selectorBorder] draws a stroke instead of a fill.
     *
     * This REPLACES the default state list animator, which on a platform button is the
     * elevation lift. That is the trade and it is deliberate: a lift of a few pixels is
     * what was already there and was already being missed.
     *
     * A fresh instance per view. A [StateListAnimator] binds to the view it is set on, and
     * one instance shared across the sixteen views here would follow the last one.
     */
    private fun pressFeedback(): StateListAnimator
    {
        val animator = StateListAnimator();
        animator.addState(intArrayOf(android.R.attr.state_pressed), pressAnimation(0.55f, 0.97f));
        animator.addState(IntArray(0), pressAnimation(1f, 1f));
        return animator;
    }

    private fun pressAnimation(alpha: Float, scale: Float): AnimatorSet
    {
        val set = AnimatorSet();
        set.duration = 60L;
        set.playTogether(
            ObjectAnimator.ofFloat(null, View.ALPHA, alpha),
            ObjectAnimator.ofFloat(null, View.SCALE_X, scale),
            ObjectAnimator.ofFloat(null, View.SCALE_Y, scale)
        );
        return set;
    }

    /**
     * The completion signal a tap owes the person who made it.
     *
     * A Toast and not a label, because the point is that it ARRIVES: a value that changes
     * in place is only noticed by someone already looking at it, and the operator's eyes
     * are on the button they just pressed.
     *
     * The text carries NO readout prefix — `ok:wiped`, not `outcome=ok:wiped`. Every flow
     * selector in tools/harness/flows/ matches either a resource id or a readout's text
     * (`outcome=…`, `state=…`, `busy=…`), and dropping the prefix is what makes it
     * impossible for one of them to match this window instead of the label it means. A
     * transient signal a flow could assert on is a flow that passes because a Toast was
     * still up (docs/IMPLEMENTATION-PITFALLS.md P7).
     *
     * The text is ALL that keeps a flow off this window, and the iOS half has a second
     * lock the Android half cannot have. A SwiftUI banner is marked
     * `accessibilityHidden`, which deletes it from the hierarchy a flow searches; a Toast
     * announces itself to the accessibility layer by design, because being heard is what a
     * Toast is for. Same rule, different strength, and the strength is the platform's
     * rather than this file's (P15).
     */
    private fun announce(text: String)
    {
        signal?.cancel();
        val toast = Toast.makeText(this, text, Toast.LENGTH_LONG);
        signal = toast;
        toast.show();
    }

    /**
     * The network work leaves the main thread; the labels are written back on it. A flow
     * waits for `busy=ready` rather than sleeping for a guessed number of seconds, and the
     * flag is set HERE, synchronously inside the click, so there is no window where a
     * started action still reads as finished.
     *
     * [indicator] is shown for exactly as long as the action runs, and [signal] is read
     * AFTER it finishes — it is a lambda rather than a string for that reason alone, since
     * the outcome it names does not exist yet at the moment of the tap.
     *
     * The restore is a `finally` and the announcement is not. A cancelled coroutine is the
     * screen going away rather than an action finishing, so the spinner and the busy flag
     * are put back and nothing is announced: a Toast is a claim that something completed
     * (P16), and one shown here would appear over whatever screen replaced this one.
     */
    private fun perform(indicator: View?, signal: () -> String, action: suspend () -> Unit)
    {
        busy = true;
        indicator?.visibility = View.VISIBLE;
        render();
        scope.launch {
            try
            {
                withContext(Dispatchers.IO) { action() };
            }
            finally
            {
                busy = false;
                indicator?.visibility = View.INVISIBLE;
                render();
            }
            announce(signal());
        };
    }

    private fun render()
    {
        stateLabel.text = "state=${model.state}";
        outcomeLabel.text = "outcome=${model.outcome}";
        busyLabel.text = if (busy) "busy=busy" else "busy=ready";
        // Permanent, not a message the two network buttons leave behind. The first device
        // run burned three attempts on a transport still shut from an earlier case: a
        // blocked switch mimics a real network drop exactly, which is what makes it worth
        // reading and impossible to notice.
        networkLabel.text = if (model.networkBlocked) "network=blocked" else "network=open";
        custodyLabel.text = "custody=${model.custody}";
        caseLabel.text = "case=${model.socialCase.wireName}";
        // The word only. A client id and a server address are what this build was given,
        // and neither belongs on a screen that ends up in a screenshot.
        socialLabel.text = "social=${HarnessSocialConfiguration.readout}";
        receiptLabel.text = "receipt=${model.receipt}";
        preconditionLabel.text = model.socialCase.precondition;

        // A sign-in and a case change are both refused while one attempt is in flight. A
        // second tap would start a second sheet over the first, and the first attempt's
        // receipt would be written about a run that no longer describes the screen.
        val available = HarnessSocialConfiguration.isConfigured && !busy;
        for (view in socialViews)
        {
            view.isEnabled = available;
        }
    }
}
