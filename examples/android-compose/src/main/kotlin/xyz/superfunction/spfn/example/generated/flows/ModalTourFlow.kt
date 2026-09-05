// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      5babeed3f41fa7c8eb049bc79d7719ff9f0d79ede06c4073015643be04668f7a
// bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
// contractVersion: 0.10.0
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

package xyz.superfunction.spfn.example.generated.flows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import xyz.superfunction.spfn.example.generated.AppContainer
import xyz.superfunction.spfn.example.generated.views.ModalOneScreen
import xyz.superfunction.spfn.example.generated.views.ModalTwoScreen
import xyz.superfunction.spfn.ui.Flow
import xyz.superfunction.spfn.ui.FlowEntry
import xyz.superfunction.spfn.ui.FlowHost
import xyz.superfunction.spfn.ui.FlowRoute

/**
 * Where the `modalTour` flow can stand.
 *
 * A screen that reads carries what its read needs; a screen that reads nothing
 * carries nothing and is a `data object`, so two entries for it are the same entry.
 */
sealed interface ModalTourRoute : FlowRoute
{
    data object ModalOne : ModalTourRoute

    data object ModalTwo : ModalTourRoute
}

/** How this flow is presented, and therefore what a back on its last route means. */
val ModalTourEntry: FlowEntry = FlowEntry.Modal;

/** A closed-over factory, so the flow opens on the screen the spec named as its start. */
@Suppress("FunctionName")
fun ModalTourFlow(): Flow<ModalTourRoute> =
    Flow(listOf(ModalTourRoute.ModalOne))

/** Renders the `modalTour` flow: one route, one model, one view. */
@Composable
fun ModalTourFlowHost(container: AppContainer)
{
    FlowHost(container.modalTourFlow, ModalTourEntry) { route ->
        when (route)
        {
            is ModalTourRoute.ModalOne ->
            {
                val model = remember(route) { container.modalOneModel() };
                ModalOneScreen(model);
            }
            is ModalTourRoute.ModalTwo ->
            {
                val model = remember(route) { container.modalTwoModel() };
                ModalTwoScreen(model);
            }
        }
    }
}
