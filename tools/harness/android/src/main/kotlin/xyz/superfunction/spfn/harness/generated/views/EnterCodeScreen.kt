// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      88e5159b5528860daa36d6ebae1f6a6940c8152eb8373bf4cb3656be70599153
// bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
// contractVersion: 0.10.0
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateHarnessUi
// Verified by:     ./gradlew :ui-codegen:spfnHarnessUiVerify

package xyz.superfunction.spfn.harness.generated.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import xyz.superfunction.spfn.harness.generated.screens.EnterCodeModel
import xyz.superfunction.spfn.harness.generated.screens.ScreenFailure
import xyz.superfunction.spfn.ui.Busy
import xyz.superfunction.spfn.ui.components.FieldKind
import xyz.superfunction.spfn.ui.components.PrimaryButton
import xyz.superfunction.spfn.ui.components.Screen
import xyz.superfunction.spfn.ui.components.SpfnText
import xyz.superfunction.spfn.ui.components.SpfnTextField
import xyz.superfunction.spfn.ui.components.StatusKind
import xyz.superfunction.spfn.ui.components.StatusText
import xyz.superfunction.spfn.ui.components.TextButton
import xyz.superfunction.spfn.ui.components.TextRole
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/** The `enterCode` screen, drawn out of spfn-ui's components. */
@Composable
fun EnterCodeScreen(model: EnterCodeModel)
{
    val state = model.state.collectAsState().value;
    val stack = model.stack.collectAsState().value;
    val scope = rememberCoroutineScope();
    var userCode by remember { mutableStateOf("") };

    Screen(title = "Approve a device", scroll = true)
    {
        Column(modifier = Modifier.fillMaxWidth().padding(SpfnTokens.space4), verticalArrangement = Arrangement.spacedBy(SpfnTokens.space4))
        {
            SpfnText(text = "state=" + stateName(state), role = TextRole.Mono);
            SpfnText(text = "stack=" + stack.size, role = TextRole.Mono);
            SpfnTextField(
                label = "Code from the device",
                id = "enterCode.userCode",
                value = userCode,
                onValueChange = { edited -> userCode = edited; model.clearError(); },
                kind = FieldKind.Code,
                error = ScreenFailure.fieldMessage(
                    (state as? Busy.Error)?.error,
                    "userCode"
                ),
                submitOnReturn = true,
                autofocus = true,
                onSubmit = { scope.launch { model.submit(userCode) } }
            );
            val failure = (state as? Busy.Error)?.error;
            if (failure != null && !ScreenFailure.isFieldRefusal(failure))
            {
                StatusText(
                    kind = StatusKind.Error,
                    text = ScreenFailure.message(failure),
                    id = "enterCode.status"
                );
            }
            TextButton(
                title = "cancel",
                id = "enterCode.cancel",
                onTap = { model.cancel() }
            );
            PrimaryButton(
                title = "submit",
                id = "enterCode.submit",
                busy = state is Busy.Busy,
                onTap = { scope.launch { model.submit(userCode) } }
            );
        }
    }
}

/** The one word a runner reads this screen's state as. */
private fun stateName(state: Busy): String = when (state)
{
    is Busy.Idle -> "idle"
    is Busy.Busy -> "busy"
    is Busy.Error -> "error"
}
