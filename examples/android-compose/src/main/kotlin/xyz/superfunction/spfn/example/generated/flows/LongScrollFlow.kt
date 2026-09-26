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
import xyz.superfunction.spfn.example.generated.views.LongScreen
import xyz.superfunction.spfn.ui.Flow
import xyz.superfunction.spfn.ui.FlowEntry
import xyz.superfunction.spfn.ui.FlowHost
import xyz.superfunction.spfn.ui.FlowRoute

/**
 * Where the `longScroll` flow can stand.
 *
 * A screen that reads carries what its read needs; a screen that reads nothing
 * carries nothing and is a `data object`, so two entries for it are the same entry.
 */
sealed interface LongScrollRoute : FlowRoute
{
    data object Long : LongScrollRoute
}

/** How this flow is presented, and therefore what a back on its last route means. */
val LongScrollEntry: FlowEntry = FlowEntry.Push;

/** A closed-over factory, so the flow opens on the screen the spec named as its start. */
@Suppress("FunctionName")
fun LongScrollFlow(): Flow<LongScrollRoute> =
    Flow(listOf(LongScrollRoute.Long))

/** Renders the `longScroll` flow: one route, one model, one view. */
@Composable
fun LongScrollFlowHost(container: AppContainer)
{
    FlowHost(container.longScrollFlow, LongScrollEntry) { route ->
        when (route)
        {
            is LongScrollRoute.Long ->
            {
                val model = remember(route) { container.longModel() };
                LongScreen(model);
            }
        }
    }
}
