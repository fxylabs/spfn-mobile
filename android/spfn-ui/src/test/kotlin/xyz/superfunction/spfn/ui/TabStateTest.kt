// SPFN Mobile — the tab state's table, one test per row that is pure state.
//
// Counterpart of Tests/SPFNUITests/TabStateTests.swift, case for case and name for name. The
// rows are docs/architecture/tab-host-design.md §4 and §9-1, written out here from that table
// rather than read off this implementation: the selection a press makes, the pop and the
// scroll it asks for, the back a tab's root takes, and the refusals. Each test names the
// design's cell it stands for.
//
// | selected | depth | act           | result                                  | cell  |
// | start    | 0     | select(other) | other selected, Switched                | C-1   |
// | start    | 2     | select(start) | PopToRoot, nothing selected anew        | C-13  |
// | start    | 0     | select(start) | ScrollToTop, count 0 → 1                | C-14  |
// | start    | any   | select(nope)  | Ignored, nothing changes                | —     |
// | start    | 1     | show(other)   | other selected, no pop, no scroll       | C-15  |
// | other    | 0     | back          | start selected, consumed                | C-9   |
// | start    | 0     | back          | not consumed (the activity finishes)    | C-10  |
// | any      | 1     | back          | not consumed (the tab's navigator pops) | C-7   |

package xyz.superfunction.spfn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOME: String = "home";
private const val ACCOUNT: String = "account";
private const val SETTINGS: String = "settings";

class TabStateTest
{
    @Test
    fun init_empty_refused()
    {
        val refusal = assertThrows(IllegalArgumentException::class.java) { TabState(emptyList()) };
        assertEquals("a tab state needs at least one tab", refusal.message);
    }

    @Test
    fun init_duplicate_refused()
    {
        val refusal = assertThrows(IllegalArgumentException::class.java) { TabState(listOf(HOME, ACCOUNT, HOME)) };
        assertEquals("tab 'home' is declared twice", refusal.message);
    }

    @Test
    fun init_unknownSelected_refused()
    {
        val refusal = assertThrows(IllegalArgumentException::class.java) {
            TabState(listOf(HOME, ACCOUNT), selected = SETTINGS);
        };
        assertEquals("tab 'settings' is not one of [home, account]", refusal.message);
    }

    @Test
    fun init_firstIsStartAndSelected()
    {
        val state = TabState(listOf(HOME, ACCOUNT));
        assertEquals(listOf(HOME, ACCOUNT), state.tabs);
        assertEquals(HOME, state.start);
        assertEquals(HOME, state.selected.value);

        // A launch that restores another tab still has the first as its start.
        val restored = TabState(listOf(HOME, ACCOUNT), selected = ACCOUNT);
        assertEquals(HOME, restored.start);
        assertEquals(ACCOUNT, restored.selected.value);
    }

    @Test
    fun select_other_switches()
    {
        val state = TabState(listOf(HOME, ACCOUNT));
        assertEquals(TabSelection.Switched, state.select(ACCOUNT, depth = 0));
        assertEquals(ACCOUNT, state.selected.value);
        // A switch is not a press on the tab it left, so neither tab's count moved.
        assertEquals(0, state.scrollToTop(HOME));
        assertEquals(0, state.scrollToTop(ACCOUNT));
    }

    @Test
    fun select_other_atDepth_switchesWithoutPopping()
    {
        // The depth is the SELECTED tab's, and a switch leaves that stack where it is: coming
        // back finds the detail as it was left (decision Q-D).
        val state = TabState(listOf(HOME, ACCOUNT));
        assertEquals(TabSelection.Switched, state.select(ACCOUNT, depth = 2));
        assertEquals(ACCOUNT, state.selected.value);
    }

    @Test
    fun select_current_atDepth_popsToRoot()
    {
        val state = TabState(listOf(HOME, ACCOUNT));
        assertEquals(TabSelection.PopToRoot, state.select(HOME, depth = 2));
        assertEquals(HOME, state.selected.value);
        // The pop is the host's; asking for it is not a scroll.
        assertEquals(0, state.scrollToTop(HOME));
    }

