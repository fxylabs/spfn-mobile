// SPFN Mobile — where a sheet is in the one round trip it makes.
//
// This is `Sheet.kt`'s memory, split out because it is the only part of a sheet a JVM test
// can drive: the composable needs a Compose runtime and a frame clock, the device this
// module is watched on is attached to a Mac, and what is left over here is arithmetic over
// an enum. `SheetPhaseTest` drives all of it.
//
// The split earns its file because the two rules below are the ones a phone caught this
// module getting wrong, and neither is visible in a screenshot of a sheet at rest
// (docs/IMPLEMENTATION-PITFALLS.md P38).
//
// ---------------------------------------------------------------------------
// Hidden means two different things, and only the phase tells them apart
// ---------------------------------------------------------------------------
//
// A sheet rests at `SheetAnchor.Hidden` twice: before it has stood up, and after it has
// been dragged away. The anchor is the same anchor and the offset is the same number, so
// "the sheet settled at Hidden" cannot on its own mean "the user closed it" — read that way,
// a sheet closes the instant it is composed, before anybody has seen it. Only [Standing]
// treats an arrival at Hidden as a dismissal, and [Standing] is reached only by a rise that
// ran to its end.
//
// ---------------------------------------------------------------------------
// Being asked to go is not being gone
// ---------------------------------------------------------------------------
//
// A flow's stack empties the moment it closes, and the sheet drawn from that stack still has
// a slide to run. So the host's request ([asked] with `open = false`) and the sheet's
// departure ([arrived] out of [Falling]) are two events, and only the second one — [Gone] —
// means the host may stop drawing. Between them the sheet is [Falling]: still composed, still
// moving, and no longer anybody's to drag.

package xyz.superfunction.spfn.ui

/**
 * The one round trip a sheet makes: composed, up, standing, down, gone.
 *
 * A phase is not a position. [Rising] and [Falling] are both drawn somewhere between the two
 * anchors, and which anchor a sheet is nearest says nothing about which way it is going —
 * that is the whole reason this enum exists rather than a reading of `AnchoredDraggableState`.
 */
internal enum class SheetPhase
{
    /** Composed, with no anchors yet: there is nowhere to move to and nothing to read. */
    Unmeasured,

    /** Measured, and on its way up to its detent. */
    Rising,

    /** Standing at its detent, which is the only phase a finger may take over. */
    Standing,

    /** Asked to go, and still on its way out of sight. */
    Falling,

    /** Settled out of sight after being asked to go. The host may stop drawing it. */
    Gone
}

/**
 * The anchor this phase is travelling to, or null when it is standing still.
 *
 * `Sheet` spends this on one `animateTo`, which is what makes an arrival and a departure the
 * same motion a drag makes — one path, one animation spec, one place to look.
 */
internal val SheetPhase.destination: SheetAnchor?
    get() = when (this)
    {
        SheetPhase.Rising -> SheetAnchor.Open
        SheetPhase.Falling -> SheetAnchor.Hidden
        else -> null
    };

/**
 * Whether a finger may take this sheet over.
 *
 * Only a sheet that has finished standing up. A drag cancels whatever `animateTo` is running,
 * and a cancelled travel never reaches the line that advances the phase — so a sheet grabbed
 * mid-rise would be left in a phase that no longer describes it, and a sheet grabbed mid-fall
 * would be pulled back into a flow that has already closed. The handle is simply not draggable
 * until there is a standing sheet to drag.
 */
internal val SheetPhase.draggable: Boolean
    get() = this == SheetPhase.Standing;

/**
 * What a measurement and the host's [open] make of this phase.
 *
 * [Unmeasured] is the only phase that waits: with no anchors there is no distance to animate
 * over, so a sheet closed before it was ever measured goes straight to [Gone] rather than
 * sliding out of a position it never held.
 *
 * [Falling] and [Gone] both answer [Rising] to `open = true`. A sheet re-opened while it is
 * leaving turns around where it stands, and one re-opened in the frame between its departure
 * and the host noticing stands back up instead of staying stuck out of sight.
 */
internal fun SheetPhase.asked(measured: Boolean, open: Boolean): SheetPhase = when (this)
{
    SheetPhase.Unmeasured -> if (!measured) this else if (open) SheetPhase.Rising else SheetPhase.Gone
    SheetPhase.Rising, SheetPhase.Standing -> if (open) this else SheetPhase.Falling
    SheetPhase.Falling, SheetPhase.Gone -> if (open) SheetPhase.Rising else this
};

/**
 * Where a travel that ran all the way to its anchor leaves this phase.
 *
 * Called only by the travel itself, after the `animateTo` it is made of returns. A travel that
 * was cancelled — by the next phase's travel, which takes the same mutator mutex — never gets
 * here, which is what keeps a phase from claiming an arrival that did not happen.
 */
internal fun SheetPhase.arrived(): SheetPhase = when (this)
{
    SheetPhase.Rising -> SheetPhase.Standing
    SheetPhase.Falling -> SheetPhase.Gone
    else -> this
};

/**
 * Whether coming to rest at [settled] is the user asking to close.
 *
 * Only from [Standing]: everywhere else a sheet at [SheetAnchor.Hidden] is one that has not
 * stood up yet or one the host already asked to leave, and reporting either as a dismissal
 * closes a flow nobody touched.
 */
internal fun SheetPhase.dismisses(settled: SheetAnchor): Boolean =
    this == SheetPhase.Standing && settled == SheetAnchor.Hidden;
