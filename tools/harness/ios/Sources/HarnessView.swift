// SPFN Mobile — the harness screen.
//
// Readouts, then the half a person drives, then the half the flows drive. This is not a
// sample app and not a design: every element exists because a flow needs to tap it or a
// person needs to read it.
//
// The order is the second thing a device run bought. The device-mode controls sit on top
// because that is what a phone is for here, and the ten lifecycle buttons sit under a
// divider that says so — with every identifier and every title they always had, because a
// flow finds them by those strings.
//
// How a flow finds them is split, and the split is forced by the platforms rather than
// chosen. A flow's `id:` matches an accessibility identifier here and a RESOURCE id on
// Android, and a resource id is fixed at build time — so a button, whose identity never
// changes, is found by id on both, while a readout, whose whole point is that its value
// changes, is found by its text. The label rides in that text (`state=unenrolled`) so the
// match names which readout it means.
//
// Two runs paid for that sentence. Setting an accessibility identifier on a SwiftUI
// `Text` leaves the automation hierarchy with the identifier and an EMPTY text, so an
// earlier attempt at id-and-text matching matched nothing on iOS; and on a real Android
// phone every case failed with "Element not found" because a content description is not
// a resource id. Neither platform was wrong — they answer different questions.
//
// android/.../HarnessActivity.kt is the same screen in Views, with the same button ids
// and the same readout text.

import SPFNHarnessSupport
import SwiftUI

struct HarnessView: View
{
    @StateObject private var model = HarnessModel()

    var body: some View
    {
        ScrollView
        {
            VStack(alignment: .leading, spacing: 12)
            {
                readouts
                Divider()
                deviceMode
                lifecycleDivider
                actions
            }
            .padding()
        }
        .task
        {
            await model.refresh()
        }
    }

    private var readouts: some View
    {
        VStack(alignment: .leading, spacing: 8)
        {
            readout("state", model.state)
            readout("outcome", model.outcome)
            readout("busy", model.busy ? "busy" : "ready")
            // Permanent, not a message the network buttons leave behind. The first device
            // run burned three attempts on a transport that was still shut from an earlier
            // case: a blocked switch mimics a real network drop exactly, which is what
            // makes it worth reading and impossible to notice.
            readout("network", model.networkBlocked ? "blocked" : "open")
            readout("custody", model.custody)
            readout("config", model.configSummary)
            readout("case", model.deviceCase.rawValue)
            readout("receipt", model.receipt)
        }
        .font(.system(.body, design: .monospaced))
    }

    /// No accessibility identifier on purpose: one would empty this element's text, which
    /// is the only thing a flow can match a changing value by.
    private func readout(_ label: String, _ value: String) -> some View
    {
        Text("\(label)=\(value)")
    }

    /// The half of the screen a person drives and no flow does. It sits above the
    /// lifecycle buttons because it is what a device run is for.
    ///
    /// A case is picked first and a provider is tapped second, in that order, because the
    /// app cannot tell a first enrolment from a re-login by itself — see
    /// `HarnessDeviceCase`. The picked case is shown in the `case=` readout above and
    /// recorded in the receipt, so a reading is never separated from what it was a
    /// reading of.
    ///
    /// Two buttons and no more. A case is a SELECTION, and the selector above them is not
    /// a row of things to tap for effect: an operator reading five case buttons beside two
    /// provider buttons has seven things that look alike and two that do something.
    private var deviceMode: some View
    {
        VStack(alignment: .leading, spacing: 10)
        {
            Text("device verification")
                .font(.system(.headline, design: .monospaced))
            caseSelector
            Text(model.deviceCase.precondition)
                .font(.system(.caption, design: .monospaced))
            providerButton(.apple, id: "btn_signin_apple", title: "sign-in-apple")
            providerButton(.google, id: "btn_signin_google", title: "sign-in-google")
        }
    }

