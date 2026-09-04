// SPFN Mobile — the sheet's arithmetic, against vectors written by hand.
//
// Counterpart of Tests/SPFNUITests/SheetGeometryTests.swift, vector for vector. The numbers
// below were computed from the rule and typed out, not printed from this implementation
// (docs/IMPLEMENTATION-PITFALLS.md P10): a vector read off the code under test agrees with
// it by construction and says nothing about whether the rule is the approved one.
//
// A container of 1000 makes every expectation readable — full is 920, half is 500, the fit
// fallback is 320 — and the same 1000 is used on the other platform, so a disagreement
// between the two halves shows up as one failing line rather than as a rounding argument.

package xyz.superfunction.spfn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A Float epsilon, not a Double one. The Android half works in `Float` because every length
// Compose hands out is one, and 0.92f x 1000f does not land on 920 exactly; 1e-3 is far
// tighter than any disagreement about the RULE could be and far looser than the last bit of
// a Float.
private const val EPSILON: Float = 1e-3f;

class SheetGeometryTest
{
    @Test
    fun `full stands at the full fraction of the container`()
    {
        assertEquals(920f, SheetGeometry.height(SheetDetent.Full, 1000f, 0f), EPSILON);
        assertEquals(920f, SheetGeometry.height(SheetDetent.Full, 1000f, 100f), EPSILON);
    }

    @Test
    fun `half stands at half the container`()
    {
        assertEquals(500f, SheetGeometry.height(SheetDetent.Half, 1000f, 0f), EPSILON);
        assertEquals(500f, SheetGeometry.height(SheetDetent.Half, 1000f, 900f), EPSILON);
    }

    @Test
    fun `fit takes the height its content measured`()
    {
        assertEquals(300f, SheetGeometry.height(SheetDetent.Fit, 1000f, 300f), EPSILON);
    }

    @Test
    fun `fit is clamped to full when its content is taller than a sheet goes`()
    {
        assertEquals(920f, SheetGeometry.height(SheetDetent.Fit, 1000f, 990f), EPSILON);
        assertEquals(920f, SheetGeometry.height(SheetDetent.Fit, 1000f, 5000f), EPSILON);
    }

    @Test
    fun `fit falls back when nothing has been measured`()
    {
        assertEquals(320f, SheetGeometry.height(SheetDetent.Fit, 1000f, 0f), EPSILON);
        assertEquals(320f, SheetGeometry.height(SheetDetent.Fit, 1000f, -1f), EPSILON);
    }

    // ---- fitHeight ---------------------------------------------------------
    //
    // The measured half of `fit`, and the one both platforms call with a header. A screen's
    // header does not scroll, so it is not in the measurement and is added back; 56 is what
    // `Metrics.HEADER_HEIGHT` is on both sides, which is why it is the number here.

    @Test
    fun `fit height adds the header to the content it was given`()
    {
        assertEquals(356f, SheetGeometry.fitHeight(300f, 56f, 920f), EPSILON);
        assertEquals(300f, SheetGeometry.fitHeight(300f, 0f, 920f), EPSILON);
    }

    @Test
    fun `fit height is clamped to the ceiling it was given`()
    {
        assertEquals(920f, SheetGeometry.fitHeight(900f, 56f, 920f), EPSILON);
        assertEquals(920f, SheetGeometry.fitHeight(5000f, 56f, 920f), EPSILON);
    }

    /**
     * A caller that knows no container passes an infinity, which is iOS: SwiftUI clamps a
     * height detent to the sheet's own maximum, so that side names no second ceiling.
     */
    @Test
    fun `fit height with no ceiling is the content and the header`()
    {
        assertEquals(356f, SheetGeometry.fitHeight(300f, 56f, Float.POSITIVE_INFINITY), EPSILON);
    }

    @Test
    fun `fit height answers zero when nothing has been measured`()
    {
        assertEquals(0f, SheetGeometry.fitHeight(0f, 56f, 920f), EPSILON);
        assertEquals(0f, SheetGeometry.fitHeight(-1f, 56f, 920f), EPSILON);
    }

    /**
     * A negative header adds nothing rather than subtracting: a sheet is not made shorter
     * than its own content by a chrome measurement that came back wrong.
     */
    @Test
    fun `fit height ignores a negative header`()
    {
        assertEquals(300f, SheetGeometry.fitHeight(300f, -50f, 920f), EPSILON);
    }

    @Test
    fun `fit height with no room answers no height`()
    {
        assertEquals(0f, SheetGeometry.fitHeight(300f, 56f, 0f), EPSILON);
        assertEquals(0f, SheetGeometry.fitHeight(300f, 56f, -10f), EPSILON);
    }

    @Test
    fun `a container with no room gives every detent no height`()
    {
        assertEquals(0f, SheetGeometry.height(SheetDetent.Full, 0f, 500f), EPSILON);
        assertEquals(0f, SheetGeometry.height(SheetDetent.Half, -10f, 500f), EPSILON);
        assertEquals(0f, SheetGeometry.height(SheetDetent.Fit, 0f, 500f), EPSILON);
    }

    @Test
    fun `a drag dismisses at the threshold and not before it`()
    {
        assertFalse(SheetGeometry.closes(0f, 400f));
        assertFalse(SheetGeometry.closes(199f, 400f));
        assertTrue(SheetGeometry.closes(200f, 400f));
        assertTrue(SheetGeometry.closes(400f, 400f));
    }

    @Test
    fun `a sheet dragged up never dismisses`()
    {
        assertFalse(SheetGeometry.closes(-50f, 400f));
    }

    @Test
    fun `a sheet with no height cannot be dismissed by a drag`()
    {
        assertFalse(SheetGeometry.closes(100f, 0f));
        assertFalse(SheetGeometry.closes(100f, -1f));
    }

    @Test
    fun `the scrim fades with the drag`()
    {
        assertEquals(1f, SheetGeometry.scrim(0f, 400f), EPSILON);
        assertEquals(0.5f, SheetGeometry.scrim(200f, 400f), EPSILON);
        assertEquals(0f, SheetGeometry.scrim(400f, 400f), EPSILON);
    }

    @Test
    fun `the scrim is clamped at both ends`()
    {
        assertEquals(0f, SheetGeometry.scrim(500f, 400f), EPSILON);
        assertEquals(1f, SheetGeometry.scrim(-50f, 400f), EPSILON);
        assertEquals(0f, SheetGeometry.scrim(10f, 0f), EPSILON);
    }
}
