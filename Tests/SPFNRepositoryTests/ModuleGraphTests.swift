// SPFN Mobile — repository invariants, asserted from inside the Swift toolchain.
//
// `swift test` alone catches module-graph and lock drift even if the shell validator is
// never run. `tools/validate/validate.sh` checks the same invariants plus the Android,
// podspec, workflow and documentation surfaces.

import Foundation
import XCTest
import SPFNCore

enum RepoPaths
{
    static let root: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // Tests/SPFNRepositoryTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // repo root

    static func json(at relativePath: String) throws -> [String: Any]
    {
        let data = try Data(contentsOf: root.appendingPathComponent(relativePath))
        guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else
        {
            throw NSError(domain: "SPFNRepositoryTests", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "\(relativePath) is not a JSON object"])
        }
        return object
    }

    static func text(at relativePath: String) throws -> String
    {
        try String(contentsOf: root.appendingPathComponent(relativePath), encoding: .utf8)
    }

    static func bytes(at relativePath: String) throws -> [UInt8]
    {
        [UInt8](try Data(contentsOf: root.appendingPathComponent(relativePath)))
    }
}

final class ModuleGraphTests: XCTestCase
{
    private func modules() throws -> [[String: Any]]
    {
        guard let modules = try RepoPaths.json(at: "tools/module-graph.json")["modules"] as? [[String: Any]]
        else
        {
            throw NSError(domain: "SPFNRepositoryTests", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "module-graph.json has no modules array"])
        }
        return modules
    }

    func testEverySwiftTargetHasSourcesAndIsDeclared() throws
    {
        let manifest = try RepoPaths.text(at: "Package.swift")
        for module in try modules()
        {
            let target = try XCTUnwrap(module["swiftTarget"] as? String)
            var isDirectory: ObjCBool = false
            let path = RepoPaths.root.appendingPathComponent("Sources/\(target)").path
            XCTAssertTrue(
                FileManager.default.fileExists(atPath: path, isDirectory: &isDirectory) && isDirectory.boolValue,
                "Sources/\(target) is missing"
            )
            XCTAssertTrue(manifest.contains("\"\(target)\""), "\(target) is not declared in Package.swift")
        }
    }

    func testSwiftDependencyEdgesMatchTheManifest() throws
    {
        let manifest = try RepoPaths.text(at: "Package.swift")
        for module in try modules()
        {
            let target = try XCTUnwrap(module["swiftTarget"] as? String)
            let dependencies = try XCTUnwrap(module["swiftDependsOn"] as? [String])
            guard !dependencies.isEmpty
            else
            {
                continue
            }
            let rendered = dependencies.map { "\"\($0)\"" }.joined(separator: ", ")
            XCTAssertTrue(
                manifest.contains(".target(name: \"\(target)\", dependencies: [\(rendered)])"),
                "Package.swift dependency edge for \(target) does not match module-graph.json"
            )
        }
    }

    func testVersionIsConsistentAcrossManifests() throws
    {
        let version = try RepoPaths.text(at: "VERSION").trimmingCharacters(in: .whitespacesAndNewlines)
        XCTAssertEqual(SPFNVersion.current, version)
        XCTAssertTrue(
            try RepoPaths.text(at: "gradle.properties").contains("spfn.version=\(version)"),
            "gradle.properties version drifted from VERSION"
        )
    }

    func testToolchainBaselineIsDeclaredInTheManifest() throws
    {
        let manifest = try RepoPaths.text(at: "Package.swift")
        XCTAssertTrue(manifest.contains("swift-tools-version: 6.0"), "D5 fixes swift-tools-version at 6.0")
        XCTAssertTrue(manifest.contains(".iOS(.v16)"), "D5 fixes the iOS baseline at 16")
        XCTAssertTrue(manifest.contains(".macOS(.v13)"), "D5 fixes the macOS baseline at 13")
    }
}

final class ContractLockTests: XCTestCase
{
    private func lock() throws -> [String: Any]
    {
        try RepoPaths.json(at: "Contracts/upstream.lock.json")
    }

