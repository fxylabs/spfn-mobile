// SPFN Mobile — the Compose example app's one screen holder.
//
// Everything below the root is generated. What is written here is the three things a
// generator cannot know: which fixture this launch asked for, where a receipt goes, and
// what to do when neither a fixture nor a configured server exists.
//
// The fixture is the only door a fake service comes through. There is no flag inside the
// app that switches one on: with no `SPFN_UI_FIXTURE` extra, `Fixtures.forCell` is never
// reached and the app builds its client against the configured server instead.
//
// `testTagsAsResourceId` is set once, here, on the root. It is what turns the generated
// views' test tags into Android resource ids, which is what a Maestro `id:` selector
// matches — the same split the harness records: a control by id, a readout by text.

package xyz.superfunction.spfn.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import xyz.superfunction.spfn.client.SpfnAndroidKeystoreEngine
import xyz.superfunction.spfn.client.SpfnKeyLifecycle
import xyz.superfunction.spfn.client.SpfnOkHttpTransport
import xyz.superfunction.spfn.client.SpfnSharedPreferencesKeyMetadataStore
import xyz.superfunction.spfn.core.SpfnVersion
import xyz.superfunction.spfn.example.generated.AppContainer
import xyz.superfunction.spfn.example.generated.flows.ApproveDeviceFlowHost
import xyz.superfunction.spfn.generated.SpfnGeneratedContract

class MainActivity : ComponentActivity()
{
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState);

        val cell = intent?.getStringExtra(FIXTURE_EXTRA).orEmpty();
        val fixture = if (cell.isEmpty()) null else Fixtures.forCell(cell);
        val container = containerFor(fixture);
        val receipts = ExampleReceiptStore(this);

        setContent {
            if (container == null)
            {
                UnconfiguredScreen();
            }
            else
            {
                ExampleRoot(
                    cell = cell.ifEmpty { NONE },
                    fixture = fixture?.name ?: NONE,
                    container = container,
                    receipts = receipts
                );
            }
        };
    }

    /**
     * The fixture's container, or the live one, or nothing.
     *
     * Nothing is a real answer and not a failure: a checkout with no `local.properties`
     * and no enrolled key has no server to reach, and an app that invented one would
     * report a refusal from an address nobody named.
     */
    private fun containerFor(fixture: Fixture?): AppContainer?
    {
        if (fixture != null)
        {
            val container = AppContainer(fixture.service());
            fixture.openAt?.let { container.approveDeviceFlow.open(it) };
            return container;
        }
        return liveContainer();
    }

    /**
     * The app against the configured server, built the SDK's own way.
     *
     * Fail-closed at both steps, and neither message names a value: no configured base URL
     * means no client, and no enrolled key means no client either — this app has no
     * enrolment path of its own, because enrolment is what tools/harness exists to drive.
     */
    private fun liveContainer(): AppContainer?
    {
        val baseUrl = BuildConfig.EXAMPLE_SERVER_BASE_URL;
        if (baseUrl.isEmpty())
        {
            return null;
        }
        val transport = SpfnOkHttpTransport();
        val provider = SpfnKeyLifecycle(
            transport = transport,
            store = SpfnSharedPreferencesKeyMetadataStore(this, KEY_STORE_NAME),
            engine = SpfnAndroidKeystoreEngine(),
            baseUrl = baseUrl
        ).activeProvider() ?: return null;
        return AppContainer.live(transport = transport, keyProvider = provider, baseUrl = baseUrl);
    }

    private companion object
    {
        const val FIXTURE_EXTRA: String = "SPFN_UI_FIXTURE";
        const val NONE: String = "none";
        const val KEY_STORE_NAME: String = "xyz.superfunction.spfn.example";
    }
}

/**
 * The app's root: the readouts a flow reads before and after the flow itself, and the one
 * control that is not a screen's.
 *
 * The receipt control lives here rather than on a screen because a cell that ends with the
 * flow closed has no screen left to press. Every generated flow unwinds itself before
 * reaching it, which is what makes the control reachable at all: a Modal flow covers this
 * view entirely while it is open, on Android as well as on iOS.
 *
 * A `Box` rather than a `Column`, and that is the whole reason the cover works. The flow
 * host is the last child, so it is drawn OVER the readouts instead of below them; in a
 * Column it would be laid out beside them and cover nothing. That is the one thing
 * `FlowHost` asks of a host app that presents a flow modally — its own header states it.
 *
 * The system bars' insets are this root's job for the same reason. A cover fills its
 * PARENT, so whatever the parent is given is what the flow's own first row is given, and
 * an app targeting API 35 or later is drawn edge-to-edge whether it asks or not: without
 * the padding here the flow's `state=` row lands under the status bar and the camera
 * cutout, where a runner's hierarchy no longer carries it
 * (docs/IMPLEMENTATION-PITFALLS.md P25).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ExampleRoot(
    cell: String,
    fixture: String,
    container: AppContainer,
    receipts: ExampleReceiptStore
)
{
    var receipt by remember { mutableStateOf("none") };
    val depth = container.approveDeviceFlow.stack.collectAsState().value.size;

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .semantics { testTagsAsResourceId = true }
    )
    {
        Column {
            BasicText(text = "fixture=$cell");
            BasicText(text = "stack=$depth");
            BasicText(text = "receipt=$receipt");
            BasicText(
                text = "write receipt",
                modifier = Modifier
                    .testTag("example.receipt")
                    .heightIn(min = 48.dp)
                    .clickable {
                        receipt = receipts.write(
                            ExampleReceipt(
                                cell = cell,
                                fixture = fixture,
                                stackDepth = depth,
                                timestampMillis = System.currentTimeMillis(),
                                sdkVersion = SpfnVersion.CURRENT,
                                contractVersion = SpfnGeneratedContract.BINDING.importedVersion
                            )
                        );
                    }
            );
        }
        ApproveDeviceFlowHost(container);
    }
}

/** What a checkout with no fixture, no server and no key has to show. */
@Composable
private fun UnconfiguredScreen()
{
    Column(modifier = Modifier.fillMaxSize())
    {
        BasicText(text = "fixture=none");
        BasicText(text = "stack=0");
        BasicText(
            text = "This build names no server and holds no enrolled key, so it sends nothing. " +
                "Launch it with SPFN_UI_FIXTURE=<cell> to drive the screens against a fixture."
        );
    }
}
