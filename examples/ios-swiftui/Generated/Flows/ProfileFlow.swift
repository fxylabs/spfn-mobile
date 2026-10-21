#if canImport(SwiftUI)
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
//
// Guarded whole, first line of code to last, the way every SwiftUI file in this
// repository is: SwiftUI is Apple's and the validator holds the guard to the file.

import SPFNUI
import SwiftUI

/// Where the `profile` flow can stand.
///
/// A screen that reads carries what its read needs; a screen that reads nothing
/// carries nothing. `Hashable` is synthesised either way — every payload here is a
/// required string or integer, and both are `Hashable` — which is what
/// `NavigationStack(path:)` identifies a stack entry by.
public enum ProfileRoute: FlowRoute
{
    case profileDetails
    case profileSummary
}

/// How this flow is presented, and therefore what a back on its last route means.
public let profileEntry: FlowEntry = .push

/// A factory, so the flow opens on the screen the spec named as its start.
@MainActor
public func ProfileFlow() -> Flow<ProfileRoute>
{
    Flow(initial: [.profileSummary])
}

/// Renders the `profile` flow: one route, one model, one view.
///
/// A screen with a source loads it here, once per route: a screen loads its own read
/// however it appeared, which is what makes a deep entry — `open(at:)` onto a whole
/// stack — behave exactly like a push.
@MainActor
public struct ProfileFlowHost: View
{
    private let container: AppContainer

    public init(container: AppContainer)
    {
        self.container = container
    }

    public var body: some View
    {
        FlowHost(flow: container.profileFlow, entry: profileEntry)
        { route in
            switch route
            {
            case .profileDetails:
                ProfileDetailsView(model: container.profileDetailsModel())
            case .profileSummary:
                ProfileSummaryView(model: container.profileSummaryModel())
            }
        }
    }
}
#endif
