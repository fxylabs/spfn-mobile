// SPFN Mobile — clientProofV1 conformance, Swift half of the parity gate.
//
// The proof-input bytes stay byte-pinned. The signature does not — an ECDSA signer
// draws a random nonce — so it is judged in both directions instead: the fixture's
// recorded signature (produced by derive-expected-values.py, outside either SDK) must
// verify here, and a signature this platform produces must verify under the fixture
// public key. The reject table then proves the verifier refuses everything that is
// not a valid raw-r‖s base16-lower signature under the named key.

#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

import Foundation
import SPFNAuth
import SPFNCore
import XCTest

enum ProofFixtures
{
    static func input(from entry: [String: SPFNCanonicalValue]) throws -> SPFNProofInput
    {
        SPFNProofInput(
            method: try entry.text("method"),
            path: try entry.text("path"),
            clientID: try entry.text("clientId"),
            keyID: try entry.text("keyId"),
            nonce: try entry.text("nonce"),
            issuedAtMillis: try entry.number("issuedAtMillis"),
            bodySha256: try entry.text("bodySha256")
        )
    }

    /// The fixture test keypair. TEST ONLY — its private half is published on purpose;
    /// see the `testKeyPair.note` field in proof/proof-input.json.
    static func testKeyPair() throws -> (privateKeyDer: [UInt8], publicKeySpkiDer: [UInt8])
    {
        let keyPair = try Fixtures.load("proof/proof-input.json").members()["testKeyPair"]!.members()
        guard let privateKey = Data(base64Encoded: try keyPair.text("privateKeyPkcs8Base64")),
              let publicKey = Data(base64Encoded: try keyPair.text("publicKeySpkiBase64"))
        else
        {
            throw ConformanceFailure.shape
        }
        return ([UInt8](privateKey), [UInt8](publicKey))
    }
}

final class ProofConformanceTests: XCTestCase
{
    func testProofInputVectors() throws
    {
        let fixture = try Fixtures.load("proof/proof-input.json").members()
        let (privateKeyDer, publicKeySpkiDer) = try ProofFixtures.testKeyPair()
        let signer = try P256.Signing.PrivateKey(derRepresentation: Data(privateKeyDer))
        let vectors = try fixture.list("vectors")
        XCTAssertFalse(vectors.isEmpty)

        for vector in vectors
        {
            let entry = try vector.members()
            let name = try entry.text("name")
            let input = try ProofFixtures.input(from: try entry["input"]!.members())

            XCTAssertEqual(
                try SPFNClientProof.canonicalString(for: input),
                try entry.text("canonicalString"),
                "canonical proof input differs for '\(name)'"
            )
            XCTAssertEqual(
                try SPFNClientProof.canonicalDigest(for: input),
                try entry.text("canonicalSha256"),
                "proof input digest differs for '\(name)'"
            )

            // The fixture signature was produced outside either SDK; verifying it
            // proves this platform's verifier interoperates rather than agreeing
            // only with its own signer.
            XCTAssertNoThrow(
                try SPFNClientProof.verify(
                    presented: try entry.text("signatureRsHex"),
                    for: input,
                    publicKeySpkiDer: publicKeySpkiDer
                ),
                "the fixture signature does not verify for '\(name)'"
            )

            // And this platform's own signature must verify under the same key.
            let own = try SPFNClientProof.proof(for: input) { message in
                Array(try signer.signature(for: Data(message)).rawRepresentation)
            }
            XCTAssertEqual(own.count, 128, "a wire proof is 128 hex characters for '\(name)'")
            XCTAssertEqual(own, own.lowercased(), "a wire proof is base16-lower for '\(name)'")
            XCTAssertNoThrow(
                try SPFNClientProof.verify(presented: own, for: input, publicKeySpkiDer: publicKeySpkiDer),
                "this platform's own signature does not verify for '\(name)'"
            )
        }
    }

