// SPFN Mobile — where a client key record persists, and what one holds.
//
// The store is a seam, not a policy. Custody policy — which hardware holds the key,
// which accessibility class protects the blob — lives in the provider and in the
// keychain store below; this protocol only says "a record can be saved, loaded and
// deleted under a slot name". The seam exists because the platform keychain is not
// reachable from every test environment (a macOS test runner has no app entitlement),
// and the alternative — a file-based fallback — would silently change the protection
// class of a real key. Tests inject an in-memory store instead; the keychain paths are
// compiled here and exercised on a device.
//
// android/spfn-client/.../SpfnKeyMetadataStore.kt is the same seam in Kotlin.

import Foundation
import SPFNCore
import Security

/// Which custody actually holds a private key.
///
/// Recorded rather than implied: a caller deciding what a key protects against needs to
/// know whether the enclave was available when the key was made, and the answer must not
/// change shape depending on which code path happened to run.
public enum SPFNKeyCustody: String, Equatable, Sendable
{
    /// The private key was generated inside the Secure Enclave and never leaves it.
    /// The persisted blob is the enclave's sealed representation, usable only by this
    /// device's enclave.
    case secureEnclave

    /// The Secure Enclave was unavailable, so the key is a software P-256 key whose
    /// persisted form is the private key itself. The fallback is recorded, never
    /// hidden, so nothing can mistake it for hardware custody.
    case softwareKeychain
}

/// One persisted client key: non-secret metadata plus the custody blob.
///
/// `keyID` and `clientID` are proof-input fields, not secrets. `keyBlob` is secret
/// exactly when custody is software — so the record as a whole never prints itself.
public struct SPFNStoredKey: Equatable, Sendable
{
    public let keyID: String

    /// The key owner's identity, which is the enrollment response's `userId`. Nil for
    /// a key that has been generated but not yet enrolled.
    public var clientID: String?

    public let custody: SPFNKeyCustody

    /// The custody blob: the enclave's sealed key, or the software key's DER.
    public let keyBlob: [UInt8]

    public init(keyID: String, clientID: String?, custody: SPFNKeyCustody, keyBlob: [UInt8])
    {
        self.keyID = keyID
        self.clientID = clientID
        self.custody = custody
        self.keyBlob = keyBlob
    }
}

// The blob can be a software private key, so the record redacts itself on every output
// path, the same three doors the error envelope closes: description, debugDescription
// and the mirror `dump` and `String(reflecting:)` walk.
extension SPFNStoredKey: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable
{
    public var description: String
    {
        "SPFNStoredKey(keyID: \(keyID), clientID: \(clientID ?? "nil"), custody: \(custody.rawValue), keyBlob: redacted)"
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

/// Persists client key records under slot names.
///
/// Two slots exist today: the active key and a rotation candidate. The store does not
/// know that — slots are the caller's vocabulary — and it never interprets a record.
public protocol SPFNKeyStore: Sendable
{
    func load(slot: String) throws -> SPFNStoredKey?

    func save(_ record: SPFNStoredKey, slot: String) throws

    func delete(slot: String) throws
}

/// The store failed to reach or read its backing keychain.
///
/// The reason is a fixed string plus the OSStatus the platform returned; nothing from
/// a record's contents is ever part of it.
public struct SPFNKeyStoreError: Error, Equatable, Sendable
{
    public let reason: String
    public let status: Int32

    public init(reason: String, status: Int32)
    {
        self.reason = reason
        self.status = status
    }
}

/// The platform keychain as an `SPFNKeyStore`.
///
/// Every item is written with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` and
/// with synchronization off: a client key names one device, so an iCloud-synchronized
/// copy would be a second signer nobody enrolled. The record travels as JSON with the
/// blob base64-encoded; the JSON is a value format here, not a wire format, so it is
/// produced and consumed only by this type.
///
/// Reaching the keychain needs a keychain entitlement the plain `swift test` runner
/// does not have, so no test in this repository constructs one of these; the suites
/// inject an in-memory store and the keychain path is device evidence, tracked with
/// the COMPATIBILITY real-device axis.
public struct SPFNKeychainKeyStore: SPFNKeyStore
{
    /// Namespaces the items this SDK writes, away from anything else the app stores.
    private let service: String

    public init(service: String = "xyz.superfunction.spfn.client-key")
    {
        self.service = service
    }

    public func load(slot: String) throws -> SPFNStoredKey?
    {
        var query = baseQuery(slot: slot)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var found: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &found)
        if status == errSecItemNotFound
        {
            return nil
        }
        guard status == errSecSuccess, let data = found as? Data
        else
        {
            throw SPFNKeyStoreError(reason: "keychain read failed", status: status)
        }
        return try Self.decode(data)
    }

    public func save(_ record: SPFNStoredKey, slot: String) throws
    {
        // Delete-then-add rather than update: an update can race an item whose
        // accessibility attributes differ from the ones this store writes, and the
        // result would be a record protected by whatever was there first.
        try delete(slot: slot)

        var attributes = baseQuery(slot: slot)
        attributes[kSecValueData as String] = Data(Self.encode(record))
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly

        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess
        else
        {
            throw SPFNKeyStoreError(reason: "keychain write failed", status: status)
        }
    }

    public func delete(slot: String) throws
    {
        let status = SecItemDelete(baseQuery(slot: slot) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound
        else
        {
            throw SPFNKeyStoreError(reason: "keychain delete failed", status: status)
        }
    }

    private func baseQuery(slot: String) -> [String: Any]
    {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: slot,
            // Explicitly local. Passing kSecAttrSynchronizableAny on reads and
            // nothing on writes is how a record quietly becomes a synchronized one.
            kSecAttrSynchronizable as String: false,
        ]
    }

    // MARK: - Record bytes

    static func encode(_ record: SPFNStoredKey) -> [UInt8]
    {
        var members: [String: SPFNCanonicalValue] = [
            "keyId": .string(record.keyID),
            "custody": .string(record.custody.rawValue),
            "keyBlobBase64": .string(Data(record.keyBlob).base64EncodedString()),
        ]
        if let clientID = record.clientID
        {
            members["clientId"] = .string(clientID)
        }
        return SPFNCanonicalJSON.encode(.object(members))
    }

    static func decode(_ data: Data) throws -> SPFNStoredKey
    {
        let malformed = SPFNKeyStoreError(reason: "stored record is not a client key record", status: 0)
        guard let parsed = try? SPFNCanonicalJSON.parse([UInt8](data)),
              case .object(let members) = parsed,
              case .string(let keyID)? = members["keyId"],
              case .string(let custodyName)? = members["custody"],
              let custody = SPFNKeyCustody(rawValue: custodyName),
              case .string(let blobBase64)? = members["keyBlobBase64"],
              let blob = Data(base64Encoded: blobBase64)
        else
        {
            throw malformed
        }

        var clientID: String?
        if case .string(let owner)? = members["clientId"]
        {
            clientID = owner
        }
        return SPFNStoredKey(keyID: keyID, clientID: clientID, custody: custody, keyBlob: [UInt8](blob))
    }
}
