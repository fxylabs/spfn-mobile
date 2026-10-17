// SPFN Mobile — the state client in SwiftUI: `@SPFNServerState` and `@SPFNConnectionStatus`.
//
// `@spfn/core/state/react` on the native SDK (state-client-design.md §2-12, Q-F). The root
// attachment takes the state client beside the stream and puts it in the environment; a view
// declares the state it draws in one line:
//
//     @SPFNServerState(AppStates.consent) var consent
//     @SPFNConnectionStatus var connection
//
// The wrapper's model holds a SPFNServerStateDriver, which reads the current entry before the
// first body and then follows the subscription on the main actor. A wrapper has no `.task`:
// the model holds the subscription, and SwiftUI releasing the view's state storage — the view
// leaving the hierarchy — ends it. A view covered by another screen keeps its subscription,
// so moving between screens does not reconnect (L-6, K-5).
//
// Reading a state with no attachment above is a programmer error, as listening is (A-8), and
// so is attaching a state client made for another stream (A-9).
//
// Guarded whole on SwiftUI, so the module builds on Linux as its toolkit-free types.
// android/spfn-events/.../SpfnServerState.kt is the Compose half.

#if canImport(SwiftUI)
import Observation
import SwiftUI
import SPFNClient
import SPFNState

private struct SPFNStateClientKey: EnvironmentKey
{
    static let defaultValue: SPFNStateClient? = nil
}

extension EnvironmentValues
{
    /// The state client the nearest `.spfnEventStream(_:keyLifecycle:stateClient:)` attached, or nil.
    public var spfnStateClient: SPFNStateClient?
    {
        get
        {
            self[SPFNStateClientKey.self]
        }
        set
        {
            self[SPFNStateClientKey.self] = newValue
        }
    }
}

extension View
{
    /// Attach `stream` and the state client over it at the app's root, once per scene. The
    /// client must be the one made for `stream`: it owns that stream's query (§2-2, A-9).
    public func spfnEventStream(_ stream: SPFNEventStream, keyLifecycle: SPFNKeyLifecycle, stateClient: SPFNStateClient) -> some View
    {
        guard spfnIsStateClient(stateClient, of: stream)
        else
        {
            preconditionFailure("the state client attached with this stream was made for another stream")
        }
        return spfnEventStream(stream, keyLifecycle: keyLifecycle).environment(\.spfnStateClient, stateClient)
    }
}

/// One state for a view: its entry, current value first. `$state` is the model.
@MainActor
@propertyWrapper
public struct SPFNServerState<Value: Equatable & Sendable, Data: Equatable & Sendable>: DynamicProperty
{
    @Environment(\.spfnStateClient) private var client
    @State private var model = SPFNServerStateModel<Value, Data>()
    private let state: SPFNStateDescriptor<Value, Data>
    private let key: SPFNStateKey

    public init(_ state: SPFNStateDescriptor<Value, Data>, key: SPFNStateKey = [:])
    {
        self.state = state
        self.key = key
    }

    public var wrappedValue: SPFNStateEntry<Value, Data>
    {
        model.entry
    }

    public var projectedValue: SPFNServerStateModel<Value, Data>
    {
        model
    }

    /// Before every body: the binding, compared by value (A-3, A-4).
    nonisolated public func update()
    {
        MainActor.assumeIsolated
        {
            guard let client
            else
            {
                preconditionFailure(spfnMissingStateClient)
            }
            model.update(client: client, state: state, key: key)
        }
    }
}

/// What `@SPFNServerState` draws from. Made by the wrapper only.
@Observable
@MainActor
public final class SPFNServerStateModel<Value: Equatable & Sendable, Data: Equatable & Sendable>
{
    public private(set) var entry: SPFNStateEntry<Value, Data> = .loading
    @ObservationIgnored private var driver: SPFNServerStateDriver<Value, Data>?

    init() {}

    public var status: SPFNStateStatus
    {
        entry.status
    }

    public var frame: SPFNStateFrame<Value, Data>?
    {
        entry.frame
    }

    public var value: Value?
    {
        entry.value
    }

    func update(client: SPFNStateClient, state: SPFNStateDescriptor<Value, Data>, key: SPFNStateKey)
    {
        if driver == nil
        {
            driver = SPFNServerStateDriver { [weak self] in self?.entry = $0 }
        }
        driver?.update(client: client, state: state, key: key)
    }
}

/// The state client's connection status for a view, current value first (A-7).
@MainActor
@propertyWrapper
public struct SPFNConnectionStatus: DynamicProperty
{
    @Environment(\.spfnStateClient) private var client
    @State private var model = SPFNConnectionStatusModel()

    public init() {}

    public var wrappedValue: SPFNStateConnection
    {
        model.connection
    }

    public var projectedValue: SPFNConnectionStatusModel
    {
        model
    }

    nonisolated public func update()
    {
        MainActor.assumeIsolated
        {
            guard let client
            else
            {
                preconditionFailure(spfnMissingStateClient)
            }
            model.update(client: client)
        }
    }
}

/// What `@SPFNConnectionStatus` draws from. Made by the wrapper only.
@Observable
@MainActor
public final class SPFNConnectionStatusModel
{
    public private(set) var connection: SPFNStateConnection = .reconnecting(attempt: 0)
    @ObservationIgnored private var driver: SPFNConnectionStatusDriver?

    init() {}

    func update(client: SPFNStateClient)
    {
        if driver == nil
        {
            driver = SPFNConnectionStatusDriver { [weak self] in self?.connection = $0 }
        }
        driver?.update(source: client)
    }
}
#endif
