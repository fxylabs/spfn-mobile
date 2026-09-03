// SPFN Mobile — the four controls a screen can put on itself.
//
// Counterpart of Sources/SPFNUI/Components/Buttons.swift. Four public composables over one
// private body, because the ROLE is what a screen spec declares (`actions.<a>.role`) and a
// generated view reads better naming the role than passing it.
//
// Three things every one of them holds to, and each is a defect this repository has already
// paid for once:
//
//   - the minimum touch target, in BOTH directions, applied BEFORE `clickable`. Compose
//     expands a control smaller than 48dp past its layout bounds for touch, neighbouring
//     expansions overlap, and the bounds reported to accessibility for one control then sit
//     on a neighbour's: `enterCode.cancel` reported a rectangle centred inside
//     `enterCode.userCode`, and cell u5's tap opened the keyboard instead of closing the
//     flow (docs/IMPLEMENTATION-PITFALLS.md P21).
//   - `busy` disables. A control that spins and still accepts a press sends the second
//     request the model is about to ignore, and the person pressing has no way to know that
//     — the screen looks identical either way.
//   - a test tag is an ARGUMENT and not an option. Every control in a generated view is
//     reached by `<screen>.<action>`, and a control a runner cannot find is a cell that
//     cannot be written.
//
// No Material (decision C2), so the fill, the border and the spinner are drawn here out of
// foundation. The spinner turns on `withFrameMillis`, which is runtime's own frame clock:
// this module holds no animation dependency and acquiring one to rotate an arc would put a
// new artifact in gradle/verification-metadata.xml for a decoration.

package xyz.superfunction.spfn.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.superfunction.spfn.ui.tokens.SpfnPalette
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/** The one thing this screen is for. */
@JvmSynthetic
@Composable
public fun PrimaryButton(
    title: String,
    id: String,
    busy: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onTap: () -> Unit
)
{
    RoleButton(ControlRole.Primary, title, id, busy, enabled, modifier, onTap);
}

/** A control that is not the point of the screen. */
@JvmSynthetic
@Composable
public fun SecondaryButton(
    title: String,
    id: String,
    busy: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onTap: () -> Unit
)
{
    RoleButton(ControlRole.Secondary, title, id, busy, enabled, modifier, onTap);
}

/** A control that takes something away. */
@JvmSynthetic
@Composable
public fun DestructiveButton(
    title: String,
    id: String,
    busy: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onTap: () -> Unit
)
{
    RoleButton(ControlRole.Destructive, title, id, busy, enabled, modifier, onTap);
}

/** A control that reads as text: a cancel, a "not now". */
@JvmSynthetic
@Composable
public fun TextButton(
    title: String,
    id: String,
    busy: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onTap: () -> Unit
)
{
    RoleButton(ControlRole.Text, title, id, busy, enabled, modifier, onTap);
}

/**
 * What all four of them are.
 *
 * The modifier chain's ORDER is the P21 rule: the size constraints come before `clickable`,
 * so the touch area is the 48dp box rather than a line of text Compose then expands past its
 * neighbours.
 */
@Composable
private fun RoleButton(
    role: ControlRole,
    title: String,
    id: String,
    busy: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onTap: () -> Unit
)
{
    val palette = spfnPalette();
    // A busy control is disabled as well as spinning: the model would ignore the second
    // press anyway, and a control that accepts a press it discards says nothing to the
    // person who made it.
    val live = enabled && !busy;
    val shape = RoundedCornerShape(SpfnTokens.radiusSmall);
    val foreground = foreground(role, palette, live);

    Row(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(min = Metrics.TOUCH_TARGET)
            .heightIn(min = Metrics.TOUCH_TARGET)
            .testTag(id)
            .background(color = background(role, palette, live), shape = shape)
            .border(
                width = if (role == ControlRole.Secondary) Metrics.BORDER_WIDTH else 0.dp,
                color = if (role == ControlRole.Secondary) foreground else Color.Transparent,
                shape = shape
            )
            .clickable(enabled = live, onClick = onTap)
            .padding(horizontal = SpfnTokens.space4),
        verticalAlignment = Alignment.CenterVertically
    )
    {
        if (busy)
        {
            Spinner(colour = foreground);
        }
        SpfnText(
            text = title,
            modifier = Modifier.padding(start = if (busy) SpfnTokens.space2 else 0.dp),
            role = TextRole.Body,
            secondary = !live
        );
    }
}

/** What the label and, for an outlined control, the border are drawn in. */
private fun foreground(role: ControlRole, palette: SpfnPalette, live: Boolean): Color
{
    if (!live)
    {
        return palette.textSecondary;
    }
    return when (role)
    {
        ControlRole.Primary, ControlRole.Destructive -> palette.background
        ControlRole.Secondary -> palette.text
        ControlRole.Text -> palette.accent
    };
}

/** What the control stands on. */
private fun background(role: ControlRole, palette: SpfnPalette, live: Boolean): Color = when (role)
{
    ControlRole.Primary -> if (live) palette.accent else palette.surface
    ControlRole.Destructive -> if (live) palette.error else palette.surface
    ControlRole.Secondary -> palette.surface
    ControlRole.Text -> Color.Transparent
}

/**
 * A turning arc, drawn rather than animated.
 *
 * `withFrameMillis` is the frame clock `androidx.compose.runtime` already carries, so this
 * costs no new artifact. The turn is read from the frame time rather than accumulated, so a
 * dropped frame moves the arc further rather than leaving it behind.
 */
@Composable
private fun Spinner(colour: Color)
{
    val turn = remember { mutableFloatStateOf(0f) };
    LaunchedEffect(Unit)
    {
        while (true)
        {
            withFrameMillis { millis -> turn.floatValue = (millis % TURN_MILLIS) / TURN_MILLIS.toFloat() };
        }
    }
    Canvas(modifier = Modifier.size(SPINNER_SIZE))
    {
        drawArc(
            color = colour,
            startAngle = turn.floatValue * 360f,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(width = SPINNER_STROKE.toPx())
        );
    }
}

private const val TURN_MILLIS: Long = 900;
private val SPINNER_SIZE: Dp = 16.dp;
private val SPINNER_STROKE: Dp = 2.dp;
