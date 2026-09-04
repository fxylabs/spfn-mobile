// SPFN Mobile — every visual value the components draw with, in one file.
//
// Counterpart of Sources/SPFNUI/Tokens/SPFNTokens.swift. The two files carry the SAME KEYS
// and different spellings of the same values, and section 15 of tools/validate/validate.sh
// compares the key sets. A token added to one platform only is a component that can be
// written for one platform only, which is the divergence section 13 prevents for the state
// vocabulary and this prevents for the visual one.
//
// ---------------------------------------------------------------------------
// The values are neutral and they are placeholders. The KEYS are not.
// ---------------------------------------------------------------------------
//
// Decision S10: what a token is called, and which token a component reaches for, is settled
// here and now; what colour it resolves to is settled by the design flow that has not run
// yet. So every value below is the plainest thing that can be correct — the system font,
// black on white, one grey in two shades, one blue, one red — and replacing them is
// expected to touch this file and its twin and nothing else.
//
// [SpfnTokens.dark] is STRUCTURE ONLY. It carries the light palette's values today, so that
// every component is already written against a palette it looks up rather than against
// constants, and the day the dark values arrive they arrive as six numbers in one place. A
// dark palette that did not exist at all would mean every component had to grow a branch
// later; a dark palette guessed at now would ship a theme nobody designed.

package xyz.superfunction.spfn.ui.tokens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The six colours a screen is drawn out of, as one value so that a scheme is one lookup.
 *
 * A data class rather than six constants per scheme: a component asks for the palette once
 * and then reads colours off the answer, which is what makes "the same component in the dark
 * palette" a different value rather than a different code path.
 */
public data class SpfnPalette(
    /** The surface a screen stands on. */
    public val background: Color,

    /** A surface raised above the background — a field, a sheet, a card. */
    public val surface: Color,

    /** Body text and anything a person reads first. */
    public val text: Color,

    /** Text that supports other text: a hint, a caption, a disabled control. */
    public val textSecondary: Color,

    /** The one colour that means "this is the thing to press". */
    public val accent: Color,

    /** The one colour that means "this went wrong". */
    public val error: Color
)

/** What every SPFN component draws with. */
public object SpfnTokens
{
    /** The palette a light appearance reads. */
    public val light: SpfnPalette = SpfnPalette(
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFF4F4F6),
        text = Color(0xFF000000),
        textSecondary = Color(0xFF6B6B70),
        accent = Color(0xFF0B5FFF),
        error = Color(0xFFC62828)
    );

    /**
     * The palette a dark appearance reads, which today is the light one.
     *
     * Structure without values, deliberately: see this file's header. Every component
     * already resolves a palette, so the dark theme is a change to these six lines.
     */
    public val dark: SpfnPalette = SpfnPalette(
        background = Color(0xFFFFFFFF),
        surface = Color(0xFFF4F4F6),
        text = Color(0xFF000000),
        textSecondary = Color(0xFF6B6B70),
        accent = Color(0xFF0B5FFF),
        error = Color(0xFFC62828)
    );

    /** The tightest gap: between a label and the thing it labels. */
    public val space1: Dp = 4.dp;

    /** Between two lines of one idea. */
    public val space2: Dp = 8.dp;

    /** Between a field and its error. */
    public val space3: Dp = 12.dp;

    /** The standard gutter, and the gap between two controls. */
    public val space4: Dp = 16.dp;

    /** Between two groups on one screen. */
    public val space5: Dp = 24.dp;

    /** Between a screen's header and what it introduces. */
    public val space6: Dp = 32.dp;

    /** A field, a button — anything a finger lands on. */
    public val radiusSmall: Dp = 8.dp;

    /** A sheet, a card — anything a screen sits inside. */
    public val radiusLarge: Dp = 16.dp;

    /** A screen's title. */
    public val title: TextStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold);

    /** Everything a person reads. */
    public val body: TextStyle = TextStyle(fontSize = 16.sp);

    /** A hint, a caption, an error line. */
    public val caption: TextStyle = TextStyle(fontSize = 13.sp);

    /** Anything whose characters have to line up: a code, a readout. */
    public val mono: TextStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace);
}
