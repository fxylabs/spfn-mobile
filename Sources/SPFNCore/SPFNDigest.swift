// SPFN Mobile — digests.
//
// CryptoKit ships with every platform this package declares support for, so there is
// no dependency to add and no hand-rolled primitive to review. The Kotlin counterpart
// uses java.security.MessageDigest, and the conformance fixtures assert both platforms
// produce the same hex for the same input. The proof signature itself lives in
// SPFNAuth: this module only ever hashes.

import CryptoKit
import Foundation

public enum SPFNDigest
{
    /// The digest of an empty body, as the proof input spells it out: 64 zeroes.
    /// Written as a literal rather than computed so a body-less operation can never
    /// accidentally carry the digest of the empty string instead.
    public static let absentBodyDigest = String(repeating: "0", count: 64)

    /// Lowercase base16 SHA-256.
    public static func sha256Hex(_ bytes: [UInt8]) -> String
    {
        hex(Array(SHA256.hash(data: Data(bytes))))
    }

    /// Lowercase base16 SHA-256 of a UTF-8 string.
    public static func sha256Hex(_ text: String) -> String
    {
        sha256Hex(Array(text.utf8))
    }

    /// Constant-time comparison of two hex digests.
    ///
    /// Proof verification compares secrets-derived values, so the comparison must not
    /// leak where two digests first differ.
    public static func constantTimeEquals(_ lhs: String, _ rhs: String) -> Bool
    {
        let left = Array(lhs.utf8)
        let right = Array(rhs.utf8)
        guard left.count == right.count
        else
        {
            return false
        }
        var difference: UInt8 = 0
        for index in 0 ..< left.count
        {
            difference |= left[index] ^ right[index]
        }
        return difference == 0
    }

    /// The encoder every fingerprint in this SDK is spelled with.
    ///
    /// Package-visible rather than private so the shared byte-to-hex vector can assert
    /// against the encoder that actually runs. It used to assert against a second copy
    /// in `SPFNSocialNonce`, which proved the copy correct and said nothing about this
    /// one; the copy is gone and the vector moved here.
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
}
