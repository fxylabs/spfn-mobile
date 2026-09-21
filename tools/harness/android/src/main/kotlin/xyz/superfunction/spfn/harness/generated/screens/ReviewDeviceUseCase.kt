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

package xyz.superfunction.spfn.harness.generated.screens

import xyz.superfunction.spfn.generated.SpfnApproveDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDenyDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoResponse
import xyz.superfunction.spfn.harness.generated.services.DeviceApprovalService

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
