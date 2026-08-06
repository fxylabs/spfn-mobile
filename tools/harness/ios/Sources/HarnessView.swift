// SPFN Mobile — the harness screen.
//
// Buttons and two labels. This is not a sample app and not a design: every element
// exists because a flow needs to tap it or read it, and each one carries an
// accessibility identifier because that is what Maestro matches on.
//
// The same identifiers appear in the Android harness, so one flow file drives both.

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
                readout
                Divider()
                actions
            }
            .padding()
        }
        .task
        {
            await model.refresh()
        }
    }

    private var readout: some View
    {
        VStack(alignment: .leading, spacing: 8)
        {
            readout("state-label", model.state)
            readout("outcome-label", model.outcome)
            readout("busy-label", model.busy ? "busy" : "ready")
        }
        .font(.system(.body, design: .monospaced))
    }

    private var actions: some View
    {
        VStack(alignment: .leading, spacing: 8)
        {
            asyncButton("enroll") { await model.enroll() }
            asyncButton("rotate") { await model.rotate() }
            asyncButton("resume") { await model.resumeRotation() }
            asyncButton("revoke") { await model.revokeActiveKey() }
            asyncButton("proven-call") { await model.provenCall() }
            asyncButton("note-revoked") { await model.noteSessionRevoked() }
            asyncButton("wipe") { await model.wipe() }
            syncButton("block-network") { model.setNetworkBlocked(true) }
            syncButton("open-network") { model.setNetworkBlocked(false) }
        }
    }

    /// One shape for every readout, and the identifier carries the value.
    ///
    /// The obvious spelling — an identifier naming the label and the text carrying the
    /// value — does not survive the trip. Setting `accessibilityIdentifier` on a SwiftUI
    /// `Text` leaves the automation hierarchy with the identifier and an EMPTY text, so a
    /// flow matching on both id and text matches nothing, forever. The first real run
    /// found this: every case failed on `state-label` while the app was working fine.
    ///
    /// So the value rides in the identifier, `state-label:unenrolled`, and a flow matches
    /// one field. The Android half spells it the same way for the same reason, which is
    /// what keeps one flow file driving both.
    private func readout(_ id: String, _ value: String) -> some View
    {
        Text(value)
            .accessibilityIdentifier("\(id):\(value)")
    }

    /// A button whose work outlives the tap. The identifier is the label, so a flow's
    /// `tapOn: id: "rotate"` reads as the action it performs.
    ///
    /// `markBusy()` runs BEFORE the task is spawned, and that ordering is the whole point.
    /// A `Task` does not start synchronously, so a model that set its own busy flag inside
    /// the async work would leave a window where the app still reads `ready` — and a flow
    /// waiting for `ready` would sail straight through it and assert on the previous
    /// action's state. That is the failure mode this harness exists to catch, so it must
    /// not be the harness's own.
    private func asyncButton(_ id: String, action: @escaping () async -> Void) -> some View
    {
        Button(id)
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
    private func syncButton(_ id: String, action: @escaping () -> Void) -> some View
    {
        Button(id, action: action)
            .accessibilityIdentifier(id)
            .buttonStyle(.bordered)
    }
}
