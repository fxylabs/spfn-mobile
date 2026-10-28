// SPFN Mobile — the state client's core, driven step by step (design §4).
//
// `StateCoreRun` feeds SPFNStateCore one input at a time and records what each produced:
// deliveries per subscription, queries handed to the stream, requests, errors, connection
// changes, app call results. After every step it runs the flush the step asked for, so a
// step is a turn; `settle: false` keeps two steps in one turn (K-6, K-7).
//
// `StateCases` reads Contracts/fixtures/state/cases.json and runs one case through the same
// run. android/spfn-state/src/test/.../StateCoreRun.kt is the counterpart.

import Foundation
import XCTest
@testable import SPFNState
import SPFNClient
import SPFNCore

/// A failure the scripted `execute` answers with: "network" in the cases.
struct StateTestNetworkError: Error, Equatable {}

final class StateCoreRun
{
    static let keyed: Set<String> = ["aiAccounts", "runtime"]

    let core: SPFNStateCore
    private(set) var cells: [SPFNStateSlot: SPFNStateCell] = [:]
    private(set) var connection: SPFNStateConnection = .reconnecting(attempt: 0)
    private var subscriptions: [String: Int] = [:]
    private var calls: [String: Int] = [:]
    private var counter = 0
    private var flushPending = false
    private(set) var timer: Int?
    private(set) var pending: [(id: Int, kind: SPFNStateRequest.Kind)] = []

    // Since the last `forget()`.
    private(set) var deliveries: [String: [SPFNStateCell?]] = [:]
    private(set) var finished: [String] = []
    private(set) var queries: [SPFNStateKey] = []
    private(set) var reconnects = 0
    private(set) var requests: [SPFNStateRequest.Kind] = []
    private(set) var errors: [any Error] = []
    private(set) var connections: [SPFNStateConnection] = []
    private(set) var resolved: [String: Result<SPFNStateResolution, any Error>] = [:]
    private(set) var timersStarted = 0
    private(set) var dropped = 0
    private(set) var watches: [String: [SPFNStateConnection]] = [:]
    private(set) var wire: [String] = []

    init(described: [String] = ["aiAccounts", "consent", "runtime"], initial: SPFNStateInitial? = nil, maxRetries: Int = 0)
    {
        core = SPFNStateCore(
            configuration: .init(
                states: described.map { Self.descriptor($0).access },
                snapshot: { _ in Self.unsent() },
                retryDelayMillis: 3_000,
                maxRetries: maxRetries
            ),
            initial: initial
        )
        record(core.start())
        forget()
    }

    static func descriptor(_ id: String) -> SPFNWritableStateDescriptor<SPFNCanonicalValue, SPFNCanonicalValue, SPFNCanonicalValue>
    {
        SPFNStateDescriptor(id: id, keyFields: keyed.contains(id) ? ["workspaceId"] : [], read: { _ in unsent(decode: { try frame($0) }) }, frame: { $0 })
            .writable(write: { _ in unsent(decode: { try frame($0) }) })
    }

    /// `{ at, value, data? }` as a frame of canonical values; `data` absent is `null`.
    static func frame(_ value: SPFNCanonicalValue) throws -> SPFNStateFrame<SPFNCanonicalValue, SPFNCanonicalValue>
    {
        let members = try SPFNDecoding.object(value, at: "$")
        guard let state = members["value"]
        else
        {
            throw SPFNDecodingError.typeMismatch(path: "$.value", expected: "a value")
        }
        return SPFNStateFrame(at: try SPFNDecoding.string(members["at"], at: "$.at"), value: state, data: members["data"] ?? .null)
    }

    private static func unsent<Request, Response>(decode: @escaping @Sendable (SPFNCanonicalValue) throws -> Response = { $0 as! Response }) -> SPFNCall<Request, Response>
    {
        SPFNCall(
            operation: SPFNOperation(id: "test.unsent", method: "GET", path: "/unsent", authProfile: "none", requiresSession: false, declaresResponse: true),
            encode: { _ in nil },
            decode: decode
        )
    }

    /// Clears what was recorded; the state itself stays.
    func forget()
    {
        deliveries = [:]
        finished = []
        queries = []
        reconnects = 0
        requests = []
        errors = []
        connections = []
        resolved = [:]
        timersStarted = 0
        dropped = 0
        wire = []
        for name in watches.keys
        {
            watches[name] = []
        }
    }

    // MARK: - Steps

    func feed(_ input: SPFNStateInput, settle: Bool = true)
    {
        record(core.process(input))
        if settle
        {
            self.settle()
        }
    }

    /// The end of the turn: the flush a step asked for.
    func settle()
    {
        while flushPending
        {
            flushPending = false
            record(core.process(.flush))
        }
    }

    func subscribe(_ name: String, _ state: String, _ key: SPFNStateKey = [:], settle: Bool = true)
    {
        counter += 1
        subscriptions[name] = counter
        deliveries[name] = deliveries[name] ?? []
        feed(.subscribe(subscription: counter, slot: SPFNStateSlot(id: state, key: key)), settle: settle)
    }

    func unsubscribe(_ name: String, settle: Bool = true)
    {
        feed(.unsubscribe(subscription: subscriptions[name]!), settle: settle)
    }

    func stream(_ state: SPFNEventStreamState)
    {
        feed(.stream(state))
    }

