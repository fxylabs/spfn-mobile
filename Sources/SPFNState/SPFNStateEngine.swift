// SPFN Mobile — the state client's engine: performs the core's effects, one input at a time.
//
// Ownership: the client owns the inbox's continuation; the engine owns the core and the
// tasks it starts; nothing the engine starts holds the client. When the app lets go of the
// client, `deinit` finishes the inbox, the loop ends and the engine cancels what still runs.
//
// The signed-in boundary (§2-14, D5) is crossed twice. The key lifecycle's observer moves
// `SPFNStateShared` to the next account inside the call that stored its key, on that call's
// thread — `current` answers `loading` from then on — and queues `.subject` for the core. Until
// the core has processed it, what the core still produces is the last account's: the engine
// hands no entry to a subscriber or to `current`, and no answer to a call, while the two
// epochs differ.
//
// android/spfn-state/.../SpfnStateClient.kt carries the same runner in Kotlin.

import Foundation
import SPFNClient
import SPFNCore

enum SPFNStateEngineInput: Sendable
{
    case core(SPFNStateInput)
    case subscribe(Int, SPFNStateSlot, SPFNStateSink)
    case call(CheckedContinuation<SPFNStateResolution, any Error>, SPFNStateInput, Int)
}

/// One subscription's way out: its sequence's continuation, typed by the caller.
struct SPFNStateSink: Sendable
{
    let deliver: @Sendable (SPFNStateCell?) -> Void
    let finish: @Sendable () -> Void
}

/// What the engine reaches beyond the core. A value, so the tasks it starts carry it
/// without carrying the engine.
struct SPFNStateServices: Sendable
{
    let stream: any SPFNStateStream
    let names: [String]
    let executor: any SPFNStateExecutor
    let sleeper: any SPFNSleeper
    let schedule: @Sendable (@escaping @Sendable () -> Void) -> Void
    let onError: @Sendable (any Error) -> Void
}

actor SPFNStateEngine
{
    private let core: SPFNStateCore
    private let services: SPFNStateServices
    private let shared: SPFNStateShared
    private let inbox: AsyncStream<SPFNStateEngineInput>.Continuation
    private var sinks: [Int: SPFNStateSink] = [:]
    private var calls: [Int: CheckedContinuation<SPFNStateResolution, any Error>] = [:]
    private var requests: [Int: Task<Void, Never>] = [:]
    private var listening: [Task<Void, Never>] = []
    private var timer: Task<Void, Never>?

    init(core: SPFNStateCore, services: SPFNStateServices, shared: SPFNStateShared, inbox: AsyncStream<SPFNStateEngineInput>.Continuation)
    {
        self.core = core
        self.services = services
        self.shared = shared
        self.inbox = inbox
        for case .publish(let slot, let cell) in core.start()
        {
            shared.publish(slot, cell)
        }
    }

    /// The wire listener and the stream's states, from construction until `close()`.
    func attach()
    {
        perform(.attachWire)
    }

    func process(_ input: SPFNStateEngineInput)
    {
        switch input
        {
        case .core(let input):
            if case .answered(let request, _) = input
            {
                requests[request] = nil
            }
            core.process(input).forEach(perform)
            if case .close = input
            {
                shared.finishConnections()
            }
        case .subscribe(let subscription, let slot, let sink):
            sinks[subscription] = sink
            core.process(.subscribe(subscription: subscription, slot: slot)).forEach(perform)
        case .call(let continuation, let input, let call):
            calls[call] = continuation
            core.process(input).forEach(perform)
        }
    }

    /// The owner let go: nothing keeps running for a client nobody holds.
    func shutDown()
    {
        for task in listening + Array(requests.values) + [timer].compactMap({ $0 })
        {
            task.cancel()
        }
        sinks.values.forEach { $0.finish() }
        calls.values.forEach { $0.resume(throwing: SPFNStateClientError.closed) }
        sinks = [:]
        calls = [:]
        shared.finishConnections()
    }

    private func perform(_ effect: SPFNStateEffect)
    {
        switch effect
        {
        case .deliver(let subscription, let cell):
            // A `loading` carries no account's value, so it is handed on even while behind.
            if current || cell == nil
            {
                sinks[subscription]?.deliver(cell)
            }
        case .finish(let subscription):
            sinks.removeValue(forKey: subscription)?.finish()
        case .publish(let slot, let cell):
            if current
            {
                shared.publish(slot, cell)
            }
        case .clear:
            shared.clear()
        case .execute(let request, let what):
            execute(request, what)
        case .setQuery(let query):
            try? services.stream.setQuery(query)
        case .reconnectNow:
            services.stream.reconnectNow()
        case .connection(let connection):
            shared.setConnection(connection)
        case .startRetryTimer(let generation, let millis):
            startTimer(generation: generation, millis: millis)
        case .cancelRetryTimer:
            timer?.cancel()
        case .report(let error):
            services.onError(error)
        case .resolve(let call, let result):
            calls.removeValue(forKey: call)?.resume(with: current ? result : .failure(SPFNStateClientError.signedInChanged))
        case .countDropped:
            shared.countDropped()
        case .scheduleFlush:
            services.schedule { [inbox] in inbox.yield(.core(.flush)) }
        case .attachWire:
            listen()
        case .detachWire:
            listening.forEach { $0.cancel() }
            listening = []
        }
    }

    /// The core works for the account `shared` was last moved to (D5).
    private var current: Bool
    {
        core.epoch == shared.epoch
    }

    private func execute(_ request: Int, _ what: SPFNStateRequest)
    {
        requests[request] = Task
        {
            [services, inbox] in
            let result: Result<SPFNCanonicalValue, any Error>
            do
            {
                result = .success(try await what.perform(services.executor))
            }
            catch is CancellationError
            {
                return
            }
            catch
            {
                result = .failure(error)
            }
            inbox.yield(.core(.answered(request: request, result)))
        }
    }

    private func startTimer(generation: Int, millis: Int64)
    {
        timer?.cancel()
        timer = Task
        {
            [sleeper = services.sleeper, inbox] in
            guard (try? await sleeper.sleep(millis: millis)) != nil, !Task.isCancelled
            else
            {
                return
            }
            inbox.yield(.core(.retryElapsed(generation: generation)))
        }
    }

    private func listen()
    {
        let wire = services.stream.listenWire(services.names)
        let states = services.stream.states
        listening = [
            Task
            {
                [inbox] in
                for await signal in wire
                {
                    inbox.yield(.core(.wire(signal)))
                }
            },
            Task
            {
                [inbox] in
                for await state in states
                {
                    inbox.yield(.core(.stream(state)))
                }
            },
        ]
    }
}

