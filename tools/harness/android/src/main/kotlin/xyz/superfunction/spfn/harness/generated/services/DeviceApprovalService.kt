// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      69fbbe100243bdb7d7c98ea11feae130988cff28c517c37bc9bb6942b05023e6
// bundleSha256:    bb0373c2c3e95bcc3923c84a160945e17ca57d5c13fd8122f341f1df181bc658
// contractVersion: 0.13.0
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateHarnessUi
// Verified by:     ./gradlew :ui-codegen:spfnHarnessUiVerify

package xyz.superfunction.spfn.harness.generated.services

import xyz.superfunction.spfn.client.SpfnClient
import xyz.superfunction.spfn.generated.SpfnGeneratedCalls
import xyz.superfunction.spfn.generated.SpfnApproveDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDenyDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoResponse

/** The `deviceApproval` service: one method per operation the spec names. */
interface DeviceApprovalService
{
    /** Lets the waiting device in, answering with the device it just let in. */
    suspend fun approve(request: SpfnApproveDeviceAuthRequest): SpfnDeviceAuthInfoResponse

    /** Refuses the waiting device. Answers 204 with no body, so it names no response type. */
    suspend fun deny(request: SpfnDenyDeviceAuthRequest)

    /** Describes the device waiting on a user code, so the approver can recognise it before deciding. */
    suspend fun lookup(request: SpfnDeviceAuthInfoRequest): SpfnDeviceAuthInfoResponse
}

/** [DeviceApprovalService] against a real server, through one client. */
class DefaultDeviceApprovalService(
    private val client: SpfnClient
) : DeviceApprovalService
{
    override suspend fun approve(request: SpfnApproveDeviceAuthRequest): SpfnDeviceAuthInfoResponse =
        client.execute(SpfnGeneratedCalls.authDeviceApprove, request)

    override suspend fun deny(request: SpfnDenyDeviceAuthRequest)
    {
        client.execute(SpfnGeneratedCalls.authDeviceDeny, request);
    }

    override suspend fun lookup(request: SpfnDeviceAuthInfoRequest): SpfnDeviceAuthInfoResponse =
        client.execute(SpfnGeneratedCalls.authDeviceInfo, request)
}
