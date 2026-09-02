// swift-tools-version: 6.1
//
// SPFN Mobile — SwiftPM manifest.
//
// The module graph is the one approved in
// `2026-07-27-spfn-mobile-sdk-repository-topology-decision.html` §3 and mirrored by
// `tools/module-graph.json`, which `tools/validate/validate.sh` cross-checks.
//
// Decision D5 (confirmed 2026-08-01, revised 2026-08-04) fixed the toolchain baseline:
//   - swift-tools-version 6.1, so Swift 6 language mode and strict concurrency are on
//     by default for every target here, and package traits exist. 6.0 was the original
//     baseline; the provider adapters need traits, so the floor moved by decision
//     (D5 revision 3b) rather than by convenience.
//   - platforms iOS 17 / macOS 14.
//
// The platform floor moved on 2026-09-02 (D5 revision, approved that day). iOS 16's last
// security update was 16.7.16 in 2026-05, and the only devices that cannot go past it are
// the iPhone 8, 8 Plus and X; COMPATIBILITY.md never promised 16, so nothing is withdrawn
// by raising it. 17 is also what the `ui` module's Swift half is written against —
// `@Observable` is an iOS 17 macro — so the floor and the code now agree.
//
// That baseline says what this package COMPILES against. It is not a support
// commitment: COMPATIBILITY.md rows stay UNRESOLVED until real-device evidence exists.
//
// Two package dependencies exist, and neither is on the path an app on a declared
// platform takes.
//
// `GoogleSignIn-iOS` is behind a trait. `SPFNSocialGoogle` needs Google's own sign-in
// SDK to obtain a provider token on the device; a consumer that does not enable the
// `SocialGoogle` trait never resolves it, never checks it out and never links it.
//
// `swift-crypto` is behind a platform condition, and it exists so this package can be
// built and its suites run on Linux. Cryptography on iOS and macOS is CryptoKit, which
// the OS ships; Linux has no CryptoKit, and swift-crypto is Apple's own port of that
// same API, so the sources swap an import and nothing else. Every target edge to it
// carries `.when(platforms: [.linux])`, so an iOS or macOS build never links it — the
// product edge is written out at each of those targets rather than bound to a name,
// because the edge is the per-target evidence `tools/module-graph.json` lists in
// `externalDeps.swift` and `tools/validate/validate.sh` reads back off these lines.
// SwiftPM still RESOLVES a conditional dependency on every platform, so the lockfile
// names it on macOS as well; `Package.resolved` is untracked here, so nothing about
// that resolution is committed.
//
// The Apple adapter needs no package at all: it is the OS's own AuthenticationServices.
//
// `traits` MUST come after `products`. The Package initialiser's argument order is
// checked by the manifest compiler, and swapping the two fails the build with
// `argument 'products' must precede argument 'traits'`.

import PackageDescription

// Trait-gated on the target edge, which is what keeps the dependency out of a
// trait-off consumer's resolution entirely rather than merely out of its link line.
let googleSignIn = Target.Dependency.product(
    name: "GoogleSignIn",
    package: "GoogleSignIn-iOS",
    condition: .when(traits: ["SocialGoogle"])
)

let package = Package(
    name: "SPFNMobile",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        .library(name: "SPFNCore", targets: ["SPFNCore"]),
        .library(name: "SPFNGenerated", targets: ["SPFNGenerated"]),
        .library(name: "SPFNAuth", targets: ["SPFNAuth"]),
        .library(name: "SPFNClient", targets: ["SPFNClient"]),
        .library(name: "SPFNSocialApple", targets: ["SPFNSocialApple"]),
        .library(name: "SPFNSocialGoogle", targets: ["SPFNSocialGoogle"]),
    ],
    traits: [
        .trait(name: "SocialApple", description: "Sign in with Apple adapter"),
        .trait(name: "SocialGoogle", description: "Google Sign-In adapter"),
        .default(enabledTraits: []),
    ],
    dependencies: [
        .package(url: "https://github.com/google/GoogleSignIn-iOS", from: "9.2.0"),
        .package(url: "https://github.com/apple/swift-crypto", from: "4.0.0"),
    ],
    targets: [
        .target(name: "SPFNCore", dependencies: [.product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))]),
        .target(name: "SPFNGenerated", dependencies: ["SPFNCore"]),
        .target(name: "SPFNAuth", dependencies: ["SPFNCore", .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))]),
        .target(name: "SPFNClient", dependencies: ["SPFNCore", "SPFNAuth", "SPFNGenerated", .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))]),

        // The two provider adapters are Apple-only, which tools/module-graph.json
        // states as `"linux": false` on their rows. SwiftPM cannot condition a target
        // on a platform, so what makes that true in the build is in the sources: every
        // file of these four targets is guarded whole on the framework it is written
        // against, and each compiles to an empty module on Linux. The graph is the
        // source of truth; this comment only says where the mechanism lives.
        .target(name: "SPFNSocialApple", dependencies: ["SPFNClient"]),
        .target(name: "SPFNSocialGoogle", dependencies: ["SPFNClient", googleSignIn]),

        .testTarget(name: "SPFNCoreTests", dependencies: ["SPFNCore"]),
        .testTarget(
            name: "SPFNAuthTests",
            dependencies: ["SPFNAuth", "SPFNCore", .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))]
        ),
        .testTarget(
            name: "SPFNClientTests",
            dependencies: ["SPFNClient", "SPFNCore", "SPFNAuth", "SPFNGenerated", .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))]
        ),
        .testTarget(name: "SPFNSocialAppleTests", dependencies: ["SPFNSocialApple", "SPFNClient"]),
        .testTarget(name: "SPFNSocialGoogleTests", dependencies: ["SPFNSocialGoogle", "SPFNClient"]),
        .testTarget(
            name: "SPFNRepositoryTests",
            dependencies: ["SPFNCore", "SPFNGenerated", "SPFNAuth", "SPFNClient"]
        ),
        .testTarget(
            name: "SPFNConformanceTests",
            dependencies: ["SPFNCore", "SPFNGenerated", "SPFNAuth", .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))]
        ),

        // Skips itself unless SPFN_REFERENCE_SERVER_URL names a running
        // tools/reference-server. `sh tools/reference-server/run-integration.sh` starts
        // one, exports the variable and fails the run when the suite left no receipt
        // behind — because a skipped XCTest is reported as a passing XCTest.
        .testTarget(
            name: "SPFNIntegrationTests",
            dependencies: ["SPFNClient", "SPFNCore", "SPFNAuth", "SPFNGenerated"]
        ),

        // Skips itself unless SPFN_VERIFY_SERVER_URL names a running scaffolded SPFN
        // app — the published @spfn/auth on a real PostgreSQL, not the reference
        // server. `sh tools/verify-server/run.sh` starts one, exports the variables
        // and fails the run when a case left no receipt behind.
        .testTarget(
            name: "SPFNVerifyTests",
            dependencies: ["SPFNClient", "SPFNCore", "SPFNAuth", "SPFNGenerated"]
        ),
    ]
)
