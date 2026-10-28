// SPFN Mobile — Contracts/fixtures/state/cases.json, run through StateCoreRun.
//
// The case table is the README's, written by hand (Contracts/fixtures/state_cases.py says
// how). Each case is run once per arrival order; `after` is checked against what the arrival
// produced. android/spfn-state/src/test/.../StateCases.kt reads the same file the same way.

import Foundation
import XCTest
@testable import SPFNState
import SPFNClient
import SPFNCore

enum StateCases
{
    static let repoRoot = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()

    static let table: [String: SPFNCanonicalValue] = {
        let url = repoRoot.appendingPathComponent("Contracts/fixtures/state/cases.json")
        let bytes = try! Array(Data(contentsOf: url))
        return try! SPFNDecoding.object(SPFNCanonicalJSON.parse(bytes), at: "$")
    }()

    static var cases: [[String: SPFNCanonicalValue]]
    {
        try! SPFNDecoding.array(table["cases"], at: "$.cases").map { try SPFNDecoding.object($0, at: "$.cases[]") }
    }

    static func run(_ id: String, file: StaticString = #filePath, line: UInt = #line) throws
    {
        guard let found = cases.first(where: { $0["id"] == .string(id) })
        else
        {
            XCTFail("no case \(id) in cases.json", file: file, line: line)
            return
        }
        let one = StateCase(found)
        for (index, arrival) in one.arrivals.enumerated()
        {
            let run = try one.start()
            try one.steps(one.before, on: run)
            run.forget()
            try one.steps(arrival, on: run)
            one.check(run, order: index, file: file, line: line)
        }
    }
}

struct StateCase
{
    let members: [String: SPFNCanonicalValue]

    init(_ members: [String: SPFNCanonicalValue])
    {
        self.members = members
    }

    var id: String
    {
        members["id"]?.string ?? "?"
    }

    var described: [String]
    {
        members["described"]?.strings ?? ["aiAccounts", "consent", "runtime"]
    }

    var before: [SPFNCanonicalValue]
    {
        members["before"]?.array ?? []
    }

    var arrivals: [[SPFNCanonicalValue]]
    {
        if let orders = members["arrivals"]?.array
        {
            return orders.map { $0.array ?? [] }
        }
        return [members["arrival"]?.array ?? []]
    }

    func start() throws -> StateCoreRun
    {
        let initial = try members["initial"].map
        {
            value -> SPFNStateInitial in
            let members = try SPFNDecoding.object(value, at: "$.initial")
            return SPFNStateInitial(
                snapshot: try SPFNStatesSnapshot(canonical: members["snapshot"]!),
                query: members["query"]?.key ?? [:]
            )
        }
        let maxRetries = Int(members["maxRetries"]?.integer ?? 0)
        return StateCoreRun(described: described, initial: initial, maxRetries: maxRetries)
    }

    func steps(_ steps: [SPFNCanonicalValue], on run: StateCoreRun) throws
    {
        for step in steps
        {
            try self.step(try SPFNDecoding.object(step, at: "$.step"), on: run)
        }
    }

    // MARK: - Steps

    private func step(_ step: [String: SPFNCanonicalValue], on run: StateCoreRun) throws
    {
        let state = step["state"]?.string ?? ""
        let key = step["key"]?.key ?? [:]
        switch step["op"]?.string
        {
        case "subscribe":
            run.subscribe(step["sub"]!.string!, state, key)
        case "unsubscribe":
            run.unsubscribe(step["sub"]!.string!)
        case "stream":
            run.stream(try Self.streamState(step["state"]!))
        case "opened":
            run.opened(
                Int(step["epoch"]!.integer!),
                step["query"]?.key ?? [:],
                initial: step["initial"]?.strings,
                denied: step["denied"]?.strings ?? [],
                described: described,
                clientID: step["clientID"]?.string
            )
        case "frame":
            run.frame(state, at: step["at"]!.string!, step["value"]!, key: step["key"]?.key, data: step["data"])
        case "granted":
            run.feed(.wire(.subscription(name: SPFNStateAccess.eventName(of: state), status: .granted)))
        case "revoked":
            run.feed(.wire(.subscription(name: SPFNStateAccess.eventName(of: state), status: .revoked)))
        case "overflow":
            run.feed(.wire(.overflow))
        case "unavailable":
            run.feed(.wire(.unavailable(step["names"]!.strings!)))
        case "answer":
            run.answer(step["request"]!.string!, answer(step))
        case "get":
            run.get(step["call"]!.string!, state, key)
        case "write":
            run.write(step["call"]!.string!, state, key)
        case "snapshotCall":
            run.snapshot(step["call"]!.string!, query: step["query"]?.key)
        case "retry":
            run.feed(.retry)
        case "close":
            run.feed(.close)
        case "timer":
            run.fireTimer()
        case "subscribeConnection":
            run.watchConnection(step["watch"]!.string!)
        case "subject":
            run.feed(.subject(clientID: step["clientID"]!.string))
        default:
            XCTFail("\(id): unknown step \(step)")
        }
    }

