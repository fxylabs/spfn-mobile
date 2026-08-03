// SPFN Mobile — custody on Apple platforms: what the suite can prove without hardware.
//
// The Secure Enclave path needs a device: the plain `swift test` runner has neither an
// enclave nor the entitlement to ask for one, so every case here drives the recorded
// software fallback and the selection, persistence and wipe logic around it. That is
// the deliberate split the provider states in its own header — the enclave branch is
// compiled by `swift build` and its runtime behaviour is real-device evidence, deferred
// with the COMPATIBILITY real-device axis.
//
// SpfnKeystoreKeyProviderTest.kt is the Android counterpart over the same case set.

import CryptoKit
import Foundation
import XCTest
@testable import SPFNClient
import SPFNCore

final class SPFNCustodyKeyTests: XCTestCase
{
    private let slot = "active"

    // MARK: - L1: which custody, recorded

    func testAKeyGeneratedWithoutAnEnclaveRecordsTheSoftwareFallback()
    {
        let key = SPFNCustodyKey.generate(keyID: "key-custody-0001", preferSecureEnclave: false)

        XCTAssertEqual(key.custody, .softwareKeychain, "the fallback is recorded, not hidden")
        XCTAssertEqual(key.keyID, "key-custody-0001")
    }

    // MARK: - L2: the public half is the contract's representation

    /// The fixture keypair pins the exact SPKI shape the server's prime256v1 gate
    /// parses; a fresh key must serialize under the same 26-byte algorithm header and
    /// the uncompressed-point marker, differing only in the point itself.
    func testThePublicKeyIsP256SpkiDerMatchingTheFixtureShape() throws
    {
        let key = SPFNCustodyKey.generate(keyID: "key-custody-0002", preferSecureEnclave: false)
        let spki = key.publicKeySpkiDer
        let fixture = try ExecuteFixtures.fixturePublicKeySpkiDer()

        XCTAssertEqual(spki.count, fixture.count, "a P-256 SPKI is 91 bytes")
        XCTAssertEqual(
            Array(spki.prefix(27)),
            Array(fixture.prefix(27)),
            "the algorithm identifier and uncompressed-point marker must match the fixture byte for byte"
        )
        XCTAssertNoThrow(try P256.Signing.PublicKey(derRepresentation: Data(spki)))
    }

    // MARK: - L3: the signer contract

    func testSignReturnsRawRSThatVerifiesAgainstTheAdvertisedPublicKey() throws
    {
        let key = SPFNCustodyKey.generate(keyID: "key-custody-0003", preferSecureEnclave: false)
        let message = [UInt8]("spfn-custody-sign-probe".utf8)

        let signature = try key.sign(message)

        XCTAssertEqual(signature.count, 64, "raw r ‖ s, two 32-byte integers")
        let publicKey = try P256.Signing.PublicKey(derRepresentation: Data(key.publicKeySpkiDer))
        let parsed = try P256.Signing.ECDSASignature(rawRepresentation: Data(signature))
        XCTAssertTrue(publicKey.isValidSignature(parsed, for: Data(message)))
        XCTAssertFalse(
            publicKey.isValidSignature(parsed, for: Data(message + [0x78])),
            "a verifier that accepts a tampered message discriminates nothing"
        )
    }

    // MARK: - L4: reload after a restart

    func testAStoredKeyReloadsWithItsMetadataAndTheSameKeyMaterial() throws
    {
        let store = InMemoryKeyStore()
        let key = SPFNCustodyKey.generate(keyID: "key-custody-0004", preferSecureEnclave: false)
        try store.save(key.record(clientID: "user-test-0001"), slot: slot)

        // A second process: nothing shared but the store.
        let reloaded = try XCTUnwrap(SPFNSecureEnclaveKeyProvider.load(from: store, slot: slot))

        XCTAssertEqual(reloaded.clientID, "user-test-0001")
        XCTAssertEqual(reloaded.keyID, "key-custody-0004")
        XCTAssertEqual(reloaded.custody, .softwareKeychain)

        // The same key, not merely the same names: what the reload signs must verify
        // against the public half the original advertised.
        let message = [UInt8]("spfn-custody-reload-probe".utf8)
        let publicKey = try P256.Signing.PublicKey(derRepresentation: Data(key.publicKeySpkiDer))
        let parsed = try P256.Signing.ECDSASignature(rawRepresentation: Data(try reloaded.sign(message)))
        XCTAssertTrue(publicKey.isValidSignature(parsed, for: Data(message)))
    }

