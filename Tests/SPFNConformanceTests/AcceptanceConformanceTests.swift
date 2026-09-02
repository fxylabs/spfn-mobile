// SPFN Mobile — replay and revocation conformance, Swift half of the parity gate.
//
// The vectors are sequences rather than single calls, because replay is a property of
// a sequence: the same proof accepted once and refused the second time is the point.
// Every fixture `proof` value is a signature derive-expected-values.py produced with
// the test keypair, so admitting one is also a statement that this platform's verifier
// accepts a signature produced outside either SDK.

#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif

import Foundation
import SPFNAuth
import SPFNCore
import XCTest

final class AcceptanceConformanceTests: XCTestCase
{
    func testReplayVectors() throws
    {
        try runAcceptanceVectors(in: "replay/replay.json")
    }

    func testRevocationVectors() throws
    {
        try runAcceptanceVectors(in: "revoke/revoke.json")
    }

    private func runAcceptanceVectors(in path: String) throws
    {
        let fixture = try Fixtures.load(path).members()
        let base = try fixture["base"]!.members()
        let window = try fixture.number("replayWindowMillis")
        let (_, publicKeySpkiDer) = try ProofFixtures.testKeyPair()
        let vectors = try fixture.list("vectors")
        XCTAssertFalse(vectors.isEmpty)

        for vector in vectors
        {
            let entry = try vector.members()
            let name = try entry.text("name")
            let revoked = try (entry["revokedKeyIds"]?.elements() ?? []).map { try $0.text() }
            var acceptance = SPFNProofAcceptance(replayWindowMillis: window, revokedKeyIDs: Set(revoked))

            for step in try entry.list("steps")
            {
                let stepEntry = try step.members()
                let input = SPFNProofInput(
                    method: try base.text("method"),
                    path: try base.text("path"),
                    clientID: try base.text("clientId"),
                    keyID: try base.text("keyId"),
                    nonce: try stepEntry.text("nonce"),
                    issuedAtMillis: try stepEntry.number("issuedAtMillis"),
                    bodySha256: try base.text("bodySha256")
                )
                let expectation = try stepEntry.text("expect")
                let nonce = try stepEntry.text("nonce")
                let presented = try stepEntry.text("proof")
                let nowMillis = try stepEntry.number("nowMillis")

                if expectation == "accept"
                {
                    XCTAssertNoThrow(
                        try acceptance.admit(
                            presented: presented,
                            input: input,
                            publicKeySpkiDer: publicKeySpkiDer,
                            nowMillis: nowMillis
                        ),
                        "'\(name)' step with nonce \(nonce) should have been admitted"
                    )
                    continue
                }

                XCTAssertThrowsError(
                    try acceptance.admit(
                        presented: presented,
                        input: input,
                        publicKeySpkiDer: publicKeySpkiDer,
                        nowMillis: nowMillis
                    ),
                    "'\(name)' step with nonce \(nonce) should have been refused with \(expectation)"
                )
                { error in
                    XCTAssertEqual((error as? SPFNAuthError)?.code, expectation, "'\(name)' refused for the wrong reason")
                }
            }
        }
    }

    func testRevocationOutranksAValidProof() throws
    {
        let fixture = try Fixtures.load("revoke/revoke.json").members()
        let base = try fixture["base"]!.members()
        let (privateKeyDer, publicKeySpkiDer) = try ProofFixtures.testKeyPair()
        let signer = try P256.Signing.PrivateKey(derRepresentation: Data(privateKeyDer))
        let keyID = try base.text("keyId")

        let input = SPFNProofInput(
            method: try base.text("method"),
            path: try base.text("path"),
            clientID: try base.text("clientId"),
            keyID: keyID,
            nonce: "nonce-order-check",
            issuedAtMillis: 1_750_000_000_000,
            bodySha256: try base.text("bodySha256")
        )
        let goodProof = try SPFNClientProof.proof(for: input) { message in
            Array(try signer.signature(for: Data(message)).rawRepresentation)
        }

        var acceptance = SPFNProofAcceptance(replayWindowMillis: try fixture.number("replayWindowMillis"))
        acceptance.revoke(keyID: keyID)

        XCTAssertThrowsError(
            try acceptance.admit(
                presented: goodProof,
                input: input,
                publicKeySpkiDer: publicKeySpkiDer,
                nowMillis: 1_750_000_001_000
            )
        )
        { error in
            XCTAssertEqual(error as? SPFNAuthError, .sessionRevoked, "revocation must be decided before verification")
        }
    }
}
