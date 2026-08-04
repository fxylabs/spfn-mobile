// SPFN Mobile — the value that ties a provider sign-in to the key being enrolled.
//
// The nonce is not a random number. It is the fingerprint of the public key this
// enrollment registers: the SHA-256 of that key's SPKI DER bytes, in lowercase hex.
// The contract's `nativeEnrollment.nonceRule` requires the body's nonce and fingerprint
// to be the same value, and the server refuses the call when they differ.
//
// Why the server wants it that way: an id_token is bearer-shaped, so whoever holds one
// can present it. If the server verified only the token, anyone who stole one could
// enroll their own key on the victim's account. Deriving the nonce from the key means a
// stolen token carries the victim's fingerprint and cannot be paired with another key.
//
// One consequence runs through this whole file: the key must exist before the provider
// is asked for a token, which is why `SPFNKeyLifecycle.enroll` takes a closure and mints
// this value itself. An app cannot construct one.
//
// The provider decides the shape of the value that goes into the provider's own request:
//
//   apple            ->  requestValue is the SHA-256 of the fingerprint, in lowercase hex
//   everyone else    ->  requestValue is the fingerprint itself
//   the SPFN body    ->  always the fingerprint, never requestValue
//
// Apple is the exception because it follows the OIDC rule literally: it hashes the nonce
// in the request and puts that hash in the token it signs, so the value the SPFN server
// compares against is the pre-image. Every other provider SPFN supports natively —
// google, kakao, naver, github — echoes the raw value back.
//
// There is exactly one public value, and the SDK picked it knowing the provider. That is
// the point: an app that could choose between two shapes would eventually choose wrong,
// and the server's refusal for that mistake is a 400 outside the contract's six error
// codes, so it reaches the app as an unknown code naming nothing.
//
// The fingerprint is lowercase hex and deliberately not base64. Naver drops a trailing
// `A` from a nonce it echoes back (spfn-primitives issue #57); lowercase hex has no `A`
// in its alphabet, so the round trip is either identical or obviously broken.
//
// android/spfn-client/.../SpfnSocialNonce.kt is the same value in Kotlin.

import Foundation
import SPFNCore

public struct SPFNSocialNonce: Sendable, CustomStringConvertible, CustomDebugStringConvertible
{
    /// The provider this nonce was minted for. Lowercase, as the enrollment path requires.
    public let provider: String

    /// The value to put in the provider's own authorization request.
    ///
    /// Public because an app may drive a provider this SDK ships no adapter for — kakao
    /// and naver are the ordinary cases — and it needs the value to hand that provider's
    /// SDK. There is only this one, so there is nothing to get wrong.
    public let requestValue: String

    /// The key's fingerprint: what the SPFN enrollment body carries as both `nonce` and
    /// `fingerprint`. Package-visible because the body is assembled in this package and
    /// nowhere else; an app has no reason to read it and no way to.
    package let fingerprint: String

    /// The provider name Apple's flow uses, and the only one whose request is hashed.
    package static let appleProvider = "apple"

    /// Mints the nonce for a key. `fingerprint` is `SPFNDigest.sha256Hex` over the key's
    /// SPKI DER bytes — the same value the enrollment body sends.
    ///
    /// Package-visible: this is called from `SPFNKeyLifecycle.enroll`, after the key
    /// exists. Handing an app the ability to mint one would let it enroll a nonce that
    /// belongs to no key it holds, which the server would refuse with a code the app
    /// cannot read.
    package init(fingerprint: String, provider: String)
    {
        self.fingerprint = fingerprint
        self.provider = provider
        // The hash is taken over the fingerprint's text, not over the bytes it spells.
        // Upstream's `hashNonce` hashes the nonce string it received, so hashing the
        // decoded bytes here would produce a value that verifies nowhere.
        self.requestValue = provider == Self.appleProvider
            ? SPFNDigest.sha256Hex(fingerprint)
            : fingerprint
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
    /// the fingerprint in every interpolated log line that ever touches a nonce. It is
    /// not a secret — it is the hash of a public key — but it names the device's key
    /// across every log it lands in, so both descriptions are written and both name only
    /// the provider.
    public var description: String
    {
        "SPFNSocialNonce(provider: \(provider))"
    }

    public var debugDescription: String
    {
        description
    }
}
