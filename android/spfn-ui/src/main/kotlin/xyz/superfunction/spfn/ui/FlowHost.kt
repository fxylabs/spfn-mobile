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
// which is precisely the last-route case. That is what makes the three entry styles a
// few lines' difference rather than three hosts: `Push` leaves it disabled and the host
// app's back applies, and `Modal` and `Sheet` put their own handler over exactly that gap.
//
// Every back this file sees goes to `Flow.back`, and every dismissal — a system back, a tap
// on a sheet's scrim, a sheet dragged away — goes to the same place. The close table is
// stated once, in `Flow`, where a JVM test can drive all six of its rows; nothing here
// decides what a back means, it only decides whether to claim the gesture, and it asks
// `Flow.handlesBack` even for that.
//
// ---------------------------------------------------------------------------
// The rule a Modal entry adds: it COVERS
// ---------------------------------------------------------------------------
//
// `Modal` means presented over something, so its stack is drawn as an opaque cover that
// fills everything the host gave this composable and stands in the hit test for every
// touch inside it. A `Push` flow is drawn plain, because it was pushed into the host's own
// navigation and is a part of it. Before this rule existed the two entry styles rendered
// identically on Android and only the back handler told them apart, so a modal flow
// appeared INLINE under the host's own content while the same flow covered the host on
// iOS — the two halves of one vocabulary disagreeing about what the word means.
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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import xyz.superfunction.spfn.ui.components.LocalScreenChrome
import xyz.superfunction.spfn.ui.components.ScreenChrome

/**
 * Renders [flow]'s top route and follows the stack as it changes.
 *
 * Renders nothing at all once the flow is closed AND whatever it was drawn as has finished
 * leaving. The two are not the same instant. `Flow.close` empties the stack in one step and a
 * cover or a sheet still has a slide to run, so [ModalCover] and [SheetCover] each keep the
 * last stack they stood on and stop when their own exit says so — which is why the branch that
 * reaches them is read BEFORE the one that answers an empty stack with nothing. Everything
 * else here does render nothing, and that is not a special case bolted on either: NavDisplay
 * requires a non-empty back stack and refuses an empty one, so a closed flow has nothing to
 * show by the navigator's own rule as well as by this module's.
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
    val host = LocalNavigationHost.current;

    when (val drawing = flowDrawing(entry, hosted = host != null, empty = routes.isEmpty()))
    {
        FlowDrawing.Appended -> Appended(host!!, flow, routes, content)
        FlowDrawing.Modal -> ModalCover(flow, routes, content)
        is FlowDrawing.Sheet -> SheetCover(flow, drawing.entry, routes, content)
        FlowDrawing.Blank -> Unit
        FlowDrawing.Inline -> InlineStack(flow, entry, routes, Modifier, content)
    };
}

/** What [FlowHost] draws a flow as: the answer [flowDrawing] gives, one case per branch. */
internal sealed interface FlowDrawing
{
    /** A registration with the surrounding [NavigationHost], and nothing drawn here. */
    data object Appended : FlowDrawing

    /** The cover a modal flow stands on, open or sliding away. */
    data object Modal : FlowDrawing

    /** The sheet a sheet flow stands on, open or sliding away. */
    data class Sheet(val entry: FlowEntry.Sheet) : FlowDrawing

    /** Nothing at all: a closed flow with no exit of its own left to run. */
    data object Blank : FlowDrawing

    /** The flow's own navigator, for a pushed flow that found no host. */
    data object Inline : FlowDrawing
}

/**
 * What a flow entered by [entry] is drawn as, given whether a host is there to append to and
 * whether its stack is empty.
 *
 * A pure function so the order of its lines is something a JVM test can hold. Two of them are
 * true at once for a sheet whose flow has just closed — it is a sheet, and its stack is empty
 * — and the sheet must win: `Flow.close` empties the stack in one step, and a sheet answered
 * with nothing leaves the composition on that frame and vanishes where iOS's `.sheet` slides
 * it away (docs/IMPLEMENTATION-PITFALLS.md P38). A modal's cover is the same case, and the
 * empty-stack line is reached only by a flow with no exit of its own to run.
 */
