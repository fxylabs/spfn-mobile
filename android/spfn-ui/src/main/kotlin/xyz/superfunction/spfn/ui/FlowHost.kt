// SPFN Mobile — the one place a Flow is bound to the platform navigator.
//
// Counterpart of Sources/SPFNUI/FlowHost.swift, and the only file in this module that
// imports Compose. Navigation 3 is what renders the stack: NavDisplay takes a plain
// `List` and an `onBack`, which is the shape that lets the Flow stay the single source of
// truth. Nothing here keeps a second copy of the stack and nothing here mutates the list
// it was handed — a system back turns into `flow.pop()`, and the next state arrives the
// same way every other state arrives, off the flow.
//
// NavDisplay disables its own back handling when the current scene has no previous entry,
// which is precisely the last-route case. That is what makes the two entry styles a
// three-line difference rather than two hosts: `Push` leaves it disabled and the host
// app's back applies, and `Modal` puts its own handler over exactly that gap and closes.

package xyz.superfunction.spfn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay

/**
 * Renders [flow]'s top route and follows the stack as it changes.
 *
 * Renders nothing at all while the flow is closed, which is not a special case bolted on:
 * NavDisplay requires a non-empty back stack and refuses an empty one, so a closed flow
 * has nothing to show by the navigator's own rule as well as by this module's.
 *
 * `@JvmSynthetic` is not decoration either. A `@Composable` function may only be called
 * from a composition, and the Compose compiler enforces that for Kotlin callers and for
 * nobody else: from Java this is an ordinary static method taking a `Composer`, and
 * calling it is a crash rather than a compile error. Erasing it from Java's view is the
 * only place the rule can be stated to a Java caller at all
 * (docs/IMPLEMENTATION-PITFALLS.md P15).
 */
@JvmSynthetic
@Composable
public fun <R : FlowRoute> FlowHost(flow: Flow<R>, entry: FlowEntry, content: @Composable (R) -> Unit)
{
    val routes: List<R> = flow.stack.collectAsState().value;
    if (routes.isEmpty())
    {
        return;
    }

    BackHandler(enabled = entry == FlowEntry.Modal && routes.size == 1) { flow.close() };

    NavDisplay(
        backStack = routes,
        onBack = { flow.pop() },
        entryProvider = { route -> NavEntry(route) { content(it) } }
    );
}
