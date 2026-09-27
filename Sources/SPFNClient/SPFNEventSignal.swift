// SPFN Mobile — what a listener receives, and how a payload describes itself.
//
// A frame is a signal, not state: delivery is at most once and a missed frame never
// returns. So besides the frames that pass its condition, a listener receives a reread
// signal whenever what it holds may be stale — when it attaches, when a new connection
// opens, when its own queue overflowed — and reads again (design §3-4).
//
// android/spfn-client/.../SpfnEventSignal.kt is the same vocabulary in Kotlin.

import SPFNCore

public enum SPFNEventSignal<Event: Sendable>: Sendable
{
    /// Read again. Delivered whatever the listener's condition says (L-13).
    case reread(SPFNRereadCause)

    /// One frame whose payload decoded and passed the listener's condition.
    case frame(Event)

    /// The server does not serve this listener's event name, so the stream runs without it
    /// and no frame of it will arrive (§10 Q-F). Sent once per listener. Rereads still come.
    case unavailable
}

extension SPFNEventSignal: Equatable where Event: Equatable {}

public enum SPFNRereadCause: Equatable, Sendable
{
    case attached
    case opened(epoch: Int)
    case overflow
}

/// An event name and its decoder in one place, so a call site writes the payload's type
/// alone: `listen(SessionActivity.self)`.
public protocol SPFNEventPayload: Sendable
{
    static var eventName: String { get }

    init(canonical: SPFNCanonicalValue) throws
}

/// Counts, never contents: a payload is never logged (§6).
public struct SPFNEventDiagnostics: Equatable, Sendable
{
    /// Frames a listener's decoder could not read (L-11).
    public var droppedFrames: Int64
    /// Frames a listener's condition declined (L-10).
    public var filteredFrames: Int64
    /// Frames named outside the configured list (E-23).
    public var unexpectedFrames: Int64

    public init(droppedFrames: Int64 = 0, filteredFrames: Int64 = 0, unexpectedFrames: Int64 = 0)
    {
        self.droppedFrames = droppedFrames
        self.filteredFrames = filteredFrames
        self.unexpectedFrames = unexpectedFrames
    }
}
