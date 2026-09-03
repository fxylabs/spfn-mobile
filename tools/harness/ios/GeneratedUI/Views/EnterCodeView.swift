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

/// The `enterCode` screen: one control per action, and the two readouts.
@MainActor
public struct EnterCodeView: View
{
    @State private var model: EnterCodeModel
    @State private var userCode: String = ""

    public init(model: EnterCodeModel)
    {
        _model = State(initialValue: model)
    }

    public var body: some View
    {
        VStack(alignment: .leading, spacing: 8)
        {
            Text("state=" + stateName(model.state))
            Text("stack=" + String(model.stack.count))
            TextField("userCode", text: $userCode)
                .accessibilityIdentifier("enterCode.userCode")
                .frame(minHeight: touchTarget)
            Button("cancel")
            {
                model.cancel()
            }
            .accessibilityIdentifier("enterCode.cancel")
            .frame(minHeight: touchTarget)
            Button("submit")
            {
                Task { await model.submit(userCode: userCode) }
            }
            .accessibilityIdentifier("enterCode.submit")
            .frame(minHeight: touchTarget)
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
private func stateName(_ state: Busy) -> String
{
    switch state
    {
    case .idle: return "idle"
    case .busy: return "busy"
    case .error: return "error"
    }
}
#endif
