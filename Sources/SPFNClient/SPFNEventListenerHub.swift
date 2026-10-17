// SPFN Mobile — the listener hub: every screen's listening, over one connection.
//
// One frame's path to one listener is decode → condition → queue (design §3-4). A decode
// failure is counted as dropped and the condition never sees it; a false condition is
// counted as filtered and costs nothing; a true one enqueues. Rereads skip both, because
// they carry no value to decide on (L-13). Each listener has its own queue of
// `deliveryBuffer` frames, and a full queue is replaced by one overflow reread — for that
// listener only. A condition cannot throw: its signature has no `throws`, which is the
// whole of L-12 on this platform.
//
// Nothing here feeds the state machine. The hub receives the machine's `deliver`,
// `reread` and `markUnavailable` effects and the listeners' attach and detach, and
// produces nothing the machine reads (§4-2, `listeners_neverReachMachine`).
//
// A lock rather than an actor, because `listen` attaches when it is CALLED — synchronously,
// so `attached` is queued before the caller's first `await` (§3-4, §8 H-9). The lock is
// held for queue bookkeeping only; decoders and conditions run outside it.
//
// A wire listener (revision 4, S-2) is the state client's kind: several names in one queue,
// no decoder and no condition, and the connection's own signals — `opened` with what the
// `connected` frame said, `subscription` grants and revokes — in order with the frames.
// The hub keeps the open connection's `connected` so a wire listener attached while it is
// open hears it once more right after `attached`; the connection ending forgets it.
//
// android/spfn-client/.../SpfnEventListenerHub.kt is the same hub in Kotlin.

import Foundation
import SPFNCore

/// What the hub keeps per listener, erased over the listener's event type.
private protocol SPFNAnyListener: AnyObject
{
    var name: String { get }

    func offer(_ payload: SPFNCanonicalValue) -> SPFNEventListenerHub.Evaluation

    func reread(_ cause: SPFNRereadCause)

    func unavailable()

    func finish()
}

final class SPFNEventListenerHub: @unchecked Sendable
{
    enum Evaluation
    {
        case enqueued
        case dropped
        case filtered
    }

    /// One listener: its name, decoder and condition, fixed when it attaches, and its queue.
    /// Its own lock, so a slow consumer never holds up delivery to another listener.
    final class Listener<Event: Sendable>: SPFNAnyListener, @unchecked Sendable
    {
        let name: String
        private let decode: @Sendable (SPFNCanonicalValue) throws -> Event
        private let condition: @Sendable (Event) -> Bool
        private let deliveryBuffer: Int
        private let lock = NSLock()
        private var queue: [SPFNEventSignal<Event>] = []
        private var pendingFrames = 0
        private var unavailableSent = false
        private var finished = false
        private var waiter: CheckedContinuation<SPFNEventSignal<Event>?, Never>?

        init(
            name: String,
            decode: @escaping @Sendable (SPFNCanonicalValue) throws -> Event,
            condition: @escaping @Sendable (Event) -> Bool,
            deliveryBuffer: Int
        )
        {
            self.name = name
            self.decode = decode
            self.condition = condition
            self.deliveryBuffer = deliveryBuffer
        }

        /// What is queued, oldest first, without consuming it. For the hub suite.
        var pending: [SPFNEventSignal<Event>]
        {
            lock.withLock { queue }
        }

        /// The next signal, suspending until there is one; nil once the listener is finished.
        func next() async -> SPFNEventSignal<Event>?
        {
            await withCheckedContinuation
            {
                (continuation: CheckedContinuation<SPFNEventSignal<Event>?, Never>) in
                let ready: SPFNEventSignal<Event>?? = lock.withLock
                {
                    if !queue.isEmpty
                    {
                        let first = queue.removeFirst()
                        if case .frame = first
                        {
                            pendingFrames -= 1
                        }
                        return .some(first)
                    }
                    if finished
                    {
                        return .some(nil)
                    }
                    waiter = continuation
                    return nil
                }
                if let ready
                {
                    continuation.resume(returning: ready)
                }
            }
        }

