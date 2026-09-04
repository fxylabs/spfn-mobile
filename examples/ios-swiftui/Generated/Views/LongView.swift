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

/// The `long` screen, drawn out of SPFNUI's components.
@MainActor
public struct LongView: View
{
    @State private var model: LongModel

    public init(model: LongModel)
    {
        _model = State(initialValue: model)
    }

    public var body: some View
    {
        Screen(title: "A body that does not fit", scroll: true)
        {
            VStack(alignment: .leading, spacing: SPFNTokens.space4)
            {
                readouts
                SpfnText("This screen reads nothing and writes nothing. Its body is long on purpose: the control at the foot of it is below the fold on a phone, so reaching it is a scroll rather than a tap, and the header above it has to stay where it is while that happens.")
                SpfnText("A header that scrolled away with the body would take the way out of the flow with it. That is the thing this screen exists to make visible, and it is a thing only a device can hold still — the frame is laid out by the platform, and a JVM test of the model would pass whatever the frame did.")
                SpfnText("The second half of it is the keyboard, which is a different screen's job. Here there is no field to focus, so the body scrolls under a header that does not and there is nothing else moving to confuse the reading.")
                SpfnText("Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat.")
                SpfnText("Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.")
                SpfnText("Sed ut perspiciatis unde omnis iste natus error sit voluptatem accusantium doloremque laudantium, totam rem aperiam, eaque ipsa quae ab illo inventore veritatis et quasi architecto beatae vitae dicta sunt explicabo.")
                SpfnText("Nemo enim ipsam voluptatem quia voluptas sit aspernatur aut odit aut fugit, sed quia consequuntur magni dolores eos qui ratione voluptatem sequi nesciunt.")
                SpfnText("Neque porro quisquam est, qui dolorem ipsum quia dolor sit amet, consectetur, adipisci velit, sed quia non numquam eius modi tempora incidunt ut labore et dolore magnam aliquam quaerat voluptatem.")
                PrimaryButton(
                    title: "done",
                    identifier: "long.done",
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
