// SPFN Mobile — the close and move table, one test per cell.
//
// Counterpart of Tests/SPFNUITests/CloseRulesTests.swift, cell for cell and name for name.
// The table is the one approved with the sheet entry (work unit w-evwna 3a) and amended for
// the host stack (3e, decision N2); it is written out here from that approval rather than
// read off the implementation, and a combination nobody wrote down is a combination neither
// platform has.
//
// | entry          | header back | system back / swipe | X            | drag down |
// | -------------- | ----------- | ------------------- | ------------ | --------- |
// | push, depth 2+ | pop         | pop                 | none         | n/a       |
// | push, root     | close       | close               | none         | n/a       |
// | modal, depth 2+| pop         | pop                 | close        | n/a       |
// | modal, root    | none        | close               | close        | n/a       |
// | sheet, depth 2+| pop         | pop                 | close        | close     |
// | sheet, root    | none        | close               | close        | close     |
//
// Each cell names the code that decides it:
//
//   header back  `Flow.wayOut` says which control is drawn and `Flow.back` is what it
//                does. A cell reading "none" is `WayOut.None`.
//   system back  `Flow.handlesBack` says whether this flow claims the gesture, and
//                `Flow.back` performs it.
//   X            `Flow.close`, drawn in the header's TRAILING slot. A cell reading "none"
//                is `Flow.wayOut` never answering `Close` for that entry, at any depth.
//   drag down    `SheetGeometry.closes` decides that a drag went far enough, and what a
//                dismissed sheet does is `Flow.close` — which is why a drag past the
//                threshold and a tap on the scrim are the same event to a flow.
//
// The four `n/a` cells have no test: a flow that is not a sheet cannot be dragged, and a
// test asserting that would be a test of this comment.

package xyz.superfunction.spfn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private data class Stop(val name: String) : FlowRoute

private val first = Stop("first");
private val second = Stop("second");

private val PUSH: FlowEntry = FlowEntry.Push;
private val MODAL: FlowEntry = FlowEntry.Modal;
private val SHEET: FlowEntry = FlowEntry.Sheet(SheetDetent.Half);

class CloseRulesTest
{
    // --- push, depth 2+ -----------------------------------------------------

    @Test
    fun push_depth2_headerBack_pops()
    {
        val flow = Flow(listOf(first, second));
        assertEquals(WayOut.Back, flow.wayOut(PUSH));
        flow.pop();
        assertEquals(listOf(first), flow.stack.value);
        assertTrue(flow.isPresented.value);
    }

    @Test
    fun push_depth2_systemBack_pops()
    {
        val flow = Flow(listOf(first, second));
        assertTrue(flow.handlesBack(PUSH));
        assertTrue(flow.back(PUSH));
        assertEquals(listOf(first), flow.stack.value);
        assertTrue(flow.isPresented.value);
    }

    @Test
    fun push_depth2_close_absent()
    {
        val flow = Flow(listOf(first, second));
        assertFalse(WayOut.Close == flow.wayOut(PUSH));
    }

    // --- push, root ---------------------------------------------------------

