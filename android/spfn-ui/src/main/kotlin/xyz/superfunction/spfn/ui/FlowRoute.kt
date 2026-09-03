// SPFN Mobile — what a flow navigates over, and how it is entered.
//
// Counterpart of Sources/SPFNUI/FlowRoute.swift. A route is the app's own type: this
// module holds the stack, the app holds what is on it. The marker exists so `Flow` can
// name a bound rather than accept `Any`, which is what would otherwise let two flows'
// routes end up on one stack.
//
// `FlowEntry` was an enum until a third presentation arrived that carries data. A sheet
// stands at a height, that height is part of how the flow is entered, and an enum entry
// cannot carry it without giving `Modal` and `Push` a detent they have no use for. A
// sealed interface says the same thing with the payload where it belongs, and it leaves
// `FlowEntry.Modal` and `FlowEntry.Push` spelled exactly as they were for every caller.

package xyz.superfunction.spfn.ui

/**
 * A destination inside one flow.
 *
 * Implementations are the app's own — typically a sealed hierarchy of data classes, so
 * that two routes carrying the same payload are the same entry. Nothing here requires
 * that; a route that is not a value type simply makes the stack compare by identity.
 */
public interface FlowRoute

/**
 * How a flow's host presents its stack.
 *
 * The difference is what happens to a back on the flow's LAST route, and each of the three
 * answers is a different act. A [Modal] flow closes — it was presented over something, and
 * dismissing it returns to what it covered. A [Sheet] closes for the same reason and can
 * also be dragged away. A [Push] flow does not handle it at all, because it was pushed
 * onto the host app's own stack and the host app's back is what should apply.
 *
 * The whole table is in [Flow.back] and [Flow.leading] rather than in either host, which
 * is what keeps the two platforms saying the same thing about the same gesture.
 */
public sealed interface FlowEntry
{
    /** Presented over the screen that opened it; back on the last route closes the flow. */
    public data object Modal : FlowEntry

    /** Pushed onto the surrounding navigation; back on the last route is the host app's. */
    public data object Push : FlowEntry

    /**
     * Presented as a sheet standing at [detent], over the screen that opened it.
     *
     * A sheet is a modal that can also be dismissed by dragging it down, and the height it
     * stands at is fixed by [detent] for the whole flow: pushing a route inside a sheet
     * navigates within that height rather than resizing it.
     */
    public data class Sheet(public val detent: SheetDetent) : FlowEntry
}

/**
 * How tall a sheet stands.
 *
 * Three heights and no fourth, because a height a caller can name is a height both
 * platforms can honour: iOS resolves these onto `presentationDetents` and Android
 * resolves them with [SheetGeometry] against the space the host gave the flow.
 */
public enum class SheetDetent
{
    /** As tall as its content needs, and never taller than [Full]. */
    Fit,

    /** Half the available height. */
    Half,

    /** As tall as a sheet goes, which is short of the whole screen by design. */
    Full
}

/**
 * What a screen's leading control is, decided by how the flow was entered and how deep it
 * stands. See [Flow.leading].
 */
public enum class ScreenLeading
{
    /** No leading control: the flow's root of a pushed flow has nothing of its own to do. */
    None,

    /** A back control, which pops one route. */
    Back,

    /** A close control, which closes the whole flow. */
    Close
}
