// swift-tools-version: 6.1
//
// SPFN Mobile — the harness's trait carrier, and nothing else.
//
// This package exists for one reason: a package trait can only be enabled by a manifest,
// and the harness app has none. XcodeGen will happily write `traits = (SocialApple,
// SocialGoogle)` into the generated project's `XCLocalSwiftPackageReference`, and Xcode
// 26.2 ignores it — a probe with those exact lines resolved no remote packages and could
// not see `SPFNGooglePresentingContext`, which lives inside `#if SocialGoogle`.
//
// So the trait is declared here, where SwiftPM reads it. One dependency, one target, one
// file of re-exports. Nothing in the SDK changed to allow this.
//
// `name: "SPFNMobile"` is not decoration. A path dependency's identity is its directory
// name, so `.product(package: "SPFNMobile")` would fail in any checkout not named
// `spfn-mobile` — a git worktree, for instance, whose directory is named after the
// branch. Naming the dependency fixes the identity to what the target lines below say.

import PackageDescription

let package = Package(
    name: "SPFNHarnessSupport",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "SPFNHarnessSupport", targets: ["SPFNHarnessSupport"]),
    ],
    dependencies: [
        .package(
            name: "SPFNMobile",
            path: "../../../..",
            traits: ["SocialApple", "SocialGoogle"]
        ),
    ],
    targets: [
        .target(
            name: "SPFNHarnessSupport",
            dependencies: [
                .product(name: "SPFNSocialApple", package: "SPFNMobile"),
                .product(name: "SPFNSocialGoogle", package: "SPFNMobile"),
            ]
        ),
    ]
)
