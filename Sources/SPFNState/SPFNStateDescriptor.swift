// SPFN Mobile — one state, described: its id, its key fields, how to read it and write it.
//
// The app does not write these. The app contract generator emits one per state of the
// server's state router — `AppStates.<id>` — from the contract document (§2-13-2, Q-G).
// The initialisers stay public so fixtures and tests can build one over a fake call.
//
// A descriptor carries the generated calls as values; the client never knows a route's
// name or path (§2-3). A frame on the stream is the read's answer plus `key`/`subject`, so
// the read's decoder reads frames too and nobody writes a frame decoder by hand (§2-4).
//
// Only a state with a write has a `SPFNWritableStateDescriptor`, and `write` takes nothing
// else, so writing a state that has no write does not compile (§2-11 item 1).
//
// android/spfn-state/.../SpfnStateDescriptor.kt is the same pair in Kotlin.

import SPFNCore

/// Any descriptor, its value types erased: what `SPFNStateClientConfiguration.states` holds.
public protocol SPFNAnyStateDescriptor: Sendable
{
    var id: String { get }
    var keyFields: [String] { get }
}

/// A state the client can read and subscribe to. `Value` is the read answer's `value`,
/// `Data` its `data` (`SPFNNoStateData` for a state without `.data()`).
public class SPFNStateDescriptor<Value: Sendable, Data: Sendable>: SPFNAnyStateDescriptor, @unchecked Sendable
{
    public let id: String
    /// The read operation's query fields, in name order; empty for a keyless state.
    public let keyFields: [String]
    let access: SPFNStateAccess

    /// - Parameters:
    ///   - read: the generated read call for a key (`get<Id>State`).
    ///   - frame: the three fields of the read's answer.
    public init<Response: Sendable>(
        id: String,
        keyFields: [String] = [],
        read: @escaping @Sendable (SPFNStateKey) -> SPFNCall<Void, Response>,
        frame: @escaping @Sendable (Response) -> SPFNStateFrame<Value, Data>
    )
    {
        self.id = id
        self.keyFields = keyFields.sorted { SPFNStateOrder.precedes($0, $1) }
        self.access = SPFNStateAccess(
            id: id,
            keyFields: self.keyFields,
            read: { key in SPFNStateAccess.canonical(read(key)) },
            decode: { key, value in frame(try read(key).decode(value)) }
        )
    }

    init(copying base: SPFNStateDescriptor<Value, Data>)
    {
        id = base.id
        keyFields = base.keyFields
        access = base.access
    }

    /// The same state with its write (`put<Id>State`). The write's answer is checked by the
    /// write call's own decoder, then read by this state's read decoder: the put answers the
    /// read's shape — the generator refuses one that does not — but as a type of its own.
    public func writable<Input: Sendable, Response: Sendable>(
        write: @escaping @Sendable (SPFNStateKey) -> SPFNCall<Input, Response>
    ) -> SPFNWritableStateDescriptor<Value, Data, Input>
    {
        SPFNWritableStateDescriptor(base: self, write: write)
    }
}

/// A state with a write: the only descriptor `SPFNStateClient.write` takes.
public final class SPFNWritableStateDescriptor<Value: Sendable, Data: Sendable, Input: Sendable>: SPFNStateDescriptor<Value, Data>, @unchecked Sendable
{
    let write: @Sendable (SPFNStateKey) -> SPFNCall<Input, SPFNCanonicalValue>

    init<Response: Sendable>(base: SPFNStateDescriptor<Value, Data>, write: @escaping @Sendable (SPFNStateKey) -> SPFNCall<Input, Response>)
    {
        self.write = { key in SPFNStateAccess.canonical(write(key)) }
        super.init(copying: base)
    }
}

/// What the client needs of a descriptor, its types erased.
struct SPFNStateAccess: Sendable
{
    let id: String
    let keyFields: [String]
    /// The read call for a key, answering the canonical value its generated decoder accepted.
    let read: @Sendable (SPFNStateKey) -> SPFNCall<Void, SPFNCanonicalValue>
    /// A read answer or a frame, decoded to the typed `SPFNStateFrame<Value, Data>`.
    let decode: @Sendable (SPFNStateKey, SPFNCanonicalValue) throws -> any Sendable

    var eventName: String
    {
        SPFNStateAccess.eventName(of: id)
    }

    static func eventName(of id: String) -> String
    {
        "state." + id + ".changed"
    }

    /// The same call, its answer kept canonical once the generated decoder has checked it:
    /// "same value" is decided on what the server sent, not on what a type kept (§2-5).
    static func canonical<Request, Response>(_ call: SPFNCall<Request, Response>) -> SPFNCall<Request, SPFNCanonicalValue>
    {
        SPFNCall(operation: call.operation, query: call.query, encode: call.encode)
        {
            value in
            _ = try call.decode(value)
            return value
        }
    }
}