    func opened(
        _ epoch: Int,
        _ query: SPFNStateKey = [:],
        initial: [String]? = nil,
        denied: [String] = [],
        described: [String] = ["aiAccounts", "consent", "runtime"],
        clientID: String? = nil
    )
    {
        let events = described.map(SPFNStateAccess.eventName(of:))
        let connected = SPFNStreamConnected(
            epoch: epoch,
            query: query,
            subscribedEvents: events.filter { !denied.contains($0) },
            deniedEvents: denied,
            initial: initial,
            clientID: clientID
        )
        feed(.wire(.opened(connected)))
    }

    /// Opens connection `epoch`, its `initial` serving every described state.
    func open(_ epoch: Int, _ query: SPFNStateKey = [:], described: [String] = ["aiAccounts", "consent", "runtime"])
    {
        stream(.open(epoch: epoch))
        opened(epoch, query, initial: described.map(SPFNStateAccess.eventName(of:)), described: described)
    }

    func frame(_ state: String, at: String, _ value: SPFNCanonicalValue, key: SPFNStateKey? = nil, data: SPFNCanonicalValue? = nil)
    {
        var members: [String: SPFNCanonicalValue] = ["at": .string(at), "value": value]
        members["data"] = data
        members["key"] = key.map { .object($0.mapValues { .string($0) }) }
        feed(.wire(.frame(name: SPFNStateAccess.eventName(of: state), value: .object(members))))
    }

    func get(_ call: String, _ state: String, _ key: SPFNStateKey = [:])
    {
        feed(.get(call: callID(call), slot: SPFNStateSlot(id: state, key: key)))
    }

    func write(_ call: String, _ state: String, _ key: SPFNStateKey = [:])
    {
        let slot = SPFNStateSlot(id: state, key: key)
        let request = SPFNStateRequest(kind: .write(slot)) { _ in throw StateTestNetworkError() }
        feed(.write(call: callID(call), slot: slot, request: request, decode: { try Self.frame($0) }))
    }

    func snapshot(_ call: String, query: SPFNStateKey?)
    {
        feed(.snapshot(call: callID(call), query: query))
    }

    /// Answers the oldest unanswered request of that kind.
    func answer(_ kind: String, _ result: Result<SPFNCanonicalValue, any Error>)
    {
        guard let index = pending.firstIndex(where: { Self.name(of: $0.kind) == kind })
        else
        {
            XCTFail("no \(kind) request is waiting")
            return
        }
        feed(.answered(request: pending.remove(at: index).id, result))
    }

    func fireTimer()
    {
        guard let timer
        else
        {
            XCTFail("no retry timer is set")
            return
        }
        self.timer = nil
        feed(.retryElapsed(generation: timer))
    }

    func watchConnection(_ name: String)
    {
        watches[name] = [connection]
    }

    func result(_ call: String) -> Result<SPFNStateResolution, any Error>?
    {
        resolved[call]
    }

    private func callID(_ name: String) -> Int
    {
        counter += 1
        calls[name] = counter
        return counter
    }

    static func name(of kind: SPFNStateRequest.Kind) -> String
    {
        switch kind
        {
        case .snapshot:
            return "snapshot"
        case .read:
            return "read"
        case .write:
            return "write"
        }
    }

    // MARK: - Effects

    private func record(_ effects: [SPFNStateEffect])
    {
        effects.forEach(record)
    }

    private func record(_ effect: SPFNStateEffect)
    {
        switch effect
        {
        case .deliver(let subscription, let cell):
            deliveries[subscriptionName(subscription), default: []].append(cell)
        case .finish(let subscription):
            finished.append(subscriptionName(subscription))
        case .publish(let slot, let cell):
            cells[slot] = cell
        case .clear:
            cells = [:]
        case .execute(let id, let request):
            pending.append((id, request.kind))
            requests.append(request.kind)
        case .setQuery(let query):
            queries.append(query)
        case .reconnectNow:
            reconnects += 1
        case .connection(let next):
            connection = next
            connections.append(next)
            for name in watches.keys
            {
                watches[name]?.append(next)
            }
        case .startRetryTimer(let generation, _):
            timer = generation
            timersStarted += 1
        case .cancelRetryTimer:
            timer = nil
        case .report(let error):
            errors.append(error)
        case .resolve(let call, let result):
            resolved[calls.first { $0.value == call }!.key] = result
        case .countDropped:
            dropped += 1
        case .scheduleFlush:
            flushPending = true
        case .attachWire:
            wire.append("attach")
        case .detachWire:
            wire.append("detach")
        }
    }

    private func subscriptionName(_ id: Int) -> String
    {
        subscriptions.first { $0.value == id }!.key
    }
}

// MARK: - Values

extension SPFNCanonicalValue
{
    static func text(_ value: String) -> SPFNCanonicalValue
    {
        .string(value)
    }
}

extension SPFNStateCell
{
    /// `ready(at, value)`, `revoked(at, value)` and `revoked` alone, for comparing.
    var described: String
    {
        guard let frame
        else
        {
            return status == .revoked ? "revoked" : "loading"
        }
        let data = frame.data.map { ", " + SPFNCanonicalJSON.encodeToString($0) } ?? ""
        return "\(status)(\(frame.at), \(SPFNCanonicalJSON.encodeToString(frame.value))\(data))"
    }
}

func describe(_ cell: SPFNStateCell?) -> String
{
    cell?.described ?? "loading"
}
