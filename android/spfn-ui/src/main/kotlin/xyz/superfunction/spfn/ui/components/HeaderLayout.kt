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
//
// ---------------------------------------------------------------------------
// A leading slot stands where Material's navigation icon stands
// ---------------------------------------------------------------------------
//
// With a leading slot the header used to put it a whole gutter in from the edge and the title
// another gutter past it: the back's mark centred 40dp from the edge and the title at 80dp.
// Material 3's small top app bar (androidx `material3` AppBar.kt, `TopAppBarLayout`) pads its
// navigation icon 4dp from the edge, the icon button's touch target is 48dp with the 24dp icon
// centred in it, and the title stands 4dp past that: the icon's mark from 16dp to 40dp, its
// centre at 28dp, the title at 56dp. Next to apps that use it, the SDK's back sat 12dp further
// in and its title 24dp further.
//
// So the slot is placed by its MARK, not by its box: the mark's start edge is at the gutter,
// and the title a gutter past the mark's end. The box is then a mark's inset
// ((48 - 24) / 2 = 12dp) nearer the edge than the gutter, and the title the same inset nearer
// the box. At the default 16dp gutter these are Material's 4dp, 28dp and 56dp; a theme's wider
// gutter moves the mark and the title the way it moves a title with no slot. An app's own
// leading item stands in the same box, at Material's navigation-icon position, so an item
// drawn at the touch target lines up with the SDK's back.

package xyz.superfunction.spfn.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import xyz.superfunction.spfn.ui.WayOut

/**
 * How a header's three boxes share its width, on its START side.
 *
 * [leadingSlot] is the leading box's minimum width, or `null` when there is no leading box at
 * all. [edgeStart] is the header's own padding before the first box, and [centreStart] the
 * centre box's padding on the leading side: both are a gutter less a mark's inset beside a
 * leading control, so its mark and not its box lines up with the gutter; with no leading box
 * the header's gutter stands before the centre and the centre adds nothing. Both sides are
 * START and END rather than left and right, so a right-to-left layout mirrors all of it.
 */
internal data class HeaderLayout(val gutter: Dp, val leadingSlot: Dp?)
{
    /** The header's padding before its first box: a gutter, or a gutter less a mark's inset before a leading control. */
    val edgeStart: Dp
        get() = if (leadingSlot == null) gutter else besideMark;

    /** The centre box's padding towards the leading slot: a gutter past the mark, none beside the edge. */
    val centreStart: Dp
        get() = if (leadingSlot == null) 0.dp else besideMark;

    /** The centre box's padding towards the trailing slot, which is always laid out. */
    val centreEnd: Dp
        get() = gutter;

    /** The trailing box's minimum width, the same whether or not it holds anything. */
    val trailingSlot: Dp
        get() = Metrics.TOUCH_TARGET;

    /**
     * How far from the header's start edge the centre of a touch-target control in the leading
     * slot stands, or `null` with no slot — the back's mark, or an app item drawn at the target.
     */
    val leadingCentre: Dp?
        get() = leadingSlot?.let { edgeStart + Metrics.TOUCH_TARGET / 2 };

    /**
     * How far from the header's start edge the centre's content starts — the title, or the
     * app's principal item, which stand in the same box. A leading item wider than the touch
     * target pushes it further; this is the least it can be.
     */
    val titleStart: Dp
        get() = edgeStart + (leadingSlot ?: 0.dp) + centreStart;

    /**
     * A gutter less the distance from a touch target's edge to its centred mark, and never
     * less than nothing: a theme's gutter narrower than that inset puts the box at the edge.
     */
    private val besideMark: Dp
        get() = (gutter - (Metrics.TOUCH_TARGET - Metrics.MARK_SIZE) / 2).coerceAtLeast(0.dp);

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
