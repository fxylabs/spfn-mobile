// SPFN Mobile — clientProofV1, algorithm SPFN-PROOF-INPUT-1.
//
// The proof input is a newline-joined list of eight fields in a fixed order. That is
// only unambiguous if no field can contain the separator, so a C0 control character in
// any field is an error rather than something to escape: an escaping scheme is one more
// thing two platforms can disagree about.
//
// The proof itself is an ECDSA P-256 signature over those bytes (contract 0.2.0): raw
// r ‖ s, 64 bytes, as base16-lower. Verification is against a registered public key in
// SPKI DER, never against a shared secret.
//
// android/spfn-auth/.../SpfnClientProof.kt implements the same algorithm, and
// Contracts/fixtures/proof/ pins the agreed output of both.

#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

import Foundation
import SPFNCore

/// The fields the proof is taken over, in the order the contract fixes.
public struct SPFNProofInput: Equatable, Sendable
{
    public let method: String
    public let path: String
    public let clientID: String
    public let keyID: String
    public let nonce: String
    public let issuedAtMillis: Int64
    public let bodySha256: String

    public init(
        method: String,
        path: String,
        clientID: String,
        keyID: String,
        nonce: String,
        issuedAtMillis: Int64,
        bodySha256: String
    )
    {
        self.method = method
        self.path = path
        self.clientID = clientID
        self.keyID = keyID
        self.nonce = nonce
        self.issuedAtMillis = issuedAtMillis
        self.bodySha256 = bodySha256
    }

    /// Builds the input for a request whose body is already canonical. An operation
    /// with no body carries the absent-body digest rather than the digest of `""`.
    public static func forRequest(
        method: String,
        path: String,
        clientID: String,
        keyID: String,
        nonce: String,
        issuedAtMillis: Int64,
        canonicalBody: [UInt8]?
    ) -> SPFNProofInput
    {
        SPFNProofInput(
            method: method,
            path: path,
            clientID: clientID,
            keyID: keyID,
            nonce: nonce,
            issuedAtMillis: issuedAtMillis,
            bodySha256: canonicalBody.map { SPFNDigest.sha256Hex($0) } ?? SPFNDigest.absentBodyDigest
        )
    }
}

public enum SPFNClientProof
{
    /// The only profile name that ever appears in a proof input.
    public static let profileName: String = SPFNAuthProfile.clientProofV1.rawValue

    /// The field order the contract fixes, named so a test can assert on it directly.
    public static let proofInputFields: [String] = [
        "profile",
        "method",
        "path",
        "clientId",
        "keyId",
        "nonce",
        "issuedAtMillis",
        "bodySha256",
    ]

    /// A raw ECDSA P-256 signature: r ‖ s, two 32-byte big-endian integers.
    public static let rawSignatureByteCount = 64

    /// The canonical proof input string, before any signature is applied.
    public static func canonicalString(for input: SPFNProofInput) throws -> String
    {
        let values = [
            ("profile", profileName),
            ("method", input.method),
            ("path", input.path),
            ("clientId", input.clientID),
            ("keyId", input.keyID),
            ("nonce", input.nonce),
            ("issuedAtMillis", String(input.issuedAtMillis)),
            ("bodySha256", input.bodySha256),
        ]

        for (field, value) in values where value.unicodeScalars.contains(where: { $0.value < 0x20 })
        {
            throw SPFNAuthError.controlCharacterInProofField(field)
        }

        return values.map(\.1).joined(separator: "\n")
    }

    /// The canonical proof input bytes. This is what the signature covers.
    public static func canonicalBytes(for input: SPFNProofInput) throws -> [UInt8]
    {
        Array(try canonicalString(for: input).utf8)
    }

    /// SHA-256 of the canonical proof input, as lowercase base16.
    ///
    /// Carries no secret, so it is the value the conformance fixtures use to prove Swift
    /// and Kotlin agree byte for byte before any key is involved.
    public static func canonicalDigest(for input: SPFNProofInput) throws -> String
    {
        SPFNDigest.sha256Hex(try SPFNClientProof.canonicalBytes(for: input))
    }

    /// The proof itself: the signer's raw r ‖ s signature over the canonical input,
    /// as lowercase base16 (128 hex characters).
    ///
    /// `sign` is the provider's one operation, taken as a closure so this module never
    /// holds a key type. A signer that returns anything but 64 bytes is a signer that
    /// emitted DER — or nothing — and is refused here rather than put on the wire.
    public static func proof(
        for input: SPFNProofInput,
        signedBy sign: ([UInt8]) throws -> [UInt8]
    ) throws -> String
    {
        let signature = try sign(try canonicalBytes(for: input))
        guard signature.count == rawSignatureByteCount
        else
        {
            throw SPFNAuthError.proofInvalid
        }
        return hexEncode(signature)
    }

