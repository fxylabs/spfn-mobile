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

package xyz.superfunction.spfn.example.generated.screens

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.superfunction.spfn.example.generated.flows.ModalTourRoute
import xyz.superfunction.spfn.ui.Busy
import xyz.superfunction.spfn.ui.Flow

/**
 * The `modalTwo` screen's state and rules, with no toolkit in sight.
 *
 * Constructor injection, so a test drives this class against a fake service and a
 * real [Flow] with no device, no composition and no server.
 */
class ModalTwoModel(
    private val flow: Flow<ModalTourRoute>
)
{
    private val mutableState: MutableStateFlow<Busy> = MutableStateFlow(Busy.Idle);

    /** What this screen's write is doing. */
    val state: StateFlow<Busy> = mutableState.asStateFlow();

    /** The flow's stack, so the screen can print its depth as a readout. */
    val stack: StateFlow<List<ModalTourRoute>> = flow.stack;

    /** Drops this route. On the flow's first route this does nothing. */
    fun back()
    {
        flow.pop();
    }

    /** Closes the flow. Its stack empties, so nothing of it is presented. */
    fun done()
    {
        flow.close();
    }
}
