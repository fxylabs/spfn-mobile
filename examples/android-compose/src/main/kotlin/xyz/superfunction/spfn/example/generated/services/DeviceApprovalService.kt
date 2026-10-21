// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      18bfbc7bb24e30e2dbb65e195f5448594bab02fa52e257cb590d5a2c534a7633
// bundleSha256:    a7cab3c8e98f3d83384693a45b5d1754640529636c193afd54ef116e98e47c1a
// contractVersion: 0.13.4
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

package xyz.superfunction.spfn.example.generated.services

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