    /// Verifies a presented proof against a registered public key (SPKI DER).
    ///
    /// Every failure is the same `proofInvalid`: a wire proof that is not exactly 128
    /// lowercase hex characters (DER, truncation, uppercase), a key that does not parse,
    /// and a signature that does not verify are one answer on purpose, so the refusal
    /// discloses nothing about which stage refused.
    public static func verify(
        presented: String,
        for input: SPFNProofInput,
        publicKeySpkiDer: [UInt8]
    ) throws
    {
        guard let signature = decodeWireSignature(presented),
              let publicKey = try? P256.Signing.PublicKey(derRepresentation: Data(publicKeySpkiDer)),
              let parsed = try? P256.Signing.ECDSASignature(rawRepresentation: Data(signature)),
              publicKey.isValidSignature(parsed, for: Data(try canonicalBytes(for: input)))
        else
        {
            throw SPFNAuthError.proofInvalid
        }
    }

    /// The raw signature bytes a wire proof carries, or nil when it is not one.
    ///
    /// Strict on purpose, and ASCII-explicit so Swift's Unicode-aware character classes
    /// cannot drift from Kotlin's byte ranges: exactly 128 characters, each one of
    /// `0-9a-f`. Uppercase is refused because the contract says base16-lower, and a
    /// value only one platform would accept is a disagreement waiting for a server.
    static func decodeWireSignature(_ presented: String) -> [UInt8]?
    {
        let scalars = Array(presented.unicodeScalars)
        guard scalars.count == rawSignatureByteCount * 2
        else
        {
            return nil
        }

        var bytes: [UInt8] = []
        bytes.reserveCapacity(rawSignatureByteCount)
        var index = 0
        while index < scalars.count
        {
            guard let high = hexNibble(scalars[index]), let low = hexNibble(scalars[index + 1])
            else
            {
                return nil
            }
            bytes.append(high << 4 | low)
            index += 2
        }
        return bytes
    }

    private static func hexNibble(_ scalar: Unicode.Scalar) -> UInt8?
    {
        switch scalar.value
        {
        case 0x30 ... 0x39:                 // '0'...'9'
            return UInt8(scalar.value - 0x30)
        case 0x61 ... 0x66:                 // 'a'...'f', lowercase only
            return UInt8(scalar.value - 0x61 + 10)
        default:
            return nil
        }
    }

    private static func hexEncode(_ bytes: [UInt8]) -> String
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

/// Replay and revocation state for a verifier.
///
/// The SDK ships this so a client can be exercised against the same acceptance rules
/// the server applies. Check order is part of the contract: a revoked key is rejected
/// before the proof is verified, which keeps revocation distinguishable from a bad
/// proof instead of collapsing both into one opaque failure.
public struct SPFNProofAcceptance: Sendable
{
    public let replayWindowMillis: Int64

    private var revokedKeyIDs: Set<String>
    private var seen: Set<String>

    public init(replayWindowMillis: Int64, revokedKeyIDs: Set<String> = [])
    {
        self.replayWindowMillis = replayWindowMillis
        self.revokedKeyIDs = revokedKeyIDs
        self.seen = []
    }

    public mutating func revoke(keyID: String)
    {
        revokedKeyIDs.insert(keyID)
    }

    /// Admits one proof presentation, or throws the reason it was refused.
    public mutating func admit(
        presented: String,
        input: SPFNProofInput,
        publicKeySpkiDer: [UInt8],
        nowMillis: Int64
    ) throws
    {
        guard !revokedKeyIDs.contains(input.keyID)
        else
        {
            throw SPFNAuthError.sessionRevoked
        }

        let age = nowMillis - input.issuedAtMillis
        guard age >= 0, age <= replayWindowMillis
        else
        {
            throw SPFNAuthError.proofExpired
        }

        let replayKey = "\(input.clientID)\u{1F}\(input.nonce)"
        guard !seen.contains(replayKey)
        else
        {
            throw SPFNAuthError.proofReplayed
        }

        try SPFNClientProof.verify(presented: presented, for: input, publicKeySpkiDer: publicKeySpkiDer)
        seen.insert(replayKey)
    }
}
