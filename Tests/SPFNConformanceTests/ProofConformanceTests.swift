// SPFN Mobile — clientProofV1 conformance, Swift half of the parity gate.

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
}

final class ProofConformanceTests: XCTestCase
{
    func testProofInputVectors() throws
    {
        let fixture = try Fixtures.load("proof/proof-input.json").members()
        let key = Array(try fixture["syntheticKey"]!.members().text("keyUtf8").utf8)
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
            XCTAssertEqual(
                try SPFNClientProof.proof(for: input, key: key),
                try entry.text("proofHmacSha256"),
                "proof MAC differs for '\(name)'"
            )
            XCTAssertNoThrow(
                try SPFNClientProof.verify(
                    presented: try entry.text("proofHmacSha256"),
                    for: input,
                    key: key
                )
            )
        }
    }

    func testProofFieldOrderMatchesTheContract() throws
    {
        let bundle = try SPFNCanonicalJSON.parse(
            [UInt8](try Data(contentsOf: Fixtures.repoRoot.appendingPathComponent("Contracts/spfn-mobile-contract.v1.json")))
        ).members()
        let declared = try bundle["clientProofV1"]!.members()["proofInput"]!.members().list("fields")
            .map { try $0.text() }

        XCTAssertEqual(SPFNClientProof.proofInputFields, declared)
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
