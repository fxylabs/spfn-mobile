// SPFN Mobile — the state of one read.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/Loadable.kt.
// Four states and no fifth: a screen is waiting, it has a value, it has looked and there
// is nothing, or it failed. `empty` is separate from `ready` on purpose — "the server
// answered with no rows" and "the server answered with rows" are different screens, and a
// caller that has to ask `value.isEmpty` to tell them apart will forget to somewhere.
//
// The error is core's own envelope rather than a type this module invents. A UI layer that
// re-wrapped it would have to decide what to drop, and the answer a screen needs — the
// code — is already the field callers classify on.

import SPFNCore

/// What one read can be.
public enum Loadable<Value: Sendable>: Sendable
{
    /// The read is in flight and has never completed.
    case loading

    /// The read completed and produced this value.
    case ready(Value)

    /// The read completed and there is nothing to show.
    case empty

    /// The read failed, carrying the envelope the server or the transport produced.
    case error(SPFNErrorEnvelope)
}

// Conditional rather than unconditional: a `Value` that is not comparable does not make
// the other three states incomparable, but it does make the whole enum so, and pretending
// otherwise would need an `==` this type cannot honestly write.
extension Loadable: Equatable where Value: Equatable {}
