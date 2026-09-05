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

import Foundation
import Observation
import SPFNUI

/// The `fullOne` screen's state and rules, with no toolkit in sight.
///
/// Constructor injection, so a test drives this class against a fake service and a
/// real `Flow` with no device, no view and no server.
@MainActor
@Observable
public final class FullOneModel
{
    /// What this screen's write is doing.
    public private(set) var state: Busy = .idle

    private let flow: Flow<SheetFullRoute>

    public init(
        flow: Flow<SheetFullRoute>
    )
    {
        self.flow = flow
    }

    /// The flow's stack, so the screen can print its depth as a readout.
    public var stack: [SheetFullRoute] { flow.stack }

    /// Closes the flow. Its stack empties, so nothing of it is presented.
    public func done()
    {
        flow.close()
    }
}
