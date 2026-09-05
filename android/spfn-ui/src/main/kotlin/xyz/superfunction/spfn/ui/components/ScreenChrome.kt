// SPFN Mobile — what a flow tells the screens inside it.
//
// Counterpart of the `screenChrome` environment value in
// Sources/SPFNUI/Components/Screen.swift. A `Screen` has to draw a way out without knowing
// which flow it is in or how deep, and a `FlowHost` knows both and does not know which of
// its routes drew a header. A composition local is the one place those two meet without
// either of them holding the other.
//
// It is deliberately not part of the module's public vocabulary. A host app never builds
// one — `FlowHost` provides it and `Screen` reads it — and a public one would be a second
// way to answer a question `Flow.wayOut` already answers.

package xyz.superfunction.spfn.ui.components

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import xyz.superfunction.spfn.ui.WayOut

/**
 * The way out a flow's screens should draw, and what each of them does.
 *
 * Both actions are carried even though only one of them is ever drawn: which one that is
 * changes with the depth of the stack, and a chrome that carried only the current one would
 * have to be rebuilt to change its mind.
 */
internal data class ScreenChrome(
    val wayOut: WayOut,
    val onBack: () -> Unit,
    val onClose: () -> Unit
)

/**
 * The chrome in scope, defaulting to none.
 *
 * A `Screen` composed outside any `FlowHost` — a preview, a host app's own screen — reads
 * this default and draws no way out at all, which is the honest answer: nothing there knows
 * what going back would mean.
 */
internal val LocalScreenChrome: ProvidableCompositionLocal<ScreenChrome> =
    staticCompositionLocalOf { ScreenChrome(wayOut = WayOut.None, onBack = {}, onClose = {}) };
