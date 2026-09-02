// SPFN Mobile — hardware custody for the client key on Apple platforms.
//
// The provider is still only a signer; what this file adds is where the key lives.
// When the Secure Enclave is available the private key is generated inside it and
// never exists as bytes this process can read — the persisted form is the enclave's
// sealed representation. When it is not (a simulator, an entitlement-less test
// runner, old hardware), the fallback is a software P-256 key persisted through the
// injected store, and the choice is recorded in `custody` rather than hidden: a
// caller deciding what the key protects against reads the record, not the code path.
//
// What a test can prove here is the software half and the selection logic; the
// enclave half compiles under `swift build` and its runtime behaviour is real-device
// evidence, deferred with the COMPATIBILITY real-device axis.
//
// The enclave backend is guarded by `canImport(CryptoKit) && canImport(Security)`,
// which is the pair of frameworks it is actually built out of: CryptoKit for
// `SecureEnclave.P256`, Security for the access control the key is created under. The
// software half is unguarded and is the whole of this file on Linux, where the SDK is
// built to run its suites rather than to ship. `SPFNKeyCustody.secureEnclave` itself
// stays on every platform — it is a wire value that a record can carry anywhere, and a
// platform that cannot open such a record answers nil rather than failing to name it.
//
// android/spfn-client/.../SpfnKeystoreKeyProvider.kt is the Android counterpart
// (Keystore, StrongBox preferred, TEE fallback).

#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

import Foundation

/// A client key under platform custody, created before any identity exists.
///
/// This is the pre-enrollment half of a provider: it can sign and it can advertise its
/// public half, but it names no client. Enrollment turns it into a full
/// `SPFNSecureEnclaveKeyProvider` by attaching the owner id the server issued.
public struct SPFNCustodyKey: Sendable
{
    public let keyID: String
    public let custody: SPFNKeyCustody

    private let backend: Backend

    private enum Backend: Sendable
    {
        #if canImport(CryptoKit) && canImport(Security)
        case secureEnclave(SecureEnclave.P256.Signing.PrivateKey)
        #endif
        case software(P256.Signing.PrivateKey)
    }

    // MARK: - Creation and reloading

    /// Generates a fresh key, inside the enclave when the platform has one.
    ///
    /// The platform's own answer is what this overload asks for; it is written as an
    /// overload rather than as a default argument because the answer is a different
    /// expression on each platform and a default argument cannot be split by `#if`.
    /// A caller that has its own answer calls the two-argument form.
    public static func generate(keyID: String) -> SPFNCustodyKey
    {
        #if canImport(CryptoKit) && canImport(Security)
        return generate(keyID: keyID, preferSecureEnclave: SecureEnclave.isAvailable)
        #else
        return generate(keyID: keyID, preferSecureEnclave: false)
        #endif
    }

    /// Generates a fresh key, inside the enclave when `preferSecureEnclave` asks for
    /// one and the platform can supply it.
    ///
    /// Tests pass false — not to skip the decision but because the decision is the
    /// platform's: what the suite owns is that the fallback works and says it is one.
    ///
    /// A preferred enclave that fails at generation time falls back the same way an
    /// absent one does. The alternative — surfacing the failure — would make first-run
    /// enrollment fail on exactly the devices whose enclave is flaky, for a key the
    /// software path can hold correctly and honestly. A platform with no enclave at all
    /// is the same fallback for the same reason, which is why true is not an error here.
    public static func generate(keyID: String, preferSecureEnclave: Bool) -> SPFNCustodyKey
    {
        #if canImport(CryptoKit) && canImport(Security)
        // The same accessibility the store writes for blobs: usable after first unlock,
        // never off this device. An enclave key is device-bound by construction; the
        // explicit access control keeps the claim in the code rather than in a default.
        var accessError: Unmanaged<CFError>?
        if preferSecureEnclave,
           let access = SecAccessControlCreateWithFlags(
               nil,
               kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
               [],
               &accessError
           ),
           let enclaveKey = try? SecureEnclave.P256.Signing.PrivateKey(accessControl: access)
        {
            return SPFNCustodyKey(keyID: keyID, custody: .secureEnclave, backend: .secureEnclave(enclaveKey))
        }
        #endif
        return SPFNCustodyKey(keyID: keyID, custody: .softwareKeychain, backend: .software(P256.Signing.PrivateKey()))
    }

    /// Reconstructs a key from a stored record, or nil when the blob does not decode
    /// under the custody the record names. Nil rather than a throw: a blob this device
    /// cannot open is a key this device cannot sign with, whatever the reason, and the
    /// caller's answer to both is re-enrollment.
    public static func reload(from record: SPFNStoredKey) -> SPFNCustodyKey?
    {
        switch record.custody
        {
        case .secureEnclave:
            #if canImport(CryptoKit) && canImport(Security)
            guard let key = try? SecureEnclave.P256.Signing.PrivateKey(dataRepresentation: Data(record.keyBlob))
            else
            {
                return nil
            }
            return SPFNCustodyKey(keyID: record.keyID, custody: .secureEnclave, backend: .secureEnclave(key))
            #else
            // A platform with no enclave cannot open a sealed enclave blob, which is
            // the same outcome as a blob that fails to decode on a platform that has
            // one: a key this device cannot sign with, and re-enrollment either way.
            return nil
            #endif
        case .softwareKeychain:
            guard let key = try? P256.Signing.PrivateKey(derRepresentation: Data(record.keyBlob))
            else
            {
                return nil
            }
            return SPFNCustodyKey(keyID: record.keyID, custody: .softwareKeychain, backend: .software(key))
        }
    }

