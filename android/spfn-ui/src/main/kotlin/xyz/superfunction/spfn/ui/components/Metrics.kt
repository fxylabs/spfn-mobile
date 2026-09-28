// SPFN Mobile — the sizes that are not design decisions.
//
// Counterpart of Sources/SPFNUI/Components/Metrics.swift. This is what is LEFT of the old
// `ScreenStyle` after the tokens took the colours, the spacing, the radii and the fonts:
// `TOUCH_TARGET`, `HEADER_HEIGHT`, `BORDER_WIDTH` and `ICON_SIZE`, four numbers that a design
// flow does not get to move, and `MARK_SIZE`, which this half alone has.
//
// 48dp is Android's minimum touch target and 44pt is Apple's, and they are the sizes
// docs/IMPLEMENTATION-PITFALLS.md P21 is about — a control smaller than one reports a
// rectangle its neighbour has already eaten, and a device runner then taps the neighbour.
// `ICON_SIZE` is the glyph drawn INSIDE that target and follows from it rather than from a
// palette; `BORDER_WIDTH` is one device pixel's worth of hairline, which is what a border is
// on both platforms before anybody designs one. `MARK_SIZE` is the header's back and close,
// drawn at Material's 24dp icon size because they are Material's marks (HeaderIcons.kt); the
// iOS half's are the system bar's and have no number here. It is not `ICON_SIZE` because the
// tab bar reads that one at 20dp. None of them is a token, because a token is
// a value the design flow replaces (decision S10) and these are the platforms'.
//
// The header height is here rather than in the tokens for a smaller reason: it is a layout
// constant of one component, not a value any other component reads.
//
// 56 has no source to cite and this comment will not invent one. It arrived whole with the
// module (`cad422b`, PR #51) and nothing in the commit, the decisions or the pitfalls argues
// it: arbitrary choice, on the grounds that it is a header tall enough to stand a 48dp
// control in with room above and below, and that both platforms' own navigation bars are
// within a few dp of it. It is the one number in this file a design flow would be entitled
// to argue with, and moving it moves nothing but the header.

package xyz.superfunction.spfn.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Sizes the platform fixes, not the palette. */
internal object Metrics
{
    /** Android's minimum touch target (docs/IMPLEMENTATION-PITFALLS.md P21). */
    val TOUCH_TARGET: Dp = 48.dp;

    /** The header's height before the status bar inset is added to it. */
    val HEADER_HEIGHT: Dp = 56.dp;

    /**
     * How thick a field's border is drawn, and the default theme's outlined-button border; an
     * app's theme may draw its own buttons' outline at another width.
     */
    val BORDER_WIDTH: Dp = 1.dp;

    /** How big a tab bar item's icon is drawn. */
    val ICON_SIZE: Dp = 20.dp;

    /** How big a way-out control's mark is drawn, inside a [TOUCH_TARGET]-sized frame: Material's icon size. */
    val MARK_SIZE: Dp = 24.dp;
}
