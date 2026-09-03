#if canImport(SwiftUI)
// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      cd02e9ed576538e540a939229a0e476a76708e84286a3ccd09f5f680bf7ab8b5
// bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
// contractVersion: 0.10.0
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateHarnessUi
// Verified by:     ./gradlew :ui-codegen:spfnHarnessUiVerify
//
// Every element here exists because a runner has to reach it or read it: one control
// per action, one field per typed input, and the two readouts. Layout is the human's,
// outside `Generated/`. Selectors follow the harness's rule — a control by the id
// `<screen>.<action>`, a readout by its text (tools/harness/ios/Sources/HarnessView.swift).

import SPFNUI
import SwiftUI

/// The `reviewDevice` screen: one control per action, and the two readouts.
@MainActor
public struct ReviewDeviceView: View
{
    @State private var model: ReviewDeviceModel

    public init(model: ReviewDeviceModel)
    {
        _model = State(initialValue: model)
    }

    public var body: some View
    {
        VStack(alignment: .leading, spacing: 8)
        {
            Text("state=" + stateName(model.state))
            Text("stack=" + String(model.stack.count))
            Button("approve")
            {
                Task { await model.approve() }
            }
            .accessibilityIdentifier("reviewDevice.approve")
            .frame(minHeight: touchTarget)
            Button("back")
            {
                model.back()
            }
            .accessibilityIdentifier("reviewDevice.back")
            .frame(minHeight: touchTarget)
            Button("deny")
            {
                Task { await model.deny() }
            }
            .accessibilityIdentifier("reviewDevice.deny")
            .frame(minHeight: touchTarget)
            Button("retry")
            {
                Task { await model.retry() }
            }
            .accessibilityIdentifier("reviewDevice.retry")
            .frame(minHeight: touchTarget)
        }
        // A screen loads its own read once, however it appeared: pushed onto the
        // stack, or already on it because the flow was opened at a whole stack.
        .task
        {
            await model.load()
        }
    }
}

/// The platform's minimum touch target, given to every control and field.
///
/// A control smaller than this is reachable only through a hit area larger than itself,
/// and neighbouring hit areas then overlap: the bounds reported for one control sit on
/// a neighbour's, and a runner tapping the reported centre taps the neighbour
/// (docs/IMPLEMENTATION-PITFALLS.md P21).
private let touchTarget: CGFloat = 44

/// The one word a runner reads this screen's state as.
private func stateName<Value: Sendable>(_ state: Loadable<Value>) -> String
{
    switch state
    {
    case .loading: return "loading"
    case .ready: return "ready"
    case .empty: return "empty"
    case .error: return "error"
    }
}
#endif
