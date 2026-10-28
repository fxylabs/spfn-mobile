// SPFN Mobile — the state client's decisions.
//
// The web client's (`@spfn/core/state/client`, `client.ts`) rules, on the native stream:
// the first snapshot before anything else (Man), one connection query that is the union of
// the subscribed keys (Str, §2-7), every arrival through the store's ordering rule (Ord),
// revoked marks per connection (Rev, §2-8), the connection status (Con, §2-9) and the
// initial snapshot (Cfg, §2-10). The signed-in boundary (§2-14) is the native client's own:
// one client lives through every account, so a change of account empties the store. Where
// the native stream differs — it is the app's, opened
// by the SDK's lifecycle, and its `opened` signal arrives in the frames' queue — §2-2 and
// §2-6 say what stands in for the web client's own `SSEClient`.
//
// One input at a time, effects back; nothing here waits or locks. SPFNStateClient's engine
// is the only caller.
//
// android/spfn-state/.../SpfnStateCore.kt is the same core in Kotlin.

import SPFNClient
import SPFNCore

final class SPFNStateCore
{
    struct Configuration: Sendable
    {
        let states: [SPFNStateAccess]
        let snapshot: @Sendable (SPFNStateKey) -> SPFNCall<Void, SPFNCanonicalValue>
        let retryDelayMillis: Int64
        let maxRetries: Int
    }

    /// Which connection an automatic read was started for, and when (§2-8).
    fileprivate struct Tag
    {
        let lifetime: Int
        let generation: Int
        let tick: Int
    }

    fileprivate enum Pending
    {
        case snapshot(query: SPFNStateKey, tag: Tag, call: Int?, first: Bool)
        case shadow(SPFNStateSlot, Tag)
        case get(SPFNStateSlot, call: Int?)
        case write(SPFNStateSlot, call: Int, decode: @Sendable (SPFNCanonicalValue) throws -> any Sendable)
    }

    /// An app call waiting for the first snapshot.
    fileprivate enum Waiter
    {
        case get(call: Int, slot: SPFNStateSlot)
        case write(call: Int, slot: SPFNStateSlot, request: SPFNStateRequest, decode: @Sendable (SPFNCanonicalValue) throws -> any Sendable)
    }

    private let configuration: Configuration
    private let states: [String: SPFNStateAccess]
    private var effects: [SPFNStateEffect] = []

    private var store = SPFNStateStore()
    private var manifest: [String: SPFNStatesSnapshot.State]?
    private var subscriptions: [Int: SPFNStateSlot] = [:]
    /// Subscribed slots in the order they were first subscribed — the latest key wins (§2-7).
    private var watched: [SPFNStateSlot] = []
    private var watchCounts: [SPFNStateSlot: Int] = [:]
    private var flushQueued = false

    private var pending: [Int: Pending] = [:]
    private var nextRequest = 0
    private var firstRead: Int?
    private var waiters: [Waiter] = []
    /// Subscriptions wait on the first snapshot: its read in flight, or its retry timer set.
    private var waiting = false
    private var retries = 0
    private var timerGeneration: Int?
    private var timers = 0

    private var lifetime = 0
    private var closed = false
    private var generation = 0
    private var tick = 0
    private var grants: [SPFNStateSlot: Int] = [:]
    private var connectionQuery: SPFNStateKey?
    private var query: SPFNStateKey = [:]
    private var streamBound = false
    private var streamFailed = false
    private var streamState: SPFNEventStreamState = .idle(.signedOut)
    private var status: SPFNStateConnection = .reconnecting(attempt: 0)
    private var reportedUnavailable: Set<String> = []

    /// Whose values the store holds: nil until the key lifecycle's first value, the baseline;
    /// then the signed-in client id, or none (§2-14).
    private var subject: String??
    /// Counts the changes of `subject`. The engine hands nothing on that the core produced
    /// for an older one (D5).
    private(set) var epoch = 0
    /// Wire frames, grants, revokes and overflows are taken only while this holds: always
    /// while the subject is unknown, else from an `opened` of a connection made for it (W-6,
    /// W-7, W-16).
    private var wireTrusted = true
    /// From a change of subject until its connection opens: the status stays
    /// `reconnecting(0)` (D6).
    private var switching = false

