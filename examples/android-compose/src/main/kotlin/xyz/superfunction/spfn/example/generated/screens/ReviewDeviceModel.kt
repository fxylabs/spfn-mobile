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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.superfunction.spfn.client.SpfnClientError
import xyz.superfunction.spfn.generated.SpfnApproveDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDenyDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoResponse
import xyz.superfunction.spfn.example.generated.flows.ApproveDeviceRoute
import xyz.superfunction.spfn.example.generated.services.DeviceApprovalService
import xyz.superfunction.spfn.ui.Loadable
import xyz.superfunction.spfn.ui.Flow

/**
 * The `reviewDevice` screen's state and rules, with no toolkit in sight.
 *
 * Constructor injection, so a test drives this class against a fake service and a
 * real [Flow] with no device, no composition and no server.
 */
class ReviewDeviceModel(
    private val useCase: ReviewDeviceUseCase,
    private val service: DeviceApprovalService,
    private val flow: Flow<ApproveDeviceRoute>,
    private val userCode: String,
)
{
    private val mutableState: MutableStateFlow<Loadable<SpfnDeviceAuthInfoResponse>> =
        MutableStateFlow(Loadable.Loading);

    /** What this screen's read has produced so far. */
    val state: StateFlow<Loadable<SpfnDeviceAuthInfoResponse>> = mutableState.asStateFlow();

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

    /** Whether one of this screen's writes is in flight. */
    private var writing: Boolean = false;

    /** Reads this screen's source. Called once when the screen appears, however it appeared. */
    suspend fun load()
    {
        val token = ++generation;
        mutableState.value = Loadable.Loading;
        val value = try
        {
            useCase.lookup(userCode);
        }
        catch (failure: SpfnClientError)
        {
            if (isCurrent(token))
            {
                mutableState.value = Loadable.Error(ScreenFailure.envelope(failure));
            }
            return;
        };
        if (isCurrent(token))
        {
            mutableState.value = Loadable.Ready(value);
        }
    }

    /**
     * Lets the waiting device in, answering with the device it just let in.
     *
     * Ignored unless this screen is showing a value and no write of its own is running.
     */
    suspend fun approve()
    {
        if (writing || mutableState.value !is Loadable.Ready)
        {
            return;
        }
        val token = ++generation;
        writing = true;
        try
        {
            service.approve(SpfnApproveDeviceAuthRequest(userCode = userCode));
        }
        catch (failure: SpfnClientError)
        {
            writing = false;
            if (isCurrent(token))
            {
                mutableState.value = Loadable.Error(ScreenFailure.envelope(failure));
            }
            return;
        }
        writing = false;
        if (!isCurrent(token))
        {
            return;
        }
        flow.close();
    }

    /** Drops this route. On the flow's first route this does nothing. */
    fun back()
    {
        generation++;
        flow.pop();
    }

    /**
     * Refuses the waiting device. Answers 204 with no body, so it names no response type.
     *
     * Ignored unless this screen is showing a value and no write of its own is running.
     */
    suspend fun deny()
    {
        if (writing || mutableState.value !is Loadable.Ready)
        {
            return;
        }
        val token = ++generation;
        writing = true;
        try
        {
            service.deny(SpfnDenyDeviceAuthRequest(userCode = userCode));
        }
        catch (failure: SpfnClientError)
        {
            writing = false;
            if (isCurrent(token))
            {
                mutableState.value = Loadable.Error(ScreenFailure.envelope(failure));
            }
            return;
        }
        writing = false;
        if (!isCurrent(token))
        {
            return;
        }
        flow.close();
    }

    /** Reads the source again. Ignored while a write of this screen's is in flight. */
    suspend fun retry()
    {
        if (writing)
        {
            return;
        }
        load();
    }

    /** Whether an answer bearing [token] still belongs to a screen that is on show. */
    private fun isCurrent(token: Int): Boolean = token == generation && flow.isPresented.value
}
