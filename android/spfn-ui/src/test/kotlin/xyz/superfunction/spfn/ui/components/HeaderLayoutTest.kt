// SPFN Mobile — where a header's title starts and ends, one screen kind at a time.
//
// `HeaderLayout.of` is the header's layout as a pure function of what the app
// passed and what the flow offers, for the reason `ScreenLayoutTest` gives: this repository
// has no Compose UI test infrastructure, so a width decided inside the composable is a width
// nothing on a JVM can read. Each case below is a kind of screen the header is drawn on,
// asserted as the title's offset from the header's start edge at the default gutter: 16dp
// with no leading slot, and with one Material 3's small top app bar — the slot 4dp from the
// edge, its mark centred at 28dp, the title at 4 + 48 + 4 = 56dp. The end side is asserted
// from the header's END edge the same way: with a trailing control its mark centred 28dp from
// it and the title ending 56dp from it, where Material's action icon and its box stand; with
// an empty trailing slot the title ending 16 + 48 + 16 = 80dp from it, as it always has.
//
// Right-to-left is not a separate case: every number here is START or END, and `Header` spends
// them as `padding(start = …, end = …)`, which Compose mirrors — so the X's 28dp is from the
// left edge in RTL, the back's from the right.
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
    fun `a close stands where Material's action icon stands`()
    {
        val layout = HeaderLayout.of(gutter, appLeading = false, wayOut = WayOut.Close);
        assertEquals(true, layout.trailingControl);
        assertEquals(4.dp, layout.edgeEnd);
        assertEquals(28.dp, layout.trailingCentre);
        assertEquals(gutter, layout.trailingCentre!! - Metrics.MARK_SIZE / 2);
        assertEquals(56.dp, layout.titleEnd);
    }

    @Test
    fun `an app-supplied trailing item stands where the close stands whatever the flow offers`()
    {
        for (wayOut in WayOut.entries)
        {
            val layout = HeaderLayout.of(gutter, appLeading = false, appTrailing = true, wayOut = wayOut);
            assertEquals(4.dp, layout.edgeEnd);
            assertEquals(28.dp, layout.trailingCentre);
            assertEquals(56.dp, layout.titleEnd);
        }
    }

    @Test
    fun `an empty trailing slot keeps its box a gutter from the end and the title 80dp from it`()
    {
        for (wayOut in listOf(WayOut.None, WayOut.Back))
        {
            val layout = HeaderLayout.of(gutter, appLeading = false, wayOut = wayOut);
            assertNull(layout.trailingCentre);
            assertEquals(gutter, layout.edgeEnd);
            assertEquals(Metrics.TOUCH_TARGET, layout.trailingSlot);
            assertEquals(gutter, layout.centreEnd);
            assertEquals(80.dp, layout.titleEnd);
        }
    }

    @Test
    fun `a wider gutter moves the trailing mark and the title's end by the same amount`()
    {
        val layout = HeaderLayout.of(24.dp, appLeading = false, wayOut = WayOut.Close);
        assertEquals(36.dp, layout.trailingCentre);
        assertEquals(24.dp + Metrics.MARK_SIZE + 24.dp, layout.titleEnd);
    }

    @Test
    fun `a gutter narrower than the mark's inset puts the trailing slot at the edge`()
    {
        val layout = HeaderLayout.of(8.dp, appLeading = false, wayOut = WayOut.Close);
        assertEquals(0.dp, layout.edgeEnd);
        assertEquals(Metrics.TOUCH_TARGET / 2, layout.trailingCentre);
        assertEquals(Metrics.TOUCH_TARGET, layout.titleEnd);
    }

    @Test
    fun `the trailing side does not depend on the leading side`()
    {
        for (wayOut in WayOut.entries)
        {
            for (appTrailing in listOf(false, true))
            {
                val without = HeaderLayout.of(gutter, appLeading = false, appTrailing = appTrailing, wayOut = wayOut);
                val with = HeaderLayout.of(gutter, appLeading = true, appTrailing = appTrailing, wayOut = wayOut);
                assertEquals(without.edgeEnd, with.edgeEnd);
                assertEquals(without.centreEnd, with.centreEnd);
                assertEquals(without.titleEnd, with.titleEnd);
            }
        }
    }

    @Test
    fun `the flow's back is drawn for a back and for nothing else`()
    {
        assertEquals(listOf(WayOut.Back), WayOut.entries.filter { HeaderLayout.drawsFlowBack(it) });
    }

    @Test
    fun `the flow's close is drawn for a close and for nothing else`()
    {
        assertEquals(listOf(WayOut.Close), WayOut.entries.filter { HeaderLayout.drawsFlowClose(it) });
    }
}
