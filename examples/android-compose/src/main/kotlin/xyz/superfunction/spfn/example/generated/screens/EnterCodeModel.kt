// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      cd02e9ed576538e540a939229a0e476a76708e84286a3ccd09f5f680bf7ab8b5
// bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
// contractVersion: 0.10.0
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

package xyz.superfunction.spfn.example.generated.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoResponse
import xyz.superfunction.spfn.example.generated.flows.ApproveDeviceRoute
import xyz.superfunction.spfn.example.generated.services.DeviceApprovalService
import xyz.superfunction.spfn.ui.Busy
import xyz.superfunction.spfn.ui.Flow

/**
 * The `enterCode` screen's state and rules, with no toolkit in sight.
 *
 * Constructor injection, so a test drives this class against a fake service and a
 * real [Flow] with no device, no composition and no server.
 */
class EnterCodeModel(
    private val deviceApproval: DeviceApprovalService,
    private val flow: Flow<ApproveDeviceRoute>
)
{
    private val mutableState: MutableStateFlow<Busy> = MutableStateFlow(Busy.Idle);

    /** What this screen's write is doing. */
    val state: StateFlow<Busy> = mutableState.asStateFlow();

    /** The flow's stack, so the screen can print its depth as a readout. */
    val stack: StateFlow<List<ApproveDeviceRoute>> = flow.stack;

    /**
     * Which request is the current one.
     *
     * Bumped by everything that starts or abandons a call, and checked again when the
     * answer comes back. An answer whose token is stale — a superseded call, or a call
     * whose flow has since closed — is dropped rather than written into a screen
     * nobody is looking at any more.
     */
    private var generation: Int = 0;

    /** Closes the flow. Its stack empties, so nothing of it is presented. */
    fun cancel()
    {
        generation++;
        flow.close();
    }

    /**
     * Describes the device waiting on a user code, so the approver can recognise it before deciding.
     *
     * Ignored while a write is already in flight, and refused outright when a required
     * input is blank — a refusal the screen states without sending anything.
     */
    suspend fun submit(userCode: String)
    {
        if (mutableState.value is Busy.Busy)
        {
            return;
        }
        if (userCode.isBlank())
        {
            mutableState.value = Busy.Error(ScreenFailure.validation("userCode"));
            return;
        }
        val token = ++generation;
        mutableState.value = Busy.Busy;
        try
        {
            deviceApproval.lookup(SpfnDeviceAuthInfoRequest(userCode = userCode));
        }
        catch (cancelled: CancellationException)
        {
            throw cancelled;
        }
        catch (failure: Exception)
        {
            if (isCurrent(token))
            {
                mutableState.value = Busy.Error(ScreenFailure.envelope(failure));
            }
            return;
        }
        if (!isCurrent(token))
        {
            return;
        }
        mutableState.value = Busy.Idle;
        flow.push(ApproveDeviceRoute.ReviewDevice(userCode = userCode));
    }

    /**
     * Whether an answer bearing [token] still belongs to a screen that is on show.
     *
     * Three questions: is this the current request, is the flow still presented, and
     * is this screen's own route the one on top of the stack. The last is not implied
     * by the others — a route popped while a call was in flight leaves both of them
     * true — and it asks for the top rather than for membership, because a screen
     * buried under a second copy of its own route is not on show either.
     */
    private fun isCurrent(token: Int): Boolean =
        token == generation &&
            flow.isPresented.value &&
            flow.stack.value.lastOrNull() == ApproveDeviceRoute.EnterCode
}
