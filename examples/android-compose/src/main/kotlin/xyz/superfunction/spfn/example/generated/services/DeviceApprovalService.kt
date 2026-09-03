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
