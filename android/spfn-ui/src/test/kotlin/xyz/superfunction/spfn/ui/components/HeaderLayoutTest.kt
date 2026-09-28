// SPFN Mobile — where a header's title starts, one screen kind at a time.
//
// `HeaderLayout.of` is the header's start-side layout as a pure function of what the app
// passed and what the flow offers, for the reason `ScreenLayoutTest` gives: this repository
// has no Compose UI test infrastructure, so a width decided inside the composable is a width
// nothing on a JVM can read. Each case below is a kind of screen the header is drawn on,
// asserted as the title's offset from the header's start edge at the default gutter: 16dp
// with no leading slot, and with one Material 3's small top app bar — the slot 4dp from the
// edge, its mark centred at 28dp, the title at 4 + 48 + 4 = 56dp.
//
// What it does NOT prove is that `Header` spends these numbers the way their names say. That
// is a measurement on a device; this suite holds the half a refactor breaks silently.

package xyz.superfunction.spfn.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.superfunction.spfn.ui.WayOut
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

class HeaderLayoutTest
{
    private val gutter = SpfnTokens.space4;

    @Test
    fun `a flow root with no way out has no leading slot and its title starts at the gutter`()
    {
        val layout = HeaderLayout.of(gutter, appLeading = false, wayOut = WayOut.None);
        assertNull(layout.leadingSlot);
        assertNull(layout.leadingCentre);
        assertEquals(gutter, layout.edgeStart);
        assertEquals(16.dp, layout.titleStart);
    }

    @Test
    fun `a pushed screen with a back has the touch-target slot and its title starts past it`()
    {
        val layout = HeaderLayout.of(gutter, appLeading = false, wayOut = WayOut.Back);
        assertEquals(Metrics.TOUCH_TARGET, layout.leadingSlot);
        assertEquals(56.dp, layout.titleStart);
    }

    @Test
    fun `a back stands where Material's navigation icon stands`()
    {
        val layout = HeaderLayout.of(gutter, appLeading = false, wayOut = WayOut.Back);
        assertEquals(4.dp, layout.edgeStart);
        assertEquals(28.dp, layout.leadingCentre);
        assertEquals(gutter, layout.leadingCentre!! - Metrics.MARK_SIZE / 2);
    }

    @Test
    fun `an app-supplied leading item has the touch-target slot whatever the flow offers`()
    {
        for (wayOut in WayOut.entries)
        {
            val layout = HeaderLayout.of(gutter, appLeading = true, wayOut = wayOut);
            assertEquals(Metrics.TOUCH_TARGET, layout.leadingSlot);
            assertEquals(28.dp, layout.leadingCentre);
            assertEquals(56.dp, layout.titleStart);
        }
    }

    @Test
    fun `a wider gutter moves the mark and the title by the same amount`()
    {
        val layout = HeaderLayout.of(24.dp, appLeading = false, wayOut = WayOut.Back);
        assertEquals(36.dp, layout.leadingCentre);
        assertEquals(24.dp + Metrics.MARK_SIZE + 24.dp, layout.titleStart);
    }

    @Test
    fun `a gutter narrower than the mark's inset puts the slot at the edge`()
    {
        val layout = HeaderLayout.of(8.dp, appLeading = false, wayOut = WayOut.Back);
        assertEquals(0.dp, layout.edgeStart);
        assertEquals(Metrics.TOUCH_TARGET, layout.titleStart);
    }

    @Test
    fun `a sheet root with a close has no leading slot and its title starts at the gutter`()
    {
        val layout = HeaderLayout.of(gutter, appLeading = false, wayOut = WayOut.Close);
        assertNull(layout.leadingSlot);
        assertEquals(16.dp, layout.titleStart);
    }

    @Test
    fun `the trailing side is the same whether or not a leading slot is laid out`()
    {
        for (wayOut in WayOut.entries)
        {
            val layout = HeaderLayout.of(gutter, appLeading = false, wayOut = wayOut);
            assertEquals(Metrics.TOUCH_TARGET, layout.trailingSlot);
            assertEquals(gutter, layout.centreEnd);
        }
    }

    @Test
    fun `the flow's back is drawn for a back and for nothing else`()
    {
        assertEquals(listOf(WayOut.Back), WayOut.entries.filter { HeaderLayout.drawsFlowBack(it) });
    }
}
