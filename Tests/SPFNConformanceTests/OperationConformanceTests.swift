// SPFN Mobile — generated client conformance, Swift half of the parity gate.
//
// Every assertion here runs against generated code. If the generator changed shape, or
// the bundle changed and the clients were not regenerated, this suite is what notices.

import Foundation
import SPFNCore
import SPFNGenerated
import XCTest

final class OperationConformanceTests: XCTestCase
{
    func testGeneratedBindingMatchesTheLock() throws
    {
        let lock = try SPFNCanonicalJSON.parse(
            [UInt8](try Data(contentsOf: Fixtures.repoRoot.appendingPathComponent("Contracts/upstream.lock.json")))
        ).members()
        let contract = try lock["contract"]!.members()

        XCTAssertEqual(SPFNGeneratedContract.binding.importedVersion, try contract.text("version"))
        XCTAssertEqual(SPFNGeneratedContract.binding.importedManifestSha256, try contract.text("manifestSha256"))
        XCTAssertEqual(SPFNGeneratedContract.binding.supportedRange, try contract.text("supportedRange"))
        XCTAssertEqual(Int64(SPFNGeneratedContract.binding.supportedMajor), try contract.number("major"))
    }

    func testGeneratedBindingDigestMatchesTheBundleOnDisk() throws
    {
        let bundle = try Data(contentsOf: Fixtures.repoRoot.appendingPathComponent("Contracts/spfn-mobile-contract.v1.json"))
        XCTAssertEqual(
            SPFNDigest.sha256Hex([UInt8](bundle)),
            SPFNGeneratedContract.binding.importedManifestSha256,
            "the generated header claims a digest the bundle does not have"
        )
    }

    func testTheBundleIsNotClaimedToBeAnUpstreamExport() throws
    {
        XCTAssertFalse(
            SPFNGeneratedContract.binding.isUpstreamExport,
            "no SPFN primitives export exists; claiming one would be the failure the lock is designed to prevent"
        )
        XCTAssertEqual(SPFNGeneratedContract.binding.origin, "spfn-mobile-step2-dev-bundle")
    }

    func testOperationDescriptorsMatchTheBundle() throws
    {
        let bundle = try SPFNCanonicalJSON.parse(
            [UInt8](try Data(contentsOf: Fixtures.repoRoot.appendingPathComponent("Contracts/spfn-mobile-contract.v1.json")))
        ).members()

        let declared = try bundle.list("operations").map { try $0.members() }
        XCTAssertEqual(SPFNGeneratedOperations.all.count, declared.count)

        for entry in declared
        {
            let id = try entry.text("id")
            let operation = try XCTUnwrap(SPFNGeneratedOperations.operation(id: id), "\(id) was not generated")
            XCTAssertEqual(operation.method, try entry.text("method"))
            XCTAssertEqual(operation.path, try entry.text("path"))
            XCTAssertEqual(operation.authProfile, "clientProofV1")
        }

        XCTAssertNil(SPFNGeneratedOperations.operation(id: "no.such.operation"))
    }

    func testRequestVectorsCanonicalizeIdentically() throws
    {
        let fixture = try Fixtures.load("request/operations.json").members()
        let requests = try fixture.list("requests")
        XCTAssertFalse(requests.isEmpty)

        for request in requests
        {
            let entry = try request.members()
            let name = try entry.text("name")
            let canonical = try roundTrip(type: try entry.text("type"), value: entry["value"]!)

            XCTAssertEqual(
                SPFNCanonicalJSON.encodeToString(canonical),
                try entry.text("canonical"),
                "canonical request bytes differ for '\(name)'"
            )
            XCTAssertEqual(
                SPFNDigest.sha256Hex(SPFNCanonicalJSON.encode(canonical)),
                try entry.text("sha256"),
                "canonical request digest differs for '\(name)'"
            )
        }
    }

    func testResponseVectorsDecodeAndReEncodeIdentically() throws
    {
        let fixture = try Fixtures.load("request/operations.json").members()
        let responses = try fixture.list("responses")
        XCTAssertFalse(responses.isEmpty)

        for response in responses
        {
            let entry = try response.members()
            let name = try entry.text("name")
            let wire = try SPFNCanonicalJSON.parse(try entry.text("wire"))
            let canonical = try roundTrip(type: try entry.text("type"), value: wire)

            XCTAssertEqual(
                SPFNCanonicalJSON.encodeToString(canonical),
                try entry.text("canonical"),
                "canonical response bytes differ for '\(name)'"
            )
            XCTAssertEqual(
                SPFNDigest.sha256Hex(SPFNCanonicalJSON.encode(canonical)),
                try entry.text("sha256"),
                "canonical response digest differs for '\(name)'"
            )
        }
    }

    func testUnsupportedContractMajorIsAnUpgradeError() throws
    {
        XCTAssertNoThrow(try SPFNGeneratedContract.binding.requireSupported(serverContractVersion: "1.4.0"))
        XCTAssertThrowsError(try SPFNGeneratedContract.binding.requireSupported(serverContractVersion: "2.0.0"))
        { error in
            guard case SPFNDecodingError.unsupportedContractVersion(let found, _) = error
            else
            {
                return XCTFail("expected an upgrade error, got \(error)")
            }
            XCTAssertEqual(found, "2.0.0")
        }
    }

    /// Decodes a fixture value into the generated type it names, then re-encodes it.
    /// The switch is the one place a test has to know the contract's type names.
    private func roundTrip(type: String, value: SPFNCanonicalValue) throws -> SPFNCanonicalValue
    {
        switch type
        {
        case "HandshakeRequest":
            return try SPFNHandshakeRequest(canonical: value).canonicalValue
        case "HandshakeResponse":
            return try SPFNHandshakeResponse(canonical: value).canonicalValue
        case "EchoRequest":
            return try SPFNEchoRequest(canonical: value).canonicalValue
        case "EchoResponse":
            return try SPFNEchoResponse(canonical: value).canonicalValue
        case "ListItemsRequest":
            return try SPFNListItemsRequest(canonical: value).canonicalValue
        case "ListItemsResponse":
            return try SPFNListItemsResponse(canonical: value).canonicalValue
        default:
            XCTFail("fixture names an unknown type '\(type)'")
            throw ConformanceFailure.shape
        }
    }
}
