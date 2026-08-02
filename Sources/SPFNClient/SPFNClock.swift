// SPFN Mobile — the two ambient inputs a proof depends on.
//
// A proof carries a timestamp and a nonce, so a session that read the wall clock and
// the system random generator directly would be untestable: no test could assert that
// two consecutive proofs carry different nonces, or that a session expires exactly at
// its expiry instant. Both are injected instead, and every test injects a fake.

import Foundation

/// Milliseconds since the Unix epoch.
public protocol SPFNClock: Sendable
{
    func nowMillis() -> Int64
}

/// The system wall clock.
///
/// Deliberately not corrected for server skew. The alpha has no skew margin at all —
/// expiry is judged against this clock as the server reported it (D23).
public struct SPFNSystemClock: SPFNClock
{
    public init() {}

    public func nowMillis() -> Int64
    {
        Int64((Date().timeIntervalSince1970 * 1000).rounded(.down))
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
