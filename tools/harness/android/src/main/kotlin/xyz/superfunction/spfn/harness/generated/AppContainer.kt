// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      91e7628f407dafb74f4b501b4effac2b2277dc629c2a7ad4de42749f410ca018
// bundleSha256:    8cce6d896e200a18e1312f23ed63ff4fc36b4ff6ea484f576c3de824be3b589e
// contractVersion: 0.13.2
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateHarnessUi
// Verified by:     ./gradlew :ui-codegen:spfnHarnessUiVerify

package xyz.superfunction.spfn.harness.generated

import xyz.superfunction.spfn.client.SpfnClient
import xyz.superfunction.spfn.client.SpfnKeyProvider
import xyz.superfunction.spfn.client.SpfnSession
import xyz.superfunction.spfn.client.SpfnTransport
import xyz.superfunction.spfn.harness.generated.flows.ApproveDeviceFlow
import xyz.superfunction.spfn.harness.generated.flows.ApproveDeviceRoute
import xyz.superfunction.spfn.harness.generated.screens.EnterCodeModel
import xyz.superfunction.spfn.harness.generated.screens.ReviewDeviceModel
import xyz.superfunction.spfn.harness.generated.screens.DefaultReviewDeviceUseCase
import xyz.superfunction.spfn.harness.generated.services.DefaultDeviceApprovalService
import xyz.superfunction.spfn.harness.generated.services.DeviceApprovalService
import xyz.superfunction.spfn.ui.Flow

/** The app's one graph: services in, flows and screen models out. */
class AppContainer(
    private val deviceApproval: DeviceApprovalService
)
{
    /** The `approveDevice` flow, open on its start screen. */
    val approveDeviceFlow: Flow<ApproveDeviceRoute> = ApproveDeviceFlow();

    /** A fresh model for one appearance of `enterCode`. */
    fun enterCodeModel(): EnterCodeModel =
        EnterCodeModel(deviceApproval, approveDeviceFlow);

    /** A fresh model for one appearance of `reviewDevice`. */
    fun reviewDeviceModel(userCode: String): ReviewDeviceModel =
        ReviewDeviceModel(DefaultReviewDeviceUseCase(deviceApproval), deviceApproval, approveDeviceFlow, userCode);

    companion object
    {
        /** The app against a real server: one transport, one session, one client. */
        fun live(
            transport: SpfnTransport,
            keyProvider: SpfnKeyProvider,
            baseUrl: String
        ): AppContainer
        {
            val session = SpfnSession(
                transport = transport,
                keyProvider = keyProvider,
                baseUrl = baseUrl
            );
            val client = SpfnClient(transport = transport, session = session);
            return AppContainer(DefaultDeviceApprovalService(client));
        }
    }
}