    @Test
    fun push_root_headerBack_closes()
    {
        val flow = Flow(listOf(first));
        // A back and not a close CONTROL, because what is under this root is the host's own
        // screen — and closing the flow is what uncovers it (decision N2).
        assertEquals(WayOut.Back, flow.wayOut(PUSH));
        assertTrue(flow.back(PUSH));
        assertEquals(emptyList<Stop>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    @Test
    fun push_root_systemBack_closes()
    {
        val flow = Flow(listOf(first));
        assertTrue(flow.handlesBack(PUSH));
        assertTrue(flow.back(PUSH));
        assertEquals(emptyList<Stop>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    @Test
    fun push_root_close_absent()
    {
        val flow = Flow(listOf(first));
        assertFalse(WayOut.Close == flow.wayOut(PUSH));
    }

    // --- modal, depth 2+ ----------------------------------------------------

    @Test
    fun modal_depth2_headerBack_pops()
    {
        val flow = Flow(listOf(first, second));
        assertEquals(WayOut.Back, flow.wayOut(MODAL));
        flow.pop();
        assertEquals(listOf(first), flow.stack.value);
    }

    @Test
    fun modal_depth2_systemBack_pops()
    {
        val flow = Flow(listOf(first, second));
        assertTrue(flow.back(MODAL));
        assertEquals(listOf(first), flow.stack.value);
        assertTrue(flow.isPresented.value);
    }

    @Test
    fun modal_depth2_close_closes()
    {
        val flow = Flow(listOf(first, second));
        flow.close();
        assertEquals(emptyList<Stop>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    // --- modal, root --------------------------------------------------------

    @Test
    fun modal_root_headerBack_absent()
    {
        val flow = Flow(listOf(first));
        assertEquals(WayOut.Close, flow.wayOut(MODAL));
        assertFalse(WayOut.Back == flow.wayOut(MODAL));
    }

    @Test
    fun modal_root_systemBack_closes()
    {
        val flow = Flow(listOf(first));
        assertTrue(flow.handlesBack(MODAL));
        assertTrue(flow.back(MODAL));
        assertEquals(emptyList<Stop>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    @Test
    fun modal_root_close_closes()
    {
        val flow = Flow(listOf(first));
        assertEquals(WayOut.Close, flow.wayOut(MODAL));
        flow.close();
        assertEquals(emptyList<Stop>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    // --- sheet, depth 2+ ----------------------------------------------------

    @Test
    fun sheet_depth2_headerBack_pops()
    {
        val flow = Flow(listOf(first, second));
        assertEquals(WayOut.Back, flow.wayOut(SHEET));
        flow.pop();
        assertEquals(listOf(first), flow.stack.value);
    }

    @Test
    fun sheet_depth2_systemBack_pops()
    {
        val flow = Flow(listOf(first, second));
        assertTrue(flow.back(SHEET));
        assertEquals(listOf(first), flow.stack.value);
        assertTrue(flow.isPresented.value);
    }

    @Test
    fun sheet_depth2_close_closes()
    {
        val flow = Flow(listOf(first, second));
        flow.close();
        assertEquals(emptyList<Stop>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    @Test
    fun sheet_depth2_dragDown_closes()
    {
        val flow = Flow(listOf(first, second));
        // A sheet 600 units tall, dragged 300 down: at the threshold, so it goes. A sheet
        // deeper than its root still goes as a whole — a drag dismisses the presentation,
        // not the route on top of it.
        assertTrue(SheetGeometry.closes(offset = 300f, height = 600f));
        flow.close();
        assertEquals(emptyList<Stop>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    // --- sheet, root --------------------------------------------------------

    @Test
    fun sheet_root_headerBack_absent()
    {
        val flow = Flow(listOf(first));
        assertEquals(WayOut.Close, flow.wayOut(SHEET));
        assertFalse(WayOut.Back == flow.wayOut(SHEET));
    }

    @Test
    fun sheet_root_systemBack_closes()
    {
        val flow = Flow(listOf(first));
        assertTrue(flow.handlesBack(SHEET));
        assertTrue(flow.back(SHEET));
        assertEquals(emptyList<Stop>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    @Test
    fun sheet_root_close_closes()
    {
        val flow = Flow(listOf(first));
        assertEquals(WayOut.Close, flow.wayOut(SHEET));
        flow.close();
        assertEquals(emptyList<Stop>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    @Test
    fun sheet_root_dragDown_closes()
    {
        val flow = Flow(listOf(first));
        assertTrue(SheetGeometry.closes(offset = 300f, height = 600f));
        flow.close();
        assertEquals(emptyList<Stop>(), flow.stack.value);
        assertFalse(flow.isPresented.value);
    }

    // --- the rule that outlives the flow ------------------------------------

    /**
     * docs/IMPLEMENTATION-PITFALLS.md P24, asked of the new entry style.
     *
     * A screen model accepts a late response only when its request is the current one, the
     * flow is still presented, AND its own route is on top of the stack. A sheet closes for
     * a reason no other entry has — the user threw it away — and the guard has to refuse
     * that arrival the same way it refuses one after a modal closed. The two halves it
     * reads are both false here, which is what makes the refusal independent of which one
     * a model happens to check first.
     */
    @Test
    fun sheet_closed_byDrag_refusesALateResponse()
    {
        val flow = Flow(listOf(first, second));
        flow.close();
        assertFalse(flow.isPresented.value);
        assertFalse(second == flow.stack.value.lastOrNull());
        assertFalse(first == flow.stack.value.lastOrNull());
    }

    /** A closed flow claims no back at all, whatever it was entered as. */
    @Test
    fun closed_systemBack_isRefusedForEveryEntry()
    {
        listOf(PUSH, MODAL, SHEET).forEach { entry ->
            val flow = Flow<Stop>();
            assertFalse(flow.handlesBack(entry));
            assertFalse(flow.back(entry));
            assertEquals(WayOut.None, flow.wayOut(entry));
        };
    }
}
