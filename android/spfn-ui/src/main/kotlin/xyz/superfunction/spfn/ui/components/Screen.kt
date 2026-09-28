// SPFN Mobile — the frame every screen in a flow is drawn in.
//
// Counterpart of Sources/SPFNUI/Components/Screen.swift. One header, one body, and the
// three things a screen used to have to remember for itself: where the status bar is, what
// the keyboard is covering, and which way out this screen has.
//
// ---------------------------------------------------------------------------
// The header is the SDK's here, and an app may turn it off
// ---------------------------------------------------------------------------
//
// The iOS half hands its header to the system navigation bar. This half cannot: the only app
// bar Compose ships is Material's, and this repository depends on no Material artifact
// (decision C2). So the header below is still drawn here, and it takes the same three items
// the iOS bar does — `leading`, `principal`, `trailing` — in the same three places
// (docs/architecture/screen-header-design.md §3-1).
//
// What only this half offers is `header = ScreenHeader.None`: no SDK header at all, for an app
// that draws the top of its screens out of its own design system. Nothing else moves with it.
// The body still owns the bottom inset and the keyboard, a tap outside a field still puts the
// keyboard away, and the way out is still the flow's — `ScreenWayOut.current` reads it and
// `WayOutButton` draws it. What such a screen takes on is the status bar: with no header to
// spend it, that inset reaches the content unconsumed, and the content pads for it.
//
// ---------------------------------------------------------------------------
// Screen owns the insets, so a screen does not
// ---------------------------------------------------------------------------
//
// The header consumes the status bar inset and nothing else in this file does — with
// `ScreenHeader.None` nothing here spends it and the content receives it — and the body
// consumes the bottom one — the navigation bar or the gesture pill, unioned with the
// keyboard so the two never add up. `Modifier.windowInsetsPadding` CONSUMES what it applies, so a host that
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
// The visual vocabulary is the injected `SpfnTheme` and its Swift twin: a palette resolved
// from the appearance, six spacing steps, two radii, four type styles and one appearance per
// button kind. What is LEFT outside the theme is `Metrics` — the platform's minimum touch target and the header's height — because
// neither is a value a design flow gets to move (decision S10).
//
// ---------------------------------------------------------------------------
// A screen fills what it was offered, EXCEPT inside a sheet that fits
// ---------------------------------------------------------------------------
//
// The root fills and the body takes what the header left, which is what paints the
// background to the foot of the window and keeps the header still while the body scrolls
// under it. Inside a `SheetDetent.Fit` sheet both of those are wrong, and wrong in a way
// that looks like a sheet bug rather than a screen one: the sheet caps its height at the
// Full height and measures its content, the content fills the cap, and the Fit sheet stands
// exactly where a Full one would (docs/IMPLEMENTATION-PITFALLS.md P34).
//
// So `Sheet` says which detent it is drawing through `LocalFitsContent` and `ScreenLayout`
// turns that into the two extents this file applies. Everything that is not a Fit sheet
// reads the default and lays out exactly as it always did.
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import xyz.superfunction.spfn.ui.ScreenWayOut
import xyz.superfunction.spfn.ui.tokens.LocalSpfnTheme
import xyz.superfunction.spfn.ui.tokens.spfnPalette

/** Whether a [Screen] draws the SDK's header. Android's alone: on iOS the bar is the system's. */
public enum class ScreenHeader
{
    /** The SDK's header: a back or an item on the left, the title, a close or an item on the right. */
    Standard,

    /**
     * No header. The status bar inset reaches the content unconsumed, and the way out is the
     * app's to draw — [WayOutButton] draws the flow's, out of [ScreenWayOut].
     */
    None
}