    /// A snapshot answer without a manifest carries the table's.
    private func answer(_ step: [String: SPFNCanonicalValue]) -> Result<SPFNCanonicalValue, any Error>
    {
        guard let value = step["with"]
        else
        {
            return .failure(StateTestNetworkError())
        }
        guard step["request"] == .string("snapshot"), case .object(var members) = value, members["manifest"] == nil
        else
        {
            return .success(value)
        }
        members["manifest"] = StateCases.table["manifest"]
        return .success(.object(members))
    }

    static func streamState(_ value: SPFNCanonicalValue) throws -> SPFNEventStreamState
    {
        let members = try SPFNDecoding.object(value, at: "$.state")
        if let epoch = members["open"]?.integer
        {
            return .open(epoch: Int(epoch))
        }
        if let attempt = members["connecting"]?.integer
        {
            return .connecting(attempt: Int(attempt))
        }
        if let attempt = members["retrying"]?.integer
        {
            return .retrying(attempt: Int(attempt), delayMillis: members["delayMillis"]?.integer ?? 1_000, reason: .network)
        }
        if let attempt = members["offline"]?.integer
        {
            return .offline(attempt: Int(attempt))
        }
        if let reason = members["idle"]?.string
        {
            return .idle(reason == "background" ? .background : .signedOut)
        }
        switch members["closed"]?.string
        {
        case "unauthorized":
            return .closed(.unauthorized)
        case "forbidden":
            return .closed(.forbidden)
        default:
            throw SPFNDecodingError.typeMismatch(path: "$.state", expected: "a stream state")
        }
    }

    // MARK: - Expectations

    func check(_ run: StateCoreRun, order: Int, file: StaticString, line: UInt)
    {
        let after = members["after"]?.object ?? [:]
        let label = "\(id) (order \(order + 1))"
        let expectedDeliveries = (after["deliveries"]?.object ?? [:]).mapValues { $0.array!.map(Self.entry) }
        for name in Set(run.deliveries.keys).union(expectedDeliveries.keys).sorted()
        {
            XCTAssertEqual(run.deliveries[name, default: []].map(describe), expectedDeliveries[name] ?? [], "\(label): deliveries to \(name)", file: file, line: line)
        }
        XCTAssertEqual(run.queries, (after["queries"]?.array ?? []).map { $0.key! }, "\(label): queries", file: file, line: line)
        XCTAssertEqual(run.requests.map(Self.request), (after["requests"]?.array ?? []).map(SPFNCanonicalJSON.encodeToString), "\(label): requests", file: file, line: line)
        XCTAssertEqual(run.errors.map(Self.error), (after["errors"]?.array ?? []).map(SPFNCanonicalJSON.encodeToString), "\(label): errors", file: file, line: line)
        XCTAssertEqual(run.finished, after["finished"]?.strings ?? [], "\(label): finished", file: file, line: line)
        XCTAssertEqual(run.reconnects, Int(after["reconnects"]?.integer ?? 0), "\(label): reconnects", file: file, line: line)
        checkOptional(run, after, label: label, file: file, line: line)
    }

    private func checkOptional(_ run: StateCoreRun, _ after: [String: SPFNCanonicalValue], label: String, file: StaticString, line: UInt)
    {
        for expected in after["entries"]?.array ?? []
        {
            let members = expected.object!
            let slot = SPFNStateSlot(id: members["state"]!.string!, key: members["key"]?.key ?? [:])
            XCTAssertEqual(describe(run.cells[slot]), Self.entry(members["entry"]!), "\(label): entry \(slot)", file: file, line: line)
        }
        if let connection = after["connection"]
        {
            XCTAssertEqual(Self.connection(run.connection), SPFNCanonicalJSON.encodeToString(connection), "\(label): connection", file: file, line: line)
        }
        if let connections = after["connections"]?.array
        {
            XCTAssertEqual(run.connections.map(Self.connection), connections.map(SPFNCanonicalJSON.encodeToString), "\(label): connections", file: file, line: line)
        }
        for (name, expected) in after["connectionWatch"]?.object ?? [:]
        {
            XCTAssertEqual(run.watches[name]?.map(Self.connection), expected.array!.map(SPFNCanonicalJSON.encodeToString), "\(label): watch \(name)", file: file, line: line)
        }
        for (name, expected) in after["calls"]?.object ?? [:]
        {
            XCTAssertEqual(run.result(name).map(Self.call), SPFNCanonicalJSON.encodeToString(expected), "\(label): call \(name)", file: file, line: line)
        }
        if let timers = after["timers"]?.integer
        {
            XCTAssertEqual(run.timersStarted, Int(timers), "\(label): timers", file: file, line: line)
        }
        if let dropped = after["dropped"]?.integer
        {
            XCTAssertEqual(run.dropped, Int(dropped), "\(label): dropped", file: file, line: line)
        }
    }

