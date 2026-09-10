// SPFN Mobile — the paged state machine, cell for cell.
//
// Counterpart of Tests/SPFNUITests/PagedTests.swift, cell for cell. Every test below is
// named for a cell of the approved table (P1–P9) so a disagreement between the two platforms
// is one line on each side with the same name.
//
// The expectations were written from the rule and typed out, not printed from the
// implementation (docs/IMPLEMENTATION-PITFALLS.md P10). Rows are strings because what a row
// IS does not enter the arithmetic; what does is how many there are and which of the two
// states each transition leaves behind.

package xyz.superfunction.spfn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnErrorEnvelope

private val ENVELOPE = SpfnErrorEnvelope("CONFLICT", "server text", "req-1");
private val OTHER = SpfnErrorEnvelope("UNAVAILABLE", "server text", "req-2");

/**
 * Nothing read yet, at the row type this suite uses.
 *
 * The declared type is what makes the transitions callable: `Paged.loading` is a
 * `Paged<Nothing>`, and a `firstPage` on THAT would take a `List<Nothing>`. A screen model
 * declares `Paged<Row>` and assigns this to it, which is the same line.
 */
private fun noRowsYet(): Paged<String> = Paged.loading;

/** Three rows read, a cursor for the next page, nothing in flight. */
private fun threeReadyWithMore(): Paged<String> =
    noRowsYet().firstPage(listOf("a", "b", "c"), "cursor-1");

class PagedTest
{
    // P1 — Loading -> firstPage(3, next) -> Ready(3), hasMore, more Idle.
    @Test
    fun `P1 a first page with a cursor is ready and has more`()
    {
        val state = noRowsYet().firstPage(listOf("a", "b", "c"), "cursor-1");

        assertEquals(Loadable.Ready(listOf("a", "b", "c")), state.page);
        assertTrue(state.hasMore);
        assertEquals(Busy.Idle, state.more);
        assertTrue(state.canLoadMore);
    }

    // P2 — Loading -> firstPage(0, null) -> Empty, hasMore false.
    //
    // The second vector is the server bug the type refuses to carry: no rows AND a cursor.
    // There is nothing on screen for a further page to be appended to, so the cursor is
    // dropped rather than offered.
    @Test
    fun `P2 an empty first page is empty and has no more`()
    {
        val state = noRowsYet().firstPage(emptyList(), null);

        assertEquals(Loadable.Empty, state.page);
        assertFalse(state.hasMore);
        assertEquals(Busy.Idle, state.more);
        assertFalse(state.canLoadMore);

        val withCursor = noRowsYet().firstPage(emptyList(), "cursor-1");

        assertEquals(Loadable.Empty, withCursor.page);
        assertFalse(withCursor.hasMore);
        assertFalse(withCursor.canLoadMore);
    }

    // P3 — Loading -> firstPageFailed -> Error, more Idle.
    @Test
    fun `P3 a failed first page is an error with nothing in flight`()
    {
        val state = noRowsYet().firstPageFailed(ENVELOPE);

        assertEquals(Loadable.Error(ENVELOPE), state.page);
        assertEquals(Busy.Idle, state.more);
        assertFalse(state.hasMore);
        assertFalse(state.canLoadMore);
    }

    // P4 — Ready(3)+hasMore -> appending -> appended(2, null) -> Ready(5), no more, Idle.
    @Test
    fun `P4 an appended last page leaves five rows and nothing more`()
    {
        val asking = threeReadyWithMore().appending();

        assertEquals(Busy.Busy, asking.more);
        assertEquals(Loadable.Ready(listOf("a", "b", "c")), asking.page);
        assertFalse(asking.canLoadMore);

        val state = asking.appended(listOf("d", "e"), null);

        assertEquals(Loadable.Ready(listOf("a", "b", "c", "d", "e")), state.page);
        assertFalse(state.hasMore);
        assertEquals(Busy.Idle, state.more);
    }

    // P5 — Ready(3)+hasMore -> appending -> appendFailed -> Ready(3) kept, more Error.
    @Test
    fun `P5 a failed append keeps the rows and fails only the footer`()
    {
        val state = threeReadyWithMore().appending().appendFailed(ENVELOPE);

        assertEquals(Loadable.Ready(listOf("a", "b", "c")), state.page);
        assertEquals(Busy.Error(ENVELOPE), state.more);
        assertTrue(state.hasMore);
        assertTrue(state.canLoadMore);
    }

    // P6 — more=Busy -> appending -> the same value.
    @Test
    fun `P6 appending while a page is in flight is ignored`()
    {
        val inFlight = Paged(Loadable.Ready(listOf("a", "b", "c")), Busy.Busy, true);

        assertFalse(inFlight.canLoadMore);
        assertEquals(inFlight, inFlight.appending());
    }

    // P7 — hasMore=false -> appending -> the same value, canLoadMore false.
    @Test
    fun `P7 appending with nothing more to read is ignored`()
    {
        val complete = Paged(Loadable.Ready(listOf("a", "b", "c")), Busy.Idle, false);

        assertFalse(complete.canLoadMore);
        assertEquals(complete, complete.appending());
    }

    // P8 — more=Error -> appending -> appended -> Ready(5), more Idle.
    //
    // The retry path: a failed append is exactly the state the footer's control is drawn in,
    // so asking again from it has to be a legal move.
    @Test
    fun `P8 appending after a failed append is allowed and succeeds`()
    {
        val failed = threeReadyWithMore().appending().appendFailed(ENVELOPE);
        val asking = failed.appending();

        assertEquals(Busy.Busy, asking.more);

        val state = asking.appended(listOf("d", "e"), null);

        assertEquals(Loadable.Ready(listOf("a", "b", "c", "d", "e")), state.page);
        assertEquals(Busy.Idle, state.more);
        assertFalse(state.hasMore);
    }

    // P9 — Ready -> Paged.loading -> the initial value, written out here rather than read
    // back off the implementation.
    @Test
    fun `P9 the initial value is loading idle and no more`()
    {
        val ready = threeReadyWithMore();

        assertEquals(Paged<Nothing>(Loadable.Loading, Busy.Idle, false), Paged.loading);
        assertNotEquals(ready, Paged.loading);
        assertFalse(Paged.loading.canLoadMore);
    }

    // The envelope is part of the value: two appends that failed differently are two states.
    @Test
    fun `two failures with different envelopes are different states`()
    {
        val one = threeReadyWithMore().appending().appendFailed(ENVELOPE);
        val two = threeReadyWithMore().appending().appendFailed(OTHER);

        assertNotEquals(one, two);
    }
}
