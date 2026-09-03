#if canImport(SwiftUI)
// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      88e5159b5528860daa36d6ebae1f6a6940c8152eb8373bf4cb3656be70599153
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

/// Where the `keyboardForm` flow can stand.
///
/// A screen that reads carries what its read needs; a screen that reads nothing
/// carries nothing. `Hashable` is synthesised either way — every payload here is a
/// required string or integer, and both are `Hashable` — which is what
/// `NavigationStack(path:)` identifies a stack entry by.
public enum KeyboardFormRoute: FlowRoute
{
    case form
}

/// How this flow is presented, and therefore what a back on its last route means.
public let keyboardFormEntry: FlowEntry = .push

/// A factory, so the flow opens on the screen the spec named as its start.
@MainActor
public func KeyboardFormFlow() -> Flow<KeyboardFormRoute>
{
    Flow(initial: [.form])
}

/// Renders the `keyboardForm` flow: one route, one model, one view.
///
/// A screen with a source loads it here, once per route: a screen loads its own read
/// however it appeared, which is what makes a deep entry — `open(at:)` onto a whole
/// stack — behave exactly like a push.
@MainActor
public struct KeyboardFormFlowHost: View
{
    private let container: AppContainer

    public init(container: AppContainer)
    {
        self.container = container
    }

    public var body: some View
    {
        FlowHost(flow: container.keyboardFormFlow, entry: keyboardFormEntry)
        { route in
            switch route
            {
            case .form:
                FormView(model: container.formModel())
            }
        }
    }
}
#endif
