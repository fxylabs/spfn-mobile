// SPFN Mobile — clientProofV1 conformance, Swift half of the parity gate.
//
// The proof-input bytes stay byte-pinned. The signature does not — an ECDSA signer
// draws a random nonce — so it is judged in both directions instead: the fixture's
// recorded signature (produced by derive-expected-values.py, outside either SDK) must
// verify here, and a signature this platform produces must verify under the fixture
// public key. The reject table then proves the verifier refuses everything that is
// not a valid raw-r‖s base16-lower signature under the named key.

import CryptoKit
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

    /// A signature over one input must fail over any other: the discriminance half of
    /// the vector above, without which every green would also be green for a verifier
    /// that accepts everything.
    func testAFixtureSignatureFailsOverATamperedInput() throws
    {
        let fixture = try Fixtures.load("proof/proof-input.json").members()
        let (_, publicKeySpkiDer) = try ProofFixtures.testKeyPair()
        let entry = try fixture.list("vectors")[0].members()
        let input = try ProofFixtures.input(from: try entry["input"]!.members())

        let tampered = SPFNProofInput(
            method: input.method,
            path: input.path,
            clientID: input.clientID,
            keyID: input.keyID,
            nonce: input.nonce + "-tampered",
            issuedAtMillis: input.issuedAtMillis,
            bodySha256: input.bodySha256
        )

        XCTAssertThrowsError(
            try SPFNClientProof.verify(
                presented: try entry.text("signatureRsHex"),
                for: tampered,
                publicKeySpkiDer: publicKeySpkiDer
            )
        )
        { error in
            XCTAssertEqual(error as? SPFNAuthError, .proofInvalid)
        }
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
