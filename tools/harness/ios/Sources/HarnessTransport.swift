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

/// Wraps a real transport with a switch a harness button owns.
final class HarnessTransport: SPFNTransport, @unchecked Sendable
{
    private let inner: any SPFNTransport
    private let lock = NSLock()
    private var blocked = false

    init(inner: any SPFNTransport = SPFNURLSessionTransport())
    {
        self.inner = inner
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
        return try await inner.execute(request)
    }
}