internal fun flowDrawing(entry: FlowEntry, hosted: Boolean, empty: Boolean): FlowDrawing = when
{
    entry is FlowEntry.Push && hosted -> FlowDrawing.Appended
    entry is FlowEntry.Modal -> FlowDrawing.Modal
    entry is FlowEntry.Sheet -> FlowDrawing.Sheet(entry)
    empty -> FlowDrawing.Blank
    else -> FlowDrawing.Inline
};

/**
 * A pushed flow inside a [NavigationHost]: no navigator of its own, and nothing drawn here.
 *
 * What this composable leaves behind is a REGISTRATION — how the host draws this flow's
 * routes, and what this flow's back does — and the host's own NavDisplay draws them. The
 * registration is refreshed on every composition so that the closure below closes over what
 * this flow now is; the host is what keeps following the stack once this composable is gone,
 * which it will be as soon as one of these routes covers the host's root.
 *
 * `SideEffect` and not a bare call, because registering is a write to something outside the
 * composition and a composition may be thrown away and run again. It runs after every
 * successful one, which is exactly when the registration is worth having.
 */
@Composable
private fun <R : FlowRoute> Appended(
    host: HostStackStore,
    flow: Flow<R>,
    routes: List<R>,
    content: @Composable (R) -> Unit
)
{
    val screen: @Composable (Any) -> Unit = { route ->
        @Suppress("UNCHECKED_CAST")
        HostedScreen(flow, route as R, content);
    };
    val registration = HostRegistration(screen = screen, back = { flow.back(FlowEntry.Push) });
    // No parentheses to put a brace after, so this one lambda opens on its own line's end:
    // `SideEffect` followed by a newline is a reference to it rather than a call.
    SideEffect {
        host.register(flow, flow.stack, registration);
        // The routes this composition SAW, so that a stack the host has not caught up with
        // yet is corrected the moment it is drawn again. The collector inside the store is
        // what carries every change; this is the first one.
        host.sync(flow, routes);
    };
}

/**
 * One route of a hosted flow, drawn with its own flow's chrome.
 *
 * The depth is read HERE rather than closed over, because this composable is what the host
 * draws and the header it draws has to be the one this flow's current depth asks for.
 */
@Composable
private fun <R : FlowRoute> HostedScreen(flow: Flow<R>, route: R, content: @Composable (R) -> Unit)
{
    val depth = flow.stack.collectAsState().value.size;
    CompositionLocalProvider(LocalScreenChrome provides rememberChrome(flow, FlowEntry.Push, depth))
    {
        content(route);
    };
}

/**
 * The flow's own NavDisplay: a sheet's stack, a modal's cover, and a pushed flow that found
 * no host to append to.
 */
@Composable
private fun <R : FlowRoute> InlineStack(
    flow: Flow<R>,
    entry: FlowEntry,
    routes: List<R>,
    modifier: Modifier,
    content: @Composable (R) -> Unit
)
{
    // Only the root is this handler's: above it NavDisplay has its own, and two enabled
    // handlers over one gesture is one of them never running.
    BackHandler(enabled = routes.size == 1 && flow.handlesBack(entry)) { flow.back(entry) };

    CompositionLocalProvider(LocalScreenChrome provides rememberChrome(flow, entry, routes.size)) {
        NavDisplay(
            backStack = routes,
            modifier = modifier,
            onBack = { flow.back(entry) },
            // The same three a pushed flow travels on, from the same place: a screen inside
            // a modal or a sheet is a screen on a stack, and moves like one.
            transitionSpec = { FlowTransitions.forward },
            popTransitionSpec = { FlowTransitions.pop },
            predictivePopTransitionSpec = { _ -> FlowTransitions.predictivePop },
            entryProvider = { route -> NavEntry(route) { content(it) } }
        );
    };
}

