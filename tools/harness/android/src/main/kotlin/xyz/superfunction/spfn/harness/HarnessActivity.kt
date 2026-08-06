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
 * Buttons and three labels, built in code. This is not a sample app and not a design:
 * every view exists because a flow needs to tap it or read it, and each one carries a
 * content description because that is what Maestro matches on.
 *
 * The identifiers are the same strings the iOS harness uses, so one flow file drives both
 * (tools/harness/ios/Sources/HarnessView.swift).
 */
class HarnessActivity : Activity()
{
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main);

    private lateinit var model: HarnessModel;
    private lateinit var stateLabel: TextView;
    private lateinit var outcomeLabel: TextView;
    private lateinit var busyLabel: TextView;

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

        for (action in actions())
        {
            column.addView(button(action));
        }

        val scroll = ScrollView(this);
        scroll.addView(column);
        return scroll;
    }

    /**
     * Every button the flows tap, paired with what it does. The name is both the visible
     * text and the content description, so a flow's `tapOn: id: "rotate"` reads as the
     * action it performs.
     */
    private fun actions(): List<Pair<String, suspend () -> Unit>> = listOf(
        "enroll" to { model.enroll() },
        "rotate" to { model.rotate() },
        "resume" to { model.resumeRotation() },
        "revoke" to { model.revokeActiveKey() },
        "proven-call" to { model.provenCall() },
        "note-revoked" to { model.noteSessionRevoked() },
        "wipe" to { model.wipe() },
        "block-network" to { model.setNetworkBlocked(true) },
        "open-network" to { model.setNetworkBlocked(false) }
    );

    private fun label(parent: ViewGroup): TextView
    {
        val view = TextView(this);
        view.textSize = 16f;
        parent.addView(view);
        return view;
    }

    /**
     * Writes one readout, with the value in the identifier as well as in the text.
     *
     * The obvious spelling — an identifier naming the label and the text carrying the
     * value — does not survive the trip on iOS: setting an accessibility identifier on a
     * SwiftUI `Text` leaves the automation hierarchy with the identifier and an empty
     * text, so a flow matching on both matches nothing. The first real run found it.
     *
     * Android does not have that problem, but the flows are shared, so this half spells it
     * the same way. One flow file, one field to match.
     */
    private fun readout(view: TextView, id: String, value: String)
    {
        view.text = value;
        view.contentDescription = "$id:$value";
    }

    private fun button(action: Pair<String, suspend () -> Unit>): Button
    {
        val view = Button(this);
        view.text = action.first;
        view.contentDescription = action.first;
        view.setOnClickListener { perform(action.second) };
        return view;
    }

    /**
     * The network work leaves the main thread; the labels are written back on it. A flow
     * waits for `busy-label` to read `ready` rather than sleeping for a guessed number of
     * seconds.
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
        readout(stateLabel, "state-label", model.state);
        readout(outcomeLabel, "outcome-label", model.outcome);
        readout(busyLabel, "busy-label", if (busy) "busy" else "ready");
    }
}
