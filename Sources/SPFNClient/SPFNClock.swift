// SPFN Mobile — the two ambient inputs a proof depends on.
//
// The monotonic source under the proof clock counts through device sleep. Darwin's
// `DispatchTime.now().uptimeNanoseconds` is `mach_absolute_time`, which stops for the
// whole of every sleep, so a phone that slept past the server's replay window would
// derive a proof timestamp that far behind and have every proof it minted refused.
//
// A proof carries a timestamp and a nonce, so a session that read the wall clock and
// the system random generator directly would be untestable: no test could assert that
// two consecutive proofs carry different nonces, or that a session expires exactly at
// its expiry instant. Both are injected instead, and every test injects a fake.

import Foundation
import SPFNCore
import SPFNGenerated

#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

/// Milliseconds since the Unix epoch.
public protocol SPFNClock: Sendable
{
    func nowMillis() -> Int64
}

/// The system wall clock used for local key-lifecycle timestamps.
///
/// clientProofV1 does not use this clock. Proof timestamps and session expiry use
/// `SPFNProcessServerClock`, whose epoch comes from the server and whose elapsed time
/// comes from a monotonic source.
public struct SPFNSystemClock: SPFNClock
{
    public init() {}

    public func nowMillis() -> Int64
    {
        Int64((Date().timeIntervalSince1970 * 1000).rounded(.down))
    }
}

/// A process-local clock that supplies clientProofV1 timestamps.
///
/// The first read synchronizes through the contract-declared, unproven `core.time`
/// operation. Implementations fail closed rather than returning device wall-clock time.
public protocol SPFNProofClock: Sendable
{
    func nowMillis(
        transport: any SPFNTransport,
        baseURL: String,
        timeoutMillis: Int64
    ) async throws -> Int64
}

/// Why the SDK could not establish or advance the server-anchored proof clock.
public enum SPFNClockSynchronizationError: Error, Equatable, Sendable
{
    case contractIncompatible
    case untrustedBaseURL
    case requestFailed
    case invalidResponse
    case monotonicClockInvalid
    case clockOverflow
}

extension SPFNClockSynchronizationError: CustomStringConvertible, CustomDebugStringConvertible
{
    public var description: String
    {
        switch self
        {
        case .contractIncompatible: "SPFNClockSynchronizationError.contractIncompatible"
        case .untrustedBaseURL: "SPFNClockSynchronizationError.untrustedBaseURL"
        case .requestFailed: "SPFNClockSynchronizationError.requestFailed"
        case .invalidResponse: "SPFNClockSynchronizationError.invalidResponse"
        case .monotonicClockInvalid: "SPFNClockSynchronizationError.monotonicClockInvalid"
        case .clockOverflow: "SPFNClockSynchronizationError.clockOverflow"
        }
    }

    public var debugDescription: String { description }
}

protocol SPFNMonotonicClock: Sendable
{
    func nowNanos() -> UInt64
}

/// The platform's sleep-inclusive monotonic source.
///
/// Darwin's `CLOCK_MONOTONIC_RAW` and Linux's `CLOCK_BOOTTIME` are the two clocks of
/// their platform that keep counting while the device is suspended; Darwin's
/// `CLOCK_UPTIME_RAW` — which is what `DispatchTime` and `mach_absolute_time` read —
/// does not. Android's counterpart is `SystemClock.elapsedRealtimeNanos`, which
/// `SpfnSystemMonotonicClock` already uses.
struct SPFNSystemMonotonicClock: SPFNMonotonicClock
{
    func nowNanos() -> UInt64
    {
        #if canImport(Darwin)
        return clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW)
        #else
        var now = timespec()
        clock_gettime(CLOCK_BOOTTIME, &now)
        return UInt64(now.tv_sec) * 1_000_000_000 + UInt64(now.tv_nsec)
        #endif
    }
}