    func testARecordWithoutAnOwnerDoesNotLoadAsAProvider() throws
    {
        let store = InMemoryKeyStore()
        let key = SPFNCustodyKey.generate(keyID: "key-custody-0005", preferSecureEnclave: false)
        try store.save(key.record(clientID: nil), slot: slot)

        XCTAssertNil(
            try SPFNSecureEnclaveKeyProvider.load(from: store, slot: slot),
            "a key that was never enrolled names no client and can prove nothing"
        )
    }

    // MARK: - L5: wipe

    func testAWipedSlotLeavesNothingToSignWith() throws
    {
        let store = InMemoryKeyStore()
        let key = SPFNCustodyKey.generate(keyID: "key-custody-0006", preferSecureEnclave: false)
        try store.save(key.record(clientID: "user-test-0001"), slot: slot)

        try store.delete(slot: slot)

        XCTAssertNil(try store.load(slot: slot), "the record is gone, metadata included")
        XCTAssertNil(
            try SPFNSecureEnclaveKeyProvider.load(from: store, slot: slot),
            "after a wipe there is no key to reload, so nothing can sign"
        )
    }

    // MARK: - L6: redaction

    func testNoDefaultOutputPathPrintsKeyMaterial() throws
    {
        let key = SPFNCustodyKey.generate(keyID: "key-custody-0007", preferSecureEnclave: false)
        let record = key.record(clientID: "user-test-0001")
        let provider = SPFNSecureEnclaveKeyProvider(clientID: "user-test-0001", key: key)

        XCTAssertEqual(
            "\(key)",
            "SPFNCustodyKey(keyID: key-custody-0007, custody: softwareKeychain, privateKey: redacted)"
        )
        XCTAssertEqual(
            "\(provider)",
            "SPFNSecureEnclaveKeyProvider(clientID: user-test-0001, keyID: key-custody-0007, "
                + "custody: softwareKeychain, privateKey: redacted)"
        )
        XCTAssertEqual(
            "\(record)",
            "SPFNStoredKey(keyID: key-custody-0007, clientID: user-test-0001, custody: softwareKeychain, keyBlob: redacted)"
        )

        // The blob is the private key under software custody, so the mirror is closed
        // too: dump and String(reflecting:) reach stored properties description skips.
        let blobHex = record.keyBlob.map { String(format: "%02x", $0) }.joined()
        var dumped = ""
        dump(record, to: &dumped)
        for rendered in [dumped, String(reflecting: record), record.debugDescription]
        {
            XCTAssertFalse(rendered.contains(blobHex), "a stored record printed its key blob")
        }
    }

    // MARK: - The keychain record bytes

    /// The keychain store itself needs an entitlement the test runner does not have,
    /// but its record format does not: a record must survive its own round trip, and a
    /// blob of foreign bytes must be refused rather than read as a key.
    func testTheKeychainRecordFormatRoundTripsAndRefusesForeignBytes() throws
    {
        let key = SPFNCustodyKey.generate(keyID: "key-custody-0008", preferSecureEnclave: false)
        let record = key.record(clientID: "user-test-0001")

        let decoded = try SPFNKeychainKeyStore.decode(Data(SPFNKeychainKeyStore.encode(record)))
        XCTAssertEqual(decoded, record)

        XCTAssertThrowsError(try SPFNKeychainKeyStore.decode(Data("not a record".utf8)))
        XCTAssertThrowsError(
            try SPFNKeychainKeyStore.decode(Data("{\"keyId\":\"k\",\"custody\":\"postIt\",\"keyBlobBase64\":\"\"}".utf8)),
            "an unknown custody name is refused, never defaulted"
        )
    }
}

// MARK: - The injected store

/// The seam's test half: slot semantics without a keychain. `@unchecked` because the
/// lock is what makes it Sendable, and the compiler cannot see that.
final class InMemoryKeyStore: SPFNKeyStore, @unchecked Sendable
{
    private let lock = NSLock()
    private var records: [String: SPFNStoredKey] = [:]

    func load(slot: String) throws -> SPFNStoredKey?
    {
        lock.lock()
        defer { lock.unlock() }
        return records[slot]
    }

    func save(_ record: SPFNStoredKey, slot: String) throws
    {
        lock.lock()
        defer { lock.unlock() }
        records[slot] = record
    }

    func delete(slot: String) throws
    {
        lock.lock()
        defer { lock.unlock() }
        records[slot] = nil
    }
}
