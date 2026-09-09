// SPFN Mobile — a sheet, drawn out of foundation and nothing else.
//
// There is no counterpart file on iOS, and that is the point: `presentationDetents` is a
// system sheet and SwiftUI draws it, so `FlowHost.swift` names the three heights and stops.
// Android has no sheet outside Material, this repository does not depend on Material
// (decision C2), and so the sheet is drawn here — a scrim, a handle, and one surface that
// slides. What the two platforms share is `SheetGeometry`: the heights and the dismissal
// threshold are the same numbers on both, and they are tested on both.
//
// ---------------------------------------------------------------------------
// The drag is the HANDLE's, and only the handle's
// ---------------------------------------------------------------------------
//
// A sheet that is draggable everywhere and a body that scrolls are two gesture detectors
// competing for the same vertical drag, and the loser is whichever one the user meant. The
// usual answer is a nested-scroll connection that hands the drag back and forth by reading
// which one is at its limit. The answer here is narrower and needs no arbitration at all:
// `anchoredDraggable` is attached to the drag handle, so a drag that starts on the handle
// moves the sheet and a drag that starts anywhere else is the body's. A screen inside a
// sheet may therefore scroll normally.
//
// The sheet also CONSUMES the status bar inset before its content sees it. A sheet stands
// at the bottom of the window and its header is nowhere near the status bar, but
// `Modifier.windowInsetsPadding` does not know where in the window it sits — it applies
// whatever the window reports and has not been consumed yet. Without this, every `Screen`
// inside a sheet would carry a status bar's worth of empty space above its title
// (docs/IMPLEMENTATION-PITFALLS.md P25).
//
// ---------------------------------------------------------------------------
// Arriving and leaving are the DRAG's path, walked by something other than a finger
// ---------------------------------------------------------------------------
//
// A sheet that appears at its detent and vanishes from it is not a sheet on either platform,
// and it is what this file used to draw: the first `updateAnchors` was handed
// `newTarget = Open`, and that argument is a SNAP — `AnchoredDraggableState.updateAnchors`
// calls `trySnapTo`, which takes the drag mutex and calls `dragTo` with the new anchor's
// position in one step (androidx.compose.foundation 1.11.4, read with javap). So the sheet was
// already standing on the frame it was measured on.
//
// There is exactly one path a sheet moves along and `AnchoredDraggableState` owns it, so the
// arrival and the departure are `animateTo` over the same anchors the handle drags between.
// Nothing here states an animation spec: `animateTo` with none falls through to
// `AnchoredDraggableDefaults.snapAnimationSpec`, which is the same spec the handle's own fling
// settles on, and a second opinion about how a sheet moves is the defect this avoids.
//
// The scrim needs no part of this. `SheetGeometry.scrim` is a function of the sheet's OFFSET,
// so a position that animates is a scrim that fades, and a position that a finger drags is a
// scrim that follows the finger — the same arithmetic answering both.
//
// What moves when is [SheetPhase], and it is a file of its own because it is the only half of
// this a JVM test can drive (docs/IMPLEMENTATION-PITFALLS.md P38).

package xyz.superfunction.spfn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import xyz.superfunction.spfn.ui.components.LocalFitsContent
import xyz.superfunction.spfn.ui.components.spfnPalette
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/** Where a sheet can rest: at the height its detent asked for, or gone. */
internal enum class SheetAnchor
{
    Open,
    Hidden
}

/**
 * Draws [content] as a sheet standing at [detent] for as long as [open], and reports what it
 * did about it: [onClose] when the user asked the sheet to go, [onHidden] once it has gone.
 *
 * The two callbacks are two different sentences and a caller needs both.
 *
 * [onClose] is a REQUEST, and this composable never grants it: the scrim was tapped, or the
 * handle was dragged past `SheetGeometry.closes`, and `FlowHost` spends that on `Flow.close`
 * so that a drag, a scrim and a system back reach the flow through the same door. Nothing
 * moves because of it — [open] going false is what moves the sheet.
 *
 * [onHidden] is a RECEIPT, and it arrives once, after the sheet has settled out of sight. A
 * flow's stack empties the instant it closes and this sheet still has its slide to run, so the
 * host has to be told when it may stop drawing; a host that stops at [open] going false is a
 * sheet that disappears instead of leaving.
 *
 * A dismissal by drag produces both, in that order and one flow round trip apart: the drag
 * settles at [SheetAnchor.Hidden], [onClose] reports it, the flow closes, [open] goes false,
 * and the fall it asks for has nowhere left to go — so it completes at once and [onHidden]
 * follows.
 */
