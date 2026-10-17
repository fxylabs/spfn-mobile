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

/// What a wire listener receives (`listenWire`, revision 4, S-2): several names in ONE
/// queue, values left as the server sent them, and the connection's own facts beside them.
/// One queue is the point: a frame of connection e is never handled before `opened` of e.
public enum SPFNWireSignal: Equatable, Sendable
{
    /// The first signal, whatever the connection is doing.
    case attached

    /// A connection opened — or, for a listener attached while one is open, that connection
    /// once more right after `attached`. Queued frames of older connections are dropped.
    case opened(SPFNStreamConnected)

    /// One frame of one of the listener's names: the envelope's `data`, undecoded.
    case frame(name: String, value: SPFNCanonicalValue)

    /// The server granted or revoked one of the listener's names on the open connection (S-3).
    case subscription(name: String, status: SPFNSubscriptionStatus)

    /// This listener's queue filled; its queued frames were dropped. Connection signals stay.
    case overflow

    /// Names of this listener the server does not serve; each name is reported once.
    case unavailable([String])
}

/// The server's `connected` frame, with what the client knows about the connection it opened.
public struct SPFNStreamConnected: Equatable, Sendable
{
    /// The stream's open count, the `epoch` of `open(epoch:)`.
    public let epoch: Int
    /// The query fields this connection was opened with (`setQuery`).
    public let query: [String: String]
    public let subscribedEvents: [String]
    public let deniedEvents: [String]
    /// The events whose initial frames follow; nil from a server that predates them.
    public let initial: [String]?

    public init(epoch: Int, query: [String: String], subscribedEvents: [String], deniedEvents: [String], initial: [String]?)
    {
        self.epoch = epoch
        self.query = query
        self.subscribedEvents = subscribedEvents
        self.deniedEvents = deniedEvents
        self.initial = initial
    }
}

public enum SPFNSubscriptionStatus: Equatable, Sendable
{
    case granted
    case revoked
}
