// SPFN Mobile — the four sizes that are not design decisions.
//
// Counterpart of Sources/SPFNUI/Components/Metrics.swift. This is what is LEFT of the old
// `ScreenStyle` after the tokens took the colours, the spacing, the radii and the fonts:
// `TOUCH_TARGET`, `HEADER_HEIGHT`, `BORDER_WIDTH` and `ICON_SIZE`, four numbers that a design
// flow does not get to move.
//
// 48dp is Android's minimum touch target and 44pt is Apple's, and they are the sizes
// docs/IMPLEMENTATION-PITFALLS.md P21 is about — a control smaller than one reports a
// rectangle its neighbour has already eaten, and a device runner then taps the neighbour.
// `ICON_SIZE` is the glyph drawn INSIDE that target and follows from it rather than from a
// palette; `BORDER_WIDTH` is one device pixel's worth of hairline, which is what a border is
// on both platforms before anybody designs one. None of them is a token, because a token is
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

import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.superfunction.spfn.ui.tokens.SpfnPalette
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/** Sizes the platform fixes, not the palette. */
internal object Metrics
{
    /** Android's minimum touch target (docs/IMPLEMENTATION-PITFALLS.md P21). */
    val TOUCH_TARGET: Dp = 48.dp;

    /** The header's height before the status bar inset is added to it. */
    val HEADER_HEIGHT: Dp = 56.dp;

    /** How thick a field's or an outlined control's border is drawn. */
    val BORDER_WIDTH: Dp = 1.dp;

    /** How big a header's own mark is drawn, inside a [TOUCH_TARGET]-sized frame. */
    val ICON_SIZE: Dp = 20.dp;
}

/**
 * The palette for the appearance in scope.
 *
 * Not a token and deliberately not in the key set: it is HOW a palette is chosen, and the two
 * platforms choose one by different mechanisms — `isSystemInDarkTheme` here, a SwiftUI
 * environment value there. A key that could not mean the same thing on both sides has no
 * business in a set the two sides are compared on.
 */
@Composable
internal fun spfnPalette(): SpfnPalette =
    if (isSystemInDarkTheme()) SpfnTokens.dark else SpfnTokens.light
