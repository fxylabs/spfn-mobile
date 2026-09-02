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
// A tap answers in three ways, and the readouts are none of them. The readouts are the
// machine-readable truth and they are written for a flow: a person who taps a button and
// watches one word near the top of the screen change from `ready` to `busy` and back
// inside a second has watched nothing happen. So every button here changes under a finger
// (`HarnessPressStyle`), the two actions that outlive their tap by seconds show a spinner
// while they run, and every action ends in a banner naming what it did (`banner`). None of
// the three is a readout and no flow may read one — `banner` says what keeps a flow's
// selector off it.
//
// android/.../HarnessActivity.kt is the same screen in Views, with the same button ids
// and the same readout text.

import Combine
import Foundation
import SPFNHarnessSupport
import SwiftUI

struct HarnessView: View
{
    @StateObject private var model = HarnessModel()

    /// The completion signal currently on screen, if one is. Held by the view rather than
    /// the model because when it goes away is a fact about this screen and nothing else.
    @State private var shown: HarnessSignal?

    /// Bumped once a second so the `expires-in` readout recomputes. Its value means
    /// nothing; changing is the whole job.
    @State private var countdown = 0

    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View
    {
        ScrollView
        {
            VStack(alignment: .leading, spacing: 12)
            {
                readouts
                Divider()
                deviceMode
                deviceCodeSection
                lifecycleDivider
                actions
            }
            .padding()
        }
        .overlay(alignment: .bottom)
        {
            banner
        }
        .onReceive(model.$signal.compactMap { $0 })
        { signal in
            show(signal)
        }
        .task
        {
            await model.refresh()
        }
    }

