// SPFN Mobile — contract generator.
//
// A build tool, never an SDK module and never published. It lives inside the
// JDK/Gradle toolchain Android already requires so the repository does not acquire a
// second toolchain (docs/architecture/README.md).
//
// Zero external dependencies and zero network access at generation time: the input is
// the vendored bundle on disk, and the JSON reader is hand-written for that reason.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Deterministic Swift/Kotlin client generator for the pinned SPFN contract bundle."

kotlin {
    jvmToolchain(libs.versions.jdk.toolchain.get().toInt())
}

dependencies {
    testImplementation(libs.junit)
}

/// Regenerates both client source sets from the pinned bundle. Deterministic: running
/// it twice produces byte-identical files, which `./gradlew spfnCodegenVerify` proves.
val generate = tasks.register<JavaExec>("spfnGenerateClients") {
    group = "build"
    description = "Generates Swift and Kotlin clients from the pinned contract bundle."
    mainClass.set("xyz.superfunction.spfn.codegen.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(rootDir.absolutePath, "write")
}

/// Generates into a scratch directory and diffs against the checked-in output, so a
/// hand-edited generated file or a drifted bundle fails instead of being trusted.
tasks.register<JavaExec>("spfnCodegenVerify") {
    group = "verification"
    description = "Fails if the checked-in generated sources differ from a fresh generation."
    mainClass.set("xyz.superfunction.spfn.codegen.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(rootDir.absolutePath, "verify")
}

tasks.named("check") {
    dependsOn("spfnCodegenVerify")
}

