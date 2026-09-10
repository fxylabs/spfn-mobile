// SPFN Mobile — the two lines every state-shaped view draws the same way.
//
// Counterpart of Sources/SPFNUI/Components/Slots.swift.
//
// `LoadableView` draws "in flight" and "it failed" for a READ; `PagedView`'s footer draws
// the same two for the page AFTER the first one. They are the same two lines and the second
// view was one copy-paste away from being a second, slightly different, answer to "what does
// loading look like" — which is exactly what section 15 of validate.sh compares the two
// platforms for, one level up.
//
// So they live here once and both views call them. Internal rather than public on purpose:
// they take no test tag of their own, they are not something a screen picks from a menu, and
// the component parity set is the list of things a screen CAN pick (validate.sh section 15
// reads `public fun`, which is what keeps `RoleButton` out of that list for the same
// reason). `@JvmSynthetic` for the reason every composable in this module carries it: an
// internal Kotlin function is still a callable public method from Java, and calling a
// composable from there crashes on a null `Composer` (docs/IMPLEMENTATION-PITFALLS.md P15).

package xyz.superfunction.spfn.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.superfunction.spfn.ui.SpfnStrings
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/**
 * Something is in flight: the SDK's own word for it.
 *
 * A word and not a spinner, where the Swift half draws a `ProgressView` as well. This module
 * links `compose-foundation` and no widget library, which is stated in `Screen.kt`'s header
 * and is why every component here is built out of `BasicText` and a `Box`; an indicator
 * would be the first material dependency in the SDK. What both platforms owe is the same
 * SENTENCE, and `SpfnStrings.stateLoading` is it.
 */
@JvmSynthetic
@Composable
internal fun LoadingLine(modifier: Modifier = Modifier)
{
    SpfnText(text = SpfnStrings.stateLoading, secondary = true, modifier = modifier);
}

/**
 * Something failed: the sentence the caller's classifier chose, and the control that runs it
 * again.
 *
 * The sentence arrives already chosen. The server's own `message` is never drawn — see
 * `LoadableView` and decision C7 — and this composable is one level below the classifier
 * that makes that true, so it takes text and not an envelope.
 */
@JvmSynthetic
@Composable
internal fun FailureLine(
    text: String,
    retryId: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier
)
{
    Column(modifier = modifier.fillMaxWidth())
    {
        StatusText(kind = StatusKind.Error, text = text);
        if (onRetry != null)
        {
            SecondaryButton(
                title = SpfnStrings.actionRetry,
                id = retryId,
                modifier = Modifier.padding(top = SpfnTokens.space3),
                onTap = onRetry
            );
        }
    }
}
