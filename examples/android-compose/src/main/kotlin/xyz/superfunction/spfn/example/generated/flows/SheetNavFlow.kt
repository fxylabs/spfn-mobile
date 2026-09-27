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
