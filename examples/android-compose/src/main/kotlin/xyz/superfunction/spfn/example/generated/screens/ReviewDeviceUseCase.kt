// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      0fbec833616cfb9717bb9d80a057d4069bae1f04d1c47d44f98d9becf08dce41
// bundleSha256:    8cce6d896e200a18e1312f23ed63ff4fc36b4ff6ea484f576c3de824be3b589e
// contractVersion: 0.13.2
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
