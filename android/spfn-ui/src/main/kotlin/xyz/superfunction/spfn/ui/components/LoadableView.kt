// SPFN Mobile — one read's four states, drawn.
//
// Counterpart of Sources/SPFNUI/Components/LoadableView.swift.
//
// `Loadable` has four members and this has four branches, which is the whole design: a screen
// that switched on the state itself would grow three of them and forget the fourth, and
// `Empty` is the one it would forget — no operation in the pinned contract can produce it
// today (examples/ui-spec/SCHEMA.md, "1단계 rule: every response is an object"), so a screen
// written by hand would never see it in a run and would ship without it.
//
// Three of the four have defaults and the fourth does not. What "ready" looks like is the
// screen's own business; what "loading", "nothing" and "it failed" look like is the SDK's,
// and a screen that had to write them would write them differently every time. The words
// come from `SpfnStrings` and the failure is classified into a KEY rather than drawn from the
// envelope, because the envelope's `message` is text a server chose (decision C7).

package xyz.superfunction.spfn.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.ui.Loadable
import xyz.superfunction.spfn.ui.SpfnStrings
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/**
 * Draws whichever of a read's four states it is in.
 *
 * @param state the read's state.
 * @param retryId the test tag of the control the error slot draws.
 * @param onRetry what that control does. Null draws no control, which is the honest shape for
 *   a screen whose spec declares no re-read.
 * @param message how an envelope becomes a sentence. The default is the least this SDK can
 *   honestly say; the generated scaffold passes its own `ScreenFailure.message`, which
 *   classifies the CODE against the pinned contract and looks the answer up in `SpfnStrings`.
 *   The lambda is here rather than a classifier inside this file because the codes are the
 *   contract's and this module does not read the contract.
 * @param ready the only slot a caller has to write.
 */
@JvmSynthetic
@Composable
public fun <V> LoadableView(
    state: Loadable<V>,
    modifier: Modifier = Modifier,
    retryId: String = "",
    onRetry: (() -> Unit)? = null,
    message: (SpfnErrorEnvelope) -> String = { SpfnStrings.errorUnexpected },
    ready: @Composable (V) -> Unit
)
{
    Column(modifier = modifier.fillMaxWidth())
    {
        when (state)
        {
            is Loadable.Loading -> SpfnText(text = SpfnStrings.stateLoading, secondary = true)
            is Loadable.Ready -> ready(state.value)
            is Loadable.Empty -> SpfnText(text = SpfnStrings.stateEmpty, secondary = true)
            is Loadable.Error ->
            {
                StatusText(kind = StatusKind.Error, text = message(state.error));
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
    }
}