/**
 * The cover a modal flow is drawn as, and the slide it arrives and leaves on.
 *
 * Composed whether the flow is open or not, which is what an `AnimatedVisibility` needs to
 * animate the arrival: a node that appears with `visible = true` already set has no state to
 * move from and simply exists.
 *
 * `drawn` is what makes the exit possible. The flow's stack is empty the instant it closes
 * and the cover has half a slide left to run, so the last stack it stood on is kept — a
 * plain remembered list rather than snapshot state, because writing state during a
 * composition is a composition asking for another one.
 */
@Composable
private fun <R : FlowRoute> ModalCover(flow: Flow<R>, routes: List<R>, content: @Composable (R) -> Unit)
{
    val drawn = remember { mutableListOf<R>() };
    if (routes.isNotEmpty())
    {
        drawn.clear();
        drawn.addAll(routes);
    }
    AnimatedVisibility(
        visible = routes.isNotEmpty(),
        enter = slideInVertically { height -> height },
        exit = slideOutVertically { height -> height }
    )
    {
        if (drawn.isNotEmpty())
        {
            InlineStack(flow, FlowEntry.Modal, drawn.toList(), cover(), content);
        }
    };
}

/**
 * The sheet a sheet flow is drawn as, and the slide it arrives and leaves on.
 *
 * The same shape as [ModalCover] and for the same reason: the flow's stack is empty the instant
 * it closes, the sheet has a slide left to run, and a host that stopped drawing at that instant
 * would make a sheet VANISH where iOS's `.sheet` slides it away. So the last stack it stood on
 * is kept in `drawn` and the sheet is told, separately, whether it is still [open].
 *
 * Where this differs from [ModalCover] is who ends the exit. `AnimatedVisibility` runs the
 * modal's on its own clock and removes the content itself; a sheet is moved by
 * `AnchoredDraggableState`, which the host cannot see, so the sheet says when it has settled
 * out of sight and `hidden` is that word written down.
 *
 * The two pieces of memory are deliberately different kinds. `drawn` is a plain remembered
 * list, as in [ModalCover] — it is only ever written while the stack is non-empty, which is
 * exactly when this composable is recomposing anyway, so making it snapshot state would buy an
 * invalidation for a frame that is already being drawn. `hidden` IS snapshot state, because the
 * one thing it does is arrive from outside a composition — a callback off the sheet's own
 * animation — and ask for the recomposition that stops drawing the sheet. Writing `false` back
 * into it during composition costs nothing after the first open: `mutableStateOf` compares
 * before it invalidates, so the same value written every frame invalidates nothing.
 */
@Composable
private fun <R : FlowRoute> SheetCover(
    flow: Flow<R>,
    entry: FlowEntry.Sheet,
    routes: List<R>,
    content: @Composable (R) -> Unit
)
{
    val drawn = remember { mutableListOf<R>() };
    var hidden by remember { mutableStateOf(true) };
    if (routes.isNotEmpty())
    {
        drawn.clear();
        drawn.addAll(routes);
        hidden = false;
    }
    if (!hidden && drawn.isNotEmpty())
    {
        Sheet(
            detent = entry.detent,
            open = routes.isNotEmpty(),
            onClose = { flow.close() },
            onHidden = { hidden = true }
        )
        {
            InlineStack(flow, entry, drawn.toList(), Modifier, content);
        };
    }
}

/**
 * What the screens inside a flow are told about their way out.
 *
 * Remembered on the three things it is made of, so that a static composition local is handed
 * the same instance frame after frame: a new one every recomposition would recompose every
 * screen in the flow for a chrome that did not change.
 */
@Composable
private fun <R : FlowRoute> rememberChrome(flow: Flow<R>, entry: FlowEntry, depth: Int): ScreenChrome =
    remember(flow, entry, depth) {
        ScreenChrome(
            wayOut = flow.wayOut(entry),
            onBack = { flow.back(entry) },
            onClose = { flow.close() }
        )
    }
