// SPFN Mobile — the tab table's two JVM cells.
//
// Cells covered here, which is every tab cell whose runner is `unit`:
//
//   tabs-c13  tabs-c15
//
// Both are rows of docs/architecture/tab-host-design.md §4 that no device runner can reach.
// C-13 is a press on the selected tab while it stands above its root — and above its root the
// bar is not on screen, because it is part of the root. C-15 is the app switching tabs in
// code, which no control in the example does. So both are driven here, on the example's own
// container, against `TabState`: the bar's press and the app's `show` are exactly these calls.
//
// The expected readouts are read out of the table (CaseTableReader), never written here, for
// the reason CellTest.kt gives (P10). The depth a tab host would pass is its stack's length,
// which with one flow on the tab is that flow's own stack.

package xyz.superfunction.spfn.example

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.superfunction.spfn.example.generated.AppContainer
import xyz.superfunction.spfn.ui.FlowEntry
import xyz.superfunction.spfn.ui.TabSelection

class TabCellTest
{
    @Test
    fun `tabs-c13 a press on the selected tab above its root asks its host to pop to the root`()
    {
        val container = AppContainer(Fixtures.ready().service());
        Flows.openTabs(container, TabLaunch("home", "itemDetail", 2));

        val asked = container.tabs.select("home", depth = container.itemDetailFlow.stack.value.size);

        assertEquals(TabSelection.PopToRoot, asked);
        assertEquals("home", container.tabs.selected.value);
        // The pop the host performs is the store's `shorten(0)`: one `Flow.back` per route the
        // flow loses, and past its root a pushed flow's back closes it (N2).
        repeat(container.itemDetailFlow.stack.value.size) { container.itemDetailFlow.back(FlowEntry.Push) };
        assertEquals(CaseTableReader.expect("tabs-c13"), listOf("stack=${container.itemDetailFlow.stack.value.size}"));
    }

    @Test
    fun `tabs-c15 show selects another tab and leaves the one it leaves where it stood`()
    {
        val container = AppContainer(Fixtures.ready().service());
        Flows.openTabs(container, TabLaunch("home", "itemDetail", 1));

        container.tabs.show("account");
        assertEquals("account", container.tabs.selected.value);
        assertEquals(0, container.tabs.scrollToTop("account"));

        container.tabs.show("home");
        assertEquals("home", container.tabs.selected.value);
        assertEquals(CaseTableReader.expect("tabs-c15"), listOf("stack=${container.itemDetailFlow.stack.value.size}"));
    }
}
