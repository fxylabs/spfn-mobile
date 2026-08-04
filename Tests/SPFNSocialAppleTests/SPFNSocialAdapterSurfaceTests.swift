// SPFN Mobile — what neither adapter is allowed to do, case table rows C8 and C9.
//
// Both rows are about absence, and absence is not observable through a call: an adapter
// that logs a token logs it wherever it likes, and one that reads a display field reads
// it in a branch a fake driver never enters. So both are judged from the sources of
// both adapter modules, and the scan itself is held to a floor — a scan that reads
// nothing must fail rather than pass quietly (P7).
//
// This file lives in the Apple suite and covers both modules on purpose: the rule is
// one rule over the adapter surface, and splitting it in two would let one half be
// deleted while the other still reports green.

import Foundation
import XCTest
import SPFNClient
import SPFNSocialApple

final class SPFNSocialAdapterSurfaceTests: XCTestCase
{
    private static let adapterDirectories = [
        "Sources/SPFNSocialApple",
        "Sources/SPFNSocialGoogle",
    ]

    /// C8: no adapter writes a token anywhere an operator could read it. Every logging
    /// entry point is refused outright, because there is no adapter line that needs one
    /// and a per-call-site judgement is exactly what stops being made later.
    func test_C8_noAdapterSourceCarriesALoggingCall() throws
    {
        let sources = try Self.adapterSources()

        for (path, text) in sources
        {
            for logger in ["print(", "NSLog(", "os_log(", "debugPrint(", "dump(", "FileHandle.standardOutput"]
            {
                XCTAssertFalse(
                    Self.activeLines(text).contains { $0.contains(logger) },
                    "\(path) calls \(logger); an adapter that can log is an adapter that can log a token"
                )
            }
        }

        // The token also must not reach the caller through an error value. Every case of
        // both adapters' error types carries a number or nothing at all, so a token
        // cannot ride out in one — asserted here against a token the flow really saw.
        let leaked = "apple-token-leak-0001"
        let thrown = SPFNSocialApple.classify(TokenCarryingError(token: leaked))
        XCTAssertFalse(String(describing: thrown).contains(leaked))
        XCTAssertFalse(String(reflecting: thrown).contains(leaked))
    }

    /// C9: no adapter reads the display fields a provider offers. The SDK's scope ends
    /// at the identity token (decision 1); an app that wants those fields asks the
    /// provider itself and sends them through a profile operation.
    func test_C9_noAdapterSourceReadsProviderProfileFields() throws
    {
        let sources = try Self.adapterSources()
        let forbidden = ["fullName", "givenName", "familyName", "nickName", "middleName", "emailAddress", "profileData"]

        for (path, text) in sources
        {
            for field in forbidden
            {
                XCTAssertFalse(
                    Self.activeLines(text).contains { $0.contains(field) },
                    "\(path) reads \(field), which decision 1 keeps out of this SDK"
                )
            }
            XCTAssertFalse(
                Self.activeLines(text).contains { $0.contains("requestedScopes") && !$0.contains("[]") },
                "\(path) requests scopes; the identity token is the whole of what an adapter reads"
            )
        }
    }

    // MARK: - Assembly

    /// Enumeration and scanning are separate steps, and the enumeration has a floor: one
    /// file per adapter module at the very least. A directory that cannot be read, or
    /// that answers with nothing, fails here instead of passing as "no hits found".
    private static func adapterSources() throws -> [(String, String)]
    {
        var sources: [(String, String)] = []
        for directory in adapterDirectories
        {
            let url = SocialSurfacePaths.root.appendingPathComponent(directory)
            let names = try FileManager.default.contentsOfDirectory(atPath: url.path)
                .filter { $0.hasSuffix(".swift") }
                .sorted()
            XCTAssertFalse(names.isEmpty, "\(directory) enumerated no Swift sources")

            for name in names
            {
                let text = try String(contentsOf: url.appendingPathComponent(name), encoding: .utf8)
                sources.append((directory + "/" + name, text))
            }
        }
        XCTAssertGreaterThanOrEqual(sources.count, adapterDirectories.count, "the adapter scan read too few files")
        return sources
    }

    /// Comments are excluded the way the shell validator excludes them: a prohibition
    /// has to be describable in the file that obeys it.
    private static func activeLines(_ text: String) -> [String]
    {
        text.split(separator: "\n", omittingEmptySubsequences: false)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.hasPrefix("//") }
    }
}

/// An error whose text carries a token, so C8 can prove the classification drops it
/// rather than merely that no adapter happened to print one.
struct TokenCarryingError: Error, CustomStringConvertible
{
    let token: String

    var description: String
    {
        "provider refused: \(token)"
    }
}

enum SocialSurfacePaths
{
    static let root: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // Tests/SPFNSocialAppleTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // repository root
}
