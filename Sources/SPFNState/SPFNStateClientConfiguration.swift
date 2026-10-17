// SPFN Mobile — what an app tells the state client once, at its composition root.
//
// The app hands over the stream it already made, its key lifecycle, and the generated
// `AppStates.all` and `AppStates.snapshot`; it writes nothing per state (§2-13-1, §2-13-4).
// Every refusal is at construction, so a missing event name is found on the first run.
//
// android/spfn-state/.../SpfnStateClientConfiguration.kt is the same value in Kotlin.

import SPFNClient
import SPFNCore

public struct SPFNStateClientConfiguration: Sendable
{
    /// The app's one event stream. Its events must include every `state.<id>.changed`
    /// (`AppStates.events`); the state client owns its connection query (§2-2).
    public let stream: SPFNEventStream
    /// Signs every read and write: asked for the signed-in client at each call (§2-3).
    public let keyLifecycle: SPFNKeyLifecycle
    /// `AppStates.all`.
    public let states: [any SPFNAnyStateDescriptor]
    /// Something the client ran on its own failed, or a `subscribe` was refused. Never a
    /// cancellation (P16).
    public let onError: @Sendable (any Error) -> Void
    /// The wait before the first snapshot is read again after it failed.
    public let retryDelayMillis: Int64
    /// How many times the first snapshot is read again before `failed`; 0 is no limit.
    public let maxRetries: Int
    public let initial: SPFNStateInitial?
    /// `AppStates.snapshot`, its answer kept canonical once the generated decoder checked it.
    let snapshot: @Sendable (SPFNStateKey) -> SPFNCall<Void, SPFNCanonicalValue>

    public init<Response: Sendable>(
        stream: SPFNEventStream,
        keyLifecycle: SPFNKeyLifecycle,
        states: [any SPFNAnyStateDescriptor],
        snapshot: @escaping @Sendable (SPFNStateKey) -> SPFNCall<Void, Response>,
        onError: @escaping @Sendable (any Error) -> Void = SPFNStateClient.logError,
        retryDelayMillis: Int64 = 3_000,
        maxRetries: Int = 0,
        initial: SPFNStateInitial? = nil
    )
    {
        self.stream = stream
        self.keyLifecycle = keyLifecycle
        self.states = states
        self.snapshot = { query in SPFNStateAccess.canonical(snapshot(query)) }
        self.onError = onError
        self.retryDelayMillis = retryDelayMillis
        self.maxRetries = maxRetries
        self.initial = initial
    }

    /// The descriptors as the core reads them, or the field that is wrong: no state, a state
    /// twice, a descriptor not made by this module, an event name the stream does not carry.
    func accesses(carriedBy events: SPFNEventStreamConfiguration) throws -> [SPFNStateAccess]
    {
        let accesses = states.compactMap(Self.access(of:))
        guard !accesses.isEmpty, accesses.count == states.count
        else
        {
            throw SPFNStateClientError.invalidConfiguration("states")
        }
        guard Set(accesses.map(\.id)).count == accesses.count
        else
        {
            throw SPFNStateClientError.invalidConfiguration("states: an id is described twice")
        }
        if let missing = accesses.first(where: { !events.contains($0.eventName) })
        {
            throw SPFNStateClientError.invalidConfiguration("stream: '\(missing.eventName)' is not among its events")
        }
        return accesses
    }

    private static func access(of descriptor: any SPFNAnyStateDescriptor) -> SPFNStateAccess?
    {
        (descriptor as? any SPFNStateAccessible)?.access
    }
}

/// A descriptor this module made, whatever its value types.
protocol SPFNStateAccessible
{
    var access: SPFNStateAccess { get }
}

extension SPFNStateDescriptor: SPFNStateAccessible {}
