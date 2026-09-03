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

package xyz.superfunction.spfn.example.generated

import xyz.superfunction.spfn.client.SpfnClient
import xyz.superfunction.spfn.client.SpfnKeyProvider
import xyz.superfunction.spfn.client.SpfnSession
import xyz.superfunction.spfn.client.SpfnTransport
import xyz.superfunction.spfn.example.generated.flows.ApproveDeviceFlow
import xyz.superfunction.spfn.example.generated.flows.ApproveDeviceRoute
import xyz.superfunction.spfn.example.generated.screens.EnterCodeModel
import xyz.superfunction.spfn.example.generated.screens.ReviewDeviceModel
import xyz.superfunction.spfn.example.generated.screens.DefaultReviewDeviceUseCase
import xyz.superfunction.spfn.example.generated.services.DefaultDeviceApprovalService
import xyz.superfunction.spfn.example.generated.services.DeviceApprovalService
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