    private init(keyID: String, custody: SPFNKeyCustody, backend: Backend)
    {
        self.keyID = keyID
        self.custody = custody
        self.backend = backend
    }

    // MARK: - What a key does

    /// The public half in the contract's representation: SPKI DER. Not a secret — this
    /// is the value enrollment registers with the server.
    public var publicKeySpkiDer: [UInt8]
    {
        switch backend
        {
        #if canImport(CryptoKit) && canImport(Security)
        case .secureEnclave(let key):
            return [UInt8](key.publicKey.derRepresentation)
        #endif
        case .software(let key):
            return [UInt8](key.publicKey.derRepresentation)
        }
    }

    /// Raw `r ‖ s`, 64 bytes — the same signer contract `SPFNKeyProvider` states.
    /// Both backends hash with SHA-256 themselves, so the message is passed raw.
    public func sign(_ message: [UInt8]) throws -> [UInt8]
    {
        switch backend
        {
        #if canImport(CryptoKit) && canImport(Security)
        case .secureEnclave(let key):
            return [UInt8](try key.signature(for: Data(message)).rawRepresentation)
        #endif
        case .software(let key):
            return [UInt8](try key.signature(for: Data(message)).rawRepresentation)
        }
    }

    /// The record that persists this key, before or after enrollment names its owner.
    ///
    /// For enclave custody the blob is the sealed representation only this device's
    /// enclave can use; for software custody it is the private key's DER, which is why
    /// the record type redacts itself and the store applies device-only accessibility.
    public func record(clientID: String?, createdAtMillis: Int64) -> SPFNStoredKey
    {
        let blob: [UInt8]
        switch backend
        {
        #if canImport(CryptoKit) && canImport(Security)
        case .secureEnclave(let key):
            blob = [UInt8](key.dataRepresentation)
        #endif
        case .software(let key):
            blob = [UInt8](key.derRepresentation)
        }
        return SPFNStoredKey(
            keyID: keyID,
            clientID: clientID,
            custody: custody,
            createdAtMillis: createdAtMillis,
            keyBlob: blob
        )
    }

    /// A key over a fixed software keypair, as the conformance fixtures pin one.
    ///
    /// For tests and the reference integration, exactly like the software provider's
    /// fixed initializer: the fixture private half is published on purpose and
    /// authenticates nothing.
    public static func software(keyID: String, privateKeyDer: [UInt8]) throws -> SPFNCustodyKey
    {
        SPFNCustodyKey(
            keyID: keyID,
            custody: .softwareKeychain,
            backend: .software(try P256.Signing.PrivateKey(derRepresentation: Data(privateKeyDer)))
        )
    }
}

// A software-custody key holds private key material, so the same three output doors
// are closed as everywhere else key material lives.
extension SPFNCustodyKey: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable
{
    public var description: String
    {
        "SPFNCustodyKey(keyID: \(keyID), custody: \(custody.rawValue), privateKey: redacted)"
    }

    public var debugDescription: String
    {
        description
    }

    public var customMirror: Mirror
    {
        Mirror(self, unlabeledChildren: [Any]())
    }
}

/// A custody key plus the identity enrollment attached to it: the hardware-backed
/// `SPFNKeyProvider` the session signs proofs with.
public struct SPFNSecureEnclaveKeyProvider: SPFNKeyProvider
{
    public let clientID: String

    public let key: SPFNCustodyKey

    public var keyID: String
    {
        key.keyID
    }

    /// Which custody the underlying key actually has — enclave, or the recorded
    /// software fallback.
    public var custody: SPFNKeyCustody
    {
        key.custody
    }

    public init(clientID: String, key: SPFNCustodyKey)
    {
        self.clientID = clientID
        self.key = key
    }

    /// Reconstructs the provider a store persisted, or nil when the slot is empty,
    /// the record has no owner yet, or the blob cannot be opened on this device.
    public static func load(from store: any SPFNKeyStore, slot: String) throws -> SPFNSecureEnclaveKeyProvider?
    {
        guard let record = try store.load(slot: slot),
              let clientID = record.clientID,
              let key = SPFNCustodyKey.reload(from: record)
        else
        {
            return nil
        }
        return SPFNSecureEnclaveKeyProvider(clientID: clientID, key: key)
    }

    public func sign(_ message: [UInt8]) throws -> [UInt8]
    {
        try key.sign(message)
    }
}

// The same exact-string redaction contract the software provider carries.
extension SPFNSecureEnclaveKeyProvider: CustomStringConvertible, CustomDebugStringConvertible
{
    public var description: String
    {
        "SPFNSecureEnclaveKeyProvider(clientID: \(clientID), keyID: \(keyID), custody: \(custody.rawValue), privateKey: redacted)"
    }

    public var debugDescription: String
    {
        description
    }
}
