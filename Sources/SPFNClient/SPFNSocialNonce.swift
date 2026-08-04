// SPFN Mobile — the one-time value that binds a provider sign-in to this enrollment.
//
// The same nonce is written into two places in two different shapes, and an app that
// gets the asymmetry wrong sees the server refuse the enrollment with nothing in any
// log saying why. So the shapes are not the app's to choose:
//
//   Apple's authorization request  ->  appleRequestValue, the SHA-256 of the raw value
//   every other provider's request ->  the raw value
//   this SDK's enrollment body     ->  the raw value
//
// Apple is the exception, not the rule: it hashes the nonce in the request and puts the
// pre-image's hash in the token it signs, so the raw value is what the server compares
// against. The adapters read the shape they need through package-visible members; an
// app can read neither, which is the entire reason this is a type rather than a String.
//
// The raw value is lowercase hex and deliberately NOT base64. A base64url value's last
// character carries fewer than 6 bits, and a provider that re-encodes the value can
// return a different last character than it was given — measured against Naver, which
// drops a trailing `A` (spfn-primitives issue #57). Hex has a fixed meaning per
// position, so a round trip through a provider is either identical or obviously broken.
// The constraint is kept from the start: changing the shape later would break flows that
// are already enrolled.
//
// android/spfn-client/.../SpfnSocialNonce.kt is the same value in Kotlin.

import Foundation
import SPFNCore

public struct SPFNSocialNonce: Sendable, CustomStringConvertible, CustomDebugStringConvertible
{
    /// 32 bytes, rendered as 64 hex characters. The pre-image of a SHA-256 an attacker
    /// sees in an Apple request, so it is sized as a digest pre-image, not as an id.
    package static let byteCount = 32

    /// Readable inside this package and nowhere else: no app can reach it, and the two
    /// in-package readers — the enrollment body and a non-Apple provider adapter — are
    /// the only ones that exist.
    private let raw: String

    /// What goes in Apple's authorization request: the SHA-256 of the raw value, in
    /// lowercase hex. Public because the Apple adapter is a separate module and an app
    /// that drives Apple's UI itself still needs the value to put in the request.
    public let appleRequestValue: String

    /// A fresh nonce. Every call returns a different raw value; nothing here is derived
    /// from device state, the clock or a counter.
    ///
    /// `SystemRandomNumberGenerator` is the platform's cryptographically secure source,
    /// which is what a value an attacker must not predict requires. The Kotlin
    /// counterpart uses `SecureRandom` for the same reason.
    public static func make() -> SPFNSocialNonce
    {
        var generator = SystemRandomNumberGenerator()
        var bytes: [UInt8] = []
        bytes.reserveCapacity(byteCount)
        for _ in 0 ..< byteCount
        {
            bytes.append(UInt8.random(in: UInt8.min ... UInt8.max, using: &generator))
        }
        return SPFNSocialNonce(raw: hex(bytes))
    }

    /// Package-visible so the conformance suites can pin the exact wire bytes a flow
    /// produces against the fixtures, which name a fixed nonce. It accepts any string
    /// because the fixture's nonce is contract data rather than something this SDK
    /// minted, and rejecting it here would make the fixture unusable as evidence.
    package init(raw: String)
    {
        self.raw = raw
        self.appleRequestValue = SPFNDigest.sha256Hex(raw)
    }

    /// The value the SPFN server compares against, and the value every provider other
    /// than Apple puts in its own request.
    package var rawValue: String
    {
        raw
    }

    /// Lowercase base16, written out rather than taken from a character-classification
    /// API. `Character.isHexDigit` accepts full-width and other non-ASCII digits that
    /// the Kotlin counterpart's explicit range does not, and two guards that disagree
    /// about a character stop being each other's check (P9).
    package static func isLowercaseHex(_ text: String) -> Bool
    {
        !text.isEmpty && text.unicodeScalars.allSatisfy
        { scalar in
            scalar.isASCII && ((scalar >= "0" && scalar <= "9") || (scalar >= "a" && scalar <= "f"))
        }
    }

    /// The hex encoder both platforms are pinned to by a shared vector in the suites.
    package static func hex(_ bytes: [UInt8]) -> String
    {
        let digits = Array("0123456789abcdef".utf8)
        var out: [UInt8] = []
        out.reserveCapacity(bytes.count * 2)
        for byte in bytes
        {
            out.append(digits[Int(byte >> 4)])
            out.append(digits[Int(byte & 0x0F)])
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// The default reflection of a struct prints its stored properties, which would put
    /// the raw value in every interpolated log line that ever touches a nonce. Both
    /// descriptions are therefore written, and both name only the public value.
    public var description: String
    {
        "SPFNSocialNonce(appleRequestValue: \(appleRequestValue))"
    }

    public var debugDescription: String
    {
        description
    }
}
