#if canImport(SwiftUI)
// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      ea4b08e490fa7f24720859c9b735a9d628949ad1595762d44cb1a833b0b7c164
// bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
// contractVersion: 0.10.0
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateHarnessUi
// Verified by:     ./gradlew :ui-codegen:spfnHarnessUiVerify
//
// Every element here exists because a runner has to reach it or read it: one control
// per action, one field per typed input, and the two readouts.
// What a VALUE looks like is the human's, outside `Generated/` — the ready slot below is
// deliberately empty. Selectors follow the harness's rule: a control by the id
// `<screen>.<action>`, a readout by its text (tools/harness/ios/Sources/HarnessView.swift).

import SPFNCore
import SPFNUI
import SwiftUI

/// The `enterCode` screen, drawn out of SPFNUI's components.
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
        Screen(title: "Approve a device", scroll: true)
        {
            VStack(alignment: .leading, spacing: SPFNTokens.space4)
            {
                SpfnTextField(
                    label: "Code from the device",
                    kind: .code,
                    identifier: "enterCode.userCode",
                    text: $userCode,
                    error: ScreenFailure.fieldMessage(failure, field: "userCode"),
                    submitOnReturn: true,
                    autofocus: true,
                    onSubmit: { Task { await model.submit(userCode: userCode) } },
                    onChange: { _ in model.clearError() }
                )
                status
                TextButton(
                    title: "cancel",
                    identifier: "enterCode.cancel",
                    onTap: { model.cancel() }
                )
                PrimaryButton(
                    title: "submit",
                    identifier: "enterCode.submit",
                    busy: model.state == .busy,
                    onTap: { Task { await model.submit(userCode: userCode) } }
                )
                readouts
            }
            .padding(SPFNTokens.space4)
        }
    }

    /// The envelope this screen is carrying, or nil.
    private var failure: SPFNErrorEnvelope?
    {
        if case .error(let envelope) = model.state
        {
            return envelope
        }
        return nil
    }

    /// A refusal that is the SCREEN's rather than one field's.
    ///
    /// A field's own refusal is drawn under the field by `SpfnTextField`, so drawing it
    /// here as well would say the same thing twice in two places.
    @ViewBuilder
    private var status: some View
    {
        if let failure = failure, !ScreenFailure.isFieldRefusal(failure)
        {
            StatusText(
                kind: .error,
                text: ScreenFailure.message(failure),
                identifier: "enterCode.status"
            )
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
