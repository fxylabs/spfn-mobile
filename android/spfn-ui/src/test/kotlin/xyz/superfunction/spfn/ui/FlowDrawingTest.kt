// SPFN Mobile — what FlowHost draws a flow as, and above all what it draws a CLOSING one as.
//
// The case that matters is the one where two answers are true at once. A sheet whose flow has
// just closed is a sheet and has an empty stack, and the sheet has to win: it still has a
// slide to run, and a flow answered with nothing leaves the composition on the frame its
// stack empties and vanishes (docs/IMPLEMENTATION-PITFALLS.md P38). Nothing on a device run
// can fail on it — a runner waits for an element to appear or to go, never asks how it went —
// so this is the reader of that order.

package xyz.superfunction.spfn.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FlowDrawingTest
{
    private val sheet = FlowEntry.Sheet(SheetDetent.Fit)

    @Test
    fun `a sheet whose stack has emptied is still drawn as the sheet`()
    {
        assertEquals(FlowDrawing.Sheet(sheet), flowDrawing(sheet, hosted = false, empty = true));
        assertEquals(FlowDrawing.Sheet(sheet), flowDrawing(sheet, hosted = true, empty = true));
    }

    @Test
    fun `a modal whose stack has emptied is still drawn as its cover`()
    {
        assertEquals(FlowDrawing.Modal, flowDrawing(FlowEntry.Modal, hosted = false, empty = true));
    }

    @Test
    fun `an open sheet and an open modal are drawn as themselves`()
    {
        assertEquals(FlowDrawing.Sheet(sheet), flowDrawing(sheet, hosted = false, empty = false));
        assertEquals(FlowDrawing.Modal, flowDrawing(FlowEntry.Modal, hosted = true, empty = false));
    }

    @Test
    fun `a push appends to a host whenever there is one, open or closed`()
    {
        assertEquals(FlowDrawing.Appended, flowDrawing(FlowEntry.Push, hosted = true, empty = false));
        assertEquals(FlowDrawing.Appended, flowDrawing(FlowEntry.Push, hosted = true, empty = true));
    }

    @Test
    fun `a push with no host draws its own stack, and nothing once that stack is empty`()
    {
        assertEquals(FlowDrawing.Inline, flowDrawing(FlowEntry.Push, hosted = false, empty = false));
        assertEquals(FlowDrawing.Blank, flowDrawing(FlowEntry.Push, hosted = false, empty = true));
    }
}
