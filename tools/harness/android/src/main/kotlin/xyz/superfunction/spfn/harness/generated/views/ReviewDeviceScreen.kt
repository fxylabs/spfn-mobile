// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      91e7628f407dafb74f4b501b4effac2b2277dc629c2a7ad4de42749f410ca018
// bundleSha256:    8cce6d896e200a18e1312f23ed63ff4fc36b4ff6ea484f576c3de824be3b589e
// contractVersion: 0.13.2
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateHarnessUi
// Verified by:     ./gradlew :ui-codegen:spfnHarnessUiVerify

package xyz.superfunction.spfn.harness.generated.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import xyz.superfunction.spfn.harness.generated.screens.ReviewDeviceModel
import xyz.superfunction.spfn.harness.generated.screens.ScreenFailure
import xyz.superfunction.spfn.ui.Loadable
import xyz.superfunction.spfn.ui.components.DestructiveButton
import xyz.superfunction.spfn.ui.components.LoadableView
import xyz.superfunction.spfn.ui.components.PrimaryButton
import xyz.superfunction.spfn.ui.components.Screen
import xyz.superfunction.spfn.ui.components.SpfnText
import xyz.superfunction.spfn.ui.components.TextButton
import xyz.superfunction.spfn.ui.components.TextRole
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/** The `reviewDevice` screen, drawn out of spfn-ui's components. */
@Composable
fun ReviewDeviceScreen(model: ReviewDeviceModel)
{
    val state = model.state.collectAsState().value;
    val stack = model.stack.collectAsState().value;
    val writing = model.writing.collectAsState().value;
    val scope = rememberCoroutineScope();

    // A screen loads its own read once, however it appeared: pushed onto the stack,
    // or already on it because the flow was opened at a whole stack at once.
    LaunchedEffect(model) { model.load() };

    Screen(title = "Review the device", scroll = true)
    {
        Column(modifier = Modifier.fillMaxWidth().padding(SpfnTokens.space4), verticalArrangement = Arrangement.spacedBy(SpfnTokens.space4))
        {
            SpfnText(text = "state=" + stateName(state), role = TextRole.Mono);
            SpfnText(text = "stack=" + stack.size, role = TextRole.Mono);
            LoadableView(
                state = state,
                retryId = "reviewDevice.retry",
                onRetry = { scope.launch { model.retry() } },
                message = ScreenFailure::message
            )
            {
                // What a value looks like is the human's, outside `generated/`.
            }
            PrimaryButton(
                title = "approve",
                id = "reviewDevice.approve",
                busy = writing,
                onTap = { scope.launch { model.approve() } }
            );
            TextButton(
                title = "back",
                id = "reviewDevice.back",
                onTap = { model.back() }
            );
            DestructiveButton(
                title = "deny",
                id = "reviewDevice.deny",
                busy = writing,
                onTap = { scope.launch { model.deny() } }
            );
        }
    }
}

/** The one word a runner reads this screen's state as. */
private fun stateName(state: Loadable<*>): String = when (state)
{
    is Loadable.Loading -> "loading"
    is Loadable.Ready -> "ready"
    is Loadable.Empty -> "empty"
    is Loadable.Error -> "error"
}