        func offer(_ payload: SPFNCanonicalValue) -> SPFNEventListenerHub.Evaluation
        {
            guard let value = try? decode(payload)
            else
            {
                return .dropped
            }
            guard condition(value)
            else
            {
                return .filtered
            }
            enqueueFrame(value)
            return .enqueued
        }

        func reread(_ cause: SPFNRereadCause)
        {
            push(.reread(cause), clearing: cause != .attached)
        }

        func unavailable()
        {
            let first = lock.withLock
            {
                defer { unavailableSent = true }
                return !unavailableSent
            }
            if first
            {
                push(.unavailable, clearing: false)
            }
        }

        /// Ends the stream the consumer is reading. Resumes a waiting consumer exactly once.
        func finish()
        {
            let pendingWaiter: CheckedContinuation<SPFNEventSignal<Event>?, Never>? = lock.withLock
            {
                finished = true
                queue.removeAll()
                defer { waiter = nil }
                return waiter
            }
            pendingWaiter?.resume(returning: nil)
        }

        /// L-4: a frame that finds the queue full replaces it with one overflow reread, which
        /// covers that frame too. Only frames count toward the limit.
        private func enqueueFrame(_ value: Event)
        {
            let overflowing = lock.withLock { pendingFrames >= deliveryBuffer }
            if overflowing
            {
                push(.reread(.overflow), clearing: true)
            }
            else
            {
                push(.frame(value), clearing: false)
            }
        }

        /// Hands `signal` to a waiting consumer, or queues it. Clearing drops queued frames
        /// and rereads; a queued `unavailable` stays, because it is sent once.
        private func push(_ signal: SPFNEventSignal<Event>, clearing: Bool)
        {
            let delivered: CheckedContinuation<SPFNEventSignal<Event>?, Never>? = lock.withLock
            {
                guard !finished
                else
                {
                    return nil
                }
                if clearing
                {
                    queue = queue.contains(where: \.isUnavailable) ? [.unavailable] : []
                    pendingFrames = 0
                }
                if let waiter, queue.isEmpty
                {
                    self.waiter = nil
                    return waiter
                }
                queue.append(signal)
                if case .frame = signal
                {
                    pendingFrames += 1
                }
                return nil
            }
            delivered?.resume(returning: signal)
        }
    }

    /// One wire listener: its names, fixed when it attaches, and its one queue (S-2).
    final class WireListener: @unchecked Sendable
    {
        let names: Set<String>
        private let deliveryBuffer: Int
        private let lock = NSLock()
        private var queue: [SPFNWireSignal] = []
        private var pendingFrames = 0
        private var reportedUnavailable: Set<String> = []
        private var finished = false
        private var waiter: CheckedContinuation<SPFNWireSignal?, Never>?

        init(names: Set<String>, deliveryBuffer: Int)
        {
            self.names = names
            self.deliveryBuffer = deliveryBuffer
        }

        /// What is queued, oldest first, without consuming it. For the hub suite.
        var pending: [SPFNWireSignal]
        {
            lock.withLock { queue }
        }

        /// The next signal, suspending until there is one; nil once the listener is finished.
        func next() async -> SPFNWireSignal?
        {
            await withCheckedContinuation
            {
                (continuation: CheckedContinuation<SPFNWireSignal?, Never>) in
                let ready: SPFNWireSignal?? = lock.withLock
                {
                    if !queue.isEmpty
                    {
                        let first = queue.removeFirst()
                        if case .frame = first
                        {
                            pendingFrames -= 1
                        }
                        return .some(first)
                    }
                    if finished
                    {
                        return .some(nil)
                    }
                    waiter = continuation
                    return nil
                }
                if let ready
                {
                    continuation.resume(returning: ready)
                }
            }
        }

        /// L-23: a frame that finds the queue full drops the queued frames for one `overflow`.
        func frame(name: String, value: SPFNCanonicalValue)
        {
            let overflowing = lock.withLock { pendingFrames >= deliveryBuffer }
            if overflowing
            {
                push(.overflow, dropping: { $0.isFrameOrOverflow })
            }
            else
            {
                push(.frame(name: name, value: value), dropping: nil)
            }
        }

