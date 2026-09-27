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
//
// This directory is one Apple-only app target. Nothing here is compiled on Linux —
// it sits outside Sources/, so the package build never sees it — which is why the
// files that import SwiftUI are guarded and the ones that do not are plain.

import SPFNClient
import SPFNUI

/// The app's one graph: services in, flows and screen models out.
///
/// Two ways in and no third. ``live(transport:keyProvider:baseURL:)`` builds the client
/// the SDK's own way — one transport, one session over it, one client over that — and
/// takes the key provider and the base URL from the app, which are the two things a
/// generator cannot know. The memberwise initialiser takes a service directly, which is
/// the door a launch fixture comes through; there is no fixture code here at all.
@MainActor
public final class AppContainer
{
    private let deviceApproval: any DeviceApprovalService

    /// The `accountSheet` flow, open on its start screen.
    public let accountSheetFlow: Flow<AccountSheetRoute>

    /// The `approveDevice` flow, open on its start screen.
    public let approveDeviceFlow: Flow<ApproveDeviceRoute>

    /// The `editProfile` flow, open on its start screen.
    public let editProfileFlow: Flow<EditProfileRoute>

    /// The `itemDetail` flow, open on its start screen.
    public let itemDetailFlow: Flow<ItemDetailRoute>

    /// The `keyboardForm` flow, open on its start screen.
    public let keyboardFormFlow: Flow<KeyboardFormRoute>

    /// The `longScroll` flow, open on its start screen.
    public let longScrollFlow: Flow<LongScrollRoute>

    /// The `modalTour` flow, open on its start screen.
    public let modalTourFlow: Flow<ModalTourRoute>

    /// The `profile` flow, open on its start screen.
    public let profileFlow: Flow<ProfileRoute>

    /// The `pushTour` flow, open on its start screen.
    public let pushTourFlow: Flow<PushTourRoute>

    /// The `sheetFit` flow, open on its start screen.
    public let sheetFitFlow: Flow<SheetFitRoute>

    /// The `sheetFull` flow, open on its start screen.
    public let sheetFullFlow: Flow<SheetFullRoute>

    /// The `sheetHalf` flow, open on its start screen.
    public let sheetHalfFlow: Flow<SheetHalfRoute>

    /// The `sheetNav` flow, open on its start screen.
    public let sheetNavFlow: Flow<SheetNavRoute>

    /// The tab bar's state: which of the spec's tabs is selected, on the first.
    public let tabs: TabState

    public init(
        deviceApproval: any DeviceApprovalService
    )
    {
        self.deviceApproval = deviceApproval
        self.accountSheetFlow = AccountSheetFlow()
        self.approveDeviceFlow = ApproveDeviceFlow()
        self.editProfileFlow = EditProfileFlow()
        self.itemDetailFlow = ItemDetailFlow()
        self.keyboardFormFlow = KeyboardFormFlow()
        self.longScrollFlow = LongScrollFlow()
        self.modalTourFlow = ModalTourFlow()
        self.profileFlow = ProfileFlow()
        self.pushTourFlow = PushTourFlow()
        self.sheetFitFlow = SheetFitFlow()
        self.sheetFullFlow = SheetFullFlow()
        self.sheetHalfFlow = SheetHalfFlow()
        self.sheetNavFlow = SheetNavFlow()
        // The spec reader refused an empty bar and a repeated id, which are the two
        // things this initialiser refuses, so it cannot throw here.
        self.tabs = try! TabState(tabs: ["home", "account"])
    }

    /// A fresh model for one appearance of `accountNote`.
    public func accountNoteModel() -> AccountNoteModel
    {
        AccountNoteModel(flow: accountSheetFlow)
    }

    /// A fresh model for one appearance of `editName`.
    public func editNameModel() -> EditNameModel
    {
        EditNameModel(flow: editProfileFlow)
    }

    /// A fresh model for one appearance of `enterCode`.
    public func enterCodeModel() -> EnterCodeModel
    {
        EnterCodeModel(deviceApproval: deviceApproval, flow: approveDeviceFlow)
    }