    /// The transient half of a tap's answer: what the action did, and — for a device-mode
    /// attempt — the name of the file it left behind. Nothing else the receipt holds ever
    /// reaches this view; the rest of that file is evidence about an account, and a banner
    /// is the part of this screen most likely to end up in a photograph of it.
    ///
    /// Two modifiers keep every Maestro flow off it, and both are needed. `allowsHitTesting`
    /// means it can never take a tap meant for a button underneath, and `accessibilityHidden`
    /// means it is not in the hierarchy a flow searches at all — so no selector can match
    /// it even by accident, and no flow can come to depend on it. A flow that passed because
    /// a banner was still up would be asserting on the wrong thing entirely.
    @ViewBuilder private var banner: some View
    {
        if let shown = shown
        {
            Text(shown.text)
                .font(.system(.footnote, design: .monospaced))
                .foregroundColor(.white)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(Color.black.opacity(0.85))
                .cornerRadius(10)
                .padding(.horizontal, 12)
                .padding(.bottom, 16)
                .transition(.opacity)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }

    /// Shows one signal and takes it away again, unless a newer one arrived first.
    ///
    /// The identity check is what makes a run of quick taps read correctly: three actions
    /// in four seconds start three dismissals, and without it the first one to come due
    /// would clear the third one's banner two seconds early.
    private func show(_ signal: HarnessSignal)
    {
        withAnimation(.easeOut(duration: 0.15))
        {
            shown = signal
        }
        Task
        {
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard shown?.id == signal.id
            else
            {
                return
            }
            withAnimation(.easeIn(duration: 0.25))
            {
                shown = nil
            }
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
            readout("device-code", model.deviceCode)
            readout("expires-in", expiresIn)
        }
        .font(.system(.body, design: .monospaced))
    }

    /// No accessibility identifier on purpose: one would empty this element's text, which
    /// is the only thing a flow can match a changing value by.
    private func readout(_ label: String, _ value: String) -> some View
    {
        Text("\(label)=\(value)")
    }

    /// Whole seconds until the shown code expires, or `-` when none is showing.
    ///
    /// Recomputed on every redraw, and `tick` is what causes one each second. The value
    /// is a countdown rather than an instant because what the person holding this phone
    /// needs to know is how long they have to walk to the other one.
    private var expiresIn: String
    {
        guard let expiry = model.deviceCodeExpiresAtMillis
        else
        {
            return "-"
        }
        let remaining = expiry - Int64(Date().timeIntervalSince1970 * 1000)
        return remaining > 0 ? "\(remaining / 1000)s" : "expired"
    }

    /// Signing this device in with a code, and approving another device that shows one.
    ///
    /// Two halves of one flow on one screen, because a harness has one phone in front of
    /// it at a time and either half has to be reachable. The code a person types to
    /// approve is its own field: a single one would let this device approve itself.
    private var deviceCodeSection: some View
    {
        VStack(alignment: .leading, spacing: 10)
        {
            Divider()
            Text("device code")
                .font(.system(.headline, design: .monospaced))
            asyncButton("btn_device_sign_in", "sign-in-with-a-code") { await model.signInWithACode() }

            Text("approve a device")
                .font(.system(.caption, design: .monospaced))
            TextField("XXXX-XXXX", text: $model.approverCode)
                .font(.system(.body, design: .monospaced))
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .textFieldStyle(.roundedBorder)
                .accessibilityIdentifier("input_device_code")
            asyncButton("btn_device_info", "device-info") { await model.describeWaitingDevice() }
            asyncButton("btn_device_approve", "device-approve") { await model.approveWaitingDevice() }
            asyncButton("btn_device_deny", "device-deny") { await model.denyWaitingDevice() }
        }
        .onReceive(tick)
        { _ in
            // A redraw a second, so `expires-in` counts down rather than sitting at
            // whatever it was when the code arrived. Nothing else on this screen changes
            // without an action, which is why this is the only timer here.
            countdown += 1
        }
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
        .buttonStyle(HarnessPressStyle())
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
    ///
    /// While the attempt runs, a spinner sits beside the title. `busy=ready` turning to
    /// `busy=busy` is what a flow needs and it is not what a person needs: an attempt puts
    /// a provider sheet up, waits for an account to be picked, enrols and writes a file,
    /// and for all of that the only thing on this button that had changed was a word
    /// somewhere above it. The title itself is untouched — the spinner is added beside it,
    /// not swapped for it, so what the button says stays what it says.
    private func providerButton(_ provider: HarnessProvider, id: String, title: String) -> some View
    {
        let ready = model.isReady(provider)
        let running = model.runningProvider == provider
        return Button
        {
            model.markBusy(running: provider)
            Task { await model.signIn(with: provider) }
        }
        label:
        {
            chrome(
                HStack(spacing: 8)
                {
                    if running
                    {
                        ProgressView()
                            .progressViewStyle(.circular)
                            .tint(.white)
                    }
                    Text(ready ? title : "\(title) (not configured)")
                },
                filled: true,
                dimmed: !ready
            )
        }
        .buttonStyle(HarnessPressStyle())
        .accessibilityIdentifier(id)
        .disabled(!ready || model.busy)
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
            syncButton("btn_block_network", "block-network") { model.toggleNetworkBlocked(true) }
            syncButton("btn_open_network", "open-network") { model.toggleNetworkBlocked(false) }
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
        Button
        {
            model.markBusy()
            Task { await action() }
        }
        label:
        {
            chrome(Text(title), filled: false, dimmed: false)
        }
        .buttonStyle(HarnessPressStyle())
        .accessibilityIdentifier(id)
    }

    /// A button whose work is finished when the tap returns. The network switch is the
    /// only kind: it flips a flag and sends nothing, so marking it busy would leave a
    /// `busy` nothing ever clears.
    ///
    /// It still owes its operator an answer, which is why it goes through
    /// `toggleNetworkBlocked` rather than `setNetworkBlocked`: the second is also what an
    /// attempt calls to arrange the `network-failure` case, and a signal there would
    /// announce a step of an attempt as though it were the result of one.
    private func syncButton(_ id: String, _ title: String, action: @escaping () -> Void) -> some View
    {
        Button(action: action)
        {
            chrome(Text(title), filled: false, dimmed: false)
        }
        .buttonStyle(HarnessPressStyle())
        .accessibilityIdentifier(id)
    }

    /// The look of an action button, drawn in the LABEL rather than taken from `.bordered`
    /// and `.borderedProminent`.
    ///
    /// The move is forced by where SwiftUI reports a press. `isPressed` reaches a
    /// `ButtonStyle` and nothing else, and a button carries one style — `.buttonStyle` does
    /// not stack, the outer one replaces the inner. So a press reaction of this screen's
    /// own choosing and a chrome of the platform's choosing cannot both be had; the chrome
    /// is the half that is easy to draw here, and the two kinds below are the same split
    /// those two platform styles drew.
    private func chrome(_ label: some View, filled: Bool, dimmed: Bool) -> some View
    {
        label
            .font(.system(.body, design: .monospaced))
            .foregroundColor(filled ? .white : .accentColor)
            .padding(.vertical, 10)
            .padding(.horizontal, 14)
            .background(filled ? Color.accentColor : Color.accentColor.opacity(0.14))
            .cornerRadius(8)
            .opacity(dimmed ? 0.4 : 1)
    }
}

/// What a button does under a finger, on every button on this screen.
///
/// Not decoration. The platform styles do react to a press, by an amount that varies with
/// the style and the OS version and that a person watching a phone across a desk can miss
/// — and the case rows were `.plain`, which reacts to a press on a custom label by
/// nothing at all: a row that did not take the tap and a row that did looked identical
/// until the fill moved. Opacity and scale are visible whatever the phone's appearance is
/// set to, and need no colour chosen here.
private struct HarnessPressStyle: ButtonStyle
{
    func makeBody(configuration: Configuration) -> some View
    {
        configuration.label
            .opacity(configuration.isPressed ? 0.45 : 1)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.08), value: configuration.isPressed)
    }
}
