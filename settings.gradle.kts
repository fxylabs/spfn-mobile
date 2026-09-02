// SPFN Mobile — Step 2 build settings.
//
// Decision D5 (confirmed 2026-08-01) fixed the Android toolchain baseline, so the
// Step 1 omissions it justified are gone: repositories, an AGP/Kotlin plugin baseline
// and a checksum-pinned Gradle wrapper now exist.
//
// What has NOT changed:
//   - No publication target, coordinate, credential or signing identity is configured.
//   - Repository declarations are limited to the three sources needed to resolve the
//     approved toolchain. tools/validate/validate.sh fails on any other repository.
//   - gradle/verification-metadata.xml stays fail-closed: every artifact resolved here
//     carries a real, network-fetched SHA-256.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Resolves the JDK 21 toolchain D5 requires without assuming which JDKs happen to
    // be installed on the machine running the build.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "spfn-mobile"

include(
    ":spfn-core",
    ":spfn-generated",
    ":spfn-auth",
    ":spfn-client",
    ":spfn-ui",
    ":spfn-social-google",
    ":contract-codegen",
    ":ui-codegen",
    ":reference-server",
    ":harness-android",
    ":example-compose"
)

project(":spfn-core").projectDir = file("android/spfn-core")
project(":spfn-generated").projectDir = file("android/spfn-generated")
project(":spfn-auth").projectDir = file("android/spfn-auth")
project(":spfn-client").projectDir = file("android/spfn-client")
project(":spfn-ui").projectDir = file("android/spfn-ui")
project(":spfn-social-google").projectDir = file("android/spfn-social-google")

// Not an SDK module and never published. The contract generator is a build tool that
// lives inside the JDK/Gradle toolchain Android already requires, so the repository
// does not acquire a second toolchain (docs/architecture/README.md).
project(":contract-codegen").projectDir = file("tools/contract-codegen")

// Not an SDK module and never published either. The screen-spec generator turns one
// JSON file into the two example apps' scaffolds, the case table and the Maestro flows;
// it is a build tool beside the contract generator and shares its toolchain.
project(":ui-codegen").projectDir = file("tools/ui-codegen")

// Also not an SDK module and never published. The reference server implements the
// pinned contract so both SDKs can be exercised over real HTTP on a developer machine.
// It is a test fixture, not a deployment: nothing here is a real endpoint.
project(":reference-server").projectDir = file("tools/reference-server")

// Also not an SDK module and never published. The Maestro harness is an application that
// drives the SDK through a screen so a flow has something to tap; everything else this
// repository proves, it proves without one.
//
// It lives under tools/ rather than android/ because the validator counts the
// directories under android/ and the `file("android/…")` mappings above against
// tools/module-graph.json: an application there would read as an undeclared SDK module
// (decision 01kzb8tjxp, D-1).
project(":harness-android").projectDir = file("tools/harness/android")

// The Compose example application, and also never published. It is what the generated
// scaffolds compile into and what a Maestro cell taps, so it is an application module
// for the same reason the harness is one.
//
// It lives under examples/ rather than android/ for the reason the harness lives under
// tools/: the validator counts the directories under android/ and the `file("android/…")`
// mappings above against tools/module-graph.json, and an application there would read as
// an undeclared SDK module (decision 01kzb8tjxp, D-1). examples/android-compose was
// already the validator's declared home for this app.
project(":example-compose").projectDir = file("examples/android-compose")
