// SPFN Mobile — screen-spec generator.
//
// A build tool, never an SDK module and never published, for the same reason
// :contract-codegen is one: it lives inside the JDK/Gradle toolchain Android already
// requires, so the repository does not acquire a second toolchain
// (docs/architecture/README.md).
//
// Zero external dependencies and zero network access at generation time. Its inputs are
// all on disk: the vendored contract bundle, read through :contract-codegen's own reader
// rather than through a second copy of it, the screen spec, the spec's repository-relative
// path — which every generated header prints — and the lock's contract block, which
// chooses the bundle file and refuses a run whose digest disagrees with it.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Deterministic Swift/Kotlin screen scaffold generator for the SPFN UI screen spec."

kotlin {
    jvmToolchain(libs.versions.jdk.toolchain.get().toInt())
}

dependencies {
    // The bundle and JSON readers, and the descriptor naming function. Depended on
    // rather than copied: an operation name this generator accepts has to be exactly a
    // name the contract generator emits, and two copies of that rule would drift.
    implementation(project(":contract-codegen"))
    testImplementation(libs.junit)
}

/// The two consumers of the one screen spec, as the arguments the generator reads them
/// from. Everything an app-specific value used to be a constant for lives here: nothing
/// under `src/main` names an application any more.
///
/// The example target is the only one that emits the case table and the Maestro flows.
/// Those artefacts name cells and FIXTURES, and the example app is the only app that
/// installs them; the harness drives the same screens against a real server through its
/// own flows, so a second copy of the table there would claim coverage nothing provides
/// (decision E6, E7).
val exampleTarget = listOf(
    "--target=example",
    "--swift-root=examples/ios-swiftui/Generated",
    "--kotlin-root=examples/android-compose/src/main/kotlin/xyz/superfunction/spfn/example/generated",
    "--kotlin-package=xyz.superfunction.spfn.example.generated",
    "--app-id=xyz.superfunction.spfn.example",
    "--table-root=examples/ui-spec/generated",
    "--generate-task=:ui-codegen:spfnGenerateUi",
    "--verify-task=:ui-codegen:spfnUiVerify"
)

/// The harness's two apps. The Swift root is `GeneratedUI/` and not `Generated/`, which
/// is XcodeGen's: that directory holds the harness's Info.plist and its entitlements, and
/// this generator DELETES every file under a directory it owns that it did not emit.
val harnessTarget = listOf(
    "--target=harness",
    "--swift-root=tools/harness/ios/GeneratedUI",
    "--kotlin-root=tools/harness/android/src/main/kotlin/xyz/superfunction/spfn/harness/generated",
    "--kotlin-package=xyz.superfunction.spfn.harness.generated",
    "--app-id=xyz.superfunction.spfn.harness",
    "--generate-task=:ui-codegen:spfnGenerateHarnessUi",
    "--verify-task=:ui-codegen:spfnHarnessUiVerify"
)

val screenSpec = "examples/ui-spec/device-approval.json"

/// Regenerates both example apps' scaffolds, the case table and the Maestro flows from
/// the one spec. Deterministic: running it twice produces byte-identical files, which
/// `./gradlew :ui-codegen:spfnUiVerify` proves.
tasks.register<JavaExec>("spfnGenerateUi") {
    group = "build"
    description = "Generates the example apps' screen scaffolds, case table and flows from the screen spec."
    mainClass.set("xyz.superfunction.spfn.uicodegen.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(listOf(rootDir.absolutePath, screenSpec, "write") + exampleTarget)
}

/// The same spec into the harness apps, which are the second consumer of these screens:
/// the harness drives them against a real reference server rather than against a fixture.
tasks.register<JavaExec>("spfnGenerateHarnessUi") {
    group = "build"
    description = "Generates the harness apps' screen scaffolds from the screen spec."
    mainClass.set("xyz.superfunction.spfn.uicodegen.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(listOf(rootDir.absolutePath, screenSpec, "write") + harnessTarget)
}

/// Generates in memory and compares against the checked-in output, so a hand-edited
/// generated file, a drifted bundle or a stale leftover fails instead of being trusted.
/// It covers the flows and the case table as well as the sources: the case table is the
/// shared artefact both runners read, and an unverified one is not evidence.
tasks.register<JavaExec>("spfnUiVerify") {
    group = "verification"
    description = "Fails if the checked-in example scaffolds, case table or flows differ from a fresh generation."
    mainClass.set("xyz.superfunction.spfn.uicodegen.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(listOf(rootDir.absolutePath, screenSpec, "verify") + exampleTarget)
}

/// The same gate for the second consumer. A separate task rather than a second argument
/// list inside the first, so a failure names WHICH app's scaffold drifted: a run that
/// verified both under one name would report the harness's staleness as the example's.
tasks.register<JavaExec>("spfnHarnessUiVerify") {
    group = "verification"
    description = "Fails if the checked-in harness scaffolds differ from a fresh generation."
    mainClass.set("xyz.superfunction.spfn.uicodegen.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(listOf(rootDir.absolutePath, screenSpec, "verify") + harnessTarget)
}

tasks.named("check") {
    dependsOn("spfnUiVerify", "spfnHarnessUiVerify")
}
