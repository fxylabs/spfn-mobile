// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      0fbec833616cfb9717bb9d80a057d4069bae1f04d1c47d44f98d9becf08dce41
// bundleSha256:    8cce6d896e200a18e1312f23ed63ff4fc36b4ff6ea484f576c3de824be3b589e
// contractVersion: 0.13.2
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

package xyz.superfunction.spfn.example.generated.flows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import xyz.superfunction.spfn.example.generated.AppContainer
import xyz.superfunction.spfn.example.generated.views.EditNameScreen
import xyz.superfunction.spfn.ui.Flow
import xyz.superfunction.spfn.ui.FlowEntry
import xyz.superfunction.spfn.ui.FlowHost
import xyz.superfunction.spfn.ui.FlowRoute

/**
 * Where the `editProfile` flow can stand.
 *
 * A screen that reads carries what its read needs; a screen that reads nothing
 * carries nothing and is a `data object`, so two entries for it are the same entry.
 */
sealed interface EditProfileRoute : FlowRoute
{
    data object EditName : EditProfileRoute
}

/** How this flow is presented, and therefore what a back on its last route means. */
val EditProfileEntry: FlowEntry = FlowEntry.Modal;

/** A closed-over factory, so the flow opens on the screen the spec named as its start. */
@Suppress("FunctionName")
fun EditProfileFlow(): Flow<EditProfileRoute> =
    Flow(listOf(EditProfileRoute.EditName))

/** Renders the `editProfile` flow: one route, one model, one view. */
@Composable
fun EditProfileFlowHost(container: AppContainer)
{
    FlowHost(container.editProfileFlow, EditProfileEntry) { route ->
        when (route)
        {
            is EditProfileRoute.EditName ->
            {
                val model = remember(route) { container.editNameModel() };
                EditNameScreen(model);
            }
        }
    }
}
