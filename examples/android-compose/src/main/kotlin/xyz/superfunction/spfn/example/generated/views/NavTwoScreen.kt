// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      18bfbc7bb24e30e2dbb65e195f5448594bab02fa52e257cb590d5a2c534a7633
// bundleSha256:    a7cab3c8e98f3d83384693a45b5d1754640529636c193afd54ef116e98e47c1a
// contractVersion: 0.13.4
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

package xyz.superfunction.spfn.example.generated.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import xyz.superfunction.spfn.example.generated.screens.NavTwoModel
import xyz.superfunction.spfn.ui.Busy
import xyz.superfunction.spfn.ui.components.PrimaryButton
import xyz.superfunction.spfn.ui.components.Screen
import xyz.superfunction.spfn.ui.components.SpfnText
import xyz.superfunction.spfn.ui.components.TextButton
import xyz.superfunction.spfn.ui.components.TextRole
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/** The `navTwo` screen, drawn out of spfn-ui's components. */
@Composable
fun NavTwoScreen(model: NavTwoModel)
{
    val state = model.state.collectAsState().value;
    val stack = model.stack.collectAsState().value;

    Screen(title = "Deeper inside a sheet", scroll = true)
    {
        Column(modifier = Modifier.fillMaxWidth().padding(SpfnTokens.space4), verticalArrangement = Arrangement.spacedBy(SpfnTokens.space4))
        {
            SpfnText(text = "state=" + stateName(state), role = TextRole.Mono);
            SpfnText(text = "stack=" + stack.size, role = TextRole.Mono);
            SpfnText(text = "This sheet holds what it shows. It fits without scrolling, so the way out is always in reach.");
            TextButton(
                title = "back",
                id = "navTwo.back",
                onTap = { model.back() }
            );
            PrimaryButton(
                title = "done",
                id = "navTwo.done",
                onTap = { model.done() }
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
