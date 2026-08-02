// swift-tools-version: 6.0
//
// SPFN Mobile — SwiftPM manifest.
//
// The module graph is the one approved in
// `2026-07-27-spfn-mobile-sdk-repository-topology-decision.html` §3 and mirrored by
// `tools/module-graph.json`, which `tools/validate/validate.sh` cross-checks.
//
// Decision D5 (confirmed 2026-08-01) fixed the toolchain baseline:
//   - swift-tools-version 6.0, so Swift 6 language mode and strict concurrency are on
//     by default for every target here.
//   - platforms iOS 16 / macOS 13.
//
// That baseline says what this package COMPILES against. It is not a support
// commitment: COMPATIBILITY.md rows stay UNRESOLVED until real-device evidence exists.
//
// Still zero package dependencies. Cryptography comes from CryptoKit, which the
// declared platforms already ship.

import PackageDescription

let package = Package(
    name: "SPFNMobile",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "SPFNCore", targets: ["SPFNCore"]),
        .library(name: "SPFNGenerated", targets: ["SPFNGenerated"]),
        .library(name: "SPFNAuth", targets: ["SPFNAuth"]),
        .library(name: "SPFNClient", targets: ["SPFNClient"]),
        .library(name: "SPFNPersistence", targets: ["SPFNPersistence"]),
        .library(name: "SPFNHybrid", targets: ["SPFNHybrid"]),
    ],
    targets: [
        .target(name: "SPFNCore"),
        .target(name: "SPFNGenerated", dependencies: ["SPFNCore"]),
        .target(name: "SPFNAuth", dependencies: ["SPFNCore"]),
        .target(name: "SPFNClient", dependencies: ["SPFNCore", "SPFNAuth", "SPFNGenerated"]),
        .target(name: "SPFNPersistence", dependencies: ["SPFNCore"]),
        .target(name: "SPFNHybrid", dependencies: ["SPFNCore", "SPFNAuth"]),

        .testTarget(name: "SPFNCoreTests", dependencies: ["SPFNCore"]),
        .testTarget(name: "SPFNAuthTests", dependencies: ["SPFNAuth", "SPFNCore"]),
        .testTarget(
            name: "SPFNClientTests",
            dependencies: ["SPFNClient", "SPFNCore", "SPFNAuth", "SPFNGenerated"]
        ),
        .testTarget(
            name: "SPFNRepositoryTests",
            dependencies: ["SPFNCore", "SPFNGenerated", "SPFNAuth", "SPFNClient", "SPFNPersistence", "SPFNHybrid"]
        ),
        .testTarget(
            name: "SPFNConformanceTests",
            dependencies: ["SPFNCore", "SPFNGenerated", "SPFNAuth"]
        ),

        // Skips itself unless SPFN_REFERENCE_SERVER_URL names a running
        // tools/reference-server. `sh tools/reference-server/run-integration.sh` starts
        // one, exports the variable and fails the run when the suite left no receipt
        // behind — because a skipped XCTest is reported as a passing XCTest.
        .testTarget(
            name: "SPFNIntegrationTests",
            dependencies: ["SPFNClient", "SPFNCore", "SPFNAuth", "SPFNGenerated"]
        ),
    ]
)
