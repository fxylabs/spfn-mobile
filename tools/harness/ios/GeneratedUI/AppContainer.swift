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

    /// The `approveDevice` flow, open on its start screen.
    public let approveDeviceFlow: Flow<ApproveDeviceRoute>

    public init(
        deviceApproval: any DeviceApprovalService
    )
    {
        self.deviceApproval = deviceApproval
        self.approveDeviceFlow = ApproveDeviceFlow()
    }

    /// A fresh model for one appearance of `enterCode`.
    public func enterCodeModel() -> EnterCodeModel
    {
        EnterCodeModel(deviceApproval: deviceApproval, flow: approveDeviceFlow)
    }

    /// A fresh model for one appearance of `reviewDevice`.
    public func reviewDeviceModel(userCode: String) -> ReviewDeviceModel
    {
        ReviewDeviceModel(useCase: DefaultReviewDeviceUseCase(service: deviceApproval), deviceApproval: deviceApproval, flow: approveDeviceFlow, userCode: userCode)
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
