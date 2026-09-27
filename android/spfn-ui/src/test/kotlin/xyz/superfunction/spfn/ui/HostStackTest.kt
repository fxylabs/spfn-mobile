// SPFN Mobile — the host stack's reconciliation, one test per rule.
//
// Counterpart of Tests/SPFNUITests/HostStackTests.swift, case for case and name for name.
// `HostStack` is what makes decision N1 possible — a pushed flow appends to the host's stack
// instead of drawing its own over it — and everything it gets wrong is invisible on a device
// until two flows are on one stack at once. So the cases below are written about the LIST
// rather than about a navigator, which is why they run here at all: this type imports no
// toolkit and this file needs no emulator.

package xyz.superfunction.spfn.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
    fun shortened_toZero_dropsEveryOwner()
    {
        val first = Any();
        val second = Any();

        var stack = HostStack();
        stack = stack.sync(first, listOf(Halt("a1")));
        stack = stack.sync(second, listOf(Halt("b1"), Halt("b2")));
        stack = stack.sync(first, listOf(Halt("a1"), Halt("a2")));

        // What a tab's pop to its root is (docs/architecture/tab-host-design.md §3-4, C-13):
        // the same cut a platform makes back to the host's root, so every flow on the stack
        // is told every route it lost, and a pushed flow told to go back past its root closes.
        assertEquals(mapOf(first to 2, second to 2), stack.shortened(0));
    }

    @Test
    fun store_shorten_spendsOneBackPerLostRoutePerOwner()
    {
        // Unconfined, so a registration's collector syncs at once and the test reads the
        // store the moment it returns: nothing here is about timing.
        val store = HostStackStore(CoroutineScope(Dispatchers.Unconfined));
        val first = Any();
        val second = Any();
        val firstRoutes = MutableStateFlow<List<Any>>(listOf(Halt("a1"), Halt("a2")));
        val secondRoutes = MutableStateFlow<List<Any>>(listOf(Halt("b1")));
        val backs = mutableMapOf(first to 0, second to 0);
        store.register(first, firstRoutes, HostRegistration(screen = {}, back = { backs[first] = backs.getValue(first) + 1 }));
        store.register(second, secondRoutes, HostRegistration(screen = {}, back = { backs[second] = backs.getValue(second) + 1 }));
        assertEquals(3, store.depth);

        store.shorten(0);

        // The fakes do not move their stacks, so the store's list stands where it was: the
        // flows are what shorten it, and this counts what each was TOLD.
        assertEquals(mapOf(first to 2, second to 1), backs.toMap());
    }

    @Test
    fun store_refusesAnOwnerItsClaimRefuses()
    {
        // One flow on two tabs' stores: the second store's claim says no, so the flow is
        // neither registered there nor synced there (docs/architecture/tab-host-design.md §2-3).
        val owners = TabOwners();
        val home = HostStackStore(CoroutineScope(Dispatchers.Unconfined)) { owners.claim(it, "home") };
        val account = HostStackStore(CoroutineScope(Dispatchers.Unconfined)) { owners.claim(it, "account") };
        val flow = Any();
        val routes = MutableStateFlow<List<Any>>(listOf(Halt("a1")));

        home.register(flow, routes, HostRegistration(screen = {}, back = {}));
        account.register(flow, routes, HostRegistration(screen = {}, back = {}));
        account.sync(flow, routes.value);

        assertEquals(1, home.depth);
        assertEquals(0, account.depth);
        assertEquals(emptyList<HostEntry>(), account.stack.value.entries);
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