/**
 * A screen inside a flow: a header, and a body under it.
 *
 * @param title what the header says. Left out, the header says nothing.
 * @param leading the header's left slot. Left out, the flow decides — a back arrow on a
 *   stack of two or more and on the root of a pushed flow, and nothing on the root of a flow
 *   presented over something (`Flow.wayOut`). A host app that passes one overrides that
 *   entirely. When the slot draws nothing it takes no width and the title starts at the
 *   header's gutter (`space4`, 16dp by default), as Material 3's top app bar does with no
 *   navigation icon; with a back or an item it is a 48dp slot placed where Material's
 *   navigation icon stands — its 24dp mark a gutter from the edge — and the title starts a
 *   gutter past the mark (56dp by default).
 * @param principal the header's centre, drawn instead of the title.
 * @param trailing the header's right slot. Left out, the flow decides — an X on the root of
 *   a modal or a sheet, and nothing anywhere else. A host app that passes one overrides that
 *   entirely, which is also how a screen suppresses the flow's own close. With an X or an
 *   item the 48dp slot stands where Material's action icon stands — its 24dp mark ending a
 *   gutter from the edge, centred 28dp from it by default — and the title ends a gutter short
 *   of the mark (56dp from the edge by default); an empty slot leaves the title ending 80dp
 *   from the edge. Passing an item that draws nothing still counts as an item.
 * @param header whether the SDK's header is drawn at all. [ScreenHeader.None] draws none,
 *   leaves the status bar inset to the content, and ignores the four header parameters above.
 * @param scroll whether the body scrolls. A body that scrolls also gets out of the
 *   keyboard's way; a body that does not is the caller saying its content always fits,
 *   which is what a screen inside a sheet says (see `Sheet.kt`). A screen that draws a
 *   [PagedView] passes `scroll = false` and is not being polite about it: that composable
 *   brings a `LazyColumn`, and a lazy list measured inside this one's `verticalScroll` is
 *   measured against an infinite height and throws at runtime. Section 22 of
 *   `tools/validate/validate.sh` reads that rule off the files that draw one.
 *
 * `@JvmSynthetic` for the reason `FlowHost` carries it: a `@Composable` function is a rule
 * the Compose compiler enforces for Kotlin callers and for nobody else, and from Java this
 * would be an ordinary static method whose first real argument is a `Composer`
 * (docs/IMPLEMENTATION-PITFALLS.md P15).
 */
@JvmSynthetic
@Composable
public fun Screen(
    title: String? = null,
    leading: (@Composable () -> Unit)? = null,
    principal: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    header: ScreenHeader = ScreenHeader.Standard,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
)
{
    val palette = spfnPalette();
    val focus = LocalFocusManager.current;
    val layout = ScreenLayout.forDetent(LocalFitsContent.current);
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(layout.root.asHeight())
            .background(palette.background)
            // A tap that lands on the frame rather than on a control puts the keyboard away.
            // `detectTapGestures` in a `pointerInput` does not consume a press a child
            // handles, so a button under this still gets its click.
            .pointerInput(Unit) {
                detectTapGestures { focus.clearFocus() };
            }
    )
    {
        if (header == ScreenHeader.Standard)
        {
            Header(title = title, leading = leading, principal = principal, trailing = trailing);
        }
        Body(extent = layout.body, scroll = scroll, content = content);
    }
}

/** This extent as a height of its own: all that was offered, or all the content asked for. */
private fun Extent.asHeight(): Modifier = when (this)
{
    Extent.Fill -> Modifier.fillMaxHeight()
    Extent.Wrap -> Modifier.wrapContentHeight()
};

/**
 * The header, and the only place the status bar inset is spent.
 *
 * The leading slot is laid out only when something is drawn in it — the app's `leading`, or
 * the flow's back — and then at the minimum touch target (docs/IMPLEMENTATION-PITFALLS.md P21:
 * a control smaller than 48dp reports a rectangle its neighbour has already claimed). When it
 * draws nothing it takes no width and the centre starts at the header's gutter, which is where
 * Material 3's top app bar puts its title when it has no navigation icon
 * (m3.material.io/components/top-app-bar/specs). When it draws, it stands where that bar's
 * navigation icon stands: its mark at the gutter and the title a gutter past the mark. Whether it draws is read from state before
 * layout ([HeaderLayout]), never measured. The trailing slot keeps its minimum width either
 * way; when it holds a control — the flow's X or the app's `trailing` — it stands where that
 * bar's action icon stands, its mark ending at the gutter and the title a gutter short of the
 * mark, so an X stands in the same place on every screen that has one.
 *
 * The centre is the title or the app's principal item, and either takes all the width the
 * slots leave — the same box, so a principal item starts where a title would.
 */
