// SPFN Mobile — the one place a Flow is bound to the platform navigator.
//
// Counterpart of Sources/SPFNUI/FlowHost.swift, and the only file in this module that
// imports Compose. Navigation 3 is what renders the stack: NavDisplay takes a plain
// `List` and an `onBack`, which is the shape that lets the Flow stay the single source of
// truth. Nothing here keeps a second copy of the stack and nothing here mutates the list
// it was handed — a system back turns into `flow.pop()`, and the next state arrives the
// same way every other state arrives, off the flow.
//
// NavDisplay disables its own back handling when the current scene has no previous entry,
// which is precisely the last-route case. That is what makes the two entry styles a
// three-line difference rather than two hosts: `Push` leaves it disabled and the host
// app's back applies, and `Modal` puts its own handler over exactly that gap and closes.
//
// ---------------------------------------------------------------------------
// The rule a Modal entry adds: it COVERS
// ---------------------------------------------------------------------------
//
// `Modal` means presented over something, so its stack is drawn as an opaque cover that
// fills everything the host gave this composable and takes the touches inside it. A
// `Push` flow is drawn plain, because it was pushed into the host's own navigation and is
// a part of it. Before this rule existed the two entry styles rendered identically on
// Android and only the back handler told them apart, so a modal flow appeared INLINE
// under the host's own content while the same flow covered the host on iOS — the two
// halves of one vocabulary disagreeing about what the word means.
//
// A cover fills its PARENT, which makes one demand of the host app: a host that wants a
// modal flow to cover the whole screen puts this composable last in a container that
// stacks its children (a `Box` filling the window), not in a `Column` where the host's own
// content is laid out beside it. examples/android-compose does exactly that.
//
// The same sentence settles the system-bar insets: the host app owns them, and it owns
// them AROUND this composable rather than only around its own content. A cover fills the
// parent it was given, and an app targeting API 35 or later is drawn edge-to-edge whether
// it asks or not, so a parent left un-inset puts the flow's first row under the status bar
// (docs/IMPLEMENTATION-PITFALLS.md P25).
//
// It is deliberately NOT a `Dialog` or a `Popup`, and that is measured rather than
// preferred. Both put their content in a second window with a semantics owner of its own,
// and `testTagsAsResourceId` — the switch that turns a Compose test tag into the Android
// resource id a Maestro `id:` selector matches — is resolved by walking `SemanticsNode`
// PARENTS until one carries it (androidx.compose.ui 1.11's
// AndroidComposeViewAccessibilityDelegateCompat, checked with javap: the loop ends at
// `getParent()` returning null). A host sets that switch on its own root, the walk from
// inside a second window never reaches it, and every control in the flow would lose its
// resource id — which is to say every `tapOn: id:` in every generated cell would stop
// matching. iOS reaches for `fullScreenCover` for the same job because a SwiftUI
// accessibility identifier is per-view and survives the presentation.
//
// What that trade costs is stated rather than hidden: the host's content stays in the
// accessibility tree BEHIND the cover, where iOS's `fullScreenCover` removes it. Nothing
// this repository asserts reads that difference — the generated flows read the screen's
// own readouts while a flow is open and the host's after it closes — but a flow that
// asserted the ABSENCE of a host readout mid-flow would pass on iOS and fail here.

package xyz.superfunction.spfn.ui

import android.util.TypedValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay

/**
 * Renders [flow]'s top route and follows the stack as it changes.
 *
 * Renders nothing at all while the flow is closed, which is not a special case bolted on:
 * NavDisplay requires a non-empty back stack and refuses an empty one, so a closed flow
 * has nothing to show by the navigator's own rule as well as by this module's.
 *
 * `@JvmSynthetic` is not decoration either. A `@Composable` function may only be called
 * from a composition, and the Compose compiler enforces that for Kotlin callers and for
 * nobody else: from Java this is an ordinary static method taking a `Composer`, and
 * calling it is a crash rather than a compile error. Erasing it from Java's view is the
 * only place the rule can be stated to a Java caller at all
 * (docs/IMPLEMENTATION-PITFALLS.md P15).
 */
@JvmSynthetic
@Composable
public fun <R : FlowRoute> FlowHost(flow: Flow<R>, entry: FlowEntry, content: @Composable (R) -> Unit)
{
    val routes: List<R> = flow.stack.collectAsState().value;
    if (routes.isEmpty())
    {
        return;
    }

    BackHandler(enabled = entry == FlowEntry.Modal && routes.size == 1) { flow.close() };

    NavDisplay(
        backStack = routes,
        modifier = when (entry)
        {
            FlowEntry.Modal -> cover()
            FlowEntry.Push -> Modifier
        },
        onBack = { flow.pop() },
        entryProvider = { route -> NavEntry(route) { content(it) } }
    );
}

/**
 * What makes a modal flow a cover: the whole parent, opaque, and touch-tight.
 *
 * All three are load-bearing. A cover that does not FILL leaves the host visible beside
 * it; one that is not OPAQUE leaves the host legible through it; and one that does not
 * take the touches is a picture of a cover — Compose hit-tests the topmost sibling that
 * holds a pointer input node, so without this an unclaimed tap inside the cover would
 * reach the host's own controls underneath it.
 *
 * The consumption runs on the Main pass, which children see FIRST. A control inside the
 * flow claims its own tap and this sees an already-consumed change; only what no control
 * claimed is stopped here.
 */
@Composable
private fun cover(): Modifier = Modifier
    .fillMaxSize()
    .background(windowBackground())
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true)
            {
                awaitPointerEvent().changes.forEach { it.consume() };
            }
        }
    }

/**
 * The colour a cover fills with: the host activity's own window background.
 *
 * A module that carries no theme has no business choosing white. `colorBackground` is the
 * one colour attribute every Android theme defines, it is what the host's own window is
 * already painted with, and it follows the host into dark mode without this module
 * knowing that dark mode exists.
 *
 * A theme that resolves it to something that is not a colour gets black. Black is wrong
 * to look at and impossible to miss; the alternative, a transparent cover, is the exact
 * defect this exists to prevent and would look like nothing at all.
 */
@Composable
private fun windowBackground(): Color
{
    val context = LocalContext.current;
    return remember(context) {
        val value = TypedValue();
        val resolved = context.theme.resolveAttribute(android.R.attr.colorBackground, value, true);
        val isColor = value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT;
        if (resolved && isColor) Color(value.data) else Color.Black
    };
}
