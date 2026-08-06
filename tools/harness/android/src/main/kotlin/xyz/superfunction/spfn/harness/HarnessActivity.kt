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

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState);
        model = HarnessModel(this, HarnessConfiguration.fromLaunch(intent));
        setContentView(buildScreen());
        model.refresh();
        render(busy = false);
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

        for (action in actions())
        {
            column.addView(button(action));
        }

        val scroll = ScrollView(this);
        scroll.addView(column);
        return scroll;
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
     * waits for `busy=ready` rather than sleeping for a guessed number of seconds, and
     * `render(busy = true)` runs HERE, synchronously inside the click, so there is no
     * window where a started action still reads as finished.
     */
    private fun perform(action: suspend () -> Unit)
    {
        render(busy = true);
        scope.launch {
            withContext(Dispatchers.IO) { action() };
            render(busy = false);
        };
    }

    private fun render(busy: Boolean)
    {
        stateLabel.text = "state=${model.state}";
        outcomeLabel.text = "outcome=${model.outcome}";
        busyLabel.text = if (busy) "busy=busy" else "busy=ready";
        custodyLabel.text = "custody=${model.custody}";
    }
}
