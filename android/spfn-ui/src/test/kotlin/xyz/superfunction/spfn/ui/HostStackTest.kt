// SPFN Mobile — the host stack's reconciliation, one test per rule.
//
// Counterpart of Tests/SPFNUITests/HostStackTests.swift, case for case and name for name.
// `HostStack` is what makes decision N1 possible — a pushed flow appends to the host's stack
// instead of drawing its own over it — and everything it gets wrong is invisible on a device
// until two flows are on one stack at once. So the cases below are written about the LIST
// rather than about a navigator, which is why they run here at all: this type imports no
// toolkit and this file needs no emulator.

package xyz.superfunction.spfn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private data class Halt(val name: String)

class HostStackTest
{
    @Test
    fun sync_twoFlowsPushingInTurn_keepsEachOwnersRoutesInOrder()
    {
        val first = Any();
        val second = Any();

        var stack = HostStack();
        stack = stack.sync(first, listOf(Halt("a1")));
        stack = stack.sync(second, listOf(Halt("b1")));
        stack = stack.sync(first, listOf(Halt("a1"), Halt("a2")));
        stack = stack.sync(second, listOf(Halt("b1"), Halt("b2")));

        // Each owner's routes stand together and in order, and the owner that arrived first
        // is still in front: a push is not a reason to jump the flow underneath.
        assertEquals(
            listOf(Halt("a1"), Halt("a2"), Halt("b1"), Halt("b2")),
            stack.entries.map { it.route }
        );
        assertEquals(listOf(first, first, second, second), stack.entries.map { it.owner });
    }

    @Test
    fun sync_closingOneFlow_leavesTheOtherFlowsEntriesStanding()
    {
        val first = Any();
        val second = Any();

        var stack = HostStack();
        stack = stack.sync(first, listOf(Halt("a1"), Halt("a2")));
        stack = stack.sync(second, listOf(Halt("b1")));
        stack = stack.sync(first, emptyList());

        assertEquals(listOf<Any>(Halt("b1")), stack.entries.map { it.route });
        assertEquals(second, stack.topOwner());
    }

    @Test
    fun shortened_cuttingThreeFromTheTail_splitsThemTwoAndOne()
    {
        val first = Any();
        val second = Any();

        var stack = HostStack();
        stack = stack.sync(first, listOf(Halt("a1"), Halt("a2")));
        stack = stack.sync(second, listOf(Halt("b1"), Halt("b2")));

        // Four entries cut to one: the tail is a2, b1, b2 — one of the first owner's and
        // two of the second's. A count alone could not say that, which is the whole reason
        // this answers per owner.
        assertEquals(mapOf(first to 1, second to 2), stack.shortened(1));
        assertEquals(mapOf(second to 2), stack.shortened(2));
        // Not a shortening at all, and therefore nothing to report.
        assertEquals(emptyMap<Any, Int>(), stack.shortened(4));
        assertEquals(emptyMap<Any, Int>(), stack.shortened(9));
    }

    @Test
    fun anEmptyStack_dropsNothingAndHasNoTopOwner()
    {
        val stack = HostStack();
        assertEquals(emptyList<HostEntry>(), stack.entries);
        assertEquals(emptyMap<Any, Int>(), stack.shortened(0));
        assertNull(stack.topOwner());
    }
}
