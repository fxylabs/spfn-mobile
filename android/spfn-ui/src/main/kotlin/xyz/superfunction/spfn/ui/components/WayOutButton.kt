// SPFN Mobile — the flow's way out, drawn wherever an app puts it.
//
// No counterpart file on iOS, deliberately: there the way out is the system navigation bar's
// back button, or the close `Screen` puts in the bar, and a screen cannot turn the bar off.
// Here the header is the SDK's own, and a screen drawn with `ScreenHeader.None` has none — so
// the app needs a way to draw the flow's back or close in a header of its own, and this is it.
// The header's own two controls are drawn by the same two functions, so a screen with the
// SDK's header and a screen with the app's show one control with one id.
//
// The size split is P21's: the mark is 20dp and the box around it is `Metrics.TOUCH_TARGET`
// in BOTH directions, and the size constraints come before `clickable`, so the touch area is
// the 48dp box rather than the 20dp mark Compose would then expand past its neighbours.

package xyz.superfunction.spfn.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import xyz.superfunction.spfn.ui.ScreenWayOut
import xyz.superfunction.spfn.ui.SpfnStrings
import xyz.superfunction.spfn.ui.WayOut

/**
 * The way out of the screen being composed: a back chevron, a close X, or nothing.
 *
 * Reads [ScreenWayOut.current], so it draws what the SDK's header would have drawn in the
 * same place — a back on a stack of two or more and on the root of a pushed flow, an X on the
 * root of a flow presented over something, and nothing outside a flow. Both are found by the
 * header's own ids, `screen.back` and `screen.close`.
 *
 * Where it stands is the caller's. Decision N3 is the rule the SDK's header follows — the back
 * on the left, the close on the right — and an app drawing its own header is asked to follow
 * it too.
 *
 * `@JvmSynthetic` for the reason `Screen` carries it (docs/IMPLEMENTATION-PITFALLS.md P15).
 */
@JvmSynthetic
@Composable
public fun WayOutButton(modifier: Modifier = Modifier)
{
    val wayOut = ScreenWayOut.current;
    when (wayOut.wayOut)
    {
        WayOut.Back -> BackControl(onClick = wayOut::back, modifier = modifier)
        WayOut.Close -> CloseControl(onClick = wayOut::close, modifier = modifier)
        WayOut.None -> Unit
    };
}

/**
 * The back control, as the header and [WayOutButton] both draw it. `@JvmSynthetic` because a
 * top-level `internal` function is a public method in the class file.
 */
@JvmSynthetic
@Composable
internal fun BackControl(onClick: () -> Unit, modifier: Modifier = Modifier)
{
    HeaderControl(label = SpfnStrings.controlBack, id = "screen.back", onClick = onClick, modifier = modifier)
    {
        BackChevron();
    }
}

/**
 * The close control, as the header and [WayOutButton] both draw it. `@JvmSynthetic` because a
 * top-level `internal` function is a public method in the class file.
 */
@JvmSynthetic
@Composable
internal fun CloseControl(onClick: () -> Unit, modifier: Modifier = Modifier)
{
    HeaderControl(label = SpfnStrings.controlClose, id = "screen.close", onClick = onClick, modifier = modifier)
    {
        CloseCross();
    }
}

/**
 * One way-out control: a mark inside the minimum touch target, in BOTH directions
 * (docs/IMPLEMENTATION-PITFALLS.md P21).
 *
 * [label] is what a screen reader says and not what is drawn, which is what keeps the ten
 * string keys the same ten they were while the words stopped being visible.
 */
@Composable
private fun HeaderControl(
    label: String,
    id: String,
    onClick: () -> Unit,
    modifier: Modifier,
    mark: @Composable () -> Unit
)
{
    Box(
        modifier = modifier
            .testTag(id)
            .semantics { contentDescription = label }
            .sizeIn(minWidth = Metrics.TOUCH_TARGET, minHeight = Metrics.TOUCH_TARGET)
            .clickable(onClick = onClick)
            .wrapContentSize(Alignment.Center),
        contentAlignment = Alignment.Center
    )
    {
        mark();
    }
}
