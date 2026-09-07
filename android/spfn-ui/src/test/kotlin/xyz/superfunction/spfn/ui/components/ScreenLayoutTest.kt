// SPFN Mobile — the four cells of the one branch a `Screen`'s height has.
//
// This repository has no Compose UI test infrastructure — no Robolectric, no instrumented
// suite — so a layout decision written as an `if` inside a composable is a decision nothing
// on a JVM can read. `ScreenLayout.forDetent` is that decision as a pure function of one
// boolean, and this is the whole of it: two extents, two callers, four answers.
//
// What it does NOT prove is that the modifiers those extents become lay out the way the
// names say. That is a measurement, it needs a device, and the evidence for it is an
// emulator and a Galaxy Z Flip4 screenshot of a Fit sheet standing shorter than a Full one.
// What this suite holds is the half that a refactor breaks silently: a `Fill` that leaked
// into the fitting case, or a `Wrap` that leaked out of it and left a pushed screen's
// background painted only as far down as its two lines of text reach.

package xyz.superfunction.spfn.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenLayoutTest
{
    @Test
    fun `inside a sheet that fits its content, the root takes only what the content came to`()
    {
        assertEquals(Extent.Wrap, ScreenLayout.forDetent(fits = true).root);
    }

    @Test
    fun `inside a sheet that fits its content, the body takes only what the content came to`()
    {
        assertEquals(Extent.Wrap, ScreenLayout.forDetent(fits = true).body);
    }

    @Test
    fun `everywhere else the root fills the height it was offered`()
    {
        assertEquals(Extent.Fill, ScreenLayout.forDetent(fits = false).root);
    }

    @Test
    fun `everywhere else the body takes everything the header left`()
    {
        assertEquals(Extent.Fill, ScreenLayout.forDetent(fits = false).body);
    }
}
