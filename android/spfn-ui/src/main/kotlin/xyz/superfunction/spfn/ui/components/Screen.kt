// SPFN Mobile — the frame every screen in a flow is drawn in.
//
// Counterpart of Sources/SPFNUI/Components/Screen.swift. One header, one body, and the
// three things a screen used to have to remember for itself: where the status bar is, what
// the keyboard is covering, and which way out this screen has.
//
// ---------------------------------------------------------------------------
// Screen owns the insets, so a screen does not
// ---------------------------------------------------------------------------
//
// The header consumes the status bar inset and nothing else does, and the body consumes the
// bottom one — the navigation bar or the gesture pill, unioned with the keyboard so the two
// never add up. `Modifier.windowInsetsPadding` CONSUMES what it applies, so a host that
// already padded its own root (examples/android-compose does, and so does the harness) hands
// this composable an inset that is already spent and the header adds nothing on top of it.
// That is what makes "the host may still own the insets" and "Screen owns the insets" the
// same layout rather than two paddings (docs/IMPLEMENTATION-PITFALLS.md P25).
//
// The keyboard is the body's, not the header's. A focused text field inside the scrolling
// body is brought into view by `verticalScroll` itself — Compose's focus system asks the
// nearest scrollable to reveal the focused node — and the ime inset is what makes the room
// for it to be revealed INTO. A body that did not shrink would scroll the field behind the
// keyboard and report success.
//
// The visual vocabulary is `SpfnTokens` and its Swift twin: a palette resolved from the
// appearance, six spacing steps, two radii and four type styles. What is LEFT outside the
// tokens is `Metrics` — the platform's minimum touch target and the header's height — because
// neither is a value a design flow gets to move (decision S10).
//
// ---------------------------------------------------------------------------
// Screen owns two of the seven keyboard clauses, and only two
// ---------------------------------------------------------------------------
//
// The body gets out of the keyboard's way, and a tap outside a field puts the keyboard away.
// Both are about the FRAME rather than about any field in it, which is why they are here and
// the other five are on `SpfnTextField`.

package xyz.superfunction.spfn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import xyz.superfunction.spfn.ui.ScreenLeading
import xyz.superfunction.spfn.ui.SpfnStrings
import xyz.superfunction.spfn.ui.tokens.SpfnTokens

/**
 * A screen inside a flow: a header, and a body under it.
 *
 * @param title what the header says.
 * @param leading the header's left slot. Left out, the flow decides — a back on a stack of
 *   two or more, a close on the root of a modal or a sheet, and nothing on the root of a
 *   pushed flow (`Flow.leading`). A host app that passes one overrides that entirely.
 * @param trailing the header's right slot. There is no default: nothing but the app knows
 *   what a screen's action is.
 * @param scroll whether the body scrolls. A body that scrolls also gets out of the
 *   keyboard's way; a body that does not is the caller saying its content always fits,
 *   which is what a screen inside a sheet says (see `Sheet.kt`).
 *
 * `@JvmSynthetic` for the reason `FlowHost` carries it: a `@Composable` function is a rule
 * the Compose compiler enforces for Kotlin callers and for nobody else, and from Java this
 * would be an ordinary static method whose first real argument is a `Composer`
 * (docs/IMPLEMENTATION-PITFALLS.md P15).
 */
@JvmSynthetic
@Composable
public fun Screen(
    title: String,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
)
{
    val palette = spfnPalette();
    val focus = LocalFocusManager.current;
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            // A tap that lands on the frame rather than on a control puts the keyboard away.
            // `detectTapGestures` in a `pointerInput` does not consume a press a child
            // handles, so a button under this still gets its click.
            .pointerInput(Unit) {
                detectTapGestures { focus.clearFocus() };
            }
    )
    {
        Header(title = title, leading = leading, trailing = trailing);
        Body(scroll = scroll, content = content);
    }
}

/**
 * The header, and the only place the status bar inset is spent.
 *
 * The two slots are laid out at the minimum touch target whether or not they hold anything,
 * so the title sits in the same place on every screen of a flow and a control that appears
 * does not move it (docs/IMPLEMENTATION-PITFALLS.md P21 is the other half of that size: a
 * control smaller than 48dp reports a rectangle its neighbour has already claimed).
 */
@Composable
private fun Header(title: String, leading: (@Composable () -> Unit)?, trailing: (@Composable () -> Unit)?)
{
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = Metrics.HEADER_HEIGHT)
            .padding(horizontal = SpfnTokens.space4),
        verticalAlignment = Alignment.CenterVertically
    )
    {
        Box(modifier = Modifier.sizeIn(minWidth = Metrics.TOUCH_TARGET), contentAlignment = Alignment.CenterStart)
        {
            if (leading != null) leading() else FlowLeading();
        }
        SpfnText(
            text = title,
            role = TextRole.Title,
            modifier = Modifier.weight(1f).padding(horizontal = SpfnTokens.space4)
        );
        Box(modifier = Modifier.sizeIn(minWidth = Metrics.TOUCH_TARGET), contentAlignment = Alignment.CenterEnd)
        {
            trailing?.invoke();
        }
    }
}

/**
 * The body, and the only place the bottom inset and the keyboard are spent.
 *
 * The two insets are UNIONED rather than applied one after the other: an open keyboard and a
 * navigation bar overlap, and padding for both in turn leaves a gap the size of the smaller
 * one under every screen with a text field on it.
 */
@Composable
private fun ColumnScope.Body(scroll: Boolean, content: @Composable ColumnScope.() -> Unit)
{
    val room = Modifier
        .fillMaxWidth()
        .weight(1f)
        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars));
    Column(modifier = if (scroll) room.verticalScroll(rememberScrollState()) else room)
    {
        content();
    }
}

/**
 * The leading control the flow asked for, or nothing.
 *
 * The chrome arrives from `FlowHost`, which is the only thing that knows both how the flow
 * was entered and how deep it stands. A `Screen` composed outside a host reads the default
 * — no control at all — rather than inventing one.
 */
@Composable
private fun FlowLeading()
{
    val chrome = LocalScreenChrome.current;
    when (chrome.leading)
    {
        ScreenLeading.None -> Unit
        ScreenLeading.Back -> HeaderControl(label = SpfnStrings.controlBack, id = "screen.back", onClick = chrome.onBack)
        ScreenLeading.Close -> HeaderControl(label = SpfnStrings.controlClose, id = "screen.close", onClick = chrome.onClose)
    }
}

/**
 * One header control, sized to the minimum touch target in BOTH directions.
 *
 * The size constraints come before `clickable`, so the touch area is the 48dp box rather
 * than a 16dp line of text that Compose then expands past its neighbours
 * (docs/IMPLEMENTATION-PITFALLS.md P21).
 */
@Composable
private fun HeaderControl(label: String, id: String, onClick: () -> Unit)
{
    SpfnText(
        text = label,
        modifier = Modifier
            .testTag(id)
            .sizeIn(minWidth = Metrics.TOUCH_TARGET, minHeight = Metrics.TOUCH_TARGET)
            .clickable(onClick = onClick)
            .wrapContentSize(Alignment.Center)
    );
}
