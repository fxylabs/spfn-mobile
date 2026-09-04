// SPFN Mobile — the nine flows, as the app's own list of them.
//
// Counterpart of
// examples/android-compose/src/main/kotlin/xyz/superfunction/spfn/example/Flows.kt, entry
// for entry.
//
// The generated `AppContainer` holds one `Flow` per flow the spec declares and opens every
// one of them on its start screen, which is right for a container and wrong for a screen:
// nine flows presented at once is nine presentations over each other. So the app closes them
// all and opens the one this launch is about — a cell's flow, or none at all, which is the
// menu.
//
// This file is the one place that names all nine, and it is hand-written because it has to
// be: `AppContainer`'s properties are typed on nine different route enums, so "every flow"
// is not something a loop can say here. The cost is stated rather than hidden — a flow added
// to the spec and not added below is a flow the menu does not offer.

import Foundation
import SPFNUI

@MainActor
enum Flows
{
    /// Every flow, in the order the menu draws them: the one the case table is about, then
    /// the three presentations, then the two stacks, then the keyboard and the long body.
    static let all: [String] = [
        "approveDevice",
        "pushTour",
        "modalTour",
        "sheetFit",
        "sheetHalf",
        "sheetFull",
        "sheetNav",
        "keyboardForm",
        "longScroll",
    ]

    /// Leaves exactly `flow` open, on `openAt` when it names a stack and on its start screen
    /// otherwise. A nil flow leaves every one of them closed, which is the menu.
    ///
    /// Closing first and unconditionally, because the container opened all nine: a launch
    /// that only opened its own would put one flow over eight others.
    static func openOnly(_ container: AppContainer, flow: String?, openAt: [ApproveDeviceRoute]?)
    {
        closeAll(container)
        guard let flow = flow
        else
        {
            return
        }
        if let openAt = openAt
        {
            try? container.approveDeviceFlow.open(at: openAt)
            return
        }
        open(container, flow: flow)
    }

    /// Opens `flow` on the screen the spec named as its start. Unknown names open nothing.
    static func open(_ container: AppContainer, flow: String)
    {
        switch flow
        {
        case "approveDevice":
            container.approveDeviceFlow.push(.enterCode)
        case "pushTour":
            container.pushTourFlow.push(.tourOne)
        case "modalTour":
            container.modalTourFlow.push(.modalOne)
        case "sheetFit":
            container.sheetFitFlow.push(.fitOne)
        case "sheetHalf":
            container.sheetHalfFlow.push(.halfOne)
        case "sheetFull":
            container.sheetFullFlow.push(.fullOne)
        case "sheetNav":
            container.sheetNavFlow.push(.navOne)
        case "keyboardForm":
            container.keyboardFormFlow.push(.form)
        case "longScroll":
            container.longScrollFlow.push(.long)
        default:
            return
        }
    }

    /// How deep the app stands, which is every flow's depth added up.
    ///
    /// A sum and not "the open one's depth", because the sum is a number this app can state
    /// without knowing which flow is on show — and because only one of them ever is, the two
    /// are the same number. The screens' own `stack=` readout reads one flow, so a run in
    /// which they disagreed would put two different values on one screen and let an
    /// assertion match whichever it found first.
    static func depth(_ container: AppContainer) -> Int
    {
        container.approveDeviceFlow.stack.count
            + container.pushTourFlow.stack.count
            + container.modalTourFlow.stack.count
            + container.sheetFitFlow.stack.count
            + container.sheetHalfFlow.stack.count
            + container.sheetFullFlow.stack.count
            + container.sheetNavFlow.stack.count
            + container.keyboardFormFlow.stack.count
            + container.longScrollFlow.stack.count
    }

    private static func closeAll(_ container: AppContainer)
    {
        container.approveDeviceFlow.close()
        container.pushTourFlow.close()
        container.modalTourFlow.close()
        container.sheetFitFlow.close()
        container.sheetHalfFlow.close()
        container.sheetFullFlow.close()
        container.sheetNavFlow.close()
        container.keyboardFormFlow.close()
        container.longScrollFlow.close()
    }
}
