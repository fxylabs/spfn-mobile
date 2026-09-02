// SPFN Mobile — the fakes the session suite injects.
//
// A session reads a clock and a random source, so every one of its observable rules —
// a fresh nonce per request, expiry judged at an exact instant, one handshake for many
// callers — is only assertable if both are supplied rather than read from the system.
// SpfnSessionTestDoubles.kt is the counterpart.

import Foundation
import SPFNClient
import SPFNGenerated

/// A clock a test moves by hand.
final class FakeClock: SPFNClock, SPFNProofClock, @unchecked Sendable
{
    private let lock = NSLock()
    private var millis: Int64

    init(_ millis: Int64)
    {
        self.millis = millis
    }

    func nowMillis() -> Int64
    {
        lock.lock()
        defer { lock.unlock() }
        return millis
    }

    func nowMillis(
        transport _: any SPFNTransport,
        baseURL _: String,
        timeoutMillis _: Int64
    ) async throws -> Int64
    {
        nowMillis()
    }

    func set(_ millis: Int64)
    {
        lock.lock()
        defer { lock.unlock() }
        self.millis = millis
    }
}

/// A sleeper that records what it was asked to wait and never really waits.
///
/// The device-code suite asserts the interval the SDK obeyed, which is only observable if
/// the wait is a value rather than elapsed time. `onSleep` runs with the 1-based wait
/// number, so a case can make something happen at an exact point in the poll loop.
///
/// Cancellation is honoured the way `Task.sleep` honours it: a wait entered by a task
/// that is already cancelled throws instead of returning, so a cancelled poll loop stops
/// at the wait and never sends the next request.
final class ScriptedSleeper: SPFNSleeper, @unchecked Sendable
{
    private let lock = NSLock()
    private var recorded: [Int64] = []
    private let onSleep: (@Sendable (Int) async -> Void)?

    init(onSleep: (@Sendable (Int) async -> Void)? = nil)
    {
        self.onSleep = onSleep
    }

    var waits: [Int64]
    {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }

    func sleep(millis: Int64) async throws
    {
        lock.lock()
        recorded.append(millis)
        let count = recorded.count
        lock.unlock()
        await onSleep?(count)
        try Task.checkCancellation()
    }
}

/// Hands out a fixed list of nonces in order, so a fixture's exact nonce can be replayed.
final class ScriptedNonceGenerator: SPFNNonceGenerator, @unchecked Sendable
{
    private let lock = NSLock()
    private var remaining: [String]
    private var issued: Int = 0

    init(_ nonces: [String])
    {
        self.remaining = nonces
    }

    func nextNonce() -> String
    {
        lock.lock()
        defer { lock.unlock() }
        issued += 1
        guard !remaining.isEmpty
        else
        {
            return "nonce-exhausted-\(issued)"
        }
        return remaining.removeFirst()
    }
}

/// Answers from a script, records every request, and can hold a call open long enough
/// for other callers to arrive while it is still in flight.
actor ScriptedTransport: SPFNTransport
{
    private var outcomes: [Result<SPFNTransportResponse, any Error>]
    private let holdNanos: UInt64

    /// Runs after the request is recorded and before its answer is produced, with the
    /// 1-based call number. The execute suite uses it to make something happen at an
    /// exact point in a call — cancelling between two attempts, for instance — rather
    /// than racing a timer against the code under test.
    private let onCall: (@Sendable (Int) async -> Void)?

    private(set) var received: [SPFNTransportRequest] = []

    init(
        _ outcomes: [Result<SPFNTransportResponse, any Error>],
        holdNanos: UInt64 = 0,
        onCall: (@Sendable (Int) async -> Void)? = nil
    )
    {
        self.outcomes = outcomes
        self.holdNanos = holdNanos
        self.onCall = onCall
    }

    var callCount: Int
    {
        received.count
    }

    func execute(_ request: SPFNTransportRequest) async throws -> SPFNTransportResponse
    {
        received.append(request)
        await onCall?(received.count)
        if holdNanos > 0
        {
            try? await Task.sleep(nanoseconds: holdNanos)
        }
        guard !outcomes.isEmpty
        else
        {
            throw SPFNTransportError.connectivity("scripted transport ran out of answers")
        }
        return try outcomes.removeFirst().get()
    }
}

extension SPFNTransportResponse
{
    /// A JSON answer, spelled the way a server would put it on the wire.
    ///
    /// The contract announcement is part of "the way a server would put it": contract
    /// 0.8.0 puts it on every response including a refusal, and this SDK refuses a
    /// response without it. A double that omitted it would make every test in this
    /// directory assert the refusal rather than what it was written to assert.
    static func json(_ statusCode: Int, _ text: String) -> SPFNTransportResponse
    {
        SPFNTransportResponse(
            statusCode: statusCode,
            headers: [("content-type", "application/json")] + announcement(),
            body: Array(text.utf8)
        )
    }

    /// The server's own announcement, defaulting to the version this build was generated
    /// from. A test that is about the announcement passes its own.
    static func announcement(
        version: String = SPFNGeneratedContract.binding.importedVersion,
        range: String = SPFNGeneratedContract.binding.supportedRange
    ) -> [(String, String)]
    {
        [
            (SPFNWireHeaders.serverContractVersion, version),
            (SPFNWireHeaders.supportedContractRange, range),
        ]
    }
}

/// What a signer that cannot sign throws. Its own type, so a test can assert the
/// session neither wrapped it nor replaced it.
enum SignerFailure: Error, Equatable
{
    case keyUnavailable
}

/// A provider whose key is gone: every sign attempt fails.
struct ThrowingKeyProvider: SPFNKeyProvider
{
    let clientID: String = SessionFixtureValues.clientID
    let keyID: String = SessionFixtureValues.keyID

    func sign(_ message: [UInt8]) throws -> [UInt8]
    {
        throw SignerFailure.keyUnavailable
    }
}

enum SessionFixtureValues
{
    /// The synthetic key every fixture vector is signed with. Not a credential; see
    /// Contracts/fixtures/MANIFEST.json.
    static let clientID = "client-test-0001"
    static let keyID = "key-test-0001"
    static let sessionID = "session-test-0001"
    static let issuedAtMillis: Int64 = 1_750_000_000_000
    static let expiresAtMillis: Int64 = 1_750_000_300_000

    /// A handshake answer in canonical form, as the server would write it.
    static func handshakeResponse(expiringAt millis: Int64) -> String
    {
        "{\"expiresAtMillis\":\(millis),\"sessionId\":\"\(sessionID)\"}"
    }

    static let handshakeResponseBody = handshakeResponse(expiringAt: expiresAtMillis)
}
