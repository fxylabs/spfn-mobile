// SPFN Mobile — the two marks a header draws for the two ways out.
//
// Counterpart of Sources/SPFNUI/Components/HeaderIcons.swift. A mark on the left for a back,
// an X on the right for a close (decision N3): the words `Back` and `Close` in body type read
// as prose rather than as controls, and a person looking for the way out of a sheet looks at
// the top right corner.
//
// Each half draws its own platform's marks. The iOS half's back is the system bar's, so it is
// SF Symbols' chevron and nothing here changes it. This half draws Material's: `arrow_back`, a
// shaft with an arrowhead, and `close`. A chevron is what iOS draws, and on Android a person
// reads it as an iOS screen rather than as the way back.
//
// Not part of the public component set, and deliberately outside the nine names section 15
// of the validator compares. These are two marks this component draws for itself; a host app
// that wants its own control passes a slot to `Screen`, which is the door that already
// exists.
//
// Material's SHAPES and not Material's artifact. This repository depends on no Material
// artifact (decision C2), so `Icons.AutoMirrored.Filled.ArrowBack` is not a thing this module
// may reach for; the two paths below are copied from Material Icons' 24px `navigation/
// arrow_back` and `navigation/close` (github.com/google/material-design-icons, src/,
// Apache License 2.0) and built with compose-ui's own vector builder. They are FILLED
// outlines on a 24-unit viewport, so they are tinted and never stroked, and a 24dp box maps
// one unit to one dp: the vector is rasterised at the box's pixel size, never a bitmap scaled
// to it.
//
// The size split is P21's: the MARK is Material's 24dp icon and the frame around it is
// `Metrics.TOUCH_TARGET`. A control drawn at the mark's own size reports a rectangle its
// neighbour has already eaten, and a device runner then taps the neighbour.

package xyz.superfunction.spfn.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import xyz.superfunction.spfn.ui.tokens.spfnPalette

/** The mark a back control draws: Material's arrow, pointing the way it goes. */
@Composable
internal fun BackArrow()
{
    HeaderMark(ARROW_BACK);
}

/** The mark a close control draws: Material's X. */
@Composable
internal fun CloseCross()
{
    HeaderMark(CLOSE);
}

/**
 * One mark at its full size, in the primary text colour of the palette in force — so it
 * follows light and dark with the title beside it. The vector's own fill is replaced by the
 * tint, which is why the colour it was built with does not matter.
 */
@Composable
private fun HeaderMark(mark: ImageVector)
{
    Image(
        painter = rememberVectorPainter(mark),
        contentDescription = null,
        colorFilter = ColorFilter.tint(spfnPalette().text),
        modifier = Modifier.size(Metrics.MARK_SIZE)
    );
}

/**
 * Material's `arrow_back`. `autoMirror` because a back points towards the start edge: in a
 * right-to-left layout it points right, as Material's own auto-mirrored arrow does.
 */
internal val ARROW_BACK: ImageVector by lazy()
{
    headerMark("ArrowBack", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z", autoMirror = true);
}

/** Material's `close`. Not mirrored: an X has no direction, and Material does not mirror it. */
internal val CLOSE: ImageVector by lazy()
{
    headerMark(
        "Close",
        "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z",
        autoMirror = false
    );
}

/**
 * One filled path on Material's 24-unit viewport, sized at [Metrics.MARK_SIZE]. Built once per
 * process by the two lazy values above, never per composition.
 */
private fun headerMark(name: String, pathData: String, autoMirror: Boolean): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = Metrics.MARK_SIZE,
        defaultHeight = Metrics.MARK_SIZE,
        viewportWidth = VIEWPORT,
        viewportHeight = VIEWPORT,
        autoMirror = autoMirror
    )
        .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        .build();

/** Material Icons' viewport, in the units its path data is written in. */
private const val VIEWPORT: Float = 24f;
