// SPFN Mobile — where the client key lives, and the one thing it does.
//
// The provider is a signer. The session hands it the canonical proof-input bytes and
// gets a raw ECDSA signature back, so the private key never exists as a value outside
// the provider: there is nothing to return, nothing to retain, and nothing for a call
// site to log. That seam is what lets a hardware-backed provider — Secure Enclave,
// Android Keystore — replace this one later without a protocol change, because
// hardware keys cannot be exported either; signing is the only operation they have.
//
// Key custody (Keychain, Secure Enclave, attestation) is a separate decision
// (docs/OPEN-DECISIONS.md). The software provider below is an alpha stand-in and says so.

import CryptoKit
import Foundation

/// Supplies the client identity and signs one message with the client key.
public protocol SPFNKeyProvider: Sendable
{
    /// The client identifier the proof is taken over. Not a secret.
    var clientID: String { get }

    /// The key identifier the proof is taken over. Not a secret.
    var keyID: String { get }

    /// Signs the canonical proof-input bytes with ECDSA P-256 over SHA-256 and returns
    /// the raw `r ‖ s` signature: two 32-byte big-endian integers, 64 bytes total.
    ///
    /// The key is never returned, so a caller cannot retain it by accident. A provider
    /// whose platform signer emits DER converts to raw before returning.
    func sign(_ message: [UInt8]) throws -> [UInt8]
}

/// Holds a P-256 private key in memory for the life of the process.
///
/// Suitable for tests and for the reference-server integration, not for a shipped app:
/// nothing here survives a restart and nothing here is protected by the platform
/// keystore. A hardware-backed provider is a separate decision.
public struct SPFNSoftwareKeyProvider: SPFNKeyProvider
{
    public let clientID: String
    public let keyID: String

    private let privateKey: P256.Signing.PrivateKey

    /// A provider over a fresh random keypair. Register `publicKeySpkiDer` with the
    /// verifier before the first handshake.
    public init(clientID: String, keyID: String)
    {
        self.clientID = clientID
        self.keyID = keyID
        self.privateKey = P256.Signing.PrivateKey()
    }

    /// A provider over a fixed keypair, as the conformance fixtures pin one.
    ///
    /// `privateKeyDer` is the DER encoding CryptoKit reads — PKCS#8 or SEC1 — which is
    /// the form the fixture's `privateKeyPkcs8Base64` decodes to.
    public init(clientID: String, keyID: String, privateKeyDer: [UInt8]) throws
    {
        self.clientID = clientID
        self.keyID = keyID
        self.privateKey = try P256.Signing.PrivateKey(derRepresentation: Data(privateKeyDer))
    }

    /// The public half in the contract's representation: SPKI DER. Not a secret — this
    /// is the value a client registers with the server.
    public var publicKeySpkiDer: [UInt8]
    {
        Array(privateKey.publicKey.derRepresentation)
    }

    public func sign(_ message: [UInt8]) throws -> [UInt8]
    {
        // `signature(for:)` hashes with SHA-256 itself, so the message is passed raw —
        // hashing here first would sign the digest of a digest and verify nowhere.
        // `rawRepresentation` is already r ‖ s as two fixed 32-byte integers; a DER
        // conversion on top of it would be a second encoding of an already-raw value.
        Array(try privateKey.signature(for: Data(message)).rawRepresentation)
    }
}

// The default reflection-based description of a struct prints every stored property,
// including the private key. Both descriptions are overridden rather than one, because
// `debugDescription` is what a debugger and most logging wrappers reach for.
extension SPFNSoftwareKeyProvider: CustomStringConvertible, CustomDebugStringConvertible
{
    public var description: String
    {
        "SPFNSoftwareKeyProvider(clientID: \(clientID), keyID: \(keyID), privateKey: redacted)"
    }

    public var debugDescription: String
    {
        description
    }
}
