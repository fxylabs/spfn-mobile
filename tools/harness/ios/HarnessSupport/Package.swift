// swift-tools-version: 6.1
//
// SPFN Mobile — the harness's trait carrier, and the part of it that can be tested.
//
// It began as a trait carrier and it is still that first: a package trait can only be
// enabled by a manifest, and the harness app has none. XcodeGen will happily write
// `traits = (SocialApple, SocialGoogle)` into the generated project's
// `XCLocalSwiftPackageReference`, and Xcode 26.2 ignores it — a probe with those exact
// lines resolved no remote packages and could not see `SPFNGooglePresentingContext`,
// which lives inside `#if SocialGoogle`. So the trait is declared here, where SwiftPM
// reads it.
//
// It carries three of the harness's own types as well, for a reason that is not tidiness:
// an Xcode app target has no suite that `swift test` can run, and the receipt's file name
// and the server-commit filter are rules with expected values worth pinning. Moving them
// into a package makes them testable without inventing a second test runner. The app
// imports this module and uses them unchanged.
//
// `name: "SPFNMobile"` is not decoration. A path dependency's identity is its directory
// name, so `.product(package: "SPFNMobile")` would fail in any checkout not named
// `spfn-mobile` — a git worktree, for instance, whose directory is named after the
// branch. Naming the dependency fixes the identity to what the target lines below say.

import PackageDescription

let package = Package(
    name: "SPFNHarnessSupport",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
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
                .product(name: "SPFNCore", package: "SPFNMobile"),
                .product(name: "SPFNGenerated", package: "SPFNMobile"),
                .product(name: "SPFNSocialApple", package: "SPFNMobile"),
                .product(name: "SPFNSocialGoogle", package: "SPFNMobile"),
            ]
        ),
        .testTarget(
            name: "SPFNHarnessSupportTests",
            dependencies: ["SPFNHarnessSupport"]
        ),
    ]
)
