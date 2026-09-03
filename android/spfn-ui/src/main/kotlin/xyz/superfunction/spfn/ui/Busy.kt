// SPFN Mobile — the state of one write.
//
// Counterpart of Sources/SPFNUI/Busy.swift. A write has no `Ready` and no `Empty`: what
// it produces is a changed read, so the value belongs to a [Loadable] somewhere else and
// the button only needs to know whether it may be pressed and whether the last press
// failed.
//
// The `Busy` nested inside the `Busy` interface is the parity name, not an oversight:
// Swift's counterpart is `case busy` and the validator compares the two sets after
// lowercasing, so renaming it here would make the names differ in the one place this
// repository checks that they do not. It costs one import: inside the interface body the
// nested classifier shadows the interface, so every member's supertype is written through
// the alias below rather than as the bare name, which would resolve to the object and
// fail with "Cannot extend an object".

package xyz.superfunction.spfn.ui

import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.ui.Busy as BusyState

/** What one write can be. */
public sealed interface Busy
{
    /** No write is in flight; the control that starts one may be pressed. */
    public data object Idle : BusyState

    /** A write is in flight; the control that started it may not be pressed again. */
    public data object Busy : BusyState

    /** The last write failed, carrying the envelope the server or the transport produced. */
    public data class Error(val error: SpfnErrorEnvelope) : BusyState
}
