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
        XCTAssertEqual(lock["status"] as? String, "RESOLVED_DEV_BUNDLE")

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

    func testProvenanceDoesNotClaimAnUpstreamExport() throws
    {
        let provenance = try XCTUnwrap(try lock()["provenance"] as? [String: Any])
        XCTAssertEqual(provenance["origin"] as? String, "spfn-mobile-step2-dev-bundle")
        XCTAssertEqual(
            provenance["exportedByUpstreamCI"] as? Bool, false,
            "no SPFN primitives export exists; claiming one is the specific failure this field prevents"
        )
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
