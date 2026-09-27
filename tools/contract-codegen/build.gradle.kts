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


// ---- an app's own contract document ------------------------------------------------------
//
// docs/architecture/app-contract-codegen.md. A consumer runs these from its own build with
// every property below; a missing one fails by name rather than falling back to anything,
// because a misspelt property that silently verified the fixture would read as green.

val appContractProperties = listOf(
    "document", "operations", "swiftOut", "swiftNamespace", "kotlinOut", "kotlinPackage"
)

fun appContractArguments(mode: String): CommandLineArgumentProvider = CommandLineArgumentProvider {
    val missing = appContractProperties.filter { providers.gradleProperty("spfn.appContract.$it").orNull.isNullOrBlank() }
    if (missing.isNotEmpty())
    {
        throw GradleException("missing ${missing.joinToString(", ") { "-Pspfn.appContract.$it" }}")
    }
    val value = { name: String -> providers.gradleProperty("spfn.appContract.$name").get() }
    listOf(
        "--mode", mode,
        "--document", value("document"),
        "--operations", value("operations"),
        "--swift-out", value("swiftOut"),
        "--swift-namespace", value("swiftNamespace"),
        "--kotlin-out", value("kotlinOut"),
        "--kotlin-package", value("kotlinPackage"),
        "--sdk-root", rootDir.absolutePath
    )
}

tasks.register<JavaExec>("spfnAppContractGenerate") {
    group = "build"
    description = "Generates Swift and Kotlin calls from an app contract document (-Pspfn.appContract.*)."
    mainClass.set("xyz.superfunction.spfn.codegen.AppContractMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    argumentProviders.add(appContractArguments("write"))
}

tasks.register<JavaExec>("spfnAppContractVerify") {
    group = "verification"
    description = "Fails if an app's generated calls are stale against its contract document or were edited."
    mainClass.set("xyz.superfunction.spfn.codegen.AppContractMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    argumentProviders.add(appContractArguments("verify"))
}

// The invented fixture document, generated into the two SDK test source sets that decode
// real wire bytes with it: android/spfn-core/src/test (JUnit) and Tests/SPFNCoreTests (XCTest).
val appContractFixture = layout.projectDirectory.dir("src/test/resources/app-contract")

fun appContractFixtureArguments(mode: String): List<String> = listOf(
    "--mode", mode,
    "--document", appContractFixture.file("contract.json").asFile.absolutePath,
    "--operations", appContractFixture.file("operations.json").asFile.absolutePath,
    "--swift-out", rootDir.resolve("Tests/SPFNCoreTests/AppContractFixture").absolutePath,
    "--swift-namespace", "FixtureAPI",
    "--kotlin-out", rootDir.resolve("android/spfn-core/src/test/kotlin/xyz/superfunction/spfn/core/appcontract").absolutePath,
    "--kotlin-package", "xyz.superfunction.spfn.core.appcontract",
    "--sdk-root", rootDir.absolutePath
)

tasks.register<JavaExec>("spfnAppContractFixtureGenerate") {
    group = "build"
    description = "Regenerates the app-contract fixture clients used by the SDK's own tests."
    mainClass.set("xyz.superfunction.spfn.codegen.AppContractMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(appContractFixtureArguments("write"))
}

tasks.register<JavaExec>("spfnAppContractFixtureVerify") {
    group = "verification"
    description = "Fails if the app-contract fixture clients differ from a fresh generation."
    mainClass.set("xyz.superfunction.spfn.codegen.AppContractMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args(appContractFixtureArguments("verify"))
}

tasks.named("check") {
    dependsOn("spfnAppContractFixtureVerify")
}
