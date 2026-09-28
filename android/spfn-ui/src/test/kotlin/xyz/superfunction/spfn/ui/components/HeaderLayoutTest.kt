// SPFN Mobile — where a header's title starts, one screen kind at a time.
//
// `HeaderLayout.of` is the header's start-side layout as a pure function of what the app
// passed and what the flow offers, for the reason `ScreenLayoutTest` gives: this repository
// has no Compose UI test infrastructure, so a width decided inside the composable is a width
// nothing on a JVM can read. Each case below is a kind of screen the header is drawn on,
// asserted as the title's offset from the header's start edge at the default gutter: 16dp
// with no leading slot, 16 + 48 + 16 = 80dp with one.
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
        assertEquals(16.dp, layout.titleStart);
    }

    @Test
    fun `a pushed screen with a back has the touch-target slot and its title starts past it`()
    {
        val layout = HeaderLayout.of(gutter, appLeading = false, wayOut = WayOut.Back);
        assertEquals(Metrics.TOUCH_TARGET, layout.leadingSlot);
        assertEquals(80.dp, layout.titleStart);
    }

    @Test
    fun `an app-supplied leading item has the touch-target slot whatever the flow offers`()
    {
        for (wayOut in WayOut.entries)
        {
            val layout = HeaderLayout.of(gutter, appLeading = true, wayOut = wayOut);
            assertEquals(Metrics.TOUCH_TARGET, layout.leadingSlot);
            assertEquals(80.dp, layout.titleStart);
        }
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
