// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      18bfbc7bb24e30e2dbb65e195f5448594bab02fa52e257cb590d5a2c534a7633
// bundleSha256:    a7cab3c8e98f3d83384693a45b5d1754640529636c193afd54ef116e98e47c1a
// contractVersion: 0.13.4
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

import Foundation
import Observation
import SPFNUI

/// The `profileSummary` screen's state and rules, with no toolkit in sight.
///
/// Constructor injection, so a test drives this class against a fake service and a
/// real `Flow` with no device, no view and no server.
@MainActor
@Observable
public final class ProfileSummaryModel
{
    /// What this screen's write is doing.
    public private(set) var state: Busy = .idle

    private let flow: Flow<ProfileRoute>

    public init(
        flow: Flow<ProfileRoute>
    )
    {
        self.flow = flow
    }

    /// The flow's stack, so the screen can print its depth as a readout.
    public var stack: [ProfileRoute] { flow.stack }

    /// Moves on to the next screen.
    public func more()
    {
        flow.push(.profileDetails)
    }
}
