// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      5babeed3f41fa7c8eb049bc79d7719ff9f0d79ede06c4073015643be04668f7a
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
import xyz.superfunction.spfn.example.generated.flows.KeyboardFormFlow
import xyz.superfunction.spfn.example.generated.flows.LongScrollFlow
import xyz.superfunction.spfn.example.generated.flows.ModalTourFlow
import xyz.superfunction.spfn.example.generated.flows.PushTourFlow
import xyz.superfunction.spfn.example.generated.flows.SheetFitFlow
import xyz.superfunction.spfn.example.generated.flows.SheetFullFlow
import xyz.superfunction.spfn.example.generated.flows.SheetHalfFlow
import xyz.superfunction.spfn.example.generated.flows.SheetNavFlow
import xyz.superfunction.spfn.example.generated.flows.ApproveDeviceRoute
import xyz.superfunction.spfn.example.generated.flows.KeyboardFormRoute
import xyz.superfunction.spfn.example.generated.flows.LongScrollRoute
import xyz.superfunction.spfn.example.generated.flows.ModalTourRoute
import xyz.superfunction.spfn.example.generated.flows.PushTourRoute
import xyz.superfunction.spfn.example.generated.flows.SheetFitRoute
import xyz.superfunction.spfn.example.generated.flows.SheetFullRoute
import xyz.superfunction.spfn.example.generated.flows.SheetHalfRoute
import xyz.superfunction.spfn.example.generated.flows.SheetNavRoute
import xyz.superfunction.spfn.example.generated.screens.EnterCodeModel
import xyz.superfunction.spfn.example.generated.screens.FitOneModel
import xyz.superfunction.spfn.example.generated.screens.FormModel
import xyz.superfunction.spfn.example.generated.screens.FullOneModel
import xyz.superfunction.spfn.example.generated.screens.HalfOneModel
import xyz.superfunction.spfn.example.generated.screens.LongModel
import xyz.superfunction.spfn.example.generated.screens.ModalOneModel
import xyz.superfunction.spfn.example.generated.screens.ModalTwoModel
import xyz.superfunction.spfn.example.generated.screens.NavOneModel
import xyz.superfunction.spfn.example.generated.screens.NavTwoModel
import xyz.superfunction.spfn.example.generated.screens.ReviewDeviceModel
import xyz.superfunction.spfn.example.generated.screens.DefaultReviewDeviceUseCase
import xyz.superfunction.spfn.example.generated.screens.TourOneModel
import xyz.superfunction.spfn.example.generated.screens.TourThreeModel
import xyz.superfunction.spfn.example.generated.screens.TourTwoModel
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

    /** The `keyboardForm` flow, open on its start screen. */
    val keyboardFormFlow: Flow<KeyboardFormRoute> = KeyboardFormFlow();

    /** The `longScroll` flow, open on its start screen. */
    val longScrollFlow: Flow<LongScrollRoute> = LongScrollFlow();

    /** The `modalTour` flow, open on its start screen. */
    val modalTourFlow: Flow<ModalTourRoute> = ModalTourFlow();

    /** The `pushTour` flow, open on its start screen. */
    val pushTourFlow: Flow<PushTourRoute> = PushTourFlow();

    /** The `sheetFit` flow, open on its start screen. */
    val sheetFitFlow: Flow<SheetFitRoute> = SheetFitFlow();

    /** The `sheetFull` flow, open on its start screen. */
    val sheetFullFlow: Flow<SheetFullRoute> = SheetFullFlow();

    /** The `sheetHalf` flow, open on its start screen. */
    val sheetHalfFlow: Flow<SheetHalfRoute> = SheetHalfFlow();

    /** The `sheetNav` flow, open on its start screen. */
    val sheetNavFlow: Flow<SheetNavRoute> = SheetNavFlow();

    /** A fresh model for one appearance of `enterCode`. */
    fun enterCodeModel(): EnterCodeModel =
        EnterCodeModel(deviceApproval, approveDeviceFlow);

    /** A fresh model for one appearance of `fitOne`. */
    fun fitOneModel(): FitOneModel =
        FitOneModel(sheetFitFlow);

    /** A fresh model for one appearance of `form`. */
    fun formModel(): FormModel =
        FormModel(deviceApproval, keyboardFormFlow);

    /** A fresh model for one appearance of `fullOne`. */
    fun fullOneModel(): FullOneModel =
        FullOneModel(sheetFullFlow);

    /** A fresh model for one appearance of `halfOne`. */
    fun halfOneModel(): HalfOneModel =
        HalfOneModel(sheetHalfFlow);

    /** A fresh model for one appearance of `long`. */
    fun longModel(): LongModel =
        LongModel(longScrollFlow);

    /** A fresh model for one appearance of `modalOne`. */
    fun modalOneModel(): ModalOneModel =
        ModalOneModel(modalTourFlow);

    /** A fresh model for one appearance of `modalTwo`. */
    fun modalTwoModel(): ModalTwoModel =
        ModalTwoModel(modalTourFlow);

    /** A fresh model for one appearance of `navOne`. */
    fun navOneModel(): NavOneModel =
        NavOneModel(sheetNavFlow);

    /** A fresh model for one appearance of `navTwo`. */
    fun navTwoModel(): NavTwoModel =
        NavTwoModel(sheetNavFlow);

    /** A fresh model for one appearance of `reviewDevice`. */
    fun reviewDeviceModel(userCode: String): ReviewDeviceModel =
        ReviewDeviceModel(DefaultReviewDeviceUseCase(deviceApproval), deviceApproval, approveDeviceFlow, userCode);

    /** A fresh model for one appearance of `tourOne`. */
    fun tourOneModel(): TourOneModel =
        TourOneModel(pushTourFlow);

    /** A fresh model for one appearance of `tourThree`. */
    fun tourThreeModel(): TourThreeModel =
        TourThreeModel(pushTourFlow);

    /** A fresh model for one appearance of `tourTwo`. */
    fun tourTwoModel(): TourTwoModel =
        TourTwoModel(pushTourFlow);

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
