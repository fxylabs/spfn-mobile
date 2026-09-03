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
import xyz.superfunction.spfn.example.generated.views.FullOneScreen
import xyz.superfunction.spfn.ui.Flow
import xyz.superfunction.spfn.ui.FlowEntry
import xyz.superfunction.spfn.ui.FlowHost
import xyz.superfunction.spfn.ui.FlowRoute
import xyz.superfunction.spfn.ui.SheetDetent

/**
 * Where the `sheetFull` flow can stand.
 *
 * A screen that reads carries what its read needs; a screen that reads nothing
 * carries nothing and is a `data object`, so two entries for it are the same entry.
 */
sealed interface SheetFullRoute : FlowRoute
{
    data object FullOne : SheetFullRoute
}

/** How this flow is presented, and therefore what a back on its last route means. */
val SheetFullEntry: FlowEntry = FlowEntry.Sheet(SheetDetent.Full);

/** A closed-over factory, so the flow opens on the screen the spec named as its start. */
@Suppress("FunctionName")
fun SheetFullFlow(): Flow<SheetFullRoute> =
    Flow(listOf(SheetFullRoute.FullOne))

/** Renders the `sheetFull` flow: one route, one model, one view. */
@Composable
fun SheetFullFlowHost(container: AppContainer)
{
    FlowHost(container.sheetFullFlow, SheetFullEntry) { route ->
        when (route)
        {
            is SheetFullRoute.FullOne ->
            {
                val model = remember(route) { container.fullOneModel() };
                FullOneScreen(model);
            }
        }
    }
}
