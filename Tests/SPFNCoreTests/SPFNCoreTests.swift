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
    private let preStable = SPFNContractBinding(
        importedVersion: "0.1.0",
        importedManifestSha256: String(repeating: "b", count: 64),
        supportedRange: ">=0.1.0 <0.2.0",
        supportedMajor: 0,
        supportedMinor: 1,
        origin: "spfn-primitives-ci-export"
    )

    private let devBundle = SPFNContractBinding(
        importedVersion: "1.0.0-dev.1",
        importedManifestSha256: String(repeating: "c", count: 64),
        supportedRange: ">=1.0.0-dev.1 <2.0.0",
        supportedMajor: 1,
        supportedMinor: 0,
        origin: "spfn-mobile-step2-dev-bundle"
    )

    func testADevBundleIsNeverReportedAsAnUpstreamExport() throws
    {
        XCTAssertFalse(devBundle.isUpstreamExport)
        XCTAssertTrue(preStable.isUpstreamExport)
    }

    /// The upper bound is derived, never parsed out of the printed range, so the two
    /// cannot disagree about what the SDK accepts.
    func testUpperBoundFollowsTheBreakingAxis() throws
    {
        XCTAssertEqual(preStable.upperBound, "0.2.0")
        XCTAssertEqual(devBundle.upperBound, "2.0.0")
        XCTAssertTrue(preStable.supportedRange.hasSuffix("<\(preStable.upperBound)"))
        XCTAssertTrue(devBundle.supportedRange.hasSuffix("<\(devBundle.upperBound)"))
    }

    /// `supportedRange` is the contract's claim; `admittedRange` is what this SDK will
    /// accept. For a release pin they agree. For a pre-release pin the declared range
    /// promises every core below the next breaking version and this SDK refuses all of
    /// them, so a refusal that named the declared range would advertise a window it will
    /// not honour.
    func testAdmittedRangeStatesWhatIsEnforced() throws
    {
        XCTAssertEqual(preStable.admittedRange, preStable.supportedRange)
        XCTAssertEqual(devBundle.admittedRange, "==1.0.0-dev.1")
        XCTAssertNotEqual(devBundle.admittedRange, devBundle.supportedRange)

        XCTAssertNoThrow(try devBundle.requireSupported(serverContractVersion: "1.0.0-dev.1"))

        // Every one of these is inside the declared range and refused by the pin.
        for version in ["1.0.0", "1.0.1", "1.9.9", "1.0.0-dev.2"]
        {
            XCTAssertThrowsError(try devBundle.requireSupported(serverContractVersion: version))
            { error in
                guard case SPFNDecodingError.unsupportedContractVersion(_, let range) = error
                else
                {
                    return XCTFail("expected an upgrade error, got \(error)")
                }
                XCTAssertEqual(range, "==1.0.0-dev.1", "'\(version)' was refused against a range that admits it")
            }
        }
    }

    /// A pin this SDK cannot parse admits nothing, and says nothing rather than printing
    /// a range derived from a version that does not exist.
    func testAnUnparsablePinAdmitsNothing() throws
    {
        let broken = SPFNContractBinding(
            importedVersion: "1.0",
            importedManifestSha256: String(repeating: "d", count: 64),
            supportedRange: ">=1.0 <2.0.0",
            supportedMajor: 1,
            supportedMinor: 0,
            origin: "spfn-mobile-step2-dev-bundle"
        )

        XCTAssertTrue(broken.admittedRange.hasPrefix("<none:"))
        XCTAssertThrowsError(try broken.requireSupported(serverContractVersion: "1.0.0"))
        XCTAssertThrowsError(try broken.requireSupported(serverContractVersion: "1.0"))
    }

    /// Every case in the shared table, which the Kotlin suite reads too. A rule that
    /// drifts on one platform fails there rather than against a real server.
    func testSharedRangeVectors() throws
    {
        let root: [String: Any]? = try vectorRoot()
        let cases = try XCTUnwrap(root?["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 30, "the shared table lost cases")

        for entry in cases
        {
            let lower = try XCTUnwrap(entry["lower"] as? String)
            let upper = try XCTUnwrap(entry["upper"] as? String)
            let candidate = try XCTUnwrap(entry["candidate"] as? String)
            let expected = try XCTUnwrap(entry["supported"] as? Bool)
            let why = try XCTUnwrap(entry["why"] as? String)

            XCTAssertEqual(
                SPFNSemVer.satisfies(candidate: candidate, atOrAbove: lower, below: upper),
                expected,
                "'\(candidate)' against [\(lower), \(upper)): \(why)"
            )
        }

        // The parser is asserted directly too. A range case can pass because the rule
        // refused for the right reason or because the parse failed for the wrong one,
        // and only these say which.
        let parsing = try XCTUnwrap(root?["parsing"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(parsing.count, 20, "the shared parser table lost cases")

        for entry in parsing
        {
            let text = try XCTUnwrap(entry["text"] as? String)
            let valid = try XCTUnwrap(entry["valid"] as? Bool)
            let why = try XCTUnwrap(entry["why"] as? String)

            XCTAssertEqual(SPFNSemVer.parse(text) != nil, valid, "'\(text)': \(why)")
        }
    }

    /// The tables are evidence only if a wrong rule fails them. These run the rules this
    /// change set replaced and require each table to catch its own. A table that merely
    /// transcribed the implementation would agree with the old rule too, and a future
    /// change that reverted the rule and relaxed the table to match would fail here.
    func testTheSharedTablesRejectTheRulesTheyReplaced() throws
    {
        let root: [String: Any]? = try vectorRoot()
        let cases = try XCTUnwrap(root?["cases"] as? [[String: Any]])
        let parsing = try XCTUnwrap(root?["parsing"] as? [[String: Any]])

        var rangeMismatches = 0
        for entry in cases
        {
            let expected = try XCTUnwrap(entry["supported"] as? Bool)
            let legacy = legacySatisfies(
                try XCTUnwrap(entry["candidate"] as? String),
                try XCTUnwrap(entry["lower"] as? String),
                try XCTUnwrap(entry["upper"] as? String)
            )
            if legacy != expected
            {
                rangeMismatches += 1
            }
        }
        XCTAssertGreaterThan(rangeMismatches, 0, "the range table no longer discriminates the rule it replaced")

        var parseMismatches = 0
        for entry in parsing
        {
            let expected = try XCTUnwrap(entry["valid"] as? Bool)
            if (legacyParse(try XCTUnwrap(entry["text"] as? String)) != nil) != expected
            {
                parseMismatches += 1
            }
        }
        XCTAssertGreaterThan(parseMismatches, 0, "the parser table no longer discriminates the rule it replaced")
    }

    private func vectorRoot() throws -> [String: Any]
    {
        let url = RepoPaths.root.appendingPathComponent("tools/conformance/semver-range-vectors.json")
        return try XCTUnwrap(
            try JSONSerialization.jsonObject(with: try Data(contentsOf: url)) as? [String: Any]
        )
    }

    private struct LegacyVersion
    {
        let core: [String]
        let preRelease: String?
    }

    /// The parser before this change set: identifiers were alphanumeric with no numeric
    /// leading-zero rule, so `0.1.0-01` parsed.
    private func legacyParse(_ text: String) -> LegacyVersion?
    {
        var body = Substring(text)

        if let plus = body.firstIndex(of: "+")
        {
            guard legacyIdentifiers(body[body.index(after: plus)...])
            else
            {
                return nil
            }
            body = body[..<plus]
        }

        var preRelease: String? = nil
        if let dash = body.firstIndex(of: "-")
        {
            let tail = body[body.index(after: dash)...]
            guard legacyIdentifiers(tail)
            else
            {
                return nil
            }
            preRelease = String(tail)
            body = body[..<dash]
        }

        let core = body.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard core.count == 3,
              core.allSatisfy({ !$0.isEmpty && $0.allSatisfy { $0.isASCII && $0.isNumber } }),
              core.allSatisfy({ $0 == "0" || !$0.hasPrefix("0") })
        else
        {
            return nil
        }
        return LegacyVersion(core: core, preRelease: preRelease)
    }

    private func legacyIdentifiers(_ text: Substring) -> Bool
    {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        return !parts.isEmpty && parts.allSatisfy { part in
            !part.isEmpty && part.allSatisfy { $0.isASCII && ($0.isNumber || $0.isLetter || $0 == "-") }
        }
    }

    /// The range rule before this change set: the pre-release had to equal the lower
    /// bound's, then the core was compared against both ends — which let a pinned
    /// pre-release admit any later core.
    private func legacySatisfies(_ candidate: String, _ lower: String, _ upper: String) -> Bool
    {
        guard let candidate = legacyParse(candidate),
              let lower = legacyParse(lower),
              let upper = legacyParse(upper),
              candidate.preRelease == lower.preRelease
        else
        {
            return false
        }
        return legacyCompareCore(candidate.core, lower.core) >= 0
            && legacyCompareCore(candidate.core, upper.core) < 0
    }

    private func legacyCompareCore(_ left: [String], _ right: [String]) -> Int
    {
        for (one, other) in zip(left, right)
        {
            if one.count != other.count
            {
                return one.count < other.count ? -1 : 1
            }
            if one != other
            {
                return one < other ? -1 : 1
            }
        }
        return 0
    }

    /// The same table driven through the public entry point, so the binding and the
    /// comparator cannot pass separately while disagreeing with each other.
    func testTheBindingRefusesWhatTheTableRefuses() throws
    {
        for candidate in ["0.2.0", "0.1.0-rc.1", "0.1", "0.01.0", "", "1.0.0"]
        {
            XCTAssertThrowsError(try preStable.requireSupported(serverContractVersion: candidate))
            { error in
                XCTAssertEqual((error as? SPFNDecodingError)?.code, "CONTRACT_UNSUPPORTED")
            }
        }
        XCTAssertNoThrow(try preStable.requireSupported(serverContractVersion: "0.1.0"))
        XCTAssertNoThrow(try preStable.requireSupported(serverContractVersion: "0.1.9"))
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
