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
import xyz.superfunction.spfn.example.generated.views.AccountNoteScreen
import xyz.superfunction.spfn.ui.Flow
import xyz.superfunction.spfn.ui.FlowEntry
import xyz.superfunction.spfn.ui.FlowHost
import xyz.superfunction.spfn.ui.FlowRoute
import xyz.superfunction.spfn.ui.SheetDetent

/**
 * Where the `accountSheet` flow can stand.
 *
 * A screen that reads carries what its read needs; a screen that reads nothing
 * carries nothing and is a `data object`, so two entries for it are the same entry.
 */
sealed interface AccountSheetRoute : FlowRoute
{
    data object AccountNote : AccountSheetRoute
}

/** How this flow is presented, and therefore what a back on its last route means. */
val AccountSheetEntry: FlowEntry = FlowEntry.Sheet(SheetDetent.Fit);

/** A closed-over factory, so the flow opens on the screen the spec named as its start. */
@Suppress("FunctionName")
fun AccountSheetFlow(): Flow<AccountSheetRoute> =
    Flow(listOf(AccountSheetRoute.AccountNote))

/** Renders the `accountSheet` flow: one route, one model, one view. */
@Composable
fun AccountSheetFlowHost(container: AppContainer)
{
    FlowHost(container.accountSheetFlow, AccountSheetEntry) { route ->
        when (route)
        {
            is AccountSheetRoute.AccountNote ->
            {
                val model = remember(route) { container.accountNoteModel() };
                AccountNoteScreen(model);
            }
        }
    }
}