    /// An expected entry in the form `describe` gives a cell.
    static func entry(_ value: SPFNCanonicalValue) -> String
    {
        guard case .object(let members) = value
        else
        {
            return value.string ?? "?"
        }
        guard let at = members["at"]?.string, let state = members["value"]
        else
        {
            return members["status"]!.string!
        }
        let data = members["data"].map { ", " + SPFNCanonicalJSON.encodeToString($0) } ?? ""
        return "\(members["status"]!.string!)(\(at), \(SPFNCanonicalJSON.encodeToString(state))\(data))"
    }

    static func request(_ kind: SPFNStateRequest.Kind) -> String
    {
        let value: SPFNCanonicalValue
        switch kind
        {
        case .snapshot(let query):
            value = .object(["snapshot": .key(query)])
        case .read(let slot):
            value = .object(["read": .object(["state": .string(slot.id), "key": .key(slot.key)])])
        case .write(let slot):
            value = .object(["write": .object(["state": .string(slot.id), "key": .key(slot.key)])])
        }
        return SPFNCanonicalJSON.encodeToString(value)
    }

    static func error(_ error: any Error) -> String
    {
        let value: SPFNCanonicalValue
        switch error
        {
        case SPFNStateClientError.keyFields(let state, let expected, let given):
            value = .object(["keyFields": .object(["state": .string(state), "expected": .texts(expected), "given": .texts(given)])])
        case SPFNStateClientError.unknownState(let state):
            value = .object(["unknownState": .string(state)])
        case SPFNStateClientError.unavailable(let name):
            value = .object(["unavailable": .string(name)])
        case SPFNStateClientError.readOnly(let state):
            value = .object(["readOnly": .string(state)])
        case is StateTestNetworkError:
            value = .string("network")
        case SPFNStateClientError.signedInChanged:
            value = .string("signedInChanged")
        default:
            value = .string(String(describing: error))
        }
        return SPFNCanonicalJSON.encodeToString(value)
    }

    static func connection(_ connection: SPFNStateConnection) -> String
    {
        switch connection
        {
        case .connected:
            return "\"connected\""
        case .failed:
            return "\"failed\""
        case .reconnecting(let attempt):
            return "{\"reconnecting\":\(attempt)}"
        }
    }

    static func call(_ result: Result<SPFNStateResolution, any Error>) -> String
    {
        switch result
        {
        case .success(.snapshot):
            return "\"snapshot\""
        case .success(.frame(let typed)):
            let frame = typed as! SPFNStateFrame<SPFNCanonicalValue, SPFNCanonicalValue>
            var members: [String: SPFNCanonicalValue] = ["at": .string(frame.at), "value": frame.value]
            members["data"] = frame.data == .null ? nil : frame.data
            return SPFNCanonicalJSON.encodeToString(.object(["frame": .object(members)]))
        case .failure(let error):
            return "{\"rejected\":" + Self.error(error) + "}"
        }
    }
}

extension SPFNCanonicalValue
{
    var string: String?
    {
        if case .string(let text) = self
        {
            return text
        }
        return nil
    }

    var integer: Int64?
    {
        if case .integer(let number) = self
        {
            return number
        }
        return nil
    }

    var array: [SPFNCanonicalValue]?
    {
        if case .array(let elements) = self
        {
            return elements
        }
        return nil
    }

    var object: [String: SPFNCanonicalValue]?
    {
        if case .object(let members) = self
        {
            return members
        }
        return nil
    }

    var strings: [String]?
    {
        array?.compactMap(\.string)
    }

    var key: SPFNStateKey?
    {
        object?.compactMapValues(\.string)
    }

    static func key(_ key: SPFNStateKey) -> SPFNCanonicalValue
    {
        .object(key.mapValues { .string($0) })
    }

    static func texts(_ texts: [String]) -> SPFNCanonicalValue
    {
        .array(texts.map { .string($0) })
    }
}
