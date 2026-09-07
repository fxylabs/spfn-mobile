// SPFN Mobile — how much of the height a `Screen` takes, and who tells it.
//
// There is no counterpart file on iOS. SwiftUI resolves a `Fit` detent by MEASURING the
// content and then laying the sheet out at that height (`SheetGeometry.fitHeight`), so the
// screen inside never has to know which detent it stands in. Android lays the sheet out
// itself, in one pass, and a screen that fills whatever it is offered turns "as tall as the
// content" into "as tall as the ceiling" — which is what this file exists to stop.
//
// ---------------------------------------------------------------------------
// A finite maximum is not a licence to fill it
// ---------------------------------------------------------------------------
//
// `Sheet` gives a `Fit` sheet `wrapContentHeight().heightIn(max = full)`: measure the
// content, and cap the answer. That reads as "as tall as it needs" and is not, because the
// cap is FINITE and everything under it was written to fill what it was given. `Screen`'s
// root asked for `fillMaxSize()` and its body asked for `weight(1f)`, so the column resolved
// to the ceiling, the wrap measured the ceiling, and a Fit sheet stood at exactly the height
// of a Full one — 290px from the top of a Galaxy Z Flip4 with its content ending at 1130
// (docs/IMPLEMENTATION-PITFALLS.md P34).
//
// So the sheet says what it is and the screen answers for its own height. [LocalFitsContent]
// carries the sentence and [ScreenLayout] is the answer, kept as a value rather than as an
// `if` inside the composable: this repository has no Compose UI test infrastructure — no
// Robolectric, no instrumented suite — so a branch written inline is a branch nothing on a
// JVM can read. As a pure function of one boolean it has four cells and a unit test.

package xyz.superfunction.spfn.ui.components

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/** What a box does with the height it was offered. */
internal enum class Extent
{
    /** Takes all of it, whatever the content came to. */
    Fill,

    /** Takes what the content came to, and no more. */
    Wrap
}

/**
 * What a `Screen`'s root and its body do with the height they were offered.
 *
 * Both terms, and not one. The root is what the background is painted on and the body is
 * what the bottom insets are spent on, and a root that wrapped over a body that still filled
 * would resolve to the ceiling exactly as before — the wrap would measure a child that had
 * already taken everything.
 */
internal data class ScreenLayout(val root: Extent, val body: Extent)
{
    internal companion object
    {
        /**
         * The layout for a screen whose presentation stands at its content's height
         * ([fits]), and the layout for every other screen.
         *
         * `false` is the answer everywhere outside a `Fit` sheet — a pushed screen, a modal
         * cover, a half or full sheet, and a `Screen` composed by a host app with no flow
         * around it at all. Those fill, which is what paints the background to the foot of
         * the window on a screen whose content is two lines long.
         */
        fun forDetent(fits: Boolean): ScreenLayout =
            if (fits) ScreenLayout(root = Extent.Wrap, body = Extent.Wrap)
            else ScreenLayout(root = Extent.Fill, body = Extent.Fill);
    }
}

/**
 * Whether the presentation a screen stands in is as tall as that screen's content.
 *
 * Provided by `Sheet` and by nothing else, and read by `Screen`. The default is `false`
 * because that is what everything that is not a `Fit` sheet means: a screen with no sheet
 * above it is offered a whole window and is meant to take it.
 *
 * Not part of the module's public vocabulary, for the reason [LocalScreenChrome] is not: a
 * host app never builds one, and a second door onto "how tall am I" is a second answer to a
 * question the detent already answers.
 */
internal val LocalFitsContent: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false };
