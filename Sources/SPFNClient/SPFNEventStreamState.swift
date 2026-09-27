// SPFN Mobile — the event stream's state, for diagnostics and decoration.
//
// A screen never decides when to read from this value: that is the reread signal's job
// (SPFNEventSignal). The state says what the connection is doing, and the example app's
// events screen prints it (docs/architecture/event-stream-design.md §3-2).
//
// android/spfn-client/.../SpfnEventStreamState.kt is the same vocabulary in Kotlin.

public enum SPFNEventStreamState: Equatable, Sendable
{
    /// Not the moment to connect: nobody is signed in, or the app is in the background.
    case idle(SPFNIdleReason)

    /// Minting a token or opening the stream. `attempt` counts from the last stable open.
    case connecting(attempt: Int)

    /// The server's `connected` frame arrived. `epoch` counts opens of this object, from 1.
    /// `unavailableEvents` are configured names the server does not serve; the stream was
    /// reopened without them (§10 Q-F).
    case open(epoch: Int, unavailableEvents: [String] = [])

    /// Waiting `delayMillis` before the next attempt.
    case retrying(attempt: Int, delayMillis: Int64, reason: SPFNRetryReason)

    /// The moment to connect, but no network: waiting for one, with no timer.
    case offline(attempt: Int)

    /// Refused for good. Left only when a condition drops and returns (E-44, E-43).
    case closed(SPFNCloseReason)
}

public enum SPFNIdleReason: Equatable, Sendable
{
    case signedOut
    case background
}

public enum SPFNRetryReason: Equatable, Sendable
{
    case network
    case silence
    case serverClosed
    case serverError(status: Int)
    case tokenRejected
    case unreadable
}

public enum SPFNCloseReason: Equatable, Sendable
{
    case unauthorized
    case forbidden
    case unknownEvents([String])
}