@Composable
internal fun Sheet(
    detent: SheetDetent,
    open: Boolean,
    onClose: () -> Unit,
    onHidden: () -> Unit,
    content: @Composable () -> Unit
)
{
    val density = LocalDensity.current;
    val state = remember { AnchoredDraggableState(initialValue = SheetAnchor.Hidden) };
    var measured by remember { mutableStateOf(false) };
    var phase by remember { mutableStateOf(SheetPhase.Unmeasured) };

    LaunchedEffect(measured, open)
    {
        phase = phase.asked(measured = measured, open = open);
    }

    // The only place a sheet moves on its own, and both directions are here. The phase that
    // FOLLOWS is the one the travel earned: `animateTo` runs under the state's drag mutex, so
    // the next phase's travel cancels this coroutine where it stands and the line below it
    // never runs — an interrupted rise does not get to claim it is standing.
    LaunchedEffect(phase)
    {
        val destination = phase.destination;
        if (destination != null)
        {
            state.animateTo(destination);
            phase = phase.arrived();
        }
        else if (phase == SheetPhase.Gone)
        {
            onHidden();
        }
    }

    // Reported once the sheet has actually stood up, so that the Hidden the state starts in —
    // and the Hidden it is still settled at all the way up — is not read as a dismissal of a
    // sheet nobody has seen yet.
    LaunchedEffect(state.settledValue)
    {
        if (phase.dismisses(state.settledValue))
        {
            onClose();
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize())
    {
        val container = constraints.maxHeight.toFloat();
        val full = SheetGeometry.height(SheetDetent.Full, container, 0f);
        val hidden = state.anchors.positionOf(SheetAnchor.Hidden);
        val offset = state.offset;

        Scrim(
            opacity = if (offset.isNaN() || hidden.isNaN()) 0f else SheetGeometry.scrim(offset, hidden),
            onTap = onClose
        );

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(detent.heightModifier(container = container, full = full, density = density))
                .onSizeChanged { size ->
                    state.updateAnchors(
                        DraggableAnchors {
                            SheetAnchor.Open at 0f;
                            SheetAnchor.Hidden at size.height.toFloat();
                        },
                        if (measured) state.targetValue else SheetAnchor.Hidden
                    );
                    measured = true;
                }
                // Off the bottom until there is an offset to believe, rather than at the
                // detent: the fallback is what a reader sees if a measurement is ever late,
                // and a sheet flashing at full height is the exact defect above.
                .offset { IntOffset(x = 0, y = sheetY(state.offset, container)) }
                .clip(RoundedCornerShape(topStart = SpfnTokens.radiusLarge, topEnd = SpfnTokens.radiusLarge))
                .background(spfnPalette().background)
                .consumeWindowInsets(WindowInsets.statusBars)
                .testTag("sheet")
        )
        {
            Handle(state = state, enabled = phase.draggable);
            // The one thing the content has to be told, and only `Fit` makes it true: this
            // sheet is as tall as what is inside it, so what is inside it may not fill.
            // Everything else here fixes a height, and a screen that fills a fixed height is
            // what a half sheet is (docs/IMPLEMENTATION-PITFALLS.md P34).
            CompositionLocalProvider(LocalFitsContent provides (detent == SheetDetent.Fit))
            {
                content();
            };
        }
    }
}

