// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      ea4b08e490fa7f24720859c9b735a9d628949ad1595762d44cb1a833b0b7c164
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
    private val deviceApproval: DeviceApprovalService,
    private val flow: Flow<ApproveDeviceRoute>,
    private val userCode: String
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

    /**
     * Whether one of this screen's writes is in flight.
     *
     * Readable, because the control that started it draws itself busy from this and a
     * control that span off a flag of its own could disagree with the model about
     * whether the press it is refusing was taken. It is a `MutableStateFlow` rather
     * than a `Boolean` for the reason `state` is: a composition reads it.
     */
    private val mutableWriting: MutableStateFlow<Boolean> = MutableStateFlow(false);

    /** Whether one of this screen's writes is in flight. */
    val writing: StateFlow<Boolean> = mutableWriting.asStateFlow();

    /** Reads this screen's source. Called once when the screen appears, however it appeared. */
    suspend fun load()
    {
        val token = ++generation;
        mutableState.value = Loadable.Loading;
        val value = try
        {
            useCase.lookup(userCode);
        }
        catch (cancelled: CancellationException)
        {
            throw cancelled;
        }
        catch (failure: Exception)
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
        if (mutableWriting.value || mutableState.value !is Loadable.Ready)
        {
            return;
        }
        val token = ++generation;
        mutableWriting.value = true;
        try
        {
            deviceApproval.approve(SpfnApproveDeviceAuthRequest(userCode = userCode));
        }
        catch (cancelled: CancellationException)
        {
            throw cancelled;
        }
        catch (failure: Exception)
        {
            mutableWriting.value = false;
            if (isCurrent(token))
            {
                mutableState.value = Loadable.Error(ScreenFailure.envelope(failure));
            }
            return;
        }
        mutableWriting.value = false;
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
        if (mutableWriting.value || mutableState.value !is Loadable.Ready)
        {
            return;
        }
        val token = ++generation;
        mutableWriting.value = true;
        try
        {
            deviceApproval.deny(SpfnDenyDeviceAuthRequest(userCode = userCode));
        }
        catch (cancelled: CancellationException)
        {
            throw cancelled;
        }
        catch (failure: Exception)
        {
            mutableWriting.value = false;
            if (isCurrent(token))
            {
                mutableState.value = Loadable.Error(ScreenFailure.envelope(failure));
            }
            return;
        }
        mutableWriting.value = false;
        if (!isCurrent(token))
        {
            return;
        }
        flow.close();
    }

    /** Reads the source again. Ignored while a write of this screen's is in flight. */
    suspend fun retry()
    {
        if (mutableWriting.value)
        {
            return;
        }
        load();
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
    private fun isCurrent(token: Int): Boolean = token == generation && isOnShow();

    /**
     * Whether this screen's own route is the one the person is standing on.
     *
     * Split out of [isCurrent] because a second caller needs it without a token: the
     * view calls [clearError] when the text changes, and that is not an answer to a
     * request — it has no generation to compare — while it is still something that must
     * not write into a screen nobody is looking at.
     */
    private fun isOnShow(): Boolean =
        flow.isPresented.value &&
            flow.stack.value.lastOrNull() == ApproveDeviceRoute.ReviewDevice(userCode = userCode)
}
