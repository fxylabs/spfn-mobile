#if canImport(SwiftUI)
// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec
// specSha256:      b38692ad8d0c5d15f156188561a68042de8ac80f22eb4491a7f84f91c2c3f830
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

/// Where the `sheetHalf` flow can stand.
///
/// A screen that reads carries what its read needs; a screen that reads nothing
/// carries nothing. `Hashable` is synthesised either way — every payload here is a
/// required string or integer, and both are `Hashable` — which is what
/// `NavigationStack(path:)` identifies a stack entry by.
public enum SheetHalfRoute: FlowRoute
{
    case halfOne
}

/// How this flow is presented, and therefore what a back on its last route means.
public let sheetHalfEntry: FlowEntry = .sheet(detent: .half)

/// A factory, so the flow opens on the screen the spec named as its start.
@MainActor
public func SheetHalfFlow() -> Flow<SheetHalfRoute>
{
    Flow(initial: [.halfOne])
}

/// Renders the `sheetHalf` flow: one route, one model, one view.
///
/// A screen with a source loads it here, once per route: a screen loads its own read
/// however it appeared, which is what makes a deep entry — `open(at:)` onto a whole
/// stack — behave exactly like a push.
@MainActor
public struct SheetHalfFlowHost: View
{
    private let container: AppContainer

    public init(container: AppContainer)
    {
        self.container = container
    }

    public var body: some View
    {
        FlowHost(flow: container.sheetHalfFlow, entry: sheetHalfEntry)
        { route in
            switch route
            {
            case .halfOne:
                HalfOneView(model: container.halfOneModel())
            }
        }
    }
}
#endif
