// SPFN Mobile — a stack of routes, and the five things that can happen to it.
//
// Counterpart of Sources/SPFNUI/Flow.swift. Deliberately free of Compose: every rule
// this class holds is a rule about a list, so it is a plain Kotlin class that a JVM unit
// test can drive through the whole transition table without a device, an emulator or a
// composition. FlowHost is the only file in this module that imports Compose.
//
// One state, not two. `isPresented` is exactly `stack.isNotEmpty()` — an open flow always
// stands on at least one route, which is why `open(at = emptyList())` is refused rather
// than accepted as a way of being open with nothing to show. The two StateFlows are kept
// in step by a single private mover that orders its two writes so that no observer can
// ever read `isPresented == true` beside an empty stack.

package xyz.superfunction.spfn.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The navigation state of one flow.
 *
 * A flow is closed when its stack is empty and open when it is not. [push] on a closed
 * flow opens it; [close] is the only thing that closes one, and [pop] on the last route
 * is a no-op rather than a close — a screen's back gesture and a flow's dismissal are
 * different acts, and collapsing them would make every last-route back tear the flow down
 * whether the host asked for that or not. [FlowHost] is where that distinction is spent:
 * a `Modal` host turns the last back into [close], a `Push` host lets it fall through.
 *
 * Nothing here is thread-confined by the type system, which is the one place this class
 * is weaker than its Swift counterpart: `SPFNUI.Flow` is `@MainActor` and the compiler
 * refuses an off-main mutation, while Kotlin has no equivalent to declare. Callers mutate
 * a flow from the main dispatcher.
 */
public class Flow<R : FlowRoute>(initial: List<R> = emptyList())
{
    private val mutableStack: MutableStateFlow<List<R>> = MutableStateFlow(initial.toList());
    private val mutableIsPresented: MutableStateFlow<Boolean> = MutableStateFlow(initial.isNotEmpty());

    /** The routes, oldest first. Empty exactly when the flow is closed. */
    public val stack: StateFlow<List<R>> = mutableStack.asStateFlow();

    /** Whether the flow is open. True exactly when [stack] is not empty. */
    public val isPresented: StateFlow<Boolean> = mutableIsPresented.asStateFlow();

    /** Puts [route] on top. On a closed flow this opens it on that one route. */
    public fun push(route: R)
    {
        moveTo(mutableStack.value + route);
    }

    /**
     * Drops the top route.
     *
     * A no-op on a stack of one: a flow standing on its first route has nothing to go back
     * to, and closing it is [close]'s job.
     */
    public fun pop()
    {
        val current = mutableStack.value;
        if (current.size > 1)
        {
            moveTo(current.dropLast(1));
        }
    }

    /** Swaps the top route for [route], leaving everything under it. A no-op when closed. */
    public fun replace(route: R)
    {
        val current = mutableStack.value;
        if (current.isNotEmpty())
        {
            moveTo(current.dropLast(1) + route);
        }
    }

    /**
     * Opens the flow on a whole stack at once — a deep link, or a restored session.
     *
     * @throws IllegalArgumentException when [at] is empty. An open flow with nothing on it
     * is not a state this type has: it would present a host with no route to render, and
     * the platform navigators underneath refuse an empty back stack outright.
     */
    public fun open(at: List<R>)
    {
        require(at.isNotEmpty()) { "a flow cannot be opened on an empty stack" };
        moveTo(at.toList());
    }

    /** Closes the flow and forgets its routes. A no-op on a flow that is already closed. */
    public fun close()
    {
        moveTo(emptyList());
    }

    /**
     * The one writer.
     *
     * The order of the two writes is the invariant: growing publishes the stack first and
     * the flag second, emptying publishes the flag first and the stack second, so an
     * observer that reads one after the other never sees a presented flow with no routes.
     * Both are [MutableStateFlow]s, which conflate an equal value, so re-applying the state
     * a flow is already in emits nothing at all.
     */
    private fun moveTo(next: List<R>)
    {
        if (next.isEmpty())
        {
            mutableIsPresented.value = false;
            mutableStack.value = next;
            return;
        }
        mutableStack.value = next;
        mutableIsPresented.value = true;
    }
}
