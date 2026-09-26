// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      91e7628f407dafb74f4b501b4effac2b2277dc629c2a7ad4de42749f410ca018
// bundleSha256:    8cce6d896e200a18e1312f23ed63ff4fc36b4ff6ea484f576c3de824be3b589e
// contractVersion: 0.13.2
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

package xyz.superfunction.spfn.example.generated.flows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import xyz.superfunction.spfn.example.generated.AppContainer
import xyz.superfunction.spfn.example.generated.views.EnterCodeScreen
import xyz.superfunction.spfn.example.generated.views.ReviewDeviceScreen
import xyz.superfunction.spfn.ui.Flow
import xyz.superfunction.spfn.ui.FlowEntry
import xyz.superfunction.spfn.ui.FlowHost
import xyz.superfunction.spfn.ui.FlowRoute

/**
 * Where the `approveDevice` flow can stand.
 *
 * A screen that reads carries what its read needs; a screen that reads nothing
 * carries nothing and is a `data object`, so two entries for it are the same entry.
 */
sealed interface ApproveDeviceRoute : FlowRoute
{
    data object EnterCode : ApproveDeviceRoute

    data class ReviewDevice(val userCode: String) : ApproveDeviceRoute
}

/** How this flow is presented, and therefore what a back on its last route means. */
val ApproveDeviceEntry: FlowEntry = FlowEntry.Modal;

/** A closed-over factory, so the flow opens on the screen the spec named as its start. */
@Suppress("FunctionName")
fun ApproveDeviceFlow(): Flow<ApproveDeviceRoute> =
    Flow(listOf(ApproveDeviceRoute.EnterCode))

/** Renders the `approveDevice` flow: one route, one model, one view. */
@Composable
fun ApproveDeviceFlowHost(container: AppContainer)
{
    FlowHost(container.approveDeviceFlow, ApproveDeviceEntry) { route ->
        when (route)
        {
            is ApproveDeviceRoute.EnterCode ->
            {
                val model = remember(route) { container.enterCodeModel() };
                EnterCodeScreen(model);
            }
            is ApproveDeviceRoute.ReviewDevice ->
            {
                val model = remember(route) { container.reviewDeviceModel(route.userCode) };
                ReviewDeviceScreen(model);
            }
        }
    }
}
