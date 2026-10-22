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
