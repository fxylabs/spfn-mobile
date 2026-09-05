// SPFN Mobile — the Compose example app's one screen holder.
//
// Counterpart of examples/ios-swiftui/Sources/ExampleApp.swift.
//
// Everything below the root is generated. What is written here is the three things a
// generator cannot know: which fixture this launch asked for, where a receipt goes, and what
// to show a person who launched the app without asking for anything.
//
// The last of those is the MENU, and it replaced a screen that said the app was
// unconfigured. `SPFN_UI_FIXTURE=<cell>` still decides everything a runner cares about — it
// says which flow opens, on which seeding, at which depth — and a launch that names no cell
// now lands on a list of the nine flows instead of on a sentence about a server.
//
// The menu runs on the same fake every cell does, and that is not the fail-closed rule
// bending. This app has no enrolment path of its own, so a client built against a configured
// server would refuse every call for want of a key: a person pressing a menu button would
// get a refusal that says nothing about the screens the button opens. There is no
// real-server path here at all — the manifest declares no INTERNET permission and nothing in
// this app can send.
//
// `testTagsAsResourceId` is set once, here, on the root. It is what turns the generated
// views' test tags into Android resource ids, which is what a Maestro `id:` selector matches
// — the same split the harness records: a control by id, a readout by text.

package xyz.superfunction.spfn.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import xyz.superfunction.spfn.core.SpfnVersion
import xyz.superfunction.spfn.example.generated.AppContainer
import xyz.superfunction.spfn.example.generated.flows.ApproveDeviceFlowHost
import xyz.superfunction.spfn.example.generated.flows.KeyboardFormFlowHost
import xyz.superfunction.spfn.example.generated.flows.LongScrollFlowHost
import xyz.superfunction.spfn.example.generated.flows.ModalTourFlowHost
import xyz.superfunction.spfn.example.generated.flows.PushTourFlowHost
import xyz.superfunction.spfn.example.generated.flows.SheetFitFlowHost
import xyz.superfunction.spfn.example.generated.flows.SheetFullFlowHost
import xyz.superfunction.spfn.example.generated.flows.SheetHalfFlowHost
import xyz.superfunction.spfn.example.generated.flows.SheetNavFlowHost
import xyz.superfunction.spfn.generated.SpfnGeneratedContract
import xyz.superfunction.spfn.ui.NavigationHost
import xyz.superfunction.spfn.ui.components.PrimaryButton
import xyz.superfunction.spfn.ui.components.Screen
import xyz.superfunction.spfn.ui.components.SecondaryButton
import xyz.superfunction.spfn.ui.components.SpfnText
import xyz.superfunction.spfn.ui.components.TextRole
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

class MainActivity : ComponentActivity()
{
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState);

        val cell = intent?.getStringExtra(FIXTURE_EXTRA).orEmpty();
        val named = Fixtures.forCell(cell);
        val fixture = named ?: Fixtures.menu();
        val container = AppContainer(fixture.service());

        // The container opened all nine flows; this decides which one is on show. Done here
        // rather than in the composition because it is a fact about the LAUNCH: a person
        // pressing a menu button reaches `Flows.open` instead, and neither should be able to
        // put a second presentation over the first.
        Flows.openOnly(container, fixture.flow, fixture.openAt);

        val receipts = ExampleReceiptStore(this);

        setContent {
            ExampleRoot(
                cell = cell.ifEmpty { NONE },
                fixture = fixture.name,
                container = container,
                receipts = receipts
            );
        };
    }

    private companion object
    {
        const val FIXTURE_EXTRA: String = "SPFN_UI_FIXTURE";
        const val NONE: String = "none";
    }
}