    init(configuration: Configuration, initial: SPFNStateInitial?)
    {
        self.configuration = configuration
        self.states = Dictionary(configuration.states.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        if let initial
        {
            hydrate(initial)
        }
    }

    /// The effects of construction: the entries `initial` brought.
    func start() -> [SPFNStateEffect]
    {
        defer { effects = [] }
        return effects
    }

    func process(_ input: SPFNStateInput) -> [SPFNStateEffect]
    {
        effects = []
        switch input
        {
        case .subscribe(let subscription, let slot):
            subscribe(subscription, slot)
        case .unsubscribe(let subscription):
            unsubscribe(subscription)
        case .flush:
            flush()
        case .wire(let signal):
            wire(signal)
        case .stream(let state):
            stream(state)
        case .answered(let request, let result):
            answered(request, result)
        case .get(let call, let slot):
            requireManifest(.get(call: call, slot: slot))
        case .write(let call, let slot, let request, let decode):
            requireManifest(.write(call: call, slot: slot, request: request, decode: decode))
        case .snapshot(let call, let query):
            readSnapshot(query ?? streamQuery(), tag: tag(), call: call, first: false)
        case .retry:
            retry()
        case .close:
            close()
        case .retryElapsed(let generation):
            retryElapsed(generation)
        case .subject(let clientID):
            follow(clientID)
        }
        return effects
    }

    // MARK: - Subscriptions and the query

    private func subscribe(_ subscription: Int, _ slot: SPFNStateSlot)
    {
        if closed
        {
            closed = false
            effects.append(.attachWire)
        }
        if let refusal = refusal(slot, beforeManifest: manifest == nil)
        {
            effects.append(.report(refusal))
            effects.append(.finish(subscription: subscription))
            return
        }
        subscriptions[subscription] = slot
        effects.append(.deliver(subscription: subscription, store.cells[slot]))
        watch(slot, by: 1)
    }

    private func unsubscribe(_ subscription: Int)
    {
        guard let slot = subscriptions.removeValue(forKey: subscription)
        else
        {
            return
        }
        watch(slot, by: -1)
    }

    /// An unsubscribe that leaves others while `failed` changes nothing (C-17).
    private func watch(_ slot: SPFNStateSlot, by delta: Int)
    {
        let count = (watchCounts[slot] ?? 0) + delta
        if count > 0
        {
            if watchCounts[slot] == nil
            {
                watched.append(slot)
            }
            watchCounts[slot] = count
        }
        else
        {
            watchCounts[slot] = nil
            watched.removeAll { $0 == slot }
        }
        if delta == 1 || watched.isEmpty || status != .failed
        {
            queueFlush()
        }
    }

    private func queueFlush()
    {
        guard !flushQueued
        else
        {
            return
        }
        flushQueued = true
        effects.append(.scheduleFlush)
    }

    /// Nothing watched: no key in the query, the start status, the retry count over (C-18).
    /// No manifest: wait for the first snapshot. Else the query, and the stream if it failed.
    private func flush()
    {
        flushQueued = false
        if watched.isEmpty
        {
            retries = 0
            waiting = false
            cancelTimer()
            unbindStream()
            setConnection(.reconnecting(attempt: 0))
            return
        }
        guard manifest != nil
        else
        {
            awaitManifest()
            return
        }
        let union = streamQuery()
        if !streamBound || streamFailed || union != query
        {
            bindStream(union)
        }
    }

    /// The union of the subscribed keys' fields; on a conflict the latest subscribed key.
    private func streamQuery() -> SPFNStateKey
    {
        watched.reduce(into: [:]) { union, slot in union.merge(slot.key) { _, latest in latest } }
    }

    /// The web client's `openStream`: here the stream is the app's, so a new query is
    /// `setQuery` and a failed stream is `reconnectNow`. Unless connected the status is the
    /// start value until it opens; a reopen while connected stays connected (C-15).
    private func bindStream(_ union: SPFNStateKey)
    {
        let first = !streamBound
        streamBound = true
        var moved = false
        if union != query
        {
            query = union
            effects.append(.setQuery(union))
            moved = true
        }
        if streamFailed
        {
            streamFailed = false
            effects.append(.reconnectNow)
            moved = true
        }
        if moved && status != .connected
        {
            setConnection(.reconnecting(attempt: 0))
        }
        else if first && !moved
        {
            followStream(streamState)
        }
    }

    private func unbindStream()
    {
        streamBound = false
        streamFailed = false
        if !query.isEmpty
        {
            query = [:]
            effects.append(.setQuery([:]))
        }
    }

    // MARK: - The manifest and the first snapshot

    /// Why `slot` cannot be subscribed or read: a state outside the configuration, an id the
    /// manifest does not name, key fields not the state's (K-8, K-9). Before the manifest only
    /// the configuration is known, and a subscription waits for the rest (K-10).
    private func refusal(_ slot: SPFNStateSlot, beforeManifest: Bool) -> SPFNStateClientError?
    {
        guard let state = states[slot.id]
        else
        {
            return .invalidConfiguration("state '\(slot.id)' is not among the configured states")
        }
        guard !beforeManifest
        else
        {
            return nil
        }
        guard let described = manifest?[slot.id]
        else
        {
            return .unknownState(slot.id)
        }
        let given = SPFNStateOrder.sortedFields(slot.key.keys)
        for expected in [SPFNStateOrder.sortedFields(described.key), state.keyFields] where expected != given
        {
            return .keyFields(state: slot.id, expected: expected, given: given)
        }
        return nil
    }

    /// One waiter per lifetime. A subscribe while `failed` starts the count over.
    private func awaitManifest()
    {
        guard !waiting
        else
        {
            return
        }
        if status == .failed
        {
            retries = 0
            setConnection(.reconnecting(attempt: 0))
        }
        waiting = true
        ensureManifest()
    }

    /// The first snapshot, read once however many wait for it (I-9, I-10).
    private func ensureManifest()
    {
        guard manifest == nil, firstRead == nil
        else
        {
            return
        }
        firstRead = readSnapshot(streamQuery(), tag: tag(), call: nil, first: true)
    }

    private func requireManifest(_ waiter: Waiter)
    {
        if manifest != nil
        {
            run(waiter)
            return
        }
        waiters.append(waiter)
        ensureManifest()
    }

    private func run(_ waiter: Waiter)
    {
        switch waiter
        {
        case .get(let call, let slot):
            if let refusal = keyRefusal(slot)
            {
                effects.append(.resolve(call: call, .failure(refusal)))
                return
            }
            send(readRequest(slot), as: .get(slot, call: call))
        case .write(let call, let slot, let request, let decode):
            if let refusal = keyRefusal(slot) ?? writeRefusal(slot)
            {
                effects.append(.resolve(call: call, .failure(refusal)))
                return
            }
            send(request, as: .write(slot, call: call, decode: decode))
        }
    }

    /// A read or write is never built with key fields other than the descriptor's (§2-13-2).
    private func keyRefusal(_ slot: SPFNStateSlot) -> SPFNStateClientError?
    {
        guard let state = states[slot.id]
        else
        {
            return .invalidConfiguration("state '\(slot.id)' is not among the configured states")
        }
        let given = SPFNStateOrder.sortedFields(slot.key.keys)
        return given == state.keyFields ? nil : .keyFields(state: slot.id, expected: state.keyFields, given: given)
    }

    private func writeRefusal(_ slot: SPFNStateSlot) -> SPFNStateClientError?
    {
        manifest?[slot.id]?.write == true ? nil : .readOnly(slot.id)
    }

    /// The manifest is static: the first one stays. Subscriptions made before it are checked
    /// now, and the stream follows for those it accepts — whichever call brought it.
    private func installManifest(_ installed: [String: SPFNStatesSnapshot.State])
    {
        guard manifest == nil
        else
        {
            return
        }
        manifest = installed
        for slot in watched
        {
            guard let refusal = refusal(slot, beforeManifest: false)
            else
            {
                continue
            }
            watched.removeAll { $0 == slot }
            watchCounts[slot] = nil
            for (subscription, subscribed) in subscriptions where subscribed == slot
            {
                subscriptions[subscription] = nil
                effects.append(.finish(subscription: subscription))
            }
            effects.append(.report(refusal))
        }
        if !watched.isEmpty
        {
            queueFlush()
        }
    }

    private func firstSnapshotAnswered(_ result: Result<SPFNCanonicalValue, any Error>, lifetime: Int)
    {
        firstRead = nil
        let ready = waiters
        waiters = []
        for waiter in ready
        {
            if case .failure(let error) = result
            {
                effects.append(.resolve(call: waiter.call, .failure(error)))
            }
            else if manifest == nil
            {
                effects.append(.resolve(call: waiter.call, .failure(SPFNStateClientError.closed)))
            }
            else
            {
                run(waiter)
            }
        }
        guard waiting, lifetime == self.lifetime
        else
        {
            return
        }
        if case .failure(let error) = result
        {
            effects.append(.report(error))
            retryLater()
            return
        }
        waiting = false
        cancelTimer()
        flush()
    }

    /// Read the first snapshot again after `retryDelay` — or, with `maxRetries` spent, give
    /// up: `failed`, no timer, no wait (C-9, C-10). The first read is not a retry.
    private func retryLater()
    {
        if configuration.maxRetries > 0 && retries >= configuration.maxRetries
        {
            waiting = false
            setConnection(.failed)
            return
        }
        retries += 1
        timers += 1
        timerGeneration = timers
        effects.append(.startRetryTimer(generation: timers, millis: configuration.retryDelayMillis))
    }

    private func retryElapsed(_ generation: Int)
    {
        guard generation == timerGeneration
        else
        {
            return
        }
        timerGeneration = nil
        waiting = false
        flush()
    }

    private func cancelTimer()
    {
        guard timerGeneration != nil
        else
        {
            return
        }
        timerGeneration = nil
        effects.append(.cancelRetryTimer)
    }

    // MARK: - Restarting

    /// Nothing to do when connected or with nothing watched. The first snapshot again before
    /// the manifest (C-11, C-12); the stream again after it (C-13).
    private func retry()
    {
        guard status != .connected, !watched.isEmpty
        else
        {
            return
        }
        if manifest != nil
        {
            streamFailed = true
            bindStream(streamQuery())
            setConnection(.reconnecting(attempt: 0))
        }
        else
        {
            rereadManifest()
        }
    }

    /// The count starts over and the first snapshot is read now. One in flight is not doubled.
    private func rereadManifest()
    {
        retries = 0
        if waiting && timerGeneration == nil
        {
            return
        }
        cancelTimer()
        setConnection(.reconnecting(attempt: 0))
        waiting = false
        flush()
    }

    /// The client's lifetime ends: answers in flight are discarded, calls waiting on the first
    /// snapshot are refused, subscriptions end, the query is emptied. The stream is the app's
    /// and stays. The entries and the manifest stay: a later `subscribe` starts again (C-19).
    private func close()
    {
        lifetime += 1
        cancelTimer()
        retries = 0
        waiting = false
        firstRead = nil
        for waiter in waiters
        {
            effects.append(.resolve(call: waiter.call, .failure(SPFNStateClientError.closed)))
        }
        waiters = []
        unbindStream()
        for subscription in subscriptions.keys.sorted()
        {
            effects.append(.finish(subscription: subscription))
        }
        subscriptions = [:]
        watched = []
        watchCounts = [:]
        setConnection(.reconnecting(attempt: 0))
        if !closed
        {
            closed = true
            effects.append(.detachWire)
        }
    }

    // MARK: - The signed-in subject

    /// The key lifecycle's client id (§2-14). The first is the baseline and clears nothing; the
    /// same id again — a rotation — changes nothing (W-1, W-17). Another id, or none, ends the
    /// last subject's values, and the wire is not trusted until the new subject's connection
    /// opens; the baseline too waits for one, so a connection replayed for another account
    /// when the client attaches is not taken.
    private func follow(_ clientID: String?)
    {
        let known = subject
        guard known != .some(clientID)
        else
        {
            return
        }
        subject = .some(clientID)
        wireTrusted = false
        guard known != nil
        else
        {
            return
        }
        epoch += 1
        switching = true
        forgetSubject()
    }

    /// D2: the entries, stamps, marks and grants go; the manifest, the names already reported
    /// unavailable and the subscriptions stay, each told `loading` — once: one already at
    /// `loading` hears nothing (W-2 … W-5). Even a closed client forgets (W-18).
    private func forgetSubject()
    {
        let held = store.cells
        store.clear()
        grants = [:]
        effects.append(.clear)
        for (subscription, slot) in subscriptions.sorted(by: { $0.key < $1.key }) where held[slot] != nil
        {
            effects.append(.deliver(subscription: subscription, nil))
        }
        refuseCalls()
        restartFirstSnapshot()
        setConnection(.reconnecting(attempt: 0))
    }

    /// Every request in flight was sent for the last subject: it is forgotten, so its answer
    /// is applied to nothing (W-9, W-10), and an app call on one — or waiting for the
    /// manifest — is refused now, once (W-11 … W-14).
    private func refuseCalls()
    {
        let calls = pending.values.compactMap(\.call) + waiters.map(\.call)
        for call in calls.sorted()
        {
            effects.append(.resolve(call: call, .failure(SPFNStateClientError.signedInChanged)))
        }
        pending = [:]
        waiters = []
    }

    /// A first snapshot in flight was the last subject's and is forgotten with it; the
    /// subscriptions that waited on it wait on one read for the next subject.
    private func restartFirstSnapshot()
    {
        guard firstRead != nil
        else
        {
            return
        }
        firstRead = nil
        if waiting
        {
            waiting = false
            queueFlush()
        }
    }

    /// While the subject is known, only a connection opened for it is trusted; any other —
    /// the last account's, replayed or late — is dropped whole, its snapshot unread (W-7). The
    /// subject's own ends the switch, and the status follows the stream again (W-8).
    private func trust(_ connected: SPFNStreamConnected) -> Bool
    {
        if case .some(let clientID) = subject, connected.clientID != clientID
        {
            return false
        }
        wireTrusted = true
        if switching
        {
            switching = false
            if streamBound
            {
                followStream(streamState)
            }
        }
        return true
    }

    // MARK: - The connection status

    /// The native stream's state, read as the web client reads its `SSEClient` callbacks —
    /// only while the client has a stream to speak of: something watched, the manifest known.
    private func stream(_ state: SPFNEventStreamState)
    {
        let wasOffline = streamState.isOffline
        streamState = state
        if wasOffline && !state.isOffline
        {
            online()
        }
        if streamBound
        {
            followStream(state)
        }
    }

    private func followStream(_ state: SPFNEventStreamState)
    {
        switch state
        {
        case .open:
            streamFailed = false
            if !switching
            {
                setConnection(.connected)
            }
        case .connecting:
            if status == .failed
            {
                setConnection(.reconnecting(attempt: 0))
            }
        case .retrying(let attempt, _, _), .offline(let attempt):
            setConnection(.reconnecting(attempt: attempt))
        case .idle:
            setConnection(.reconnecting(attempt: 0))
        case .closed:
            streamFailed = true
            setConnection(.failed)
        }
    }

    /// The browser's `online`: the event module already takes the stream out of `offline`, so
    /// only the first snapshot is left to restart (C-20, C-21).
    private func online()
    {
        guard manifest == nil, status != .connected, !watched.isEmpty
        else
        {
            return
        }
        rereadManifest()
    }

    private func setConnection(_ next: SPFNStateConnection)
    {
        guard next != status
        else
        {
            return
        }
        status = next
        effects.append(.connection(next))
    }

    // MARK: - The wire

    private func wire(_ signal: SPFNWireSignal)
    {
        switch signal
        {
        case .attached:
            break
        case .opened(let connected):
            opened(connected)
        case .frame(let name, let value) where wireTrusted:
            frame(name, value)
        case .subscription(let name, let change) where wireTrusted:
            subscription(name, change)
        case .overflow where wireTrusted:
            if let connectionQuery, manifest != nil, !watched.isEmpty
            {
                refresh(connectionQuery)
            }
        case .unavailable(let names):
            unavailable(names)
        case .frame, .subscription, .overflow:
            break
        }
    }

    /// Every connection must cover what was sent while none was open. The snapshot is read
    /// only when a described state is neither served in `initial` nor denied, or when the
    /// server predates `initial` (C-26 … C-28); a denied watched state is revoked (R-3, R-4).
    private func opened(_ connected: SPFNStreamConnected)
    {
        guard trust(connected)
        else
        {
            return
        }
        generation = connected.epoch
        connectionQuery = connected.query
        guard manifest != nil, !watched.isEmpty
        else
        {
            return
        }
        let denied = Set(connected.deniedEvents)
        let served = Set(connected.initial ?? [])
        revokeDenied(denied, query: connected.query)
        let unserved = configuration.states.filter { !denied.contains($0.eventName) && !served.contains($0.eventName) }
        if connected.initial != nil && unserved.isEmpty
        {
            readShadowedKeys(connected.query)
        }
        else
        {
            refresh(connected.query)
        }
    }

    private func revokeDenied(_ denied: Set<String>, query: SPFNStateKey)
    {
        for state in configuration.states where denied.contains(state.eventName)
        {
            guard let key = keyIn(state.id, query)
            else
            {
                continue
            }
            let slot = SPFNStateSlot(id: state.id, key: key)
            if watchCounts[slot] != nil
            {
                changed(slot, store.revoke(slot, generation: generation))
            }
        }
    }

    /// A frame for a key nobody here subscribed is dropped (O-11); a subscribed shadowed key's
    /// frame is applied under the ordering rule (K-4). A frame that does not decode is counted.
    private func frame(_ name: String, _ value: SPFNCanonicalValue)
    {
        guard let state = configuration.states.first(where: { $0.eventName == name }),
              let key = Self.frameKey(value)
        else
        {
            return
        }
        let slot = SPFNStateSlot(id: state.id, key: key)
        guard watchCounts[slot] != nil
        else
        {
            return
        }
        guard let arrival = decode(state, key: key, value)
        else
        {
            effects.append(.countDropped)
            return
        }
        changed(slot, store.serve(slot, arrival, generation: generation))
    }

    /// A frame's `key`; absent is keyless. A key with a field that is not a string is no key.
    private static func frameKey(_ value: SPFNCanonicalValue) -> SPFNStateKey?
    {
        guard case .object(let members) = value, let key = members["key"]
        else
        {
            return [:]
        }
        guard case .object(let fields) = key
        else
        {
            return nil
        }
        var result: SPFNStateKey = [:]
        for (field, value) in fields
        {
            guard case .string(let text) = value
            else
            {
                return nil
            }
            result[field] = text
        }
        return result
    }

    /// A revoke or grant concerns the key the connection carries. Granted: the mark goes and
    /// the key is read once; the server's frame may come before or after (R-10).
    private func subscription(_ name: String, _ change: SPFNSubscriptionStatus)
    {
        guard let state = configuration.states.first(where: { $0.eventName == name }),
              let key = keyIn(state.id, connectionQuery ?? [:])
        else
        {
            return
        }
        let slot = SPFNStateSlot(id: state.id, key: key)
        switch change
        {
        case .revoked:
            changed(slot, store.revoke(slot, generation: generation))
        case .granted:
            tick += 1
            grants[slot] = tick
            store.allow(slot)
            if watchCounts[slot] != nil
            {
                send(readRequest(slot), as: .get(slot, call: nil))
            }
        }
    }

    /// C-24: said once per name; the state stays unserved, so every open reads the snapshot.
    private func unavailable(_ names: [String])
    {
        for name in names where !reportedUnavailable.contains(name)
        {
            reportedUnavailable.insert(name)
            effects.append(.report(SPFNStateClientError.unavailable(name)))
        }
    }

    // MARK: - Reads

    /// The snapshot for the connection's query, and a read of each key it shadows.
    private func refresh(_ query: SPFNStateKey)
    {
        readSnapshot(query, tag: tag(), call: nil, first: false)
        readShadowedKeys(query)
    }

    private func readShadowedKeys(_ query: SPFNStateKey)
    {
        for slot in watched where !slot.key.isEmpty && !SPFNStateOrder.carries(query, slot.key)
        {
            send(readRequest(slot), as: .shadow(slot, tag()))
        }
    }

    @discardableResult
    private func readSnapshot(_ query: SPFNStateKey, tag: Tag, call: Int?, first: Bool) -> Int
    {
        let snapshot = configuration.snapshot(query)
        let request = SPFNStateRequest(kind: .snapshot(query: query))
        {
            try await $0.execute(snapshot, request: ())
        }
        return send(request, as: .snapshot(query: query, tag: tag, call: call, first: first))
    }

    private func readRequest(_ slot: SPFNStateSlot) -> SPFNStateRequest
    {
        let call = states[slot.id]!.read(slot.key)
        return SPFNStateRequest(kind: .read(slot)) { try await $0.execute(call, request: ()) }
    }

    @discardableResult
    private func send(_ request: SPFNStateRequest, as purpose: Pending) -> Int
    {
        nextRequest += 1
        pending[nextRequest] = purpose
        effects.append(.execute(request: nextRequest, request))
        return nextRequest
    }

    private func tag() -> Tag
    {
        tick += 1
        return Tag(lifetime: lifetime, generation: generation, tick: tick)
    }

    // MARK: - Answers

    private func answered(_ request: Int, _ result: Result<SPFNCanonicalValue, any Error>)
    {
        guard let purpose = pending.removeValue(forKey: request)
        else
        {
            return
        }
        switch purpose
        {
        case .snapshot(let query, let tag, let call, let first):
            snapshotAnswered(result, query: query, tag: tag, call: call, first: first && firstRead == request)
        case .shadow(let slot, let tag):
            readAnswered(result, slot: slot, generation: tag.generation, call: nil)
        case .get(let slot, let call):
            readAnswered(result, slot: slot, generation: nil, call: call)
        case .write(let slot, let call, let decode):
            writeAnswered(result, slot: slot, call: call, decode: decode)
        }
    }

    /// A snapshot answered after `close()` is discarded; the call that asked still gets it.
    private func snapshotAnswered(_ result: Result<SPFNCanonicalValue, any Error>, query: SPFNStateKey, tag: Tag, call: Int?, first: Bool)
    {
        let parsed = result.flatMap { value in Result { try SPFNStatesSnapshot(canonical: value) } }
        if case .success(let snapshot) = parsed, tag.lifetime == lifetime
        {
            installManifest(snapshot.manifest)
            applyPresent(snapshot, query: query, generation: tag.generation)
            applyOmitted(snapshot, query: query, tag: tag)
        }
        if let call
        {
            effects.append(.resolve(call: call, parsed.map { .snapshot($0) }))
        }
        else if !first, case .failure(let error) = parsed, tag.lifetime == lifetime
        {
            effects.append(.report(error))
        }
        if first
        {
            firstSnapshotAnswered(parsed.map { _ in .null }, lifetime: tag.lifetime)
        }
    }

    /// Each state present, served under its connection. A state the manifest does not
    /// describe, or a keyed one whose key the query lacks, is skipped (K-13, I-3, I-6).
    private func applyPresent(_ snapshot: SPFNStatesSnapshot, query: SPFNStateKey, generation: Int)
    {
        for id in SPFNStateOrder.sortedFields(snapshot.states.keys)
        {
            guard let state = states[id], let key = keyIn(id, query), let entry = snapshot.states[id]
            else
            {
                continue
            }
            let slot = SPFNStateSlot(id: id, key: key)
            guard let arrival = decode(state, key: key, SPFNStateArrival.canonical(at: snapshot.at, entry: entry))
            else
            {
                effects.append(.countDropped)
                continue
            }
            changed(slot, store.serve(slot, arrival, generation: generation))
        }
    }

    /// A watched state the query asked for and the snapshot omits is not served to this
    /// subject — unless a newer connection exists or a grant came after the read started
    /// (R-5, R-6, R-15, R-16).
    private func applyOmitted(_ snapshot: SPFNStatesSnapshot, query: SPFNStateKey, tag: Tag)
    {
        guard tag.generation == generation
        else
        {
            return
        }
        for slot in watched where snapshot.states[slot.id] == nil
        {
            let asked = slot.key.isEmpty || SPFNStateOrder.carries(query, slot.key)
            let grantedSince = (grants[slot] ?? 0) > tag.tick
            if asked && !grantedSince
            {
                changed(slot, store.revoke(slot, generation: tag.generation))
            }
        }
    }

    /// A shadow read is tagged and lifts an older mark (R-13, R-14); an app's `get` and the read
    /// after a grant are not, and only apply (§2-8).
    private func readAnswered(_ result: Result<SPFNCanonicalValue, any Error>, slot: SPFNStateSlot, generation: Int?, call: Int?)
    {
        let arrival = result.flatMap
        {
            value in
            Result { try decodeOrThrow(states[slot.id]!, key: slot.key, value) }
        }
        switch arrival
        {
        case .success(let frame):
            if let generation
            {
                changed(slot, store.serve(slot, frame, generation: generation))
            }
            else
            {
                changed(slot, store.apply(slot, frame))
            }
        case .failure(let error) where call == nil:
            effects.append(.report(error))
        case .failure:
            break
        }
        if let call
        {
            effects.append(.resolve(call: call, arrival.map { .frame($0.typed) }))
        }
    }

    /// A write's answer is applied at once, like a frame; one that cannot be read applies
    /// nothing and the call is refused — the write's own frame brings the value (O-10, O-15).
    private func writeAnswered(_ result: Result<SPFNCanonicalValue, any Error>, slot: SPFNStateSlot, call: Int, decode: @Sendable (SPFNCanonicalValue) throws -> any Sendable)
    {
        let arrival = result.flatMap
        {
            value in
            Result { try Self.arrival(value, typed: try decode(value)) }
        }
        if case .success(let frame) = arrival
        {
            changed(slot, store.apply(slot, frame))
        }
        effects.append(.resolve(call: call, arrival.map { .frame($0.typed) }))
    }

    // MARK: - Helpers

    /// The key of state `id` the query names, by the manifest's fields; `[:]` for a keyless
    /// state; nil when the query lacks one of the fields.
    private func keyIn(_ id: String, _ query: SPFNStateKey) -> SPFNStateKey?
    {
        guard let fields = manifest?[id]?.key
        else
        {
            return nil
        }
        var key: SPFNStateKey = [:]
        for field in fields
        {
            guard let value = query[field]
            else
            {
                return nil
            }
            key[field] = value
        }
        return key
    }

    private func decode(_ state: SPFNStateAccess, key: SPFNStateKey, _ value: SPFNCanonicalValue) -> SPFNStateArrival?
    {
        try? decodeOrThrow(state, key: key, value)
    }

    private func decodeOrThrow(_ state: SPFNStateAccess, key: SPFNStateKey, _ value: SPFNCanonicalValue) throws -> SPFNStateArrival
    {
        try Self.arrival(value, typed: try state.decode(key, value))
    }

    private static func arrival(_ value: SPFNCanonicalValue, typed: any Sendable) throws -> SPFNStateArrival
    {
        let members = try SPFNDecoding.object(value, at: "$")
        return SPFNStateArrival(
            at: try SPFNDecoding.string(members["at"], at: "$.at"),
            value: members["value"] ?? .null,
            data: members["data"],
            typed: typed
        )
    }

    /// A changed entry: `current` answers it, and every subscription of the slot hears it.
    private func changed(_ slot: SPFNStateSlot, _ cell: SPFNStateCell?)
    {
        guard let cell
        else
        {
            return
        }
        effects.append(.publish(slot, cell))
        for (subscription, subscribed) in subscriptions.sorted(by: { $0.key < $1.key }) where subscribed == slot
        {
            effects.append(.deliver(subscription: subscription, cell))
        }
    }

    /// Start from a snapshot already read: its manifest, then each state present, under
    /// generation 0. Nothing is marked revoked: an omission there is no denial (I-1 … I-3).
    private func hydrate(_ initial: SPFNStateInitial)
    {
        installManifest(initial.snapshot.manifest)
        applyPresent(initial.snapshot, query: initial.query, generation: tag().generation)
    }
}

private extension SPFNEventStreamState
{
    var isOffline: Bool
    {
        if case .offline = self
        {
            return true
        }
        return false
    }
}

private extension SPFNStateCore.Pending
{
    /// The app call this request answers; nil for an automatic read.
    var call: Int?
    {
        switch self
        {
        case .snapshot(_, _, let call, _), .get(_, let call):
            return call
        case .write(_, let call, _):
            return call
        case .shadow:
            return nil
        }
    }
}

private extension SPFNStateCore.Waiter
{
    var call: Int
    {
        switch self
        {
        case .get(let call, _), .write(let call, _, _, _):
            return call
        }
    }
}
