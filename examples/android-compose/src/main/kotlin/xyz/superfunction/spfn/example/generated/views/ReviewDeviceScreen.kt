// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      cd02e9ed576538e540a939229a0e476a76708e84286a3ccd09f5f680bf7ab8b5
// bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
// contractVersion: 0.10.0
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

package xyz.superfunction.spfn.example.generated.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.superfunction.spfn.example.generated.screens.ReviewDeviceModel
import xyz.superfunction.spfn.ui.Loadable

/** The `reviewDevice` screen: one control per action, and the two readouts. */
@Composable
fun ReviewDeviceScreen(model: ReviewDeviceModel)
{
    val state = model.state.collectAsState().value;
    val stack = model.stack.collectAsState().value;
    val scope = rememberCoroutineScope();

    // A screen loads its own read once, however it appeared: pushed onto the stack,
    // or already on it because the flow was opened at a whole stack at once.
    LaunchedEffect(model) { model.load() };

    Column(modifier = Modifier.fillMaxWidth())
    {
        BasicText(text = "state=" + stateName(state));
        BasicText(text = "stack=" + stack.size);
        BasicText(
            text = "approve",
            modifier = Modifier
                .testTag("reviewDevice.approve")
                .heightIn(min = TouchTarget)
                .clickable { scope.launch { model.approve() } }
        );
        BasicText(
            text = "back",
            modifier = Modifier
                .testTag("reviewDevice.back")
                .heightIn(min = TouchTarget)
                .clickable { model.back() }
        );
        BasicText(
            text = "deny",
            modifier = Modifier
                .testTag("reviewDevice.deny")
                .heightIn(min = TouchTarget)
                .clickable { scope.launch { model.deny() } }
        );
        BasicText(
            text = "retry",
            modifier = Modifier
                .testTag("reviewDevice.retry")
                .heightIn(min = TouchTarget)
                .clickable { scope.launch { model.retry() } }
        );
    }
}

/**
 * The platform's minimum touch target, given to every control and field.
 *
 * Compose expands a control smaller than this past its layout bounds for touch, and
 * in a column of one-line controls those expansions overlap: the bounds reported for
 * one control then sit on a neighbour's, and a runner tapping the reported centre taps
 * the neighbour (docs/IMPLEMENTATION-PITFALLS.md P21). Sized here, nothing is expanded.
 */
private val TouchTarget: Dp = 48.dp;

/** The one word a runner reads this screen's state as. */
private fun stateName(state: Loadable<*>): String = when (state)
{
    is Loadable.Loading -> "loading"
    is Loadable.Ready -> "ready"
    is Loadable.Empty -> "empty"
    is Loadable.Error -> "error"
}
