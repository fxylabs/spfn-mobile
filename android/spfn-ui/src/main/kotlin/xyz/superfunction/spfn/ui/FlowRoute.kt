// SPFN Mobile — what a flow navigates over, and how it is entered.
//
// Counterpart of Sources/SPFNUI/FlowRoute.swift. A route is the app's own type: this
// module holds the stack, the app holds what is on it. The marker exists so `Flow` can
// name a bound rather than accept `Any`, which is what would otherwise let two flows'
// routes end up on one stack.

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
 * The difference is what happens to the system back gesture on the flow's LAST route.
 * A [Modal] flow closes — it was presented over something, and dismissing it returns to
 * what it covered. A [Push] flow does not handle it at all, because it was pushed onto
 * the host app's own stack and the host app's back is what should apply.
 */
public enum class FlowEntry
{
    /** Presented over the screen that opened it; back on the last route closes the flow. */
    Modal,

    /** Pushed onto the surrounding navigation; back on the last route is the host app's. */
    Push
}
