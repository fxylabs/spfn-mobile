// SPFN Mobile — every value `Screen` draws with, in one object.
//
// Counterpart of Sources/SPFNUI/Components/ScreenStyle.swift. There are no design tokens in
// this repository yet, so a screen is drawn in the system font on white with black text.
// That is a placeholder and it is deliberately a SHALLOW one: the whole of it is here, six
// values and two text styles, so the work that brings tokens in replaces this file and
// touches no layout.
//
// The two touch targets are not placeholders. 48dp is Android's minimum and 44pt is
// Apple's, and they are the sizes docs/IMPLEMENTATION-PITFALLS.md P21 is about — a control
// smaller than one reports a rectangle its neighbour has already eaten, and a device runner
// then taps the neighbour.

package xyz.superfunction.spfn.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What `Screen` draws with until the token work replaces it. */
internal object ScreenStyle
{
    /** The surface a screen stands on. */
    val BACKGROUND: Color = Color.White;

    /** Text and controls. */
    val FOREGROUND: Color = Color.Black;

    /** Android's minimum touch target (docs/IMPLEMENTATION-PITFALLS.md P21). */
    val TOUCH_TARGET: Dp = 48.dp;

    /** The header's height before the status bar inset is added to it. */
    val HEADER_HEIGHT: Dp = 56.dp;

    /** The margin down both sides of a header. */
    val GUTTER: Dp = 16.dp;

    /** The title. */
    @Composable
    fun title(): TextStyle = TextStyle(color = FOREGROUND, fontSize = 20.sp, fontWeight = FontWeight.SemiBold);

    /** A header control. */
    @Composable
    fun control(): TextStyle = TextStyle(color = FOREGROUND, fontSize = 16.sp);
}
