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
            // The graph's edges are the LEADING dependencies of the target, in order.
            // A target may carry more — a trait-gated external product is declared on
            // the same line — and those are held to `externalDeps` instead, by the
            // shell validator's section 7 and by the manifest itself.
            let rendered = dependencies.map { "\"\($0)\"" }.joined(separator: ", ")
            XCTAssertTrue(
                manifest.contains(".target(name: \"\(target)\", dependencies: [\(rendered)"),
                "Package.swift dependency edge for \(target) does not match module-graph.json"
            )
        }
    }

    /// The graph is also the allowlist for what the manifest may pull in from outside.
    /// Both directions are checked: a trait or an external package the graph declares
    /// must appear in the manifest, and a package the manifest declares must be named
    /// by some module's `externalDeps`.
    func testDeclaredTraitsAndExternalPackagesMatchTheManifest() throws
    {
        let manifest = try RepoPaths.text(at: "Package.swift")
        var declaredPackages: Set<String> = []

        for module in try modules()
        {
            let external = try XCTUnwrap(module["externalDeps"] as? [String: Any])
            let swiftPackages = try XCTUnwrap(external["swift"] as? [String])
            declaredPackages.formUnion(swiftPackages)

            if let trait = module["swiftTrait"] as? String
            {
                XCTAssertTrue(
                    manifest.contains(".trait(name: \"\(trait)\""),
                    "Package.swift declares no trait named \(trait)"
                )
            }
            for package in swiftPackages
            {
                XCTAssertTrue(manifest.contains("/\(package)\""), "Package.swift declares no dependency on \(package)")
            }
        }

        let manifestPackages = manifest
            .split(separator: "\n")
            .filter { $0.contains(".package(url:") }
        XCTAssertEqual(
            manifestPackages.count, declaredPackages.count,
            "Package.swift declares \(manifestPackages.count) external packages; the graph allows \(declaredPackages.count)"
        )
        for line in manifestPackages
        {
            XCTAssertTrue(
                declaredPackages.contains { line.contains("/\($0)\"") },
                "Package.swift declares an external package the module graph does not allow: \(line)"
            )
        }

        // Traits carry no external dependency of their own, so the default trait set
        // has to stay empty: a default-enabled trait would resolve Google's SDK for a
        // consumer that never asked for it.
        XCTAssertTrue(manifest.contains(".default(enabledTraits: [])"), "the default trait set must stay empty")
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
        // D5 revision 3b moved the floor from 6.0 to 6.1: the provider adapters select
        // their external dependency with package traits, which 6.0 has no notion of.
        XCTAssertTrue(manifest.contains("swift-tools-version: 6.1"), "D5 fixes swift-tools-version at 6.1")
        // D5 revision, approved 2026-09-02: the platform floor moved to iOS 17 / macOS 14.
        // 16 reached its last security update (16.7.16) in 2026-05 and no COMPATIBILITY.md
        // row ever promised it.
        XCTAssertTrue(manifest.contains(".iOS(.v17)"), "D5 fixes the iOS baseline at 17")
        XCTAssertTrue(manifest.contains(".macOS(.v14)"), "D5 fixes the macOS baseline at 14")
    }
}

/// Two files, two questions. `upstream.lock.json` answers what only this repository can
/// know — which primitives commit was read, and where the vendored copy sits in this
/// tree. `upstream-provenance.json` answers what the contract IS, in the exporter's own
/// words, copied here unmodified. Since lockVersion 3 neither restates the other, so
/// there is no pair of copies to hold equal and no way for them to drift apart.
final class ContractLockTests: XCTestCase
{
    private func lock() throws -> [String: Any]
    {
        try RepoPaths.json(at: "Contracts/upstream.lock.json")
    }

    private func evidence() throws -> [String: Any]
    {
        try RepoPaths.json(at: "Contracts/upstream-provenance.json")
    }

    func testThePinnedBundleHashesToTheDigestTheEvidenceRecords() throws
    {
        XCTAssertEqual(try lock()["status"] as? String, "RESOLVED_UPSTREAM")

        let contract = try XCTUnwrap(try evidence()["contract"] as? [String: Any])
        let digest = try XCTUnwrap(contract["bundleSha256"] as? String)
        XCTAssertEqual(digest.count, 64)
        XCTAssertTrue(
            digest.allSatisfy { $0.isHexDigit && !$0.isUppercase },
            "bundleSha256 must be 64 lowercase hex characters"
        )

        let bundlePath = try XCTUnwrap(
            (try lock()["contract"] as? [String: Any])?["bundlePath"] as? String
        )
        XCTAssertEqual(
            SPFNDigest.sha256Hex(try RepoPaths.bytes(at: bundlePath)),
            digest,
            "the file the lock points at is not the one the evidence describes"
        )
    }

    /// An upstream claim has to be true rather than absent. The evidence file is copied
    /// unmodified from the same upstream commit — which is why it still carries the
    /// exporter's `RECORDED_BY_CONSUMER` placeholder, a file being unable to state the
    /// commit it was read at. The commit itself is the lock's to record.
    func testProvenanceClaimIsBackedByUpstreamEvidence() throws
    {
        let provenance = try XCTUnwrap(try lock()["provenance"] as? [String: Any])
        XCTAssertEqual(provenance["origin"] as? String, "spfn-primitives-ci-export")
        XCTAssertEqual(provenance["exportedByUpstreamCI"] as? Bool, true)

        let record = try evidence()
        XCTAssertEqual(record["origin"] as? String, "spfn-primitives-ci-export")
        XCTAssertEqual(
            record["exportedByUpstreamCI"] as? Bool, true,
            "the lock may claim an upstream export only when the exporter's own evidence says so"
        )

        let evidenceSource = try XCTUnwrap(record["source"] as? [String: Any])
        XCTAssertEqual(
            evidenceSource["commit"] as? String, "RECORDED_BY_CONSUMER",
            "the evidence was edited on the way here; it must be the exporter's file verbatim"
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

    /// Below 1.0.0 the breaking axis is the minor, so the range the evidence declares
    /// must be bounded by the next minor. A range bounded by the next major would say
    /// the SDK supports contracts it has never seen.
    func testPreStableRangeIsBoundedByTheNextMinor() throws
    {
        let contract = try XCTUnwrap(try evidence()["contract"] as? [String: Any])
        let major = try XCTUnwrap(contract["major"] as? Int)
        let version = try XCTUnwrap(contract["version"] as? String)
        // Derived, not read: the evidence records the version and the major and stops
        // there, because a minor written beside a version is a second chance to be wrong
        // about the same number.
        let minor = try XCTUnwrap(Int(version.split(separator: ".").dropFirst().first ?? ""))

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

    /// A value with two homes can disagree with itself, so the lock stopped carrying a
    /// second copy of anything the evidence states. Named here so re-adding one fails
    /// the Swift suite as well as `tools/validate/validate.sh` section 5.
    func testTheLockRestatesNothingTheEvidenceOwns() throws
    {
        let contract = try XCTUnwrap(try lock()["contract"] as? [String: Any])
        for shed in ["version", "major", "minor", "manifestSha256", "supportedRange", "rangeRule"]
        {
            XCTAssertNil(
                contract[shed],
                "contract.\(shed) belongs to Contracts/upstream-provenance.json and nowhere else"
            )
        }
        XCTAssertNil(
            try lock()["authProfiles"],
            "the auth allowlist is an SDK policy; its home is SPFNAuthProfile, not the contract pin"
        )
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
