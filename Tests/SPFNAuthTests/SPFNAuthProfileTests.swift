// SPFN Mobile — auth boundary unit tests.

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
    private let key = Array("spfn-test-key-not-a-secret-0001".utf8)

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

    func testTamperingWithAnyFieldChangesTheProof() throws
    {
        let base = try SPFNClientProof.proof(for: input(), key: key)
        let tampered = try SPFNClientProof.proof(for: input(nonce: "nonce-0002"), key: key)
        XCTAssertNotEqual(base, tampered)

        XCTAssertThrowsError(try SPFNClientProof.verify(presented: tampered, for: input(), key: key))
        { error in
            XCTAssertEqual(error as? SPFNAuthError, .proofInvalid)
        }
    }

    func testADifferentKeyDoesNotVerify() throws
    {
        let proof = try SPFNClientProof.proof(for: input(), key: key)
        XCTAssertThrowsError(
            try SPFNClientProof.verify(presented: proof, for: input(), key: Array("another-test-key".utf8))
        )
    }
}
