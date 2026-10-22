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

import Foundation
import Observation
import SPFNUI

/// The `long` screen's state and rules, with no toolkit in sight.
///
/// Constructor injection, so a test drives this class against a fake service and a
/// real `Flow` with no device, no view and no server.
@MainActor
@Observable
public final class LongModel
{
    /// What this screen's write is doing.
    public private(set) var state: Busy = .idle

    private let flow: Flow<LongScrollRoute>

    public init(
        flow: Flow<LongScrollRoute>
    )
    {
        self.flow = flow
    }

    /// The flow's stack, so the screen can print its depth as a readout.
    public var stack: [LongScrollRoute] { flow.stack }

    /// Closes the flow. Its stack empties, so nothing of it is presented.
    public func done()
    {
        flow.close()
    }
}
