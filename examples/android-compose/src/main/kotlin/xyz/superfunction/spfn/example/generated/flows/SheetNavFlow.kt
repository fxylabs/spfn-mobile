// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      88e5159b5528860daa36d6ebae1f6a6940c8152eb8373bf4cb3656be70599153
// bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
// contractVersion: 0.10.0
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

package xyz.superfunction.spfn.example.generated.flows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import xyz.superfunction.spfn.example.generated.AppContainer
import xyz.superfunction.spfn.example.generated.views.NavOneScreen
import xyz.superfunction.spfn.example.generated.views.NavTwoScreen
import xyz.superfunction.spfn.ui.Flow
import xyz.superfunction.spfn.ui.FlowEntry
import xyz.superfunction.spfn.ui.FlowHost
import xyz.superfunction.spfn.ui.FlowRoute
import xyz.superfunction.spfn.ui.SheetDetent

/**
 * Where the `sheetNav` flow can stand.
 *
 * A screen that reads carries what its read needs; a screen that reads nothing
 * carries nothing and is a `data object`, so two entries for it are the same entry.
 */
sealed interface SheetNavRoute : FlowRoute
{
    data object NavOne : SheetNavRoute

    data object NavTwo : SheetNavRoute
}

/** How this flow is presented, and therefore what a back on its last route means. */
val SheetNavEntry: FlowEntry = FlowEntry.Sheet(SheetDetent.Half);

/** A closed-over factory, so the flow opens on the screen the spec named as its start. */
@Suppress("FunctionName")
fun SheetNavFlow(): Flow<SheetNavRoute> =
    Flow(listOf(SheetNavRoute.NavOne))

/** Renders the `sheetNav` flow: one route, one model, one view. */
@Composable
fun SheetNavFlowHost(container: AppContainer)
{
    FlowHost(container.sheetNavFlow, SheetNavEntry) { route ->
        when (route)
        {
            is SheetNavRoute.NavOne ->
            {
                val model = remember(route) { container.navOneModel() };
                NavOneScreen(model);
            }
            is SheetNavRoute.NavTwo ->
            {
                val model = remember(route) { container.navTwoModel() };
                NavTwoScreen(model);
            }
        }
    }
}
