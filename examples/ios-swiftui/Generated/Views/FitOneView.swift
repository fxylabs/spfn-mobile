#if canImport(SwiftUI)
// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-alpha.3
// spec:            examples/ui-spec
// specSha256:      0fbec833616cfb9717bb9d80a057d4069bae1f04d1c47d44f98d9becf08dce41
// bundleSha256:    8cce6d896e200a18e1312f23ed63ff4fc36b4ff6ea484f576c3de824be3b589e
// contractVersion: 0.13.2
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify
//
// Every element here exists because a runner has to reach it or read it: one control
// per action, one field per typed input, and the two readouts.
// The readouts stand FIRST so a body long enough to scroll cannot put them out of
// reach: a runner reads them before it has done anything at all.
// What a VALUE looks like is the human's, outside `Generated/` — the ready slot below is
// deliberately empty. Selectors follow the harness's rule: a control by the id
// `<screen>.<action>`, a readout by its text (tools/harness/ios/Sources/HarnessView.swift).

import SPFNCore
import SPFNUI
import SwiftUI

/// The `fitOne` screen, drawn out of SPFNUI's components.
@MainActor
public struct FitOneView: View
{
    @State private var model: FitOneModel

    public init(model: FitOneModel)
    {
        _model = State(initialValue: model)
    }

    public var body: some View
    {
        Screen(title: "A sheet that fits", scroll: true)
        {
            VStack(alignment: .leading, spacing: SPFNTokens.space4)
            {
                readouts
                SpfnText("This sheet holds what it shows. It fits without scrolling, so the way out is always in reach.")
                PrimaryButton(
                    title: "done",
                    identifier: "fitOne.done",
                    onTap: { model.done() }
                )
            }
            .padding(SPFNTokens.space4)
        }
    }

    /// What a runner reads this screen's state and its flow's depth as.
    @ViewBuilder
    private var readouts: some View
    {
        SpfnText("state=" + stateName(model.state), role: .mono)
        SpfnText("stack=" + String(model.stack.count), role: .mono)
    }
}

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