/**
 * The app's root: the menu, the readouts a flow reads before and after the flow itself, and
 * the one control that is not a screen's.
 *
 * The receipt control lives here rather than on a screen because a cell that ends with the
 * flow closed has no screen left to press. Every generated flow unwinds itself before
 * reaching it, which is what makes the control reachable at all: a Modal flow covers this
 * view entirely while it is open, on Android as well as on iOS, and a pushed one stands on
 * the host's back stack over it.
 *
 * `stack=` counts the FLOWS' own depths added up (`Flows.depth`) and not the host's back
 * stack, which is what keeps every cell's expectation the same number it was: a pushed flow
 * that now appends to the host has exactly the depth it had when it drew its own stack.
 *
 * A `Box` rather than a `Column`, and that is the whole reason the cover works. The flow
 * hosts are the last children, so they are drawn OVER the menu instead of below it; in a
 * Column they would be laid out beside it and cover nothing. That is the one thing
 * `FlowHost` asks of a host app that presents a flow modally — its own header states it.
 *
 * The `NavigationHost` around the Box is what a PUSHED flow appends to (decision N1).
 * Without it `PushTourFlowHost` would draw a NavDisplay of its own over this Box, with no
 * transition into it and no entry under its first screen to go back to
 * (docs/IMPLEMENTATION-PITFALLS.md P31). A modal and a sheet need nothing from it.
 *
 * The insets stay on the Box and not on the host, which is right for both: the Box is what a
 * cover fills, and a pushed screen is drawn by the host OUTSIDE the Box, where `Screen`
 * spends the status bar inset on its own header because nothing above it has consumed one
 * (docs/IMPLEMENTATION-PITFALLS.md P25).
 *
 * The system bars' insets are this root's job for the same reason. A cover fills its PARENT,
 * so whatever the parent is given is what the flow's own first row is given, and an app
 * targeting API 35 or later is drawn edge-to-edge whether it asks or not: without the
 * padding here the flow's `state=` row lands under the status bar and the camera cutout,
 * where a runner's hierarchy no longer carries it
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
    val depth = Flows.depth(container);

    // OUTSIDE the host, and that is the whole of P33. `NavigationHost` draws a pushed
    // flow's routes out of its own NavDisplay, which makes them SIBLINGS of the root below
    // rather than children of it — so a switch set on that root is not above them, and
    // `testTagsAsResourceId` resolves by walking semantics PARENTS. Set inside, every
    // control in every pushed flow silently loses its resource id and every `tapOn: id:` in
    // those cells stops matching.
    Box(modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true })
    {
        NavigationHost {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
            )
            {
                Menu(container = container, cell = cell, depth = depth, receipt = receipt)
                {
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
                };

                ApproveDeviceFlowHost(container);
                PushTourFlowHost(container);
                ModalTourFlowHost(container);
                SheetFitFlowHost(container);
                SheetHalfFlowHost(container);
                SheetFullFlowHost(container);
                SheetNavFlowHost(container);
                KeyboardFormFlowHost(container);
                LongScrollFlowHost(container);
            }
        };
    };
}

/**
 * The list of flows, and the three readouts over it.
 *
 * Drawn out of the SDK's own components rather than out of `BasicText` with a `clickable`,
 * because a row of text a person taps is exactly the control that reports its neighbour's
 * rectangle to a runner: `PrimaryButton` carries the 48dp minimum and the menu gets it for
 * free (docs/IMPLEMENTATION-PITFALLS.md P21).
 *
 * The readouts and the receipt control come ABOVE the list, and that is a rule about reach
 * rather than about layout. Every cell that ends with its flow closed reads `stack=0` here
 * and then presses `example.receipt` here, and nine buttons stacked over them would put both
 * below the fold on a phone — a menu that scrolled its own diagnostics out of sight would
 * fail those cells for a reason that is not a defect.
 *
 * `fixture=` is the CELL this launch named, which is `none` on the menu even though a fake
 * is installed: the fake is what the menu runs on, and the receipt's own record is where its
 * name is written down.
 *
 * The padding and the spacing are `ExampleApp.swift`'s, value for value: that menu is a
 * `VStack(spacing: SPFNTokens.space4)` under a `.padding(SPFNTokens.space4)` and this one had
 * neither, so on the 3e screenshots the Android menu's readouts began at the screen's left
 * edge and its ten controls made one unbroken column. Nothing else on either platform draws
 * its own body: `Screen` is a frame, and what a screen puts inside it decides its own
 * spacing — which is why the generated views emit this same pair (KotlinEmitter.kt) and this
 * hand-written one has to say it for itself.
 *
 * P25 is what bounds the step, and it is arithmetic rather than taste. What every cell that
 * ends with its flow closed reaches for is above the fold with room to spare: inside the
 * root's system-bar padding a Pixel 3a leaves 714dp, `Screen`'s header takes 56 of them, and
 * the four items a cell reads — three 13sp mono readouts and `example.receipt` at the 48dp
 * minimum, with 16dp of top padding and three 16dp gaps — end about 160dp into the 658dp
 * body. Of the nine flow buttons under them, at 64dp each with their gaps, eight are fully
 * in the first viewport and `longScroll` is the one clipped; `menu.pushTour`, which is the
 * only one any cell names (pushTour-rootBack, pushTour-rootSystemBack), is the second.
 */
@Composable
private fun Menu(
    container: AppContainer,
    cell: String,
    depth: Int,
    receipt: String,
    onReceipt: () -> Unit
)
{
    Screen(title = "SPFN showcase")
    {
        Column(
            modifier = Modifier.fillMaxWidth().padding(SpfnTokens.space4),
            verticalArrangement = Arrangement.spacedBy(SpfnTokens.space4)
        )
        {
            SpfnText(text = "fixture=$cell", role = TextRole.Mono);
            SpfnText(text = "stack=$depth", role = TextRole.Mono);
            SpfnText(text = "receipt=$receipt", role = TextRole.Mono);
            SecondaryButton(title = "write receipt", id = "example.receipt", onTap = onReceipt);
            Flows.ALL.forEach { flow ->
                PrimaryButton(title = flow, id = "menu.$flow", onTap = { Flows.open(container, flow) });
            };
        }
    }
}
