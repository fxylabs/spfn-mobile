// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      69fbbe100243bdb7d7c98ea11feae130988cff28c517c37bc9bb6942b05023e6
// bundleSha256:    bb0373c2c3e95bcc3923c84a160945e17ca57d5c13fd8122f341f1df181bc658
// contractVersion: 0.13.0
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