/**
 * What a detent means to the layout.
 *
 * `Fit` is the only one that does not fix a height: it lets the content measure itself and
 * caps the result, which is what makes "as tall as it needs" a measurement rather than a
 * guess. `wrapContentHeight().heightIn(max = full)` is [SheetGeometry.fitHeight] expressed
 * as layout — the same content, the same ceiling — and the header term is zero here because
 * this sheet's header is inside the column being measured rather than above it. iOS cannot
 * express it as layout, because SwiftUI resolves a detent before laying the sheet out, so it
 * calls the arithmetic with a header and a measurement of its own; that is the version both
 * platforms test.
 *
 * The ceiling being FINITE is why the `Fit` case needs the composition local above as well
 * as this modifier. A wrap measures what its content came to, and content written to fill
 * what it is offered comes to the ceiling: without a `Screen` that wraps in turn, this line
 * resolves to `full` every time and a Fit sheet is a Full sheet
 * (docs/IMPLEMENTATION-PITFALLS.md P34).
 */
private fun SheetDetent.heightModifier(container: Float, full: Float, density: Density): Modifier
{
    val fullDp = with(density) { full.toDp() };
    return when (this)
    {
        SheetDetent.Fit -> Modifier.wrapContentHeight().heightIn(max = fullDp)
        SheetDetent.Half -> Modifier.height(with(density) { SheetGeometry.height(SheetDetent.Half, container, 0f).toDp() })
        SheetDetent.Full -> Modifier.height(fullDp)
    };
}

/**
 * How far down the sheet is drawn, in whole pixels.
 *
 * [offset] is NaN until the first `updateAnchors`, which happens inside the measure pass of
 * the sheet's own first frame — so placement, which runs after that pass, has always read a
 * real number. [container] is what is drawn if that ever stops being true: the sheet's own
 * container is at least as tall as the sheet, so it puts the sheet off the bottom edge, which
 * is where an unmeasured sheet belongs. Zero — the detent, the top of the travel — would put a
 * sheet nobody has measured at full height for a frame and then drop it.
 */
private fun sheetY(offset: Float, container: Float): Int =
    if (offset.isNaN()) container.roundToInt() else offset.roundToInt();

/**
 * The dimmed surface behind the sheet. Tapping it asks to close, which is the one
 * affordance a sheet has that a full-screen modal does not.
 */
@Composable
private fun Scrim(opacity: Float, onTap: () -> Unit)
{
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = opacity * SCRIM_OPACITY))
            .testTag("sheet.scrim")
            .pointerInput(Unit) {
                detectTapGestures { onTap() };
            }
    );
}

/**
 * The grip, and the only part of a sheet that drags it.
 *
 * The row is a whole touch target tall (docs/IMPLEMENTATION-PITFALLS.md P21) even though the
 * bar drawn inside it is a few pixels: what a person grabs is the row.
 *
 * [enabled] is `SheetPhase.draggable`, and it is false for as long as the sheet is travelling
 * on its own. A drag takes the state's mutator mutex at a higher priority than an animation
 * does, so a finger on the handle mid-travel cancels the `animateTo` that the phase was
 * waiting on — and the sheet is left where the finger dropped it, in a phase that no longer
 * describes it. There is nothing to arbitrate: a sheet that is not standing is not yet the
 * user's to move.
 */
@Composable
private fun Handle(state: AnchoredDraggableState<SheetAnchor>, enabled: Boolean)
{
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HANDLE_ROW)
            .testTag("sheet.handle")
            .anchoredDraggable(
                state = state,
                orientation = Orientation.Vertical,
                enabled = enabled,
                flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                    state = state,
                    positionalThreshold = { distance -> distance * SheetGeometry.DISMISS_FRACTION }
                )
            ),
        contentAlignment = Alignment.Center
    )
    {
        Box(
            modifier = Modifier
                .size(width = HANDLE_WIDTH, height = HANDLE_HEIGHT)
                .clip(RoundedCornerShape(HANDLE_HEIGHT))
                .background(HANDLE_COLOUR)
        );
    }
}

/** How dark the scrim goes at rest. Not a token: it is this component's own arithmetic. */
private const val SCRIM_OPACITY: Float = 0.4f;
private val HANDLE_ROW = 48.dp;
private val HANDLE_WIDTH = 36.dp;
private val HANDLE_HEIGHT = 4.dp;
private val HANDLE_COLOUR: Color = Color.Black.copy(alpha = 0.2f);
