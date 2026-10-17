// SPFN Mobile — the state client's vocabulary: keys, frames, entries, connection status.
//
// An entry is one of three things and only those three: nothing has arrived yet
// (`loading`), a value is served (`ready`), or the server stopped serving it (`revoked`,
// with the last value if one arrived). The web client's `StateEntry` is a union of
// optional fields; here the combinations it never produces cannot be written
// (docs/architecture/state-client-design.md §2-11).
//
// android/spfn-state/.../SpfnStateTypes.kt is the same vocabulary in Kotlin.

import SPFNCore

/// A keyed state's key: its key fields and their values. A keyless state's key is `[:]`.
public typealias SPFNStateKey = [String: String]

/// One value of one state as the server stamped it: the read's `at`, its `value`, its `data`.
public struct SPFNStateFrame<Value: Sendable, Data: Sendable>: Sendable
{
    /// `new Date().toISOString()` on the server, compared as a string (§2-5).
    public let at: String
    public let value: Value
    public let data: Data

    public init(at: String, value: Value, data: Data)
    {
        self.at = at
        self.value = value
        self.data = data
    }
}

extension SPFNStateFrame: Equatable where Value: Equatable, Data: Equatable {}

/// The `Data` of a state declared without `.data()`.
public struct SPFNNoStateData: Equatable, Sendable
{
    public init() {}
}

public enum SPFNStateStatus: Equatable, Sendable
{
    case loading
    case ready
    case revoked
}

public enum SPFNStateEntry<Value: Sendable, Data: Sendable>: Sendable
{
    /// No value has arrived for this key yet.
    case loading

    /// The server serves this key; the frame is the newest value.
    case ready(SPFNStateFrame<Value, Data>)

    /// The server stopped serving this key to this subject; the last value, if any arrived.
    case revoked(SPFNStateFrame<Value, Data>?)

    public var status: SPFNStateStatus
    {
        switch self
        {
        case .loading:
            return .loading
        case .ready:
            return .ready
        case .revoked:
            return .revoked
        }
    }

    public var frame: SPFNStateFrame<Value, Data>?
    {
        switch self
        {
        case .loading:
            return nil
        case .ready(let frame):
            return frame
        case .revoked(let frame):
            return frame
        }
    }

    public var value: Value?
    {
        frame?.value
    }
}

extension SPFNStateEntry: Equatable where Value: Equatable, Data: Equatable {}

/// Whether the server's stream is serving this client (§2-9).
public enum SPFNStateConnection: Equatable, Sendable
{
    case connected

    /// No stream is open yet, nothing is subscribed, or the stream is retrying a dropped one;
    /// `attempt` is 0 until a failure since the last open or restart.
    case reconnecting(attempt: Int)

    /// The first snapshot spent `maxRetries`, or the server refused the stream.
    case failed
}

/// A snapshot already read — `snapshot()`'s answer, or a fixture — to start from (§2-10).
public struct SPFNStateInitial: Sendable
{
    public let snapshot: SPFNStatesSnapshot
    /// The query the snapshot was read with: keyed states apply under its key.
    public let query: SPFNStateKey

    public init(snapshot: SPFNStatesSnapshot, query: SPFNStateKey = [:])
    {
        self.snapshot = snapshot
        self.query = query
    }
}

/// What the state client refuses. The state id and field names only: a key's values and a
/// state's value are the app's data and are never in an error.
public enum SPFNStateClientError: Error, Equatable, Sendable
{
    /// A configuration the client cannot run with; `field` names what is wrong.
    case invalidConfiguration(String)

    /// The server's manifest names no state with this id (K-9).
    case unknownState(String)

    /// The key's fields are not the state's (K-8).
    case keyFields(state: String, expected: [String], given: [String])

    /// The manifest marks this state `write: false` (I-11).
    case readOnly(String)

    /// The server does not serve this configured state event (C-24).
    case unavailable(String)

    /// Nobody is signed in, so no request could be signed.
    case signedOut

    /// `close()` ended the call before it was answered (C-19).
    case closed
}
