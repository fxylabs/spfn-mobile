// SPFN Mobile — where a `Screen` header's title starts, and what decides it.
//
// There is no counterpart file on iOS: that half hands its header to the system navigation
// bar, and the system bar already places its title the way this file now does.
//
// ---------------------------------------------------------------------------
// A slot that draws nothing takes no width
// ---------------------------------------------------------------------------
//
// The header used to lay its leading slot out at the minimum touch target whether or not
// anything was drawn in it, so the title of a flow root with no back, of a sheet root and of a
// tab root started at gutter + 48dp + gutter from the edge. Material 3's top app bar puts the
// title at its 16dp start margin when there is no navigation icon, and next to apps that use
// it the SDK's title read as indented. So the leading slot is now there only when something is
// drawn in it, and the centre then starts at the header's own gutter.
//
// The decision is made from STATE, before layout: the app's `leading` being non-null, or the
// flow's way out being a back — the same input `FlowBack` draws from. Measuring the slot after
// the fact to find it empty would be a second layout pass, or a frame drawn at the old offset
// and then moved. As a pure function it also has cells a JVM test can read, for the reason
// `ScreenLayout.kt` states: this repository has no Compose UI test infrastructure.
//
// The trailing slot keeps its minimum width either way, so a sheet's X stays where it was.

package xyz.superfunction.spfn.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.superfunction.spfn.ui.WayOut

/**
 * How a header's three boxes share its width, on its START side.
 *
 * [leadingSlot] is the leading box's minimum width, or `null` when there is no leading box at
 * all. [centreStart] is the centre box's own padding on that side: a gutter between it and a
 * leading control, and nothing when the header's gutter already stands there. Both sides are
 * START and END rather than left and right, so a right-to-left layout mirrors all of it.
 */
internal data class HeaderLayout(val gutter: Dp, val leadingSlot: Dp?)
{
    /** The centre box's padding towards the leading slot: a gutter beside a control, none beside the edge. */
    val centreStart: Dp
        get() = if (leadingSlot == null) 0.dp else gutter;

    /** The centre box's padding towards the trailing slot, which is always laid out. */
    val centreEnd: Dp
        get() = gutter;

    /** The trailing box's minimum width, the same whether or not it holds anything. */
    val trailingSlot: Dp
        get() = Metrics.TOUCH_TARGET;

    /**
     * How far from the header's start edge the centre's content starts — the title, or the
     * app's principal item, which stand in the same box. A leading item wider than the touch
     * target pushes it further; this is the least it can be.
     */
    val titleStart: Dp
        get() = gutter + (leadingSlot ?: 0.dp) + centreStart;

    internal companion object
    {
        /**
         * The layout for a header whose app passed a leading item ([appLeading]) or not, on a
         * screen whose flow offers [wayOut].
         */
        fun of(gutter: Dp, appLeading: Boolean, wayOut: WayOut): HeaderLayout =
            HeaderLayout(gutter, if (appLeading || drawsFlowBack(wayOut)) Metrics.TOUCH_TARGET else null);

        /** Whether the flow's own back is drawn in the leading slot: the one input `FlowBack` reads. */
        fun drawsFlowBack(wayOut: WayOut): Boolean = wayOut == WayOut.Back;
    }
}