/// What the synchronous reads answer from, and the connection's subscribers.
final class SPFNStateShared: @unchecked Sendable
{
    private let lock = NSLock()
    private var cells: [SPFNStateSlot: SPFNStateCell] = [:]
    private var status: SPFNStateConnection = .reconnecting(attempt: 0)
    private var watchers: [UUID: AsyncStream<SPFNStateConnection>.Continuation] = [:]
    private var dropped = 0
    private var subject: String??
    private var changes = 0

    var connection: SPFNStateConnection
    {
        lock.withLock { status }
    }

    var droppedFrames: Int
    {
        lock.withLock { dropped }
    }

    /// Counts the changes of the signed-in account, as the core's `epoch` does.
    var epoch: Int
    {
        lock.withLock { changes }
    }

    func cell(_ slot: SPFNStateSlot) -> SPFNStateCell?
    {
        lock.withLock { cells[slot] }
    }

    func publish(_ slot: SPFNStateSlot, _ cell: SPFNStateCell)
    {
        lock.withLock { cells[slot] = cell }
    }

    func clear()
    {
        lock.withLock { cells = [:] }
    }

    /// The key lifecycle's observer, inside the call that stored the key: the first id is the
    /// baseline; another one empties what `current` answers before that call returns.
    func signedIn(_ clientID: String?)
    {
        lock.withLock
        {
            let known = subject
            subject = .some(clientID)
            guard known != nil, known != .some(clientID)
            else
            {
                return
            }
            changes += 1
            cells = [:]
        }
    }

    func countDropped()
    {
        lock.withLock { dropped += 1 }
    }

    func subscribeConnection() -> AsyncStream<SPFNStateConnection>
    {
        let (stream, continuation) = AsyncStream.makeStream(of: SPFNStateConnection.self)
        let id = UUID()
        continuation.onTermination = { [weak self] _ in self?.remove(id) }
        let current = lock.withLock
        {
            watchers[id] = continuation
            return status
        }
        continuation.yield(current)
        return stream
    }

    func setConnection(_ connection: SPFNStateConnection)
    {
        let targets = lock.withLock
        {
            status = connection
            return Array(watchers.values)
        }
        targets.forEach { $0.yield(connection) }
    }

    func finishConnections()
    {
        let targets = lock.withLock
        {
            defer { watchers.removeAll() }
            return Array(watchers.values)
        }
        targets.forEach { $0.finish() }
    }

    private func remove(_ id: UUID)
    {
        _ = lock.withLock { watchers.removeValue(forKey: id) }
    }

    /// A cell as the entry of the descriptor's types; `loading` for none.
    static func entry<Value, Data>(_ cell: SPFNStateCell?) -> SPFNStateEntry<Value, Data>
    {
        guard let cell
        else
        {
            return .loading
        }
        let frame = cell.frame?.typed as? SPFNStateFrame<Value, Data>
        if cell.status == .revoked
        {
            return .revoked(frame)
        }
        return frame.map { .ready($0) } ?? .loading
    }

    /// An error's name: a state client error's case, or the type of anything else.
    static func name(of error: any Error) -> String
    {
        if let error = error as? SPFNStateClientError
        {
            return String(describing: error)
        }
        return String(describing: type(of: error))
    }
}