    /// A signature over one input must fail when any single field differs — one case
    /// per proof-input field (A1–A8), because a verifier that ignored one line of the
    /// canonical form would stay green under a single-field probe of any other line.
    ///
    /// Each tampered value avoids C0 controls (so the failure is the verification
    /// path, never the proof-input error path), `issuedAtMillis` changes as a number,
    /// and the tampered `bodySha256` stays 64 lowercase hex (so it reaches signature
    /// verification rather than any format gate). Every case first asserts the
    /// canonical bytes really changed, so a vacuous tamper cannot pass.
    func testEachProofInputFieldTamperedIndividuallyFailsVerification() throws
    {
        let fixture = try Fixtures.load("proof/proof-input.json").members()
        let (privateKeyDer, publicKeySpkiDer) = try ProofFixtures.testKeyPair()
        let entry = try fixture.list("vectors")[0].members()
        let input = try ProofFixtures.input(from: try entry["input"]!.members())
        let recorded = try entry.text("signatureRsHex")
        let originalBytes = try SPFNClientProof.canonicalBytes(for: input)

        // A2–A8: the seven fields the input type carries.
        let bodyDigestTampered = tamperedHexDigest(input.bodySha256)
        let cases: [(field: String, tampered: SPFNProofInput)] = [
            ("A2-method", modified(input, method: "PUT")),
            ("A3-path", modified(input, path: input.path + "-x")),
            ("A4-clientId", modified(input, clientID: input.clientID + "-x")),
            ("A5-keyId", modified(input, keyID: input.keyID + "-x")),
            ("A6-nonce", modified(input, nonce: input.nonce + "-x")),
            ("A7-issuedAtMillis", modified(input, issuedAtMillis: input.issuedAtMillis + 1)),
            ("A8-bodySha256", modified(input, bodySha256: bodyDigestTampered)),
        ]

        for (field, tampered) in cases
        {
            XCTAssertNotEqual(
                try SPFNClientProof.canonicalBytes(for: tampered),
                originalBytes,
                "'\(field)' tampering did not change the canonical bytes; the case is vacuous"
            )
            XCTAssertThrowsError(
                try SPFNClientProof.verify(
                    presented: recorded,
                    for: tampered,
                    publicKeySpkiDer: publicKeySpkiDer
                ),
                "'\(field)' tampering was accepted"
            )
            { error in
                XCTAssertEqual(error as? SPFNAuthError, .proofInvalid, "'\(field)' refused with the wrong error")
            }
        }

        // A1: the profile is a constant the input type cannot carry, so the tamper
        // runs the other way — a signature over bytes whose profile line differs must
        // fail against the real input. The verify call here is the same code path as
        // above; only the signed message is different. Deliberately not
        // "clientProofV2" upside down: this never touches profile *policy*
        // (unknownProfilePolicy is a contract refusal), only the signature.
        let originalString = String(decoding: originalBytes, as: UTF8.self)
        XCTAssertTrue(originalString.hasPrefix("clientProofV1\n"), "the first proof-input line is the profile")
        let profileTamperedBytes = Array(("clientProofX" + originalString.dropFirst("clientProofV1".count)).utf8)
        XCTAssertNotEqual(profileTamperedBytes, originalBytes, "'A1-profile' tampering is vacuous")

        let signer = try P256.Signing.PrivateKey(derRepresentation: Data(privateKeyDer))
        let overTamperedProfile = hexLower(Array(try signer.signature(for: Data(profileTamperedBytes)).rawRepresentation))
        XCTAssertThrowsError(
            try SPFNClientProof.verify(
                presented: overTamperedProfile,
                for: input,
                publicKeySpkiDer: publicKeySpkiDer
            ),
            "'A1-profile' tampering was accepted"
        )
        { error in
            XCTAssertEqual(error as? SPFNAuthError, .proofInvalid, "'A1-profile' refused with the wrong error")
        }
    }

    /// A different 64-character lowercase hex digest: still valid in form, so the
    /// refusal it provokes is the signature check and never a format gate.
    private func tamperedHexDigest(_ digest: String) -> String
    {
        let last = digest.last == "0" ? "f" : "0"
        return digest.dropLast() + String(last)
    }

    private func modified(
        _ input: SPFNProofInput,
        method: String? = nil,
        path: String? = nil,
        clientID: String? = nil,
        keyID: String? = nil,
        nonce: String? = nil,
        issuedAtMillis: Int64? = nil,
        bodySha256: String? = nil
    ) -> SPFNProofInput
    {
        SPFNProofInput(
            method: method ?? input.method,
            path: path ?? input.path,
            clientID: clientID ?? input.clientID,
            keyID: keyID ?? input.keyID,
            nonce: nonce ?? input.nonce,
            issuedAtMillis: issuedAtMillis ?? input.issuedAtMillis,
            bodySha256: bodySha256 ?? input.bodySha256
        )
    }

