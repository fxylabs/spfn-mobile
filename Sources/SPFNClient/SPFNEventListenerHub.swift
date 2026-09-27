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

    private let deliveryBuffer: Int
    private let lock = NSLock()
    private var listeners: [ObjectIdentifier: any SPFNAnyListener] = [:]
    private var unavailableNames: Set<String> = []
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

    /// L-2: removed with its queue, and its consumer's stream ends. The connection is not told.
    func detach(_ listener: AnyObject)
    {
        let removed = lock.withLock { listeners.removeValue(forKey: ObjectIdentifier(listener)) }
        removed?.finish()
    }

    /// E-22: one frame of a configured name, to every listener of that name.
    func deliver(name: String, data: String)
    {
        let targets = lock.withLock { listeners.values.filter { $0.name == name } }
        guard !targets.isEmpty
        else
        {
            return
        }
        guard let payload = Self.envelopePayload(data)
        else
        {
            count { $0.droppedFrames += Int64(targets.count) }
            return
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

    /// Q-F: the names the server does not serve. Each listener of one hears it once.
    func markUnavailable(_ names: [String])
    {
        let targets = lock.withLock
        {
            unavailableNames = Set(names)
            return listeners.values.filter { names.contains($0.name) }
        }
        for target in targets
        {
            target.unavailable()
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
