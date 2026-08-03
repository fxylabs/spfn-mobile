// SPFN Mobile — auth boundary unit tests.

import CryptoKit
import Foundation
import XCTest
@testable import SPFNAuth
import SPFNCore

final class SPFNAuthProfileTests: XCTestCase
{
    func testAllowlistIsExactlyClientProofV1() throws
    {
        XCTAssertEqual(SPFNAuthProfile.allCases, [.clientProofV1])
        XCTAssertEqual(SPFNAuthPolicy.allowedProfiles, [.clientProofV1])
        XCTAssertEqual(SPFNAuthPolicy.defaultProfile, .clientProofV1)
    }

    func testEveryDeclaredProfileIsAllowed() throws
    {
        for profile in SPFNAuthProfile.allCases
        {
            XCTAssertTrue(
                SPFNAuthPolicy.allowedProfiles.contains(profile),
                "profile \(profile.rawValue) is declared but not allowlisted"
            )
        }
    }

    func testUnknownProfileIsRejectedWithoutFallback() throws
    {
        XCTAssertThrowsError(try SPFNAuthPolicy.resolve(profileName: "somethingElseV1"))
        { error in
            XCTAssertEqual(error as? SPFNAuthError, .unknownProfileRejected("somethingElseV1"))
            XCTAssertEqual((error as? SPFNAuthError)?.code, "PROFILE_REJECTED")
        }
    }

    func testTheProofInputNamesTheProfileItself() throws
    {
        XCTAssertEqual(SPFNClientProof.profileName, "clientProofV1")
        XCTAssertEqual(SPFNClientProof.proofInputFields.first, "profile")
    }
}

final class SPFNClientProofUnitTests: XCTestCase
{
    /// A fresh in-process P-256 keypair. TEST ONLY — never persisted, never registered.
    private let privateKey = P256.Signing.PrivateKey()

    private func sign(_ message: [UInt8]) throws -> [UInt8]
    {
        Array(try privateKey.signature(for: Data(message)).rawRepresentation)
    }

    private var publicKeySpkiDer: [UInt8]
    {
        Array(privateKey.publicKey.derRepresentation)
    }

    private func input(nonce: String = "nonce-0001", bodySha256: String? = nil) -> SPFNProofInput
    {
        SPFNProofInput(
            method: "POST",
            path: "/v1/echo",
            clientID: "client-0001",
            keyID: "key-0001",
            nonce: nonce,
            issuedAtMillis: 1_750_000_000_000,
            bodySha256: bodySha256 ?? SPFNDigest.absentBodyDigest
        )
    }

    func testProofInputIsTheEightFieldsInOrder() throws
    {
        let lines = try SPFNClientProof.canonicalString(for: input()).components(separatedBy: "\n")
        XCTAssertEqual(lines.count, SPFNClientProof.proofInputFields.count)
        XCTAssertEqual(lines[0], "clientProofV1")
        XCTAssertEqual(lines[1], "POST")
        XCTAssertEqual(lines[6], "1750000000000")
    }

    func testAbsentBodyUsesTheAbsentBodyDigest() throws
    {
        let built = SPFNProofInput.forRequest(
            method: "POST",
            path: "/v1/echo",
            clientID: "client-0001",
            keyID: "key-0001",
            nonce: "nonce-0001",
            issuedAtMillis: 1_750_000_000_000,
            canonicalBody: nil
        )
        XCTAssertEqual(built.bodySha256, SPFNDigest.absentBodyDigest)
    }

    func testAProofVerifiesOverItsOwnInputAndFailsOverAnother() throws
    {
        let proof = try SPFNClientProof.proof(for: input(), signedBy: sign)

        XCTAssertNoThrow(
            try SPFNClientProof.verify(presented: proof, for: input(), publicKeySpkiDer: publicKeySpkiDer)
        )
        XCTAssertThrowsError(
            try SPFNClientProof.verify(
                presented: proof,
                for: input(nonce: "nonce-0002"),
                publicKeySpkiDer: publicKeySpkiDer
            )
        )
        { error in
            XCTAssertEqual(error as? SPFNAuthError, .proofInvalid)
        }
    }

    func testADifferentKeyDoesNotVerify() throws
    {
        let proof = try SPFNClientProof.proof(for: input(), signedBy: sign)
        let otherKey = Array(P256.Signing.PrivateKey().publicKey.derRepresentation)
        XCTAssertThrowsError(
            try SPFNClientProof.verify(presented: proof, for: input(), publicKeySpkiDer: otherKey)
        )
    }

    /// A signer that hands back anything but 64 bytes emitted DER — or nothing — and
    /// is refused before its output can reach the wire.
    func testASignerThatReturnsTheWrongLengthIsRefused() throws
    {
        XCTAssertThrowsError(
            try SPFNClientProof.proof(for: input()) { _ in [UInt8](repeating: 0, count: 70) }
        )
        { error in
            XCTAssertEqual(error as? SPFNAuthError, .proofInvalid)
        }
    }
}
