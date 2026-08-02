// SPFN Mobile — driving the reference server's test hooks from Swift.
//
// `/control` is not part of the contract, so nothing in the SDK knows about it and
// nothing here goes through the SDK. It is plain URLSession over canonical JSON, which
// is also a small independent check that the server's canonical encoder produces
// something Swift's canonical parser accepts.

import Foundation
import SPFNCore

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// The counters `/control/stats` reports.
struct SPFNReferenceStats: Sendable
{
    let requestCount: Int64
    let handshakeCount: Int64
    let echoCount: Int64
    let itemsListCount: Int64
    let refusalCount: Int64
}

struct SPFNReferenceControlClient: Sendable
{
    let environment: SPFNIntegrationEnvironment

    /// Returns the server to the state it started in. Every case calls this first, so a
    /// case that revoked a key cannot decide the outcome of the one that runs after it.
    func reset() async throws
    {
        try await post("/control/reset", body: .object([:]))
    }

    /// Drops every session the server holds, without touching the expiry it advertised.
    func expireSessions() async throws
    {
        try await post("/control/expire-sessions", body: .object([:]))
    }

    func revokeKey(_ keyID: String) async throws
    {
        try await post("/control/revoke-key", body: .object(["keyId": .string(keyID)]))
    }

    /// Makes the next `count` requests to `path` wait, so a timeout has something to time out on.
    func hold(path: String, millis: Int64, count: Int64) async throws
    {
        try await post(
            "/control/hold",
            body: .object(["path": .string(path), "millis": .integer(millis), "count": .integer(count)])
        )
    }

    func stats() async throws -> SPFNReferenceStats
    {
        let value = try await send("GET", "/control/stats", body: nil)
        guard case .object(let members) = value
        else
        {
            throw SPFNIntegrationFailure.control("stats was not an object")
        }
        return SPFNReferenceStats(
            requestCount: try integer(members, "requestCount"),
            handshakeCount: try integer(members, "handshakeCount"),
            echoCount: try integer(members, "echoCount"),
            itemsListCount: try integer(members, "itemsListCount"),
            refusalCount: try integer(members, "refusalCount")
        )
    }

    // MARK: - Plumbing

    private func post(_ path: String, body: SPFNCanonicalValue) async throws
    {
        _ = try await send("POST", path, body: body)
    }

    private func send(_ method: String, _ path: String, body: SPFNCanonicalValue?) async throws -> SPFNCanonicalValue
    {
        guard let url = URL(string: environment.baseURL + path)
        else
        {
            throw SPFNIntegrationFailure.control("\(path) is not a URL")
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 10
        request.addValue(environment.controlToken, forHTTPHeaderField: "x-spfn-reference-control")
        if let body
        {
            request.addValue("application/json", forHTTPHeaderField: "content-type")
            request.httpBody = Data(SPFNCanonicalJSON.encode(body))
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200 ... 299).contains(http.statusCode)
        else
        {
            let status = (response as? HTTPURLResponse)?.statusCode ?? -1
            throw SPFNIntegrationFailure.control("\(path) answered \(status)")
        }
        return try SPFNCanonicalJSON.parse([UInt8](data))
    }

    private func integer(_ members: [String: SPFNCanonicalValue], _ name: String) throws -> Int64
    {
        guard case .integer(let value)? = members[name]
        else
        {
            throw SPFNIntegrationFailure.control("stats.\(name) is missing")
        }
        return value
    }
}