    func testLockIsPinnedToARealDigest() throws
    {
        let lock = try lock()
        XCTAssertEqual(lock["status"] as? String, "RESOLVED_UPSTREAM")

        let contract = try XCTUnwrap(lock["contract"] as? [String: Any])
        let digest = try XCTUnwrap(contract["manifestSha256"] as? String)
        XCTAssertEqual(digest.count, 64)
        XCTAssertTrue(
            digest.allSatisfy { $0.isHexDigit && !$0.isUppercase },
            "manifestSha256 must be 64 lowercase hex characters"
        )

        let bundlePath = try XCTUnwrap(contract["bundlePath"] as? String)
        XCTAssertEqual(
            SPFNDigest.sha256Hex(try RepoPaths.bytes(at: bundlePath)),
            digest,
            "the lock digest does not match the bundle it points at"
        )
    }

    /// An upstream claim now has to be true rather than absent. The evidence file is
    /// copied unmodified from the same upstream commit, so the lock is checked against
    /// something the exporter wrote instead of against itself.
    func testProvenanceClaimIsBackedByUpstreamEvidence() throws
    {
        let provenance = try XCTUnwrap(try lock()["provenance"] as? [String: Any])
        XCTAssertEqual(provenance["origin"] as? String, "spfn-primitives-ci-export")
        XCTAssertEqual(provenance["exportedByUpstreamCI"] as? Bool, true)

        let evidence = try RepoPaths.json(at: "Contracts/upstream-provenance.json")
        XCTAssertEqual(evidence["origin"] as? String, "spfn-primitives-ci-export")
        XCTAssertEqual(
            evidence["exportedByUpstreamCI"] as? Bool, true,
            "the lock may claim an upstream export only when the exporter's own evidence says so"
        )

        let contract = try XCTUnwrap(try lock()["contract"] as? [String: Any])
        let evidenceContract = try XCTUnwrap(evidence["contract"] as? [String: Any])
        XCTAssertEqual(
            contract["manifestSha256"] as? String,
            evidenceContract["bundleSha256"] as? String,
            "the lock pins a digest the upstream evidence does not record"
        )

        let source = try XCTUnwrap(try lock()["source"] as? [String: Any])
        let commit = try XCTUnwrap(source["commit"] as? String)
        XCTAssertEqual(commit.count, 40)
        XCTAssertTrue(
            commit.allSatisfy { $0.isHexDigit && !$0.isUppercase },
            "an upstream pin names an exact commit, never a branch or a tag"
        )
        XCTAssertFalse(
            (try XCTUnwrap(source["repository"] as? String)).contains("spfn-mobile"),
            "a bundle this repository wrote is not an upstream export"
        )
    }

    /// Below 1.0.0 the breaking axis is the minor, so the range the lock prints must be
    /// bounded by the next minor. A range bounded by the next major would say the SDK
    /// supports contracts it has never seen.
    func testPreStableLockRangeIsBoundedByTheNextMinor() throws
    {
        let contract = try XCTUnwrap(try lock()["contract"] as? [String: Any])
        let major = try XCTUnwrap(contract["major"] as? Int)
        let minor = try XCTUnwrap(contract["minor"] as? Int)
        let version = try XCTUnwrap(contract["version"] as? String)

        XCTAssertTrue(version.hasPrefix("\(major).\(minor)."))
        if major == 0
        {
            XCTAssertEqual(contract["supportedRange"] as? String, ">=\(version) <0.\(minor + 1).0")
        }
        else
        {
            XCTAssertEqual(contract["supportedRange"] as? String, ">=\(version) <\(major + 1).0.0")
        }
    }

    func testLockAllowlistMatchesTheSwiftAllowlist() throws
    {
        let profiles = try XCTUnwrap(try lock()["authProfiles"] as? [String: Any])
        XCTAssertEqual(profiles["allowed"] as? [String], ["clientProofV1"])
        XCTAssertEqual(profiles["unknownProfilePolicy"] as? String, "reject")
    }

    func testFixtureManifestMatchesTheFilesOnDisk() throws
    {
        let manifest = try RepoPaths.json(at: "Contracts/fixtures/MANIFEST.json")
        let fixtures = try XCTUnwrap(manifest["fixtures"] as? [[String: Any]])
        XCTAssertEqual(manifest["fixtureCount"] as? Int, fixtures.count)
        XCTAssertFalse(fixtures.isEmpty, "a resolved contract must carry conformance vectors")

        for fixture in fixtures
        {
            let path = try XCTUnwrap(fixture["path"] as? String)
            XCTAssertEqual(
                SPFNDigest.sha256Hex(try RepoPaths.bytes(at: path)),
                fixture["sha256"] as? String,
                "\(path) drifted from the digest recorded in MANIFEST.json"
            )
        }
    }
}
