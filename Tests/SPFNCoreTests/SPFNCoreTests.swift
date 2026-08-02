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

/// An envelope's three fields are text a server wrote, so none of them may reach a log
/// through a default rendering — and the redaction that stops that must not disturb what
/// the rest of the SDK reads the envelope for.
final class SPFNErrorEnvelopeTests: XCTestCase
{
    /// Markers a real server would never send, so a hit is unambiguous.
    private let code = "MARKER_CODE_7f31"
    private let message = "session-marker-message-a4c2"
    private let requestID = "req-marker-b8e5"

    private func envelope() -> SPFNErrorEnvelope
    {
        SPFNErrorEnvelope(code: code, message: message, requestID: requestID)
    }

    func testNoDefaultRenderingCarriesServerText() throws
    {
        let subject = envelope()

        var dumped = ""
        dump(subject, to: &dumped)

        let renderings: [(String, String)] = [
            ("string interpolation", "\(subject)"),
            ("String(describing:)", String(describing: subject)),
            ("String(reflecting:)", String(reflecting: subject)),
            ("dump", dumped),
            ("Mirror children", "\(Mirror(reflecting: subject).children.map { "\($0.value)" })"),
        ]

        for (path, rendering) in renderings
        {
            for marker in [code, message, requestID]
            {
                XCTAssertFalse(rendering.contains(marker), "\(path) exposed server-controlled text")
            }
        }

        // Exact, so a rendering cannot start naming fields again in some other wording.
        let expected = "SPFNErrorEnvelope(code: redacted, message: redacted, requestID: redacted)"
        XCTAssertEqual("\(subject)", expected)
        XCTAssertEqual(String(reflecting: subject), expected)
        XCTAssertTrue(Mirror(reflecting: subject).children.isEmpty)
    }

    /// The fields stay readable, because classifying an error is the whole point of
    /// having them. Only printing one by accident is blocked.
    func testFieldsRemainReadable()
    {
        let subject = envelope()

        XCTAssertEqual(subject.code, code)
        XCTAssertEqual(subject.message, message)
        XCTAssertEqual(subject.requestID, requestID)
    }

    /// Redaction changed how the value prints and nothing else.
    func testEqualityAndCanonicalFormAreUnchanged()
    {
        XCTAssertEqual(envelope(), envelope())
        XCTAssertNotEqual(
            envelope(),
            SPFNErrorEnvelope(code: code, message: message, requestID: "req-other")
        )
        XCTAssertEqual(
            SPFNCanonicalJSON.encodeToString(envelope().canonicalValue),
            #"{"error":{"code":"MARKER_CODE_7f31","message":"session-marker-message-a4c2","requestId":"req-marker-b8e5"}}"#
        )
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
