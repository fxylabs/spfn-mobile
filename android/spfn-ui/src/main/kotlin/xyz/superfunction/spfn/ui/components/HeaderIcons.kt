// SPFN Mobile — the two marks a header draws for the two ways out.
//
// Counterpart of Sources/SPFNUI/Components/HeaderIcons.swift. A chevron on the left for a
// back, an X on the right for a close (decision N3): the words `Back` and `Close` in body
// type read as prose rather than as controls, and a person looking for the way out of a
// sheet looks at the top right corner.
//
// Not part of the public component set, and deliberately outside the nine names section 15
// of the validator compares. These are two marks this component draws for itself; a host app
// that wants its own control passes a slot to `Screen`, which is the door that already
// exists.
//
// `Canvas` and not Material. This repository depends on no Material artifact (decision C2),
// so `Icons.Default.Close` is not a thing this module may reach for, and two strokes are
// two strokes: what has to match the iOS half is the GEOMETRY — 20dp across, inside the
// platform's minimum touch target, in the primary text colour — and not which glyph table
// they came out of.
//
// The size split is P21's, stated once here and once on the SwiftUI half: the MARK is 20dp
// and the frame around it is `Metrics.TOUCH_TARGET`. A control drawn at the mark's own size
// reports a rectangle its neighbour has already eaten, and a device runner then taps the
// neighbour.

package xyz.superfunction.spfn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/** The mark a back control draws: one chevron, pointing the way it goes. */
@Composable
internal fun BackChevron()
{
    val colour = spfnPalette().text;
    Canvas(modifier = Modifier.size(Metrics.ICON_SIZE))
    {
        // Inset so the stroke's own width stays inside the 20dp box: a line drawn to the
        // edge is half a line outside it, and the two marks would then sit at two
        // different visual sizes.
        val inset = size.minDimension * CHEVRON_INSET;
        val middle = size.height / 2f;
        drawLine(
            color = colour,
            start = Offset(size.width - inset, inset),
            end = Offset(inset, middle),
            strokeWidth = STROKE.toPx(),
            cap = StrokeCap.Round
        );
        drawLine(
            color = colour,
            start = Offset(inset, middle),
            end = Offset(size.width - inset, size.height - inset),
            strokeWidth = STROKE.toPx(),
            cap = StrokeCap.Round
        );
    }
}

/** The mark a close control draws: two strokes across the same box. */
@Composable
internal fun CloseCross()
{
    val colour = spfnPalette().text;
    Canvas(modifier = Modifier.size(Metrics.ICON_SIZE))
    {
        val inset = size.minDimension * CROSS_INSET;
        drawLine(
            color = colour,
            start = Offset(inset, inset),
            end = Offset(size.width - inset, size.height - inset),
            strokeWidth = STROKE.toPx(),
            cap = StrokeCap.Round
        );
        drawLine(
            color = colour,
            start = Offset(size.width - inset, inset),
            end = Offset(inset, size.height - inset),
            strokeWidth = STROKE.toPx(),
            cap = StrokeCap.Round
        );
    }
}

/** How thick both marks are drawn. Not a token: it is these two shapes' own arithmetic. */
private val STROKE = 2.dp;

/** How far the chevron's ends stand inside its box, as a fraction of it. */
private const val CHEVRON_INSET: Float = 0.28f;

/** The same for the cross, which reaches further because it has two ends to spare. */
private const val CROSS_INSET: Float = 0.22f;
