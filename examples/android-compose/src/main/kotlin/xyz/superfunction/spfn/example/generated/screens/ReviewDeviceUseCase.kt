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

import xyz.superfunction.spfn.generated.SpfnApproveDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDenyDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoResponse
import xyz.superfunction.spfn.example.generated.services.DeviceApprovalService

/** What `reviewDevice` reads, named as the app's own act rather than as an operation. */
interface ReviewDeviceUseCase
{
    suspend fun lookup(userCode: String): SpfnDeviceAuthInfoResponse
}

/** The pass-through. It adds a seam, not a rule. */
class DefaultReviewDeviceUseCase(
    private val service: DeviceApprovalService
) : ReviewDeviceUseCase
{
    override suspend fun lookup(userCode: String): SpfnDeviceAuthInfoResponse =
        service.lookup(SpfnDeviceAuthInfoRequest(userCode = userCode))
}
