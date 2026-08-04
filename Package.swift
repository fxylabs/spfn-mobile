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
//   - platforms iOS 16 / macOS 13.
//
// That baseline says what this package COMPILES against. It is not a support
// commitment: COMPATIBILITY.md rows stay UNRESOLVED until real-device evidence exists.
//
// One package dependency exists, and only behind a trait. `SPFNSocialGoogle` needs
// Google's own sign-in SDK to obtain a provider token on the device; a consumer that
// does not enable the `SocialGoogle` trait never resolves it, never checks it out and
// never links it. Everything else still comes from the platform: cryptography is
// CryptoKit and the Apple adapter is the OS's own AuthenticationServices.
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
        .iOS(.v16),
        .macOS(.v13),
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
    ],
    targets: [
        .target(name: "SPFNCore"),
        .target(name: "SPFNGenerated", dependencies: ["SPFNCore"]),
        .target(name: "SPFNAuth", dependencies: ["SPFNCore"]),
        .target(name: "SPFNClient", dependencies: ["SPFNCore", "SPFNAuth", "SPFNGenerated"]),
        .target(name: "SPFNSocialApple", dependencies: ["SPFNClient"]),
        .target(name: "SPFNSocialGoogle", dependencies: ["SPFNClient", googleSignIn]),

        .testTarget(name: "SPFNCoreTests", dependencies: ["SPFNCore"]),
        .testTarget(name: "SPFNAuthTests", dependencies: ["SPFNAuth", "SPFNCore"]),
        .testTarget(
            name: "SPFNClientTests",
            dependencies: ["SPFNClient", "SPFNCore", "SPFNAuth", "SPFNGenerated"]
        ),
        .testTarget(name: "SPFNSocialAppleTests", dependencies: ["SPFNSocialApple", "SPFNClient"]),
        .testTarget(name: "SPFNSocialGoogleTests", dependencies: ["SPFNSocialGoogle", "SPFNClient"]),
        .testTarget(
            name: "SPFNRepositoryTests",
            dependencies: ["SPFNCore", "SPFNGenerated", "SPFNAuth", "SPFNClient"]
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
