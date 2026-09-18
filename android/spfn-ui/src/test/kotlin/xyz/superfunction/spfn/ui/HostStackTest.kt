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
    fun sync_twoFlowsPushingInTurn_interleavesThemInTheOrderTheyArrived()
    {
        val first = Any();
        val second = Any();

        var stack = HostStack();
        stack = stack.sync(first, listOf(Halt("a1")));
        stack = stack.sync(second, listOf(Halt("b1")));
        stack = stack.sync(first, listOf(Halt("a1"), Halt("a2")));
        stack = stack.sync(second, listOf(Halt("b1"), Halt("b2")));

        // Four pushes in four turns, so four entries in those four turns: the stack is the
        // order a person pushed, not the owners gathered into two blocks. Grouping them
        // would have put a2 under b1 while a2 is what its own flow believes is on top.
        assertEquals(
            listOf(Halt("a1"), Halt("b1"), Halt("a2"), Halt("b2")),
            stack.entries.map { it.route }
        );
        assertEquals(listOf(first, second, first, second), stack.entries.map { it.owner });
    }

    @Test
    fun sync_pushFromACoveredFlow_landsOnTop()
    {
        val first = Any();
        val second = Any();

        var stack = HostStack();
        stack = stack.sync(first, listOf(Halt("a1")));
        stack = stack.sync(second, listOf(Halt("b1")));
        // The first flow is covered by the second and pushes anyway. What the host draws has
        // to be what that flow now believes is its top, or the two disagree about the screen
        // in front of the person and a system back is spent on the wrong flow.
        stack = stack.sync(first, listOf(Halt("a1"), Halt("a2")));

        assertEquals(listOf(Halt("a1"), Halt("b1"), Halt("a2")), stack.entries.map { it.route });
        assertEquals(first, stack.topOwner());
    }

    @Test
    fun sync_popFromACoveredFlow_removesItInPlace()
    {
        val first = Any();
        val second = Any();

        var stack = HostStack();
        stack = stack.sync(first, listOf(Halt("a1"), Halt("a2")));
        stack = stack.sync(second, listOf(Halt("b1")));
        stack = stack.sync(first, listOf(Halt("a1")));

        // a2 left from under b1, and b1 did not move for it: nothing about the second flow
        // changed, so nothing about where it stands does either.
        assertEquals(listOf(Halt("a1"), Halt("b1")), stack.entries.map { it.route });
        assertEquals(second, stack.topOwner());
    }

    @Test
    fun sync_replacingATail_keepsThePrefixInPlace()
    {
        val first = Any();
        val second = Any();

        var stack = HostStack();
        stack = stack.sync(first, listOf(Halt("a1"), Halt("a2")));
        stack = stack.sync(second, listOf(Halt("b1")));
        stack = stack.sync(first, listOf(Halt("a1"), Halt("a3")));

        // a1 is shared with what was there and stays where it was; a2 is gone and a3 is new,
        // so a3 goes on top — a route pushed now is above everything pushed before it.
        assertEquals(listOf(Halt("a1"), Halt("b1"), Halt("a3")), stack.entries.map { it.route });
        assertEquals(listOf(first, second, first), stack.entries.map { it.owner });
    }

    @Test
    fun sync_emptyRoutes_removesEveryEntryOfThatOwner()
    {
        val first = Any();
        val second = Any();

        var stack = HostStack();
        stack = stack.sync(first, listOf(Halt("a1")));
        stack = stack.sync(second, listOf(Halt("b1")));
        stack = stack.sync(first, listOf(Halt("a1"), Halt("a2")));
        // Interleaved, so the closing flow's entries are not one run to cut out.
        stack = stack.sync(first, emptyList());

        assertEquals(listOf<Any>(Halt("b1")), stack.entries.map { it.route });
        assertEquals(listOf(second), stack.entries.map { it.owner });
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

    /**
     * The same route value pushed twice is two entries on this list, and one screen on the
     * device. The list is right and the device is the layer this suite cannot reach.
     *
     * `HostStack` is a list and nothing else: two equal values pushed in turn are two
     * entries, in the order they arrived, and `shortened` reports two of them going. What
     * draws them does not agree. Navigation 3's `NavDisplay` identifies a back-stack entry by
     * its VALUE — that is how it keeps a `NavEntry`'s saved state across a recomposition —
     * so two equal entries are one entry to it, and the person sees one screen where the flow
     * believes it has two. The system back then pops the flow to depth 1 while the display
     * was already showing depth 1, and a screen the person never saw leaves without moving.
     *
     * SwiftUI's `NavigationStack(path:)` is an ARRAY of values and does not de-duplicate, so
     * the iOS half of this flow shows two screens. The divergence is real and it is below
     * what any automated test in this repository touches: there is no Compose runtime in this
     * suite, no instrumented suite at all, and a Maestro cell asserting "one screen" or "two"
     * would be asserting against a readout both screens produce.
     *
     * So this case documents the list's answer and names the layer that disagrees
     * (docs/IMPLEMENTATION-PITFALLS.md P41). A flow that can push the same route value twice
     * carries the route's identity in the value — an id, an index — rather than relying on
     * the navigator to tell two of them apart.
     */
    @Test
    fun sync_theSameRouteValueTwice_isTwoEntriesOnTheList()
    {
        val owner = Any();

        var stack = HostStack();
        stack = stack.sync(owner, listOf(Halt("a1")));
        stack = stack.sync(owner, listOf(Halt("a1"), Halt("a1")));

        assertEquals(listOf<Any>(Halt("a1"), Halt("a1")), stack.entries.map { it.route });
        assertEquals(listOf(owner, owner), stack.entries.map { it.owner });
        assertEquals(owner, stack.topOwner());
        // Both of them go when the platform cuts back to the host's own root, which is the
        // arithmetic a de-duplicating navigator would have made wrong by one.
        assertEquals(mapOf(owner to 2), stack.shortened(0));
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
