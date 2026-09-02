// SPFN Mobile — the SwiftUI example app's one screen holder.
//
// Counterpart of
// examples/android-compose/src/main/kotlin/xyz/superfunction/spfn/example/MainActivity.kt.
// Everything below the root is generated; what is written here is the three things a
// generator cannot know: which fixture this launch asked for, where a receipt goes, and
// what to do when neither a fixture nor a configured server exists.
//
// The fixture is the only door a fake service comes through. There is no flag inside the
// app that switches one on: with no `-SPFN_UI_FIXTURE <cell>` launch argument,
// `Fixtures.forCell` is never reached.
//
// The argument is read straight out of `ProcessInfo.processInfo.arguments`. Maestro's
// `launchApp: arguments:` reaches iOS as `-key value` pairs, which is also why the harness
// can read the same pairs through `UserDefaults`; this app reads the array because what it
// wants is one launch's own argument and not a value that outlives the launch.

import Foundation
import SPFNClient
import SPFNCore
import SPFNGenerated
import SPFNUI
import SwiftUI

@main
struct ExampleApp: App
{
    var body: some Scene
    {
        WindowGroup
        {
            RootView()
        }
    }
}

/// The app's root: the readouts a flow reads before and after the flow itself, and the one
/// control that is not a screen's.
///
/// The receipt control lives here rather than on a screen because a cell that ends with
/// the flow closed has no screen left to press. Every generated flow unwinds itself before
/// reaching it, which is what makes the control reachable here at all — a modal flow
/// covers this view entirely while it is open.
@MainActor
struct RootView: View
{
    @State private var launch = Launch.fromProcess()
    @State private var receipt = "none"

    var body: some View
    {
        if let container = launch.container
        {
            configured(container)
        }
        else
        {
            unconfigured
        }
    }

    @ViewBuilder
    private func configured(_ container: AppContainer) -> some View
    {
        VStack(alignment: .leading, spacing: 8)
        {
            Text("fixture=" + launch.cell)
            Text("stack=" + String(container.approveDeviceFlow.stack.count))
            Text("receipt=" + receipt)
            Button("write receipt")
            {
                receipt = write(depth: container.approveDeviceFlow.stack.count)
            }
            .accessibilityIdentifier("example.receipt")
            ApproveDeviceFlowHost(container: container)
        }
    }

    /// What a checkout with no fixture, no server and no key has to show.
    private var unconfigured: some View
    {
        VStack(alignment: .leading, spacing: 8)
        {
            Text("fixture=none")
            Text("stack=0")
            Text(
                "This build names no server and holds no enrolled key, so it sends nothing. "
                    + "Launch it with -SPFN_UI_FIXTURE <cell> to drive the screens against a fixture."
            )
        }
    }

    /// Writes the receipt, or answers with what went wrong instead of pretending it wrote
    /// one. A run whose receipt silently did not appear is indistinguishable from a run
    /// that never happened (P7).
    private func write(depth: Int) -> String
    {
        let receipt = ExampleReceipt(
            cell: launch.cell,
            fixture: launch.fixture,
            stackDepth: depth,
            timestampMillis: Int64(Date().timeIntervalSince1970 * 1000),
            sdkVersion: SPFNVersion.current,
            contractVersion: SPFNGeneratedContract.binding.importedVersion
        )
        do
        {
            return try ExampleReceiptStore().write(receipt)
        }
        catch
        {
            return "unwritable"
        }
    }
}

/// What this launch asked for, resolved once.
@MainActor
struct Launch
{
    /// The cell the launch named, or `none`.
    let cell: String

    /// The seeding that cell runs under, or `none`.
    let fixture: String

    /// The app's graph, or `nil` when there is neither a fixture nor a configured server.
    let container: AppContainer?

    static func fromProcess() -> Launch
    {
        let cell = argument(named: "SPFN_UI_FIXTURE") ?? ""
        guard !cell.isEmpty, let fixture = Fixtures.forCell(cell)
        else
        {
            return Launch(cell: cell.isEmpty ? "none" : cell, fixture: "none", container: nil)
        }
        let container = AppContainer(deviceApproval: fixture.service())
        if let openAt = fixture.openAt
        {
            try? container.approveDeviceFlow.open(at: openAt)
        }
        return Launch(cell: cell, fixture: fixture.name, container: container)
    }

    /// The value after `-<name>` in this process's arguments, or `nil`.
    private static func argument(named name: String) -> String?
    {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "-" + name), index + 1 < arguments.count
        else
        {
            return nil
        }
        let value = arguments[index + 1]
        // An argument passed as an empty string is an absent argument. Maestro writes one
        // when a flow leaves a variable unset, which is the same rule the harness records.
        return value.isEmpty ? nil : value
    }
}