@Composable
private fun Header(
    title: String?,
    leading: (@Composable () -> Unit)?,
    principal: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?
)
{
    val gutter = LocalSpfnTheme.current.spacing.space4;
    val layout = HeaderLayout.of(
        gutter,
        appLeading = leading != null,
        appTrailing = trailing != null,
        wayOut = ScreenWayOut.current.wayOut
    );
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = Metrics.HEADER_HEIGHT)
            .padding(start = layout.edgeStart, end = layout.edgeEnd),
        verticalAlignment = Alignment.CenterVertically
    )
    {
        if (layout.leadingSlot != null)
        {
            Box(modifier = Modifier.sizeIn(minWidth = layout.leadingSlot), contentAlignment = Alignment.CenterStart)
            {
                if (leading != null) leading() else FlowBack();
            }
        }
        Box(
            modifier = Modifier.weight(1f).padding(start = layout.centreStart, end = layout.centreEnd),
            contentAlignment = Alignment.CenterStart
        )
        {
            if (principal != null) principal() else SpfnText(text = title ?: "", role = TextRole.Title);
        }
        Box(modifier = Modifier.sizeIn(minWidth = layout.trailingSlot), contentAlignment = Alignment.CenterEnd)
        {
            if (trailing != null) trailing() else FlowClose();
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
private fun ColumnScope.Body(extent: Extent, scroll: Boolean, content: @Composable ColumnScope.() -> Unit)
{
    val room = Modifier
        .fillMaxWidth()
        .then(share(extent))
        .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars));
    Column(modifier = if (scroll) room.verticalScroll(rememberScrollState()) else room)
    {
        content();
    }
}

/**
 * The body's share of its column: everything the header left, or its own content's height.
 *
 * The size constraint comes BEFORE `verticalScroll` in the chain above, and that order is
 * what makes a wrapped body scroll rather than overflow: the scroll node measures its child
 * against an infinite height and then takes the smaller of that child and the height it was
 * itself offered, so a body longer than the sheet's ceiling is capped there and scrolls
 * inside the cap. Chained the other way the scroll would be the thing being sized and the
 * cap would apply to nothing.
 */
private fun ColumnScope.share(extent: Extent): Modifier = when (extent)
{
    Extent.Fill -> Modifier.weight(1f)
    Extent.Wrap -> Modifier.wrapContentHeight()
};

/**
 * The header's LEFT slot when the app passed none: the flow's back, or nothing.
 *
 * The chrome arrives from `FlowHost`, which is the only thing that knows both how the flow
 * was entered and how deep it stands. [HeaderLayout.drawsFlowBack] is the test, and `Header`
 * asks it too, to decide before layout whether this slot takes any width. A `Screen` composed outside a host reads the default
 * — no control at all — rather than inventing one.
 */
@Composable
private fun FlowBack()
{
    val wayOut = ScreenWayOut.current;
    if (HeaderLayout.drawsFlowBack(wayOut.wayOut))
    {
        BackControl(onClick = wayOut::back);
    }
}

/**
 * The header's RIGHT slot when the app passed none: the flow's close, or nothing.
 *
 * The X lives here and the back lives on the left, which is decision N3 and is what both
 * platforms' users already reach for.
 */
@Composable
private fun FlowClose()
{
    val wayOut = ScreenWayOut.current;
    if (HeaderLayout.drawsFlowClose(wayOut.wayOut))
    {
        CloseControl(onClick = wayOut::close);
    }
}
