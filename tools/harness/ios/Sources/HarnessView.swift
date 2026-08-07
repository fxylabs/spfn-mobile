// SPFN Mobile — the harness screen.
//
// Buttons and four labels. This is not a sample app and not a design: every element
// exists because a flow needs to tap it or read it.
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
            readout("custody", model.custody)
        }
        .font(.system(.body, design: .monospaced))
    }

    /// No accessibility identifier on purpose: one would empty this element's text, which
    /// is the only thing a flow can match a changing value by.
    private func readout(_ label: String, _ value: String) -> some View
    {
        Text("\(label)=\(value)")
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
