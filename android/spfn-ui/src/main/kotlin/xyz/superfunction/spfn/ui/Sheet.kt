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

package xyz.superfunction.spfn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
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
import xyz.superfunction.spfn.ui.components.spfnPalette
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/** Where a sheet can rest: at the height its detent asked for, or gone. */
internal enum class SheetAnchor
{
    Open,
    Hidden
}

/**
 * Draws [content] as a sheet standing at [detent], and calls [onClose] when the sheet is
 * dismissed by the scrim or by a drag.
 *
 * Dismissal is reported rather than performed: this composable never closes anything
 * itself, it says that the user asked, and `FlowHost` spends that on `Flow.back` so that a
 * drag and a system back reach the flow through the same door.
 */
@Composable
internal fun Sheet(detent: SheetDetent, onClose: () -> Unit, content: @Composable () -> Unit)
{
    val density = LocalDensity.current;
    val state = remember { AnchoredDraggableState(initialValue = SheetAnchor.Hidden) };
    var measured by remember { mutableStateOf(false) };

    // Reported once the sheet has actually stood up, so that the Hidden the state starts in
    // is not read as a dismissal of a sheet nobody has seen yet.
    LaunchedEffect(state.settledValue)
    {
        if (measured && state.settledValue == SheetAnchor.Hidden)
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
                        if (measured) state.targetValue else SheetAnchor.Open
                    );
                    measured = true;
                }
                .offset { IntOffset(x = 0, y = state.offset.let { if (it.isNaN()) 0 else it.roundToInt() }) }
                .clip(RoundedCornerShape(topStart = SpfnTokens.radiusLarge, topEnd = SpfnTokens.radiusLarge))
                .background(spfnPalette().background)
                .consumeWindowInsets(WindowInsets.statusBars)
                .testTag("sheet")
        )
        {
            Handle(state = state);
            content();
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
 */
@Composable
private fun Handle(state: AnchoredDraggableState<SheetAnchor>)
{
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(HANDLE_ROW)
            .testTag("sheet.handle")
            .anchoredDraggable(
                state = state,
                orientation = Orientation.Vertical,
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
