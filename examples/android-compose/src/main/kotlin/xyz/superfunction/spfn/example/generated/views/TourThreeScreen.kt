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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import xyz.superfunction.spfn.example.generated.screens.TourThreeModel
import xyz.superfunction.spfn.ui.Busy
import xyz.superfunction.spfn.ui.ScreenWayOut
import xyz.superfunction.spfn.ui.WayOut
import xyz.superfunction.spfn.ui.components.PrimaryButton
import xyz.superfunction.spfn.ui.components.Screen
import xyz.superfunction.spfn.ui.components.ScreenHeader
import xyz.superfunction.spfn.ui.components.SpfnText
import xyz.superfunction.spfn.ui.components.TextButton
import xyz.superfunction.spfn.ui.components.TextRole
import xyz.superfunction.spfn.ui.components.WayOutButton
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/** The `tourThree` screen, drawn out of spfn-ui's components. */
@Composable
fun TourThreeScreen(model: TourThreeModel)
{
    val state = model.state.collectAsState().value;
    val stack = model.stack.collectAsState().value;

    Screen(title = "Pushed, three of three", header = ScreenHeader.None, scroll = true)
    {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = SpfnTokens.space4),
            horizontalArrangement =
                if (ScreenWayOut.current.wayOut == WayOut.Close) Arrangement.End else Arrangement.Start
        )
        {
            WayOutButton();
        }
        Column(modifier = Modifier.fillMaxWidth().padding(SpfnTokens.space4), verticalArrangement = Arrangement.spacedBy(SpfnTokens.space4))
        {
            SpfnText(text = "state=" + stateName(state), role = TextRole.Mono);
            SpfnText(text = "stack=" + stack.size, role = TextRole.Mono);
            SpfnText(text = "This screen reads nothing and writes nothing. It is here so the presentation around it can be looked at on its own: the frame, the way out it offers, and what the platform does to it when a person swipes.");
            SpfnText(text = "The control below moves the flow. Nothing on this screen reaches a server.");
            TextButton(
                title = "back",
                id = "tourThree.back",
                onTap = { model.back() }
            );
            PrimaryButton(
                title = "done",
                id = "tourThree.done",
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