    /// A fresh model for one appearance of `fitOne`.
    public func fitOneModel() -> FitOneModel
    {
        FitOneModel(flow: sheetFitFlow)
    }

    /// A fresh model for one appearance of `form`.
    public func formModel() -> FormModel
    {
        FormModel(deviceApproval: deviceApproval, flow: keyboardFormFlow)
    }

    /// A fresh model for one appearance of `fullOne`.
    public func fullOneModel() -> FullOneModel
    {
        FullOneModel(flow: sheetFullFlow)
    }

    /// A fresh model for one appearance of `halfOne`.
    public func halfOneModel() -> HalfOneModel
    {
        HalfOneModel(flow: sheetHalfFlow)
    }

    /// A fresh model for one appearance of `item`.
    public func itemModel() -> ItemModel
    {
        ItemModel(flow: itemDetailFlow)
    }

    /// A fresh model for one appearance of `itemMore`.
    public func itemMoreModel() -> ItemMoreModel
    {
        ItemMoreModel(flow: itemDetailFlow)
    }

    /// A fresh model for one appearance of `long`.
    public func longModel() -> LongModel
    {
        LongModel(flow: longScrollFlow)
    }

    /// A fresh model for one appearance of `modalOne`.
    public func modalOneModel() -> ModalOneModel
    {
        ModalOneModel(flow: modalTourFlow)
    }

    /// A fresh model for one appearance of `modalTwo`.
    public func modalTwoModel() -> ModalTwoModel
    {
        ModalTwoModel(flow: modalTourFlow)
    }

    /// A fresh model for one appearance of `navOne`.
    public func navOneModel() -> NavOneModel
    {
        NavOneModel(flow: sheetNavFlow)
    }

    /// A fresh model for one appearance of `navTwo`.
    public func navTwoModel() -> NavTwoModel
    {
        NavTwoModel(flow: sheetNavFlow)
    }

    /// A fresh model for one appearance of `profileDetails`.
    public func profileDetailsModel() -> ProfileDetailsModel
    {
        ProfileDetailsModel(flow: profileFlow)
    }

    /// A fresh model for one appearance of `profileSummary`.
    public func profileSummaryModel() -> ProfileSummaryModel
    {
        ProfileSummaryModel(flow: profileFlow)
    }

    /// A fresh model for one appearance of `reviewDevice`.
    public func reviewDeviceModel(userCode: String) -> ReviewDeviceModel
    {
        ReviewDeviceModel(useCase: DefaultReviewDeviceUseCase(service: deviceApproval), deviceApproval: deviceApproval, flow: approveDeviceFlow, userCode: userCode)
    }

    /// A fresh model for one appearance of `tourOne`.
    public func tourOneModel() -> TourOneModel
    {
        TourOneModel(flow: pushTourFlow)
    }

    /// A fresh model for one appearance of `tourThree`.
    public func tourThreeModel() -> TourThreeModel
    {
        TourThreeModel(flow: pushTourFlow)
    }

    /// A fresh model for one appearance of `tourTwo`.
    public func tourTwoModel() -> TourTwoModel
    {
        TourTwoModel(flow: pushTourFlow)
    }

    /// The app against a real server: one transport, one session, one client.
    ///
    /// Throws `SPFNSessionError.untrustedBaseURL` when `baseURL` is neither https nor
    /// http to loopback: the session refuses cleartext at creation, and this is where
    /// a generated app creates one.
    public static func live(
        transport: any SPFNTransport,
        keyProvider: any SPFNKeyProvider,
        baseURL: String
    ) throws -> AppContainer
    {
        let session = try SPFNSession(
            transport: transport,
            keyProvider: keyProvider,
            baseURL: baseURL
        )
        let client = SPFNClient(transport: transport, session: session)
        return AppContainer(deviceApproval: DefaultDeviceApprovalService(client: client))
    }
}
