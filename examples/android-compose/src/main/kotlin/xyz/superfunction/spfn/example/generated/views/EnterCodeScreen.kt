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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import xyz.superfunction.spfn.example.generated.screens.EnterCodeModel
import xyz.superfunction.spfn.ui.Busy

/** The `enterCode` screen: one control per action, and the two readouts. */
@Composable
fun EnterCodeScreen(model: EnterCodeModel)
{
    val state = model.state.collectAsState().value;
    val stack = model.stack.collectAsState().value;
    val scope = rememberCoroutineScope();
    var userCode by remember { mutableStateOf("") };

    Column(modifier = Modifier.fillMaxWidth())
    {
        BasicText(text = "state=" + stateName(state));
        BasicText(text = "stack=" + stack.size);
        BasicTextField(
            value = userCode,
            onValueChange = { userCode = it },
            modifier = Modifier.testTag("enterCode.userCode")
        );
        BasicText(
            text = "cancel",
            modifier = Modifier.testTag("enterCode.cancel").clickable { model.cancel() }
        );
        BasicText(
            text = "submit",
            modifier = Modifier.testTag("enterCode.submit").clickable { scope.launch { model.submit(userCode) } }
        );
    }
}

/** The one word a runner reads this screen's state as. */
private fun stateName(state: Busy): String = when (state)
{
    is Busy.Idle -> "idle"
    is Busy.Busy -> "busy"
    is Busy.Error -> "error"
}
