// SPFN Mobile — the way out of the screen being composed, for a view that draws its own.
//
// Counterpart of Sources/SPFNUI/ScreenWayOut.swift. `ScreenChrome` is what a `FlowHost` tells
// the screens inside it and it stays internal: a host app never provides one, and a public
// one would be a second way to answer a question `Flow.wayOut` already answers. What is
// public is the READING of it — which way out this screen has, and the two acts — so a screen
// drawn with `ScreenHeader.None`, or any view of the app's own, can offer the way out the flow
// means without knowing which flow it is in.
//
// Read-only by construction: the constructor is internal and the composition local behind
// [ScreenWayOut.current] is internal too, so an app can read a way out and cannot make one.

package xyz.superfunction.spfn.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import xyz.superfunction.spfn.ui.components.LocalScreenChrome

/**
 * The way out of the screen being composed, and the two acts that take it.
 *
 * [None] outside any `FlowHost`: nothing there knows what going back would mean. Inside one,
 * [wayOut] is the flow's answer for this screen's depth and entry, and both acts go through
 * the flow — [back] is `Flow.back(entry)`, which is what the system back does too, and
 * [close] is `Flow.close()`.
 *
 * Both acts are carried even though a screen offers one of them: which one changes with the
 * depth of the stack.
 */
public class ScreenWayOut internal constructor(
    /** The way out this screen has: a back, a close, or none. */
    public val wayOut: WayOut,
    private val onBack: () -> Unit,
    private val onClose: () -> Unit
)
{
    /** Goes back one step, the way the system back does. */
    public fun back()
    {
        onBack();
    }

    /** Closes the flow, the way the close in the header does. */
    public fun close()
    {
        onClose();
    }

    public companion object
    {
        /** The way out of a screen composed outside any flow: none, and two acts that do nothing. */
        @JvmField
        public val None: ScreenWayOut = ScreenWayOut(WayOut.None, {}, {});

        /**
         * The way out of the screen being composed, read out of the chrome its `FlowHost`
         * provided. The Compose spelling of SwiftUI's `@Environment(\.screenWayOut)`.
         */
        @get:JvmSynthetic
        public val current: ScreenWayOut
            @Composable
            @ReadOnlyComposable
            get() = LocalScreenChrome.current.let { ScreenWayOut(it.wayOut, it.onBack, it.onClose) };
    }
}
