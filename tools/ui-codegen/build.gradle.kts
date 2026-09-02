// SPFN Mobile — screen-spec generator.
//
// A build tool, never an SDK module and never published, for the same reason
// :contract-codegen is one: it lives inside the JDK/Gradle toolchain Android already
// requires, so the repository does not acquire a second toolchain
// (docs/architecture/README.md).
//
// Zero external dependencies and zero network access at generation time. Its two inputs
// are both on disk: the vendored contract bundle, read through :contract-codegen's own
// reader rather than through a second copy of it, and the screen spec.

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

/// Regenerates both example apps' scaffolds, the case table and the Maestro flows from
/// the one spec. Deterministic: running it twice produces byte-identical files, which
/// `./gradlew :ui-codegen:spfnUiVerify` proves.
tasks.register<JavaExec>("spfnGenerateUi") {
    group = "build"
    description = "Generates the example apps' screen scaffolds, case table and flows from the screen spec."
    mainClass.set("xyz.superfunction.spfn.uicodegen.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(rootDir.absolutePath, "examples/ui-spec/device-approval.json", "write")
}

/// Generates in memory and compares against the checked-in output, so a hand-edited
/// generated file, a drifted bundle or a stale leftover fails instead of being trusted.
/// It covers the flows and the case table as well as the sources: the case table is the
/// shared artefact both runners read, and an unverified one is not evidence.
tasks.register<JavaExec>("spfnUiVerify") {
    group = "verification"
    description = "Fails if the checked-in scaffolds, case table or flows differ from a fresh generation."
    mainClass.set("xyz.superfunction.spfn.uicodegen.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(rootDir.absolutePath, "examples/ui-spec/device-approval.json", "verify")
}

tasks.named("check") {
    dependsOn("spfnUiVerify")
}
