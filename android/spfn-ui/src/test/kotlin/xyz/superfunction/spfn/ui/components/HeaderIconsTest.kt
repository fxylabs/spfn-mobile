// SPFN Mobile — the header's two marks are Material's, at Material's size.
//
// The marks are `ImageVector`s built from Material Icons' path data, and a vector is data a
// JVM test can read without drawing it: its size, its viewport, whether it mirrors, and the
// path it fills. What it does NOT prove is how a device rasterises it; that is a screenshot.

package xyz.superfunction.spfn.ui.components

import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HeaderIconsTest
{
    @Test
    fun `both marks are 24dp on Material's 24-unit viewport, one unit to one dp`()
    {
        for (mark in listOf(ARROW_BACK, CLOSE))
        {
            assertEquals(24.dp, mark.defaultWidth);
            assertEquals(24.dp, mark.defaultHeight);
            assertEquals(24f, mark.viewportWidth);
            assertEquals(24f, mark.viewportHeight);
        }
    }

    @Test
    fun `the back arrow mirrors in a right-to-left layout and the close does not`()
    {
        assertTrue(ARROW_BACK.autoMirror);
        assertFalse(CLOSE.autoMirror);
    }

    @Test
    fun `each mark is one filled path with no stroke`()
    {
        for (mark in listOf(ARROW_BACK, CLOSE))
        {
            assertEquals(1, mark.root.size);
            val path = mark.root[0] as VectorPath;
            assertNotNull(path.fill);
            assertEquals(null, path.stroke);
            assertTrue(path.pathData.isNotEmpty());
        }
    }

    @Test
    fun `each mark is built once, not per read`()
    {
        assertSame(ARROW_BACK, ARROW_BACK);
        assertSame(CLOSE, CLOSE);
    }
}
