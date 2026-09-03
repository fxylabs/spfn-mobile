// SPFN Mobile — the state of one write.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/Busy.kt. A
// write has no `ready` and no `empty`: what it produces is a changed read, so the value
// belongs to a `Loadable` somewhere else and the button only needs to know whether it may
// be pressed and whether the last press failed.

import SPFNCore

/// What one write can be.
public enum Busy: Sendable, Equatable
{
    /// No write is in flight; the control that starts one may be pressed.
    case idle

    /// A write is in flight; the control that started it may not be pressed again.
    case busy

    /// The last write failed, carrying the envelope the server or the transport produced.
    case error(SPFNErrorEnvelope)
}