    /// The five cases as one single-choice control, boxed so it cannot be mistaken for a
    /// stack of actions.
    ///
    /// Rows rather than a `Picker`: the five names do not fit a segmented control on a
    /// phone, and a picker's rows are not elements a flow can name. Each row keeps the
    /// accessibility identifier it always had (`btn_case_<wire name>`), so nothing that
    /// could already find one stops finding it.
    private var caseSelector: some View
    {
        VStack(alignment: .leading, spacing: 4)
        {
            Text("case (pick one)")
                .font(.system(.caption, design: .monospaced))
            ForEach(HarnessDeviceCase.allCases, id: \.self)
            { value in
                caseRow(value)
            }
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color.secondary, lineWidth: 1)
        )
    }

    /// One case, spelled exactly as the shared spec spells it and as Android spells it.
    ///
    /// The label is the wire name and nothing else. It used to be `[first-enroll]` when
    /// selected and ` first-enroll ` when not, against Android's `case-first-enroll`, and
    /// a device run spent a round trip working out that the three were one case. Selection
    /// now rides in the fill and in the accessibility trait, where a selection belongs, and
    /// never in the text.
    private func caseRow(_ value: HarnessDeviceCase) -> some View
    {
        let selected = value == model.deviceCase
        return Button
        {
            model.selectCase(value)
        }
        label:
        {
            HStack
            {
                Text(value.rawValue)
                Spacer()
            }
            .padding(.vertical, 6)
            .padding(.horizontal, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(selected ? Color.accentColor.opacity(0.30) : Color.clear)
            .cornerRadius(6)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("btn_case_\(value.rawValue)")
        .accessibilityAddTraits(selected ? [.isSelected] : [])
        // A case changed while an attempt runs would leave the `case=` readout naming one
        // case and the receipt about to be written naming another. The case is read at the
        // start of an attempt, so the running one cannot be corrupted — the screen can.
        .disabled(model.busy)
    }

    /// Disabled, rather than absent, when this build has no configuration for it. An
    /// absent button reads as a harness that lost a feature; a disabled one beside a
    /// `config=` readout naming the missing half reads as the truth.
    ///
    /// Also disabled while anything is in flight. A second tap would put a second sheet
    /// over the first, and the first attempt's receipt would be written about a screen
    /// that no longer describes it.
    private func providerButton(_ provider: HarnessProvider, id: String, title: String) -> some View
    {
        Button(model.isReady(provider) ? title : "\(title) (not configured)")
        {
            model.markBusy()
            Task { await model.signIn(with: provider) }
        }
        .accessibilityIdentifier(id)
        .buttonStyle(.borderedProminent)
        .disabled(!model.isReady(provider) || model.busy)
    }

    /// What separates the two halves of this screen, and says which half is below it.
    ///
    /// The buttons under it are the ones the Maestro flows tap. Every identifier and every
    /// title below is exactly what it was, because a flow finds them by those strings and
    /// a rearranged screen must not be a renamed one.
    private var lifecycleDivider: some View
    {
        VStack(alignment: .leading, spacing: 8)
        {
            Divider()
            Text("sdk lifecycle (flows)")
                .font(.system(.headline, design: .monospaced))
        }
    }

    private var actions: some View
    {
        VStack(alignment: .leading, spacing: 8)
        {
            asyncButton("btn_enroll", "enroll") { await model.enroll() }
            asyncButton("btn_rotate", "rotate") { await model.rotate() }
            asyncButton("btn_resume", "resume") { await model.resumeRotation() }
            asyncButton("btn_revoke", "revoke") { await model.revokeActiveKey() }
            asyncButton("btn_proven_call", "proven-call") { await model.provenCall() }
            asyncButton("btn_note_revoked", "note-revoked") { await model.noteSessionRevoked() }
            asyncButton("btn_wipe", "wipe") { await model.wipe() }
            asyncButton("btn_custody_probe", "custody-probe") { await model.probeCustody() }
            syncButton("btn_block_network", "block-network") { model.setNetworkBlocked(true) }
            syncButton("btn_open_network", "open-network") { model.setNetworkBlocked(false) }
        }
    }

    /// A button whose work outlives the tap.
    ///
    /// `markBusy()` runs BEFORE the task is spawned, and that ordering is the whole point.
    /// A `Task` does not start synchronously, so a model that set its own busy flag inside
    /// the async work would leave a window where the app still reads `ready` — and a flow
    /// waiting for `ready` would sail straight through it and assert on the previous
    /// action's state. That is the failure mode this harness exists to catch, so it must
    /// not be the harness's own.
    private func asyncButton(_ id: String, _ title: String, action: @escaping () async -> Void) -> some View
    {
        Button(title)
        {
            model.markBusy()
            Task { await action() }
        }
        .accessibilityIdentifier(id)
        .buttonStyle(.bordered)
    }

    /// A button whose work is finished when the tap returns. The network switch is the
    /// only kind: it flips a flag and sends nothing, so marking it busy would leave a
    /// `busy` nothing ever clears.
    private func syncButton(_ id: String, _ title: String, action: @escaping () -> Void) -> some View
    {
        Button(title, action: action)
            .accessibilityIdentifier(id)
            .buttonStyle(.bordered)
    }
}