        /// L-26: a new connection's `opened` replaces whatever the old one left queued.
        func opened(_ connected: SPFNStreamConnected)
        {
            push(.opened(connected), dropping: { $0.belongsToConnection })
        }

        func subscription(name: String, status: SPFNSubscriptionStatus)
        {
            push(.subscription(name: name, status: status), dropping: nil)
        }

        func attached()
        {
            push(.attached, dropping: nil)
        }

        /// L-24: each of its names is reported unavailable once, however often it is marked.
        func unavailable(_ marked: Set<String>)
        {
            let fresh: [String] = lock.withLock
            {
                let fresh = names.intersection(marked).subtracting(reportedUnavailable)
                reportedUnavailable.formUnion(fresh)
                return fresh.sorted()
            }
            if !fresh.isEmpty
            {
                push(.unavailable(fresh), dropping: nil)
            }
        }

        func finish()
        {
            let pendingWaiter: CheckedContinuation<SPFNWireSignal?, Never>? = lock.withLock
            {
                finished = true
                queue.removeAll()
                defer { waiter = nil }
                return waiter
            }
            pendingWaiter?.resume(returning: nil)
        }

        /// Hands `signal` to a waiting consumer, or queues it after dropping what `dropping` names.
        private func push(_ signal: SPFNWireSignal, dropping: ((SPFNWireSignal) -> Bool)?)
        {
            let delivered: CheckedContinuation<SPFNWireSignal?, Never>? = lock.withLock
            {
                guard !finished
                else
                {
                    return nil
                }
                if let dropping
                {
                    queue.removeAll(where: dropping)
                    pendingFrames = 0
                }
                if let waiter, queue.isEmpty
                {
                    self.waiter = nil
                    return waiter
                }
                queue.append(signal)
                if case .frame = signal
                {
                    pendingFrames += 1
                }
                return nil
            }
            delivered?.resume(returning: signal)
        }
    }

    private let deliveryBuffer: Int
    private let lock = NSLock()
    private var listeners: [ObjectIdentifier: any SPFNAnyListener] = [:]
    private var wireListeners: [ObjectIdentifier: WireListener] = [:]
    private var unavailableNames: Set<String> = []
    private var connection: SPFNStreamConnected?
    private var counts = SPFNEventDiagnostics()

    init(deliveryBuffer: Int)
    {
        self.deliveryBuffer = deliveryBuffer
    }

    var diagnostics: SPFNEventDiagnostics
    {
        lock.withLock { counts }
    }

    /// L-1: added, and its first signal is `attached` whatever the connection is doing.
    func attach<Event: Sendable>(
        _ name: String,
        decode: @escaping @Sendable (SPFNCanonicalValue) throws -> Event,
        condition: @escaping @Sendable (Event) -> Bool
    ) -> Listener<Event>
    {
        let listener = Listener(name: name, decode: decode, condition: condition, deliveryBuffer: deliveryBuffer)
        listener.reread(.attached)
        let unavailable = lock.withLock
        {
            listeners[ObjectIdentifier(listener)] = listener
            return unavailableNames.contains(name)
        }
        if unavailable
        {
            listener.unavailable()
        }
        return listener
    }

    /// L-19, L-20: `attached`, any of its names already unavailable, and — while a connection
    /// is open — that connection's `opened` once more. Queued under the hub's lock, so a
    /// connection opening at the same moment cannot put its `opened` first.
    func attachWire(_ names: Set<String>) -> WireListener
    {
        let listener = WireListener(names: names, deliveryBuffer: deliveryBuffer)
        lock.withLock
        {
            wireListeners[ObjectIdentifier(listener)] = listener
            listener.attached()
            listener.unavailable(unavailableNames)
            if let connection
            {
                listener.opened(connection)
            }
        }
        return listener
    }

