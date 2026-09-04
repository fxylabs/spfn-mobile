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
 * The difference is what a back on the flow's LAST route LOOKS like, and each of the three
 * answers is a different act. A [Modal] flow closes — it was presented over something, and
 * dismissing it returns to what it covered. A [Sheet] closes for the same reason and can
 * also be dragged away. A [Push] flow closes too, and closing it is what returns the person
 * to the host's own screen: its stack was appended to the host's inside a [NavigationHost],
 * so the route under its root is the host's own.
 *
 * What differs between them is therefore the CONTROL rather than the outcome: a pushed
 * flow's root offers a back, because what is under it is the host's screen, and a presented
 * flow's root offers a close, because what is under it is the screen it covered.
 *
 * The whole table is in [Flow.back] and [Flow.wayOut] rather than in either host, which is
 * what keeps the two platforms saying the same thing about the same gesture.
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
 * What a screen's way out is, decided by how the flow was entered and how deep it stands.
 * See [Flow.wayOut].
 *
 * Named for what it MEANS rather than for where it is drawn. It was `ScreenLeading` while
 * both controls lived in the header's left slot; the close is now an X in the RIGHT one
 * (decision N3), and a value called "leading" that decides what the trailing slot draws is
 * a name that has to be unlearned at every call site.
 */
public enum class WayOut
{
    /** No way out of this screen's own: a closed flow, and nothing else. */
    None,

    /**
     * A back control in the header's leading slot, which pops one route — or, on the root
     * of a pushed flow, hands the person back to the host by closing it.
     */
    Back,

    /** A close control in the header's trailing slot, which closes the whole flow. */
    Close
}
