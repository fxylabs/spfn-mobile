// SPFN Mobile — the state of one read.
//
// Counterpart of Sources/SPFNUI/Loadable.swift. Four states and no fifth: a screen is
// waiting, it has a value, it has looked and there is nothing, or it failed. `Empty` is
// separate from `Ready` on purpose — "the server answered with no rows" and "the server
// answered with rows" are different screens, and a caller that has to ask
// `value.isEmpty()` to tell them apart will forget to somewhere.
//
// The error is core's own envelope rather than a type this module invents. A UI layer
// that re-wrapped it would have to decide what to drop, and the answer a screen needs —
// the code — is already the field callers classify on.

package xyz.superfunction.spfn.ui

import xyz.superfunction.spfn.core.SpfnErrorEnvelope

/** What one read can be. */
public sealed interface Loadable<out V>
{
    /** The read is in flight and has never completed. */
    public data object Loading : Loadable<Nothing>

    /** The read completed and produced [value]. */
    public data class Ready<V>(val value: V) : Loadable<V>

    /** The read completed and there is nothing to show. */
    public data object Empty : Loadable<Nothing>

    /** The read failed, carrying the envelope the server or the transport produced. */
    public data class Error(val error: SpfnErrorEnvelope) : Loadable<Nothing>
}
