// SPFN Mobile — the arithmetic a sheet is made of, with no toolkit under it.
//
// Counterpart of Sources/SPFNUI/SheetGeometry.swift, function for function and vector for
// vector. A sheet is the one presentation whose behaviour is a number rather than a rule
// about a list: how tall it stands, when a drag has gone far enough to dismiss it, and how
// far the scrim has faded on the way. Pulling those three out of the composable is what
// lets them be tested on a JVM at all — a drag is a gesture, but the decision a drag ends
// in is arithmetic, and arithmetic does not need a device.
//
// The two platforms do not draw sheets the same way and are not meant to: iOS hands the
// heights to `presentationDetents` and the system draws the sheet, Android draws its own.
// What they share is these numbers, so a `half` sheet is the same half on both.
//
// The unit is `Float` here and `Double` on the Swift side, which is the one place the two
// halves are spelled differently on purpose: every length Compose hands out is a `Float`
// and every length SwiftUI hands out is a `CGFloat`, and a module that converted at this
// boundary would be converting on every frame of every drag to make a comment true. The
// vectors both suites are written against are the same numbers either way.

package xyz.superfunction.spfn.ui

import kotlin.math.max
import kotlin.math.min

/** Where a sheet's heights and thresholds come from. */
public object SheetGeometry
{
    /**
     * How tall a sheet stands, in the same unit [container] and [content] are given in.
     *
     * [SheetDetent.Fit] is the only one that reads [content], and it is clamped to
     * [SheetDetent.Full] so that a screen with more content than the sheet has room for
     * becomes a full sheet rather than a sheet taller than the window. Content of zero or
     * less means nothing has been measured yet — which is the permanent state on a platform
     * that resolves detents itself rather than laying the sheet out — and it falls back to
     * [FIT_FALLBACK_FRACTION] rather than to a sheet of no height.
     *
     * A container of zero or less has no room for a sheet at all, and every detent gives
     * zero: a host that has not been measured yet draws nothing rather than something wrong.
     */
    public fun height(detent: SheetDetent, container: Float, content: Float): Float
    {
        if (container <= 0f)
        {
            return 0f;
        }
        val full = container * FULL_FRACTION;
        return when (detent)
        {
            SheetDetent.Full -> full
            SheetDetent.Half -> container * HALF_FRACTION
            SheetDetent.Fit -> if (content <= 0f) container * FIT_FALLBACK_FRACTION else min(content, full)
        };
    }

    /**
     * Whether releasing a sheet dragged [offset] down from its resting position dismisses it.
     *
     * The threshold is a fraction of the sheet's own [height] rather than a fixed distance,
     * so a short sheet is not harder to throw away than a tall one. An offset at or above
     * the threshold dismisses; a negative offset is a sheet dragged UP past where it rests,
     * which never dismisses. A sheet of no height cannot be dragged, so it never dismisses
     * either — which is what stops an unmeasured host from closing a flow nobody touched.
     */
    public fun closes(offset: Float, height: Float): Boolean
    {
        if (height <= 0f)
        {
            return false;
        }
        return offset >= height * DISMISS_FRACTION;
    }

    /**
     * How opaque the scrim behind a sheet is at [offset], from 1 at rest to 0 when the sheet
     * has been dragged its whole [height] away.
     *
     * The scrim fades with the drag rather than with the dismissal, which is what makes a
     * drag that is released short of the threshold read as a drag that did nothing.
     */
    public fun scrim(offset: Float, height: Float): Float
    {
        if (height <= 0f)
        {
            return 0f;
        }
        return min(1f, max(0f, 1f - offset / height));
    }

    /** The tallest a sheet stands, as a fraction of the space it was given. */
    public const val FULL_FRACTION: Float = 0.92f;

    /** What [SheetDetent.Half] means, as a fraction of the space the sheet was given. */
    public const val HALF_FRACTION: Float = 0.5f;

    /** What a [SheetDetent.Fit] sheet stands at while its content is unmeasured. */
    public const val FIT_FALLBACK_FRACTION: Float = 0.32f;

    /** How much of its own height a sheet is dragged down before releasing dismisses it. */
    public const val DISMISS_FRACTION: Float = 0.5f;
}
