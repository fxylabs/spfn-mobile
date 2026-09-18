// SPFN Mobile — a sheet's round trip, one test per cell of every table it has.
//
// There is no Compose runtime in this suite and there is no device on this machine, so what is
// checked here is `SheetPhase` and nothing around it. That is the whole reason the phase is a
// file of its own: the composable that spends it needs a frame clock and an
// `AnchoredDraggableState`, and neither of those is what got this wrong on a phone
// (docs/IMPLEMENTATION-PITFALLS.md P38).
//
// The first four cases are the rules a person watching a Galaxy Z Flip4 either saw broken or
// would have:
//
//   a. a sheet resting at Hidden before it has stood up is not a sheet the user closed;
//   b. a sheet resting at Hidden after it stood up IS;
//   c. being asked to close is not being closed — only an arrival is;
//   d. a sheet asked to open while it is leaving turns around.
//
// Everything under them is the TABLES, written out cell by cell and named after the cell.
// Four rules read four of the twelve cells `asked(measured, open)` has, and a table read in
// part is a table whose other cells can be changed without anything going red — which for a
// phase machine is the same as not being tested, because the cell nobody wrote down is the
// one a refactor gets wrong. `asked` has twelve cells: `Unmeasured` answers four (measured or
// not, asked open or closed) and the four other phases answer two each. `arrived()` has five
// and three of them are no-ops. `draggable` has five and one of them is true.

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

    // --- the `asked(measured, open)` table, cell by cell ----------------------
    //
    // Named for the cell rather than for the rule, so that a cell nobody wrote is a cell
    // nobody can point at. The four rules above assert the same arithmetic through the
    // sentences it is FOR; these assert that the table is the whole table.

    /**
     * Unmeasured waits, and it is the only phase that does.
     *
     * With no anchors there is nowhere to animate to and no distance to animate over, so the
     * answer to both questions is "ask again when there is".
     */
    @Test
    fun unmeasured_notMeasured_open_waits()
    {
        assertEquals(
            SheetPhase.Unmeasured,
            SheetPhase.Unmeasured.asked(measured = false, open = true)
        );
    }

    @Test
    fun unmeasured_notMeasured_closed_waits()
    {
        assertEquals(
            SheetPhase.Unmeasured,
            SheetPhase.Unmeasured.asked(measured = false, open = false)
        );
    }

    @Test
    fun unmeasured_measured_open_rises()
    {
        assertEquals(
            SheetPhase.Rising,
            SheetPhase.Unmeasured.asked(measured = true, open = true)
        );
    }

    /**
     * A sheet closed before it ever stood up has nothing to slide out of.
     *
     * The one cell that skips `Falling` entirely: a fall is a travel between two anchors and
     * this sheet never held the first of them, so animating it would be sliding a surface
     * away from a position it was never drawn at.
     */
    @Test
    fun unmeasured_measured_closed_goesStraightToGone()
    {
        assertEquals(
            SheetPhase.Gone,
            SheetPhase.Unmeasured.asked(measured = true, open = false)
        );
    }

    @Test
    fun rising_open_keepsRising()
    {
        assertEquals(SheetPhase.Rising, SheetPhase.Rising.asked(measured = true, open = true));
    }

    @Test
    fun rising_closed_falls()
    {
        assertEquals(SheetPhase.Falling, SheetPhase.Rising.asked(measured = true, open = false));
    }

    @Test
    fun standing_open_keepsStanding()
    {
        assertEquals(SheetPhase.Standing, SheetPhase.Standing.asked(measured = true, open = true));
    }

    @Test
    fun standing_closed_falls()
    {
        assertEquals(SheetPhase.Falling, SheetPhase.Standing.asked(measured = true, open = false));
    }

    @Test
    fun falling_open_rises()
    {
        assertEquals(SheetPhase.Rising, SheetPhase.Falling.asked(measured = true, open = true));
    }

    @Test
    fun falling_closed_keepsFalling()
    {
        assertEquals(SheetPhase.Falling, SheetPhase.Falling.asked(measured = true, open = false));
    }

    /**
     * A sheet re-opened after it has gone stands back up rather than staying out of sight.
     *
     * The frame between a sheet's departure and the host noticing is a real frame, and a flow
     * re-opened inside it would otherwise be a flow whose sheet never comes back.
     */
    @Test
    fun gone_open_rises()
    {
        assertEquals(SheetPhase.Rising, SheetPhase.Gone.asked(measured = true, open = true));
    }

    @Test
    fun gone_closed_staysGone()
    {
        assertEquals(SheetPhase.Gone, SheetPhase.Gone.asked(measured = true, open = false));
    }

    // --- the `arrived()` table: two travels end, three phases have none -------
    //
    // `arrived` is called by the travel itself, after its `animateTo` returns. Only `Rising`
    // and `Falling` ARE travels, so the other three answer themselves — and that is worth
    // asserting rather than assuming, because a version of this that advanced an unmeasured
    // sheet on any arrival would stand it up before it had anchors to stand between.

    @Test
    fun arrived_unmeasured_isNoChange()
    {
        assertEquals(SheetPhase.Unmeasured, SheetPhase.Unmeasured.arrived());
    }

    @Test
    fun arrived_standing_isNoChange()
    {
        assertEquals(SheetPhase.Standing, SheetPhase.Standing.arrived());
    }

    @Test
    fun arrived_gone_isNoChange()
    {
        assertEquals(SheetPhase.Gone, SheetPhase.Gone.arrived());
    }

    // --- the `draggable` table: one phase of five --------------------------------
    //
    // A drag cancels whatever `animateTo` is running and a cancelled travel never reaches the
    // line that advances the phase, so a sheet grabbed mid-rise would be left in a phase that
    // no longer describes it and one grabbed mid-fall would be pulled back into a flow that
    // has already closed. Only a sheet that has finished standing up is anybody's to drag, and
    // the four refusals are the half of that sentence a single `Standing` assertion misses.

    @Test
    fun draggable_isTrueForStandingAndForNothingElse()
    {
        assertTrue("a standing sheet is the one a finger may take over", SheetPhase.Standing.draggable);
        assertFalse("a sheet with no anchors has nowhere to be dragged", SheetPhase.Unmeasured.draggable);
        assertFalse("a sheet mid-rise would be left in a phase that no longer describes it", SheetPhase.Rising.draggable);
        assertFalse("a sheet mid-fall would be pulled back into a flow that has closed", SheetPhase.Falling.draggable);
        assertFalse("a sheet that has gone is not on screen to grab", SheetPhase.Gone.draggable);
    }
}
