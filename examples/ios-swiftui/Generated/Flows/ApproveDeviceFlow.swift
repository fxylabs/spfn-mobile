#if canImport(SwiftUI)
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
//
// Guarded whole, first line of code to last, the way every SwiftUI file in this
// repository is: SwiftUI is Apple's and the validator holds the guard to the file.

import SPFNUI
import SwiftUI

/// Where the `approveDevice` flow can stand.
///
/// A screen that reads carries what its read needs; a screen that reads nothing
/// carries nothing. `Hashable` is synthesised either way — every payload here is a
/// required string or integer, and both are `Hashable` — which is what
/// `NavigationStack(path:)` identifies a stack entry by.
public enum ApproveDeviceRoute: FlowRoute
{
    case enterCode
    case reviewDevice(userCode: String)
}

/// How this flow is presented, and therefore what a back on its last route means.
public let approveDeviceEntry: FlowEntry = .modal

/// A factory, so the flow opens on the screen the spec named as its start.
@MainActor
public func ApproveDeviceFlow() -> Flow<ApproveDeviceRoute>
{
    Flow(initial: [.enterCode])
}

/// Renders the `approveDevice` flow: one route, one model, one view.
///
/// A screen with a source loads it here, once per route: a screen loads its own read
/// however it appeared, which is what makes a deep entry — `open(at:)` onto a whole
/// stack — behave exactly like a push.
@MainActor
public struct ApproveDeviceFlowHost: View
{
    private let container: AppContainer

    public init(container: AppContainer)
    {
        self.container = container
    }

    public var body: some View
    {
        FlowHost(flow: container.approveDeviceFlow, entry: approveDeviceEntry)
        { route in
            switch route
            {
            case .enterCode:
                EnterCodeView(model: container.enterCodeModel())
            case .reviewDevice(let userCode):
                ReviewDeviceView(model: container.reviewDeviceModel(userCode: userCode))
            }
        }
    }
}
#endif
