// SPFN Mobile — the four rules a sheet's round trip is made of.
//
// There is no Compose runtime in this suite and there is no device on this machine, so what is
// checked here is `SheetPhase` and nothing around it. That is the whole reason the phase is a
// file of its own: the composable that spends it needs a frame clock and an
// `AnchoredDraggableState`, and neither of those is what got this wrong on a phone
// (docs/IMPLEMENTATION-PITFALLS.md P38).
//
// One cell per rule, and each is a rule a person watching a Galaxy Z Flip4 either saw broken
// or would have:
//
//   a. a sheet resting at Hidden before it has stood up is not a sheet the user closed;
//   b. a sheet resting at Hidden after it stood up IS;
//   c. being asked to close is not being closed — only an arrival is;
//   d. a sheet asked to open while it is leaving turns around.

package xyz.superfunction.spfn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetPhaseTest
{
    /**
     * a. Hidden is where a sheet starts, and it is where it goes on its way up.
     *
     * Both are settled readings of `SheetAnchor.Hidden` taken from a sheet the user has not
     * touched, and the version of this file that read them as a dismissal closed every sheet
     * on the frame it was composed.
     */
    @Test
    fun `Hidden is not a dismissal until the sheet has stood up`()
    {
        assertFalse(SheetPhase.Unmeasured.dismisses(SheetAnchor.Hidden));

        val rising = SheetPhase.Unmeasured.asked(measured = true, open = true);
        assertEquals(SheetPhase.Rising, rising);
        assertFalse(rising.dismisses(SheetAnchor.Hidden));
    }

    /**
     * b. Once the rise has arrived, an arrival at Hidden is the user's word.
     *
     * The drag is the only thing that can put a standing sheet there, which is what makes the
     * reading safe as soon as — and only as soon as — the rise completed.
     */
    @Test
    fun `Hidden is a dismissal once the rise has arrived`()
    {
        val standing = SheetPhase.Rising.arrived();
        assertEquals(SheetPhase.Standing, standing);
        assertTrue(standing.dismisses(SheetAnchor.Hidden));
        assertFalse(standing.dismisses(SheetAnchor.Open));
    }

    /**
     * c. The host's request starts a fall; only the fall's arrival ends the sheet.
     *
     * `Gone` is the one phase the host stops drawing at, and nothing but an arrival out of
     * `Falling` reaches it — which is the difference between a sheet that slides away and a
     * sheet that disappears.
     */
    @Test
    fun `a close request removes nothing until the fall arrives`()
    {
        val falling = SheetPhase.Standing.asked(measured = true, open = false);
        assertEquals(SheetPhase.Falling, falling);
        assertEquals(SheetAnchor.Hidden, falling.destination);
        assertEquals(SheetPhase.Falling, falling.asked(measured = true, open = false));
        assertEquals(SheetPhase.Gone, falling.arrived());
    }

    /**
     * d. A sheet re-opened while it is leaving turns around where it stands.
     *
     * `Rising` is what the composable spends on a fresh `animateTo(Open)`, and that call
     * cancels the fall rather than queueing behind it.
     */
    @Test
    fun `a sheet asked to open while it is falling turns back`()
    {
        val falling = SheetPhase.Standing.asked(measured = true, open = false);
        val again = falling.asked(measured = true, open = true);
        assertEquals(SheetPhase.Rising, again);
        assertEquals(SheetAnchor.Open, again.destination);
        assertFalse("a sheet on its way out is nobody's to drag", falling.draggable);
    }
}