    @Test
    fun select_current_atRoot_scrollsToTop_countsUp()
    {
        val state = TabState(listOf(HOME, ACCOUNT));
        assertEquals(TabSelection.ScrollToTop, state.select(HOME, depth = 0));
        assertEquals(1, state.scrollToTop(HOME));
        assertEquals(TabSelection.ScrollToTop, state.select(HOME, depth = 0));
        assertEquals(2, state.scrollToTop(HOME));
        // One tab's count is that tab's alone.
        assertEquals(0, state.scrollToTop(ACCOUNT));
        assertEquals(HOME, state.selected.value);
    }

    @Test
    fun select_unknown_ignored()
    {
        val state = TabState(listOf(HOME, ACCOUNT));
        assertEquals(TabSelection.Ignored, state.select(SETTINGS, depth = 0));
        assertEquals(TabSelection.Ignored, state.select(SETTINGS, depth = 3));
        assertEquals(HOME, state.selected.value);
        assertEquals(0, state.scrollToTop(SETTINGS));
    }

    @Test
    fun show_never_popsOrScrolls()
    {
        val state = TabState(listOf(HOME, ACCOUNT));
        state.show(ACCOUNT);
        assertEquals(ACCOUNT, state.selected.value);
        // The tab already selected: nothing, and in particular no scroll request.
        state.show(ACCOUNT);
        assertEquals(ACCOUNT, state.selected.value);
        assertEquals(0, state.scrollToTop(ACCOUNT));
        // Not a tab: nothing.
        state.show(SETTINGS);
        assertEquals(ACCOUNT, state.selected.value);
        // And back again, as C-15 does.
        state.show(HOME);
        assertEquals(HOME, state.selected.value);
        assertEquals(0, state.scrollToTop(HOME));
    }

    @Test
    fun back_nonStartRoot_selectsStart()
    {
        val state = TabState(listOf(HOME, ACCOUNT, SETTINGS), selected = SETTINGS);
        assertTrue(state.back(depth = 0));
        // The START tab, not the one visited before: tab history is not retraced (Q-B).
        assertEquals(HOME, state.selected.value);
    }

    @Test
    fun back_startRoot_notHandled()
    {
        val state = TabState(listOf(HOME, ACCOUNT));
        assertFalse(state.back(depth = 0));
        assertEquals(HOME, state.selected.value);
    }

    @Test
    fun back_atDepth_notHandled()
    {
        val state = TabState(listOf(HOME, ACCOUNT), selected = ACCOUNT);
        assertFalse(state.back(depth = 1));
        assertEquals(ACCOUNT, state.selected.value);
        // C-33: two backs above the root are the tab's own; the third is this state's.
        assertFalse(state.back(depth = 2));
        assertTrue(state.back(depth = 0));
        assertEquals(HOME, state.selected.value);
    }

    @Test
    fun handlesBack_matches_back()
    {
        listOf(HOME, ACCOUNT).forEach { selected ->
            (0..2).forEach { depth ->
                val asked = TabState(listOf(HOME, ACCOUNT), selected = selected);
                val acted = TabState(listOf(HOME, ACCOUNT), selected = selected);
                assertEquals("selected $selected, depth $depth", asked.handlesBack(depth), acted.back(depth));
            };
        };
    }

    @Test
    fun owners_firstTabKeepsAFlow()
    {
        val flow = Any();
        val other = Any();
        val owners = TabOwners();

        assertTrue(owners.claim(flow, HOME));
        // The same tab registering again — every recomposition does — is not a second claim.
        assertTrue(owners.claim(flow, HOME));
        // Another tab asking for the same flow is refused, and the first keeps it.
        assertFalse(owners.claim(flow, ACCOUNT));
        assertTrue(owners.claim(flow, HOME));
        // A different flow is free for any tab.
        assertTrue(owners.claim(other, ACCOUNT));
    }
}
