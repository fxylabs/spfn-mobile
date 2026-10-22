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

package xyz.superfunction.spfn.example.generated.screens

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.superfunction.spfn.example.generated.flows.ItemDetailRoute
import xyz.superfunction.spfn.ui.Busy
import xyz.superfunction.spfn.ui.Flow

/**
 * The `itemMore` screen's state and rules, with no toolkit in sight.
 *
 * Constructor injection, so a test drives this class against a fake service and a
 * real [Flow] with no device, no composition and no server.
 */
class ItemMoreModel(
    private val flow: Flow<ItemDetailRoute>
)
{
    private val mutableState: MutableStateFlow<Busy> = MutableStateFlow(Busy.Idle);

    /** What this screen's write is doing. */
    val state: StateFlow<Busy> = mutableState.asStateFlow();

    /** The flow's stack, so the screen can print its depth as a readout. */
    val stack: StateFlow<List<ItemDetailRoute>> = flow.stack;

    /** Closes the flow. Its stack empties, so nothing of it is presented. */
    fun done()
    {
        flow.close();
    }
}
