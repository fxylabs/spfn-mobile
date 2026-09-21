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

package xyz.superfunction.spfn.example.generated.screens

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.superfunction.spfn.example.generated.flows.SheetFullRoute
import xyz.superfunction.spfn.ui.Busy
import xyz.superfunction.spfn.ui.Flow

/**
 * The `fullOne` screen's state and rules, with no toolkit in sight.
 *
 * Constructor injection, so a test drives this class against a fake service and a
 * real [Flow] with no device, no composition and no server.
 */
class FullOneModel(
    private val flow: Flow<SheetFullRoute>
)
{
    private val mutableState: MutableStateFlow<Busy> = MutableStateFlow(Busy.Idle);

    /** What this screen's write is doing. */
    val state: StateFlow<Busy> = mutableState.asStateFlow();

    /** The flow's stack, so the screen can print its depth as a readout. */
    val stack: StateFlow<List<SheetFullRoute>> = flow.stack;

    /** Closes the flow. Its stack empties, so nothing of it is presented. */
    fun done()
    {
        flow.close();
    }
}
