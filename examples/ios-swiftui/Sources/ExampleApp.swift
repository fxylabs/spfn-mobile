// SPFN Mobile — the SwiftUI example app's one screen holder.
//
// Counterpart of
// examples/android-compose/src/main/kotlin/xyz/superfunction/spfn/example/MainActivity.kt.
// Everything below the root is generated; what is written here is the three things a
// generator cannot know: which fixture this launch asked for, where a receipt goes, and what
// to show a person who launched the app without asking for anything.
//
// The last of those is the MENU, and it replaced a screen that said the app was
// unconfigured. `-SPFN_UI_FIXTURE <cell>` still decides everything a runner cares about — it
// says which flow opens, on which seeding, at which depth — and a launch that names no cell
// now lands on a list of the nine flows instead of on a sentence about a server.
//
// The menu runs on the same fake every cell does, and that is not the fail-closed rule
// bending. This app has no enrolment path of its own, so a client built against a configured
// server would refuse every call for want of a key: a person pressing a menu button would
// get a refusal that says nothing about the screens the button opens. There is no
// real-server path in this app at all.
//
// The argument is read straight out of `ProcessInfo.processInfo.arguments`. Maestro's
// `launchApp: arguments:` reaches iOS as `-key value` pairs, which is also why the harness
// can read the same pairs through `UserDefaults`; this app reads the array because what it
// wants is one launch's own argument and not a value that outlives the launch.

import Foundation
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

/// The app's root: the menu, the readouts a flow reads before and after the flow itself, and
/// the one control that is not a screen's.
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
        ZStack
        {
            menu
            hosts
        }
    }

    /// Every flow's host, drawn over the menu.
    ///
    /// A `ZStack` and not a `VStack`, and the hosts last, for the reason the Compose half
    /// uses a `Box`: a pushed flow's stack is drawn OVER the menu rather than beside it, and
    /// a modal or a sheet needs nothing from its host but a place in the view tree.
    ///
    /// Split out of `body` rather than listed there because a `ViewBuilder` takes ten
    /// children and nine hosts beside the menu is exactly ten — a tenth flow in the spec
    /// would have failed to compile with an error about none of this.
    @ViewBuilder
    private var hosts: some View
    {
        ApproveDeviceFlowHost(container: launch.container)
        PushTourFlowHost(container: launch.container)
        ModalTourFlowHost(container: launch.container)
        SheetFitFlowHost(container: launch.container)
        SheetHalfFlowHost(container: launch.container)
        SheetFullFlowHost(container: launch.container)
        SheetNavFlowHost(container: launch.container)
        KeyboardFormFlowHost(container: launch.container)
        LongScrollFlowHost(container: launch.container)
    }

    /// The list of flows, and the three readouts over it.
    ///
    /// Drawn out of the SDK's own components rather than out of `Text` with a tap gesture,
    /// because a row of text a person taps is exactly the control that reports its
    /// neighbour's rectangle to a runner: `PrimaryButton` carries the 44pt minimum and the
    /// menu gets it for free (docs/IMPLEMENTATION-PITFALLS.md P21).
    ///
    /// The readouts and the receipt control come ABOVE the list, and that is a rule about
    /// reach rather than about layout. Every cell that ends with its flow closed reads
    /// `stack=0` here and then presses `example.receipt` here, and nine buttons stacked over
    /// them would put both below the fold on a phone.
    ///
    /// `fixture=` is the CELL this launch named, which is `none` on the menu even though a
    /// fake is installed: the fake is what the menu runs on, and the receipt's own record is
    /// where its name is written down.
    private var menu: some View
    {
        Screen(title: "SPFN showcase")
        {
            VStack(alignment: .leading, spacing: SPFNTokens.space4)
            {
                SpfnText("fixture=" + launch.cell, role: .mono)
                SpfnText("stack=" + String(Flows.depth(launch.container)), role: .mono)
                SpfnText("receipt=" + receipt, role: .mono)
                SecondaryButton(
                    title: "write receipt",
                    identifier: "example.receipt",
                    onTap: { receipt = write(depth: Flows.depth(launch.container)) }
                )
                ForEach(Flows.all, id: \.self)
                { flow in
                    PrimaryButton(
                        title: flow,
                        identifier: "menu." + flow,
                        onTap: { Flows.open(launch.container, flow: flow) }
                    )
                }
            }
            .padding(SPFNTokens.space4)
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

    /// The seeding that cell runs under. Every launch has one, the menu included.
    let fixture: String

    /// The app's graph, with exactly the flow this launch is about left open.
    let container: AppContainer

    static func fromProcess() -> Launch
    {
        let named = argument(named: "SPFN_UI_FIXTURE") ?? ""
        let fixture = Fixtures.forCell(named) ?? Fixtures.menu()
        let container = AppContainer(deviceApproval: fixture.service())
        // The container opened all nine flows; this decides which one is on show. Done here
        // rather than in the view because it is a fact about the LAUNCH: a person pressing a
        // menu button reaches `Flows.open` instead, and neither should be able to put a
        // second presentation over the first.
        Flows.openOnly(container, flow: fixture.flow, openAt: fixture.openAt)
        return Launch(cell: named.isEmpty ? "none" : named, fixture: fixture.name, container: container)
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
