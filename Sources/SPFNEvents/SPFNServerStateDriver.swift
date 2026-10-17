// SPFN Mobile — one state of the state client, kept on the main actor for a view.
//
// The toolkit-free body of `@SPFNServerState` (SPFNServerState.swift): `@spfn/core/state/react`'s
// `useServerState` on the native SDK. It reads the client's current entry first — a cached
// entry or `initial` draws `ready` on the first frame, never `loading` (A-1) — then consumes
// the subscription, taking only what differs from what it holds, so the subscription's first
// element (the same current entry) changes nothing. The key is compared by value: the same
// fields again does nothing, other fields end the old subscription and open the new one,
// current value first (A-3, A-4). `end()` is the view leaving (A-5).
//
// The driver is `@MainActor`; the state client's engine is not. What crosses is one element
// of the subscription per change, received by a main-actor task (A-6, §2-12).
//
// Plain Swift, so the A-cells run on Linux. android/spfn-events/.../SpfnServerStateDriver.kt
// is the same driver in Kotlin.

import SPFNClient
import SPFNState

@MainActor
public final class SPFNServerStateDriver<Value: Equatable & Sendable, Data: Equatable & Sendable>
{
    /// The entry the view draws.
    public private(set) var entry: SPFNStateEntry<Value, Data> = .loading
    private let onChange: @MainActor (SPFNStateEntry<Value, Data>) -> Void
    private var binding: Binding?
    // Read by `deinit`, which is not on the main actor; written on it only.
    nonisolated(unsafe) private var consuming: Task<Void, Never>?

    private struct Binding: Equatable
    {
        let source: ObjectIdentifier
        let id: String
        let key: SPFNStateKey
    }

    /// - Parameter onChange: called on the main actor after `entry` changed.
    public init(onChange: @escaping @MainActor (SPFNStateEntry<Value, Data>) -> Void = { _ in })
    {
        self.onChange = onChange
    }

    /// The model that holds the driver went away with its view: the subscription ends (A-5).
    deinit
    {
        consuming?.cancel()
    }

    /// Binds the driver to `state` under `key` on `client`. The same binding again does nothing.
    public func update(client: SPFNStateClient, state: SPFNStateDescriptor<Value, Data>, key: SPFNStateKey)
    {
        update(source: client, state: state, key: key)
    }

    /// The view left: the subscription ends.
    public func end()
    {
        consuming?.cancel()
        consuming = nil
        binding = nil
    }

    func update(source: any SPFNStateSource, state: SPFNStateDescriptor<Value, Data>, key: SPFNStateKey)
    {
        let next = Binding(source: ObjectIdentifier(source), id: state.id, key: key)
        guard next != binding
        else
        {
            return
        }
        end()
        binding = next
        take(source.current(state, key: key))
        let entries = source.subscribe(state, key: key)
        consuming = Task
        {
            @MainActor [weak self] in
            for await entry in entries
            {
                self?.take(entry)
            }
        }
    }

    private func take(_ next: SPFNStateEntry<Value, Data>)
    {
        guard next != entry
        else
        {
            return
        }
        entry = next
        onChange(next)
    }
}

/// The connection status for a view: `connection()` first, then each change (A-7).
@MainActor
final class SPFNConnectionStatusDriver
{
    private(set) var connection: SPFNStateConnection = .reconnecting(attempt: 0)
    private let onChange: @MainActor (SPFNStateConnection) -> Void
    private var source: ObjectIdentifier?
    nonisolated(unsafe) private var consuming: Task<Void, Never>?

    init(onChange: @escaping @MainActor (SPFNStateConnection) -> Void = { _ in })
    {
        self.onChange = onChange
    }

    deinit
    {
        consuming?.cancel()
    }

    func update(source: any SPFNConnectionSource)
    {
        guard ObjectIdentifier(source) != self.source
        else
        {
            return
        }
        end()
        self.source = ObjectIdentifier(source)
        take(source.connection())
        let changes = source.subscribeConnection()
        consuming = Task
        {
            @MainActor [weak self] in
            for await connection in changes
            {
                self?.take(connection)
            }
        }
    }

    func end()
    {
        consuming?.cancel()
        consuming = nil
        source = nil
    }

    private func take(_ next: SPFNStateConnection)
    {
        guard next != connection
        else
        {
            return
        }
        connection = next
        onChange(next)
    }
}

/// What the drivers read of a state client; the suites stand a scripted one in.
protocol SPFNStateSource: AnyObject, Sendable
{
    func current<Value, Data>(_ state: SPFNStateDescriptor<Value, Data>, key: SPFNStateKey) -> SPFNStateEntry<Value, Data>
    func subscribe<Value, Data>(_ state: SPFNStateDescriptor<Value, Data>, key: SPFNStateKey) -> AsyncStream<SPFNStateEntry<Value, Data>>
}

protocol SPFNConnectionSource: AnyObject, Sendable
{
    func connection() -> SPFNStateConnection
    func subscribeConnection() -> AsyncStream<SPFNStateConnection>
}

extension SPFNStateClient: SPFNStateSource, SPFNConnectionSource {}

/// A-8: what a view that reads a state without the root attachment is told.
let spfnMissingStateClient = "no .spfnEventStream(_:keyLifecycle:stateClient:) above this view: attach the state client at the app's root"

/// A-9: the query of a stream has one owner, its state client — attaching a client made for
/// another stream is a programmer error the attachment reports at once.
func spfnIsStateClient(_ stateClient: SPFNStateClient, of stream: SPFNEventStream) -> Bool
{
    stateClient.configuration.stream === stream
}
