// SPFN Mobile — the harness's one transport trick.
//
// Three of the ten cases the flows cover need the lifecycle in `rotationPending`, and
// there is exactly one way to get there: `rotate()` persists the candidate BEFORE it
// sends, and a transport failure — no response at all — leaves that candidate in place
// because the server may or may not have applied the request.
//
// So the harness needs to be able to drop the network on command. This wrapper is that
// command. It is not a fake server and it does not answer anything: it refuses to send,
// with the same `connectivity` error a real network drop produces, so the state the app
// lands in is the state a real network drop lands in.
//
// Nothing in the SDK changed to make this possible. The transport is injected, which is
// what the boundary exists for.

import Foundation
import SPFNClient

/// What the wire said, for a receipt to record.
///
/// The SDK's own errors carry an HTTP status where one exists, but only some of them do,
/// and a success carries none at all. A receipt needs the same field filled the same way
/// whatever happened, so it is read from the one place every request passes through.
struct HarnessWireObservation: Sendable
{
    let statusCode: Int
    let serverCommit: String?
}

/// Wraps a real transport with a switch a harness button owns.
final class HarnessTransport: SPFNTransport, @unchecked Sendable
{
    /// Header names a server might state its build under, lowercased. None of the SPFN
    /// servers in this repository emits one today, so a receipt recording `null` here is
    /// the expected reading rather than a gap — the field exists for a server that does.
    private static let commitHeaders = ["x-spfn-commit", "x-spfn-server-commit", "x-commit"]

    private let inner: any SPFNTransport
    private let lock = NSLock()
    private var blocked = false
    private var lastObservation: HarnessWireObservation?

    init(inner: any SPFNTransport = SPFNURLSessionTransport())
    {
        self.inner = inner
    }

    /// Forgets the previous attempt's response.
    ///
    /// Called before an attempt rather than after it: without this, an attempt that never
    /// reached the network would report the status of whatever the last one did, and the
    /// network-failure cell — whose whole point is that there was no response — would
    /// carry a 200 from the enrolment before it.
    func beginAttempt()
    {
        lock.lock()
        lastObservation = nil
        lock.unlock()
    }

    var observation: HarnessWireObservation?
    {
        lock.lock()
        defer { lock.unlock() }
        return lastObservation
    }

    var isBlocked: Bool
    {
        lock.lock()
        defer { lock.unlock() }
        return blocked
    }

    func setBlocked(_ value: Bool)
    {
        lock.lock()
        blocked = value
        lock.unlock()
    }

    func execute(_ request: SPFNTransportRequest) async throws -> SPFNTransportResponse
    {
        if isBlocked
        {
            throw SPFNTransportError.connectivity("harness: network blocked")
        }
        let response = try await inner.execute(request)
        record(response)
        return response
    }

    private func record(_ response: SPFNTransportResponse)
    {
        let commit = response.headers
            .first { Self.commitHeaders.contains($0.0.lowercased()) }?
            .1
        lock.lock()
        lastObservation = HarnessWireObservation(statusCode: response.statusCode, serverCommit: commit)
        lock.unlock()
    }
}