    private func hexLower(_ bytes: [UInt8]) -> String
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

    /// DER, uppercase, truncation, non-hex, a wrong key and r = s = 0 are all one
    /// refusal. The DER, uppercase and truncated entries derive from a signature that
    /// DOES verify in its correct form, so a verifier that ignores encoding admits
    /// them and fails here.
    func testSignatureRejectVectors() throws
    {
        let fixture = try Fixtures.load("proof/proof-input.json").members()
        let (_, publicKeySpkiDer) = try ProofFixtures.testKeyPair()
        let vectors = try fixture.list("vectors").map { try $0.members() }
        let rejects = try fixture.list("signatureRejects")
        XCTAssertFalse(rejects.isEmpty)

        for reject in rejects
        {
            let entry = try reject.members()
            let name = try entry.text("name")
            let vectorName = try entry.text("vector")
            let vector = try XCTUnwrap(
                vectors.first { (try? $0.text("name")) == vectorName },
                "'\(name)' names an unknown vector '\(vectorName)'"
            )
            let input = try ProofFixtures.input(from: try vector["input"]!.members())

            XCTAssertThrowsError(
                try SPFNClientProof.verify(
                    presented: try entry.text("presented"),
                    for: input,
                    publicKeySpkiDer: publicKeySpkiDer
                ),
                "'\(name)' was accepted but must be refused"
            )
            { error in
                XCTAssertEqual(error as? SPFNAuthError, .proofInvalid, "'\(name)' refused with the wrong error")
            }
        }
    }

    func testProofFieldOrderMatchesTheContract() throws
    {
        let bundle = try SPFNCanonicalJSON.parse(
            [UInt8](try Data(contentsOf: Fixtures.repoRoot.appendingPathComponent("Contracts/spfn-mobile-contract.json")))
        ).members()
        let declared = try bundle["clientProofV1"]!.members()["proofInput"]!.members().list("fields")
            .map { try $0.text() }

        XCTAssertEqual(SPFNClientProof.proofInputFields, declared)
    }

    /// The bundle's signature clause and this SDK's mechanism have to be the same
    /// statement — and the retired `mac` clause has to be gone, so a stale bundle (or a
    /// stale SDK) fails a test run rather than surfacing as a 401 against a server.
    func testSignatureClauseMatchesTheContract() throws
    {
        let bundle = try SPFNCanonicalJSON.parse(
            [UInt8](try Data(contentsOf: Fixtures.repoRoot.appendingPathComponent("Contracts/spfn-mobile-contract.json")))
        ).members()
        let profile = try bundle["clientProofV1"]!.members()

        XCTAssertNil(profile["mac"], "the HMAC clause was retired with contract 0.2.0")

        let signature = try profile["signature"].map { try $0.members() }
        let clause = try XCTUnwrap(signature, "contract 0.2.0 declares a signature clause")
        XCTAssertEqual(try clause.text("algorithm"), "ECDSA P-256 with SHA-256")
        XCTAssertTrue(try clause.text("encoding").contains("base16-lower (128 hex characters)"))
        XCTAssertTrue(try clause.text("publicKey").hasPrefix("SPKI DER, base64"))
    }

    func testControlCharactersInProofFieldsAreRejected() throws
    {
        let fixture = try Fixtures.load("proof/rejects.json").members()
        let vectors = try fixture.list("vectors")
        XCTAssertFalse(vectors.isEmpty)

        for vector in vectors
        {
            let entry = try vector.members()
            let name = try entry.text("name")
            let field = try entry.text("field")
            let input = try ProofFixtures.input(from: try entry["input"]!.members())

            XCTAssertThrowsError(try SPFNClientProof.canonicalString(for: input), "'\(name)' was accepted")
            { error in
                XCTAssertEqual(error as? SPFNAuthError, .controlCharacterInProofField(field))
                XCTAssertEqual((error as? SPFNAuthError)?.code, try? entry.text("errorCode"))
            }
        }
    }
}
