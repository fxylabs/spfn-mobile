package xyz.superfunction.spfn.harness

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The harness screen.
 *
 * Buttons and four labels, built in code. This is not a sample app and not a design:
 * every view exists because a flow needs to tap it or read it.
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
    private lateinit var custodyLabel: TextView;
    private lateinit var caseLabel: TextView;
    private lateinit var socialLabel: TextView;
    private lateinit var receiptLabel: TextView;

    /**
     * Whether an action is running, held here rather than passed to each `render` call.
     *
     * It was a parameter, and a case button repainted the screen with `busy=false` while
     * another action was still in flight — a readout that told a flow to stop waiting for
     * something that had not finished. One piece of state, written where it changes.
     */
    private var busy: Boolean = false;

    /** The views a running action must not be able to be interrupted through. */
    private val socialViews = mutableListOf<Button>();

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
        custodyLabel = label(column);
        caseLabel = label(column);
        socialLabel = label(column);
        receiptLabel = label(column);

        for (action in actions())
        {
            column.addView(button(action));
        }

        for (case in HarnessSocialCase.entries)
        {
            val view = caseButton(case);
            socialViews.add(view);
            column.addView(view);
        }
        val signIn = socialButton();
        socialViews.add(signIn);
        column.addView(signIn);

        val scroll = ScrollView(this);
        scroll.addView(column);
        return scroll;
    }

    /**
     * The case picker: one button per case, and the selection rides in the `case=` label.
     *
     * A button rather than a spinner for the same reason the rest of this screen is
     * buttons — a resource id is what a flow can find, and a spinner's rows are not views
     * a flow can name. Selecting is instant work, so it does not go through [perform]:
     * showing `busy=busy` for a field assignment would teach a flow to wait for nothing.
     *
     * It is disabled while an attempt runs all the same. The case is read at the start of
     * an attempt, so changing it mid-flight cannot corrupt the running one — but it would
     * leave the screen naming a case the receipt about to be written is not.
     */
    private fun caseButton(case: HarnessSocialCase): Button
    {
        val view = Button(this);
        view.id = caseId(case);
        view.text = "case-${case.wireName}";
        view.isAllCaps = false;
        view.setOnClickListener {
            model.selectSocialCase(case);
            render();
        };
        return view;
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
     * The one button that opens a real provider sheet.
     *
     * Disabled — visibly, with the reason on the `social=` label — when this build carries
     * no client id or no server address. A checkout of this repository is exactly that
     * build, and it installs and runs: the configuration is missing, so the action that
     * needs it is unavailable, which is a state rather than a crash.
     */
    private fun socialButton(): Button
    {
        val view = Button(this);
        view.id = R.id.btn_social_google;
        view.text = "social-google";
        view.isAllCaps = false;
        view.setOnClickListener { perform { model.signInWithGoogle(this@HarnessActivity) } };
        return view;
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
        view.setOnClickListener { perform(action.third) };
        return view;
    }

    /**
     * The network work leaves the main thread; the labels are written back on it. A flow
     * waits for `busy=ready` rather than sleeping for a guessed number of seconds, and the
     * flag is set HERE, synchronously inside the click, so there is no window where a
     * started action still reads as finished.
     */
    private fun perform(action: suspend () -> Unit)
    {
        busy = true;
        render();
        scope.launch {
            withContext(Dispatchers.IO) { action() };
            busy = false;
            render();
        };
    }

    private fun render()
    {
        stateLabel.text = "state=${model.state}";
        outcomeLabel.text = "outcome=${model.outcome}";
        busyLabel.text = if (busy) "busy=busy" else "busy=ready";
        custodyLabel.text = "custody=${model.custody}";
        caseLabel.text = "case=${model.socialCase.wireName}";
        // The word only. A client id and a server address are what this build was given,
        // and neither belongs on a screen that ends up in a screenshot.
        socialLabel.text = "social=${HarnessSocialConfiguration.readout}";
        receiptLabel.text = "receipt=${model.receipt}";

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
