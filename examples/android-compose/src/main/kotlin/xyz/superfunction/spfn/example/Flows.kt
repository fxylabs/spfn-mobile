// SPFN Mobile — the nine flows, as the app's own list of them.
//
// Counterpart of examples/ios-swiftui/Sources/Flows.swift, entry for entry.
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
// to the spec and not added below is a flow the menu does not offer — and `FlowMenuTest` is
// what turns that into a failing test rather than a missing button.

package xyz.superfunction.spfn.example

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import xyz.superfunction.spfn.example.generated.AppContainer
import xyz.superfunction.spfn.example.generated.flows.ApproveDeviceRoute
import xyz.superfunction.spfn.example.generated.flows.KeyboardFormRoute
import xyz.superfunction.spfn.example.generated.flows.LongScrollRoute
import xyz.superfunction.spfn.example.generated.flows.ModalTourRoute
import xyz.superfunction.spfn.example.generated.flows.PushTourRoute
import xyz.superfunction.spfn.example.generated.flows.SheetFitRoute
import xyz.superfunction.spfn.example.generated.flows.SheetFullRoute
import xyz.superfunction.spfn.example.generated.flows.SheetHalfRoute
import xyz.superfunction.spfn.example.generated.flows.SheetNavRoute

object Flows
{
    /**
     * Every flow, in the order the menu draws them: the one the case table is about, then
     * the three presentations, then the two stacks, then the keyboard and the long body.
     */
    val ALL: List<String> = listOf(
        "approveDevice",
        "pushTour",
        "modalTour",
        "sheetFit",
        "sheetHalf",
        "sheetFull",
        "sheetNav",
        "keyboardForm",
        "longScroll"
    );

    /**
     * Leaves exactly [flow] open, on [openAt] when it names a stack and on its start screen
     * otherwise. A null flow leaves every one of them closed, which is the menu.
     *
     * Closing first and unconditionally, because the container opened all nine: a launch
     * that only opened its own would put one flow over eight others.
     */
    fun openOnly(container: AppContainer, flow: String?, openAt: List<ApproveDeviceRoute>?)
    {
        closeAll(container);
        if (flow == null)
        {
            return;
        }
        if (openAt != null)
        {
            container.approveDeviceFlow.open(openAt);
            return;
        }
        open(container, flow);
    }

    /** Opens [flow] on the screen the spec named as its start. Unknown names open nothing. */
    fun open(container: AppContainer, flow: String)
    {
        when (flow)
        {
            "approveDevice" -> container.approveDeviceFlow.push(ApproveDeviceRoute.EnterCode)
            "pushTour" -> container.pushTourFlow.push(PushTourRoute.TourOne)
            "modalTour" -> container.modalTourFlow.push(ModalTourRoute.ModalOne)
            "sheetFit" -> container.sheetFitFlow.push(SheetFitRoute.FitOne)
            "sheetHalf" -> container.sheetHalfFlow.push(SheetHalfRoute.HalfOne)
            "sheetFull" -> container.sheetFullFlow.push(SheetFullRoute.FullOne)
            "sheetNav" -> container.sheetNavFlow.push(SheetNavRoute.NavOne)
            "keyboardForm" -> container.keyboardFormFlow.push(KeyboardFormRoute.Form)
            "longScroll" -> container.longScrollFlow.push(LongScrollRoute.Long)
        };
    }

    private fun closeAll(container: AppContainer)
    {
        container.approveDeviceFlow.close();
        container.pushTourFlow.close();
        container.modalTourFlow.close();
        container.sheetFitFlow.close();
        container.sheetHalfFlow.close();
        container.sheetFullFlow.close();
        container.sheetNavFlow.close();
        container.keyboardFormFlow.close();
        container.longScrollFlow.close();
    }

    /**
     * How deep the app stands, which is every flow's depth added up.
     *
     * A sum and not "the open one's depth", because the sum is a number this app can state
     * without knowing which flow is on show — and because only one of them ever is, the two
     * are the same number. The screens' own `stack=` readout reads one flow, so a run in
     * which they disagreed would put two different values on one screen and let an assertion
     * match whichever it found first.
     */
    @Composable
    fun depth(container: AppContainer): Int =
        container.approveDeviceFlow.stack.collectAsState().value.size +
            container.pushTourFlow.stack.collectAsState().value.size +
            container.modalTourFlow.stack.collectAsState().value.size +
            container.sheetFitFlow.stack.collectAsState().value.size +
            container.sheetHalfFlow.stack.collectAsState().value.size +
            container.sheetFullFlow.stack.collectAsState().value.size +
            container.sheetNavFlow.stack.collectAsState().value.size +
            container.keyboardFormFlow.stack.collectAsState().value.size +
            container.longScrollFlow.stack.collectAsState().value.size
}
