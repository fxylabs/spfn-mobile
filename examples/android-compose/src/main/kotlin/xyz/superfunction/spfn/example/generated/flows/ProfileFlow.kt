// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      83ef5335dcd7c486e823ce1bc60ee788e75fb2c3a16a9bcd084222a5a68fb888
// bundleSha256:    7879036f33c46ced4c51035521fd25d3934bbb38c21aa9b68779a1c6d1578846
// contractVersion: 0.13.5
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

package xyz.superfunction.spfn.example.generated.flows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import xyz.superfunction.spfn.example.generated.AppContainer
import xyz.superfunction.spfn.example.generated.views.ProfileDetailsScreen
import xyz.superfunction.spfn.example.generated.views.ProfileSummaryScreen
import xyz.superfunction.spfn.ui.Flow
import xyz.superfunction.spfn.ui.FlowEntry
import xyz.superfunction.spfn.ui.FlowHost
import xyz.superfunction.spfn.ui.FlowRoute

/**
 * Where the `profile` flow can stand.
 *
 * A screen that reads carries what its read needs; a screen that reads nothing
 * carries nothing and is a `data object`, so two entries for it are the same entry.
 */
sealed interface ProfileRoute : FlowRoute
{
    data object ProfileDetails : ProfileRoute

    data object ProfileSummary : ProfileRoute
}

/** How this flow is presented, and therefore what a back on its last route means. */
val ProfileEntry: FlowEntry = FlowEntry.Push;

/** A closed-over factory, so the flow opens on the screen the spec named as its start. */
@Suppress("FunctionName")
fun ProfileFlow(): Flow<ProfileRoute> =
    Flow(listOf(ProfileRoute.ProfileSummary))

/** Renders the `profile` flow: one route, one model, one view. */
@Composable
fun ProfileFlowHost(container: AppContainer)
{
    FlowHost(container.profileFlow, ProfileEntry) { route ->
        when (route)
        {
            is ProfileRoute.ProfileDetails ->
            {
                val model = remember(route) { container.profileDetailsModel() };
                ProfileDetailsScreen(model);
            }
            is ProfileRoute.ProfileSummary ->
            {
                val model = remember(route) { container.profileSummaryModel() };
                ProfileSummaryScreen(model);
            }
        }
    }
}
