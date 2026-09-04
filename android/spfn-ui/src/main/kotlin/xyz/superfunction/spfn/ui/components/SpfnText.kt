// SPFN Mobile — one line of text, in one of the four roles a screen has.
//
// Counterpart of Sources/SPFNUI/Components/SpfnText.swift.
//
// The name carries a prefix and its Swift twin carries the same one, which is the one place
// this component set gives up the module's bare naming. `Text` is SwiftUI's own and a
// generated screen imports both modules there: a second `Text` would not shadow SwiftUI's,
// it would make every unqualified use of either ambiguous, in files first compiled on a Mac
// nobody on the CI host can run. The name is then spelled the same here, because a component
// set whose members are called different things on the two platforms is two component sets.
// `SpfnTextField` carries the prefix for the same collision; the other six collide with
// nothing and are spelled bare on both.
//
// `BasicText` and not Material's `Text`: decision C2, this repository depends on no design
// system it did not write.

package xyz.superfunction.spfn.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/**
 * Text drawn in a token font and a token colour.
 *
 * @param text what it says. Never a server's words — see `SpfnStrings`.
 * @param role which of the four type tokens it is set in.
 * @param secondary whether it is the supporting colour rather than the primary one.
 *
 * `@JvmSynthetic` for the reason `Screen` carries it: a `@Composable` function is a rule the
 * Compose compiler enforces for Kotlin callers and for nobody else, and from Java this would
 * be a static method whose first real argument is a `Composer`
 * (docs/IMPLEMENTATION-PITFALLS.md P15).
 */
@JvmSynthetic
@Composable
public fun SpfnText(
    text: String,
    role: TextRole = TextRole.Body,
    secondary: Boolean = false,
    modifier: Modifier = Modifier
)
{
    val palette = spfnPalette();
    BasicText(
        text = text,
        modifier = modifier,
        style = styleOf(role).copy(color = if (secondary) palette.textSecondary else palette.text)
    );
}

/** The token a role resolves to. Written once so no component picks a font of its own. */
internal fun styleOf(role: TextRole): TextStyle = when (role)
{
    TextRole.Title -> SpfnTokens.title
    TextRole.Body -> SpfnTokens.body
    TextRole.Caption -> SpfnTokens.caption
    TextRole.Mono -> SpfnTokens.mono
}
