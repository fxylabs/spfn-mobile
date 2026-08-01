// SPFN Mobile — core unit tests.

import Foundation
import XCTest
@testable import SPFNCore

final class SPFNVersionTests: XCTestCase
{
    func testVersionConstantMatchesVersionFile() throws
    {
        let versionFile = RepoPaths.root.appendingPathComponent("VERSION")
        let onDisk = try String(contentsOf: versionFile, encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        XCTAssertEqual(SPFNVersion.current, onDisk, "SPFNVersion.current drifted from the VERSION file")
    }

    func testVersionIsAPreRelease() throws
    {
        XCTAssertTrue(
            SPFNVersion.current.contains("-"),
            "no stable release exists, so the version must carry a SemVer pre-release identifier (D9)"
        )
    }
}

final class SPFNScaffoldTests: XCTestCase
{
    func testBuildDeclaresItselfAScaffold() throws
    {
        XCTAssertTrue(SPFNScaffold.isScaffold)
        XCTAssertTrue(SPFNScaffold.disclaimer.contains("no supported release"))
    }

    func testUnimplementedEntryPointsStillCarryTheirPlannedStep() throws
    {
        let error = SPFNScaffoldError.notImplementedInScaffold(
            symbol: "SPFNPersistence.open(storeName:)",
            plannedStep: "Step 3+"
        )
        XCTAssertEqual(
            error,
            .notImplementedInScaffold(symbol: "SPFNPersistence.open(storeName:)", plannedStep: "Step 3+")
        )
    }
}

final class SPFNContractBindingTests: XCTestCase
{
    private let binding = SPFNContractBinding(
        importedVersion: "1.0.0-dev.1",
        importedManifestSha256: String(repeating: "a", count: 64),
        supportedRange: ">=1.0.0-dev.1 <2.0.0",
        supportedMajor: 1,
        origin: "spfn-mobile-step2-dev-bundle"
    )

    func testADevBundleIsNeverReportedAsAnUpstreamExport() throws
    {
        XCTAssertFalse(binding.isUpstreamExport)
    }

    func testSupportedMajorIsAccepted() throws
    {
        XCTAssertNoThrow(try binding.requireSupported(serverContractVersion: "1.7.3"))
    }

    func testOtherMajorsRaiseAnUpgradeError() throws
    {
        for version in ["2.0.0", "0.9.0", "not-a-version"]
        {
            XCTAssertThrowsError(try binding.requireSupported(serverContractVersion: version))
            { error in
                XCTAssertEqual((error as? SPFNDecodingError)?.code, "CONTRACT_UNSUPPORTED")
            }
        }
    }
}

final class SPFNDigestTests: XCTestCase
{
    func testKnownSha256Vector() throws
    {
        XCTAssertEqual(
            SPFNDigest.sha256Hex("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )
    }

    func testAbsentBodyDigestIsNotTheDigestOfTheEmptyString()
    {
        XCTAssertNotEqual(SPFNDigest.absentBodyDigest, SPFNDigest.sha256Hex(""))
        XCTAssertEqual(SPFNDigest.absentBodyDigest.count, 64)
    }

    func testConstantTimeEqualsAgreesWithEquality()
    {
        XCTAssertTrue(SPFNDigest.constantTimeEquals("abcd", "abcd"))
        XCTAssertFalse(SPFNDigest.constantTimeEquals("abcd", "abce"))
        XCTAssertFalse(SPFNDigest.constantTimeEquals("abcd", "abcde"))
    }
}

/// Locates the repository root relative to this source file so tests can assert
/// on repository facts without hardcoding an absolute path.
enum RepoPaths
{
    static let root: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // Tests/SPFNCoreTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // repo root
}