/// The default clientProofV1 clock.
///
/// `shared` owns one in-memory anchor per normalized base URL for the process lifetime.
/// Concurrent first readers share one request. Nothing is persisted across processes.
public actor SPFNProcessServerClock: SPFNProofClock
{
    public static let shared = SPFNProcessServerClock()

    private struct Anchor: Sendable
    {
        let serverTimeMillis: Int64
        let monotonicReceiptNanos: UInt64
    }

    private struct InFlight: Sendable
    {
        let id: UInt64
        let task: Task<Anchor, Error>
    }

    private let monotonicClock: any SPFNMonotonicClock
    private let operationResolver: @Sendable () -> SPFNOperation?
    private var anchors: [String: Anchor] = [:]
    private var inFlight: [String: InFlight] = [:]
    private var nextInFlightID: UInt64 = 0

    /// Creates an empty process clock. Most applications use `shared`.
    public init()
    {
        monotonicClock = SPFNSystemMonotonicClock()
        operationResolver = {
            SPFNGeneratedOperations.operation(id: SPFNGeneratedContract.clockSynchronizationOperationID)
        }
    }

    init(
        monotonicClock: any SPFNMonotonicClock,
        operationResolver: @escaping @Sendable () -> SPFNOperation? = {
            SPFNGeneratedOperations.operation(id: SPFNGeneratedContract.clockSynchronizationOperationID)
        }
    )
    {
        self.monotonicClock = monotonicClock
        self.operationResolver = operationResolver
    }

    public func nowMillis(
        transport: any SPFNTransport,
        baseURL: String,
        timeoutMillis: Int64
    ) async throws -> Int64
    {
        let key = Self.normalizedBaseURL(baseURL)
        if let anchor = anchors[key]
        {
            return try derivedTime(from: anchor)
        }

        let claim: InFlight
        if let existing = inFlight[key]
        {
            claim = existing
        }
        else
        {
            guard Self.isTrusted(baseURL: key)
            else
            {
                throw SPFNClockSynchronizationError.untrustedBaseURL
            }
            guard let operation = operationResolver(),
                  operation.authProfile == "none", !operation.requiresSession
            else
            {
                throw SPFNClockSynchronizationError.contractIncompatible
            }

            nextInFlightID &+= 1
            let id = nextInFlightID
            let monotonicClock = self.monotonicClock
            let task = Task<Anchor, Error>
            {
                let response: SPFNTransportResponse
                do
                {
                    response = try await transport.execute(
                        SPFNTransportRequest(
                            method: operation.method,
                            url: key + operation.path,
                            headers: [],
                            body: nil,
                            timeoutMillis: timeoutMillis
                        )
                    )
                }
                catch is CancellationError
                {
                    throw CancellationError()
                }
                catch
                {
                    throw SPFNClockSynchronizationError.requestFailed
                }

                let receipt = monotonicClock.nowNanos()
                guard (200 ... 299).contains(response.statusCode),
                      let canonical = try? SPFNCanonicalJSON.parse(response.body),
                      let decoded = try? SPFNServerTimeResponse(canonical: canonical)
                else
                {
                    throw SPFNClockSynchronizationError.invalidResponse
                }
                return Anchor(
                    serverTimeMillis: decoded.serverTimeMillis,
                    monotonicReceiptNanos: receipt
                )
            }
            claim = InFlight(id: id, task: task)
            inFlight[key] = claim
        }

        do
        {
            let anchor = try await claim.task.value
            if inFlight[key]?.id == claim.id
            {
                anchors[key] = anchor
                inFlight[key] = nil
            }
            try Task.checkCancellation()
            return try derivedTime(from: anchor)
        }
        catch
        {
            if inFlight[key]?.id == claim.id
            {
                inFlight[key] = nil
            }
            throw error
        }
    }

    private func derivedTime(from anchor: Anchor) throws -> Int64
    {
        let now = monotonicClock.nowNanos()
        guard now >= anchor.monotonicReceiptNanos
        else
        {
            throw SPFNClockSynchronizationError.monotonicClockInvalid
        }
        let elapsedNanos = now - anchor.monotonicReceiptNanos
        let elapsedUnsigned = elapsedNanos / 1_000_000
        guard elapsedUnsigned <= UInt64(Int64.max)
        else
        {
            throw SPFNClockSynchronizationError.clockOverflow
        }
        let elapsed = Int64(elapsedUnsigned)
        let (result, overflow) = anchor.serverTimeMillis.addingReportingOverflow(elapsed)
        guard !overflow
        else
        {
            throw SPFNClockSynchronizationError.clockOverflow
        }
        return result
    }

    private static func normalizedBaseURL(_ value: String) -> String
    {
        var result = value
        while result.hasSuffix("/")
        {
            result.removeLast()
        }
        return result
    }

    private static func isTrusted(baseURL: String) -> Bool
    {
        guard let components = URLComponents(string: baseURL),
              let scheme = components.scheme?.lowercased(),
              let host = components.host?.lowercased()
        else
        {
            return false
        }
        if scheme == "https"
        {
            return true
        }
        return scheme == "http" && (host == "localhost" || host == "::1" || host.hasPrefix("127."))
    }
}

/// Produces one fresh nonce per request.
public protocol SPFNNonceGenerator: Sendable
{
    func nextNonce() -> String
}

/// 128 bits from the system's cryptographic random source, as lowercase base16.
///
/// Hex rather than any denser encoding because a proof field may not contain a C0
/// control character and must survive an HTTP header value unchanged; hex satisfies
/// both without an escaping rule two platforms could implement differently.
public struct SPFNRandomNonceGenerator: SPFNNonceGenerator
{
    private static let byteCount = 16

    public init() {}

    public func nextNonce() -> String
    {
        var generator = SystemRandomNumberGenerator()
        let digits = Array("0123456789abcdef".utf8)
        var out: [UInt8] = []
        out.reserveCapacity(Self.byteCount * 2)
        for _ in 0 ..< Self.byteCount
        {
            let byte = UInt8.random(in: UInt8.min ... UInt8.max, using: &generator)
            out.append(digits[Int(byte >> 4)])
            out.append(digits[Int(byte & 0x0F)])
        }
        return String(decoding: out, as: UTF8.self)
    }
}
