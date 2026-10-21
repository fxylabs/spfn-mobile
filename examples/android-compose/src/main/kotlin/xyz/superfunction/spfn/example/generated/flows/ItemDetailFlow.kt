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

package xyz.superfunction.spfn.example.generated.flows

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import xyz.superfunction.spfn.example.generated.AppContainer
import xyz.superfunction.spfn.example.generated.views.ItemScreen
import xyz.superfunction.spfn.example.generated.views.ItemMoreScreen
import xyz.superfunction.spfn.ui.Flow
import xyz.superfunction.spfn.ui.FlowEntry
import xyz.superfunction.spfn.ui.FlowHost
import xyz.superfunction.spfn.ui.FlowRoute

/**
 * Where the `itemDetail` flow can stand.
 *
 * A screen that reads carries what its read needs; a screen that reads nothing
 * carries nothing and is a `data object`, so two entries for it are the same entry.
 */
sealed interface ItemDetailRoute : FlowRoute
{
    data object Item : ItemDetailRoute

    data object ItemMore : ItemDetailRoute
}

/** How this flow is presented, and therefore what a back on its last route means. */
val ItemDetailEntry: FlowEntry = FlowEntry.Push;

/** A closed-over factory, so the flow opens on the screen the spec named as its start. */
@Suppress("FunctionName")
fun ItemDetailFlow(): Flow<ItemDetailRoute> =
    Flow(listOf(ItemDetailRoute.Item))

/** Renders the `itemDetail` flow: one route, one model, one view. */
@Composable
fun ItemDetailFlowHost(container: AppContainer)
{
    FlowHost(container.itemDetailFlow, ItemDetailEntry) { route ->
        when (route)
        {
            is ItemDetailRoute.Item ->
            {
                val model = remember(route) { container.itemModel() };
                ItemScreen(model);
            }
            is ItemDetailRoute.ItemMore ->
            {
                val model = remember(route) { container.itemMoreModel() };
                ItemMoreScreen(model);
            }
        }
    }
}
