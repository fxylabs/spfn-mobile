// SPFN Mobile — the two sizes that are not design decisions.
//
// Counterpart of Sources/SPFNUI/Components/Metrics.swift. This is what is LEFT of the old
// `ScreenStyle` after the tokens took the colours, the spacing, the radii and the fonts: two
// numbers that a design flow does not get to move.
//
// 48dp is Android's minimum touch target and 44pt is Apple's, and they are the sizes
// docs/IMPLEMENTATION-PITFALLS.md P21 is about — a control smaller than one reports a
// rectangle its neighbour has already eaten, and a device runner then taps the neighbour.
// They are not tokens because a token is a value the design flow replaces (decision S10) and
// these two are the platforms'.
//
// The header height is here rather than in the tokens for a smaller reason: it is a layout
// constant of one component, not a value any other component reads.

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