    /// L-2: removed with its queue, and its consumer's stream ends. The connection is not told.
    func detach(_ listener: AnyObject)
    {
        let key = ObjectIdentifier(listener)
        let removed: (any SPFNAnyListener)?
        let removedWire: WireListener?
        (removed, removedWire) = lock.withLock { (listeners.removeValue(forKey: key), wireListeners.removeValue(forKey: key)) }
        removed?.finish()
        removedWire?.finish()
    }

    /// E-22: one frame of a configured name, to every listener of that name.
    func deliver(name: String, data: String)
    {
        let (targets, wireTargets) = lock.withLock
        {
            (listeners.values.filter { $0.name == name }, wireListeners.values.filter { $0.names.contains(name) })
        }
        guard !targets.isEmpty || !wireTargets.isEmpty
        else
        {
            return
        }
        guard let payload = Self.envelopePayload(data)
        else
        {
            count { $0.droppedFrames += Int64(targets.count + wireTargets.count) }
            return
        }
        for wireTarget in wireTargets
        {
            wireTarget.frame(name: name, value: payload)
        }
        for target in targets
        {
            switch target.offer(payload)
            {
            case .enqueued:
                break
            case .dropped:
                count { $0.droppedFrames += 1 }
            case .filtered:
                count { $0.filteredFrames += 1 }
            }
        }
    }

    /// E-13: a new epoch. Every queue's old frames go; the reread covers them.
    func reread(epoch: Int)
    {
        for listener in lock.withLock({ Array(listeners.values) })
        {
            listener.reread(.opened(epoch: epoch))
        }
    }

    /// S-2: the connection that just opened, to every wire listener, and kept for those that
    /// attach while it stays open.
    func connected(_ connected: SPFNStreamConnected)
    {
        for listener in lock.withLock({ connection = connected; return Array(wireListeners.values) })
        {
            listener.opened(connected)
        }
    }

    /// The connection ended: a wire listener attaching now hears `attached` only (L-19).
    func disconnected()
    {
        lock.withLock { connection = nil }
    }

    /// S-3 (L-25): a grant or revoke, to the wire listeners of that name. `listen` never hears it.
    func subscription(name: String, status: SPFNSubscriptionStatus)
    {
        for listener in lock.withLock({ wireListeners.values.filter { $0.names.contains(name) } })
        {
            listener.subscription(name: name, status: status)
        }
    }

    /// Q-F: the names the server does not serve. Each listener of one hears it once.
    func markUnavailable(_ names: [String])
    {
        let (targets, wireTargets) = lock.withLock
        {
            unavailableNames = Set(names)
            return (listeners.values.filter { names.contains($0.name) }, Array(wireListeners.values))
        }
        for target in targets
        {
            target.unavailable()
        }
        for wireTarget in wireTargets
        {
            wireTarget.unavailable(Set(names))
        }
    }

    func countUnexpected()
    {
        count { $0.unexpectedFrames += 1 }
    }

    private func count(_ change: (inout SPFNEventDiagnostics) -> Void)
    {
        lock.withLock { change(&counts) }
    }

    /// The server's envelope `{"event": name, "data": payload}`, unwrapped. Nil when it is
    /// not one: nobody can decode what is not there.
    static func envelopePayload(_ data: String) -> SPFNCanonicalValue?
    {
        guard case .object(let members)? = try? SPFNCanonicalJSON.parse(data)
        else
        {
            return nil
        }
        return members["data"]
    }
}

private extension SPFNEventSignal
{
    var isUnavailable: Bool
    {
        guard case .unavailable = self
        else
        {
            return false
        }
        return true
    }
}

private extension SPFNWireSignal
{
    var isFrameOrOverflow: Bool
    {
        switch self
        {
        case .frame, .overflow:
            return true
        case .attached, .opened, .subscription, .unavailable:
            return false
        }
    }

    /// What a new connection's `opened` supersedes: everything the old connection queued.
    var belongsToConnection: Bool
    {
        switch self
        {
        case .frame, .overflow, .opened, .subscription:
            return true
        case .attached, .unavailable:
            return false
        }
    }
}
