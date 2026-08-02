import java.io.File

// SPFN Mobile — local reference server.
//
// A test fixture, never an SDK module and never published. It implements the pinned
// contract bundle so both SDKs can be driven over real HTTP on a developer machine,
// which is the one thing fixtures and stand-in transports cannot prove.
//
// Zero external dependencies in `main`: the HTTP layer is `com.sun.net.httpserver`,
// which the JDK 21 toolchain D5 already requires ships. Adding a server framework here
// would mean adding checksums to gradle/verification-metadata.xml for something that
// never reaches a consumer.
//
// The verification primitives are NOT reimplemented. The canonical serializer, the
// digests, the proof input and the generated contract listing are compiled straight out
// of the Android modules' source directories. An Android library cannot be a dependency
// of a JVM module, and a second copy of SPFN-CANON-JSON-1 would make the round trip
// prove that two copies agree rather than that the contract holds. Independence is
// already supplied from outside: Contracts/fixtures/derive-expected-values.py is a third
// implementation, and the Swift suite crosses the same wire from a different codebase.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Local reference server implementing the pinned SPFN contract, plus the two-platform integration suites."

kotlin {
    jvmToolchain(libs.versions.jdk.toolchain.get().toInt())
}

sourceSets {
    main {
        // Verification primitives and the generated contract listing, shared by source.
        kotlin.srcDir("../../android/spfn-core/src/main/kotlin")
        kotlin.srcDir("../../android/spfn-generated/src/main/kotlin")
        kotlin.srcDir("../../android/spfn-auth/src/main/kotlin")
    }
    test {
        // The integration suite drives the shipped client, so it compiles the shipped
        // client rather than a copy of it. Test-only: the server itself never sees it.
        kotlin.srcDir("../../android/spfn-client/src/main/kotlin")
    }
}

dependencies {
    testImplementation(libs.junit)
    // Required by the client sources above, not by the server.
    testImplementation(libs.okhttp)
    testImplementation(libs.kotlinx.coroutines.core)
}

/// Where a test records that it actually ran. `sh tools/reference-server/run-integration.sh`
/// passes the directory it also checks afterwards, so a suite that skipped every case
/// cannot be read as a suite that passed. Read as a Gradle property rather than an
/// environment variable: a long-lived daemon carries the environment it was started
/// with, and a stale value there would defeat the whole point of the receipt.
val integrationReceipts: Provider<String> =
    providers.gradleProperty("spfn.integrationReceipts")
        .orElse(layout.buildDirectory.dir("integration-receipts").map { it.asFile.absolutePath })

/// Where the integration suite's server is, when it is not the one the suite starts.
///
/// Gradle properties for the same reason as the receipts directory: a long-lived daemon
/// carries the environment it was started with, and a stale target there would point a run
/// at a server nobody meant. Only `spfnIntegrationTest` receives them — the unit gate runs
/// in process always, and a target leaking into it would make `./gradlew build` depend on
/// something outside the repository.
val integrationTargetUrl: Provider<String> = providers.gradleProperty("spfn.integrationTargetUrl")
val integrationLaunchFile: Provider<String> = providers.gradleProperty("spfn.integrationLaunchFile")
val integrationControlToken: Provider<String> = providers.gradleProperty("spfn.integrationControlToken")

/// The default gate. Integration cases are excluded: they bind a socket and are run by
/// `spfnIntegrationTest`, so `./gradlew build` stays a unit gate.
tasks.named<Test>("test") {
    exclude("**/*IntegrationTest.class")
    systemProperty("spfn.integrationReceipts", integrationReceipts.get())
    systemProperty("spfn.repoRoot", rootDir.absolutePath)
}

/// Everything the default gate excludes. Never up to date: each run has to prove the
/// exchange still happens, and a cached "success" from a previous run proves nothing.
tasks.register<Test>("spfnIntegrationTest") {
    group = "verification"
    description = "Drives the Android SDK over real HTTP, against the local reference server or a named external one."

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/*IntegrationTest.class")

    systemProperty("spfn.integrationReceipts", integrationReceipts.get())
    systemProperty("spfn.repoRoot", rootDir.absolutePath)
    integrationTargetUrl.orNull?.let { systemProperty("spfn.integrationTargetUrl", it) }
    integrationLaunchFile.orNull?.let { systemProperty("spfn.integrationLaunchFile", it) }
    integrationControlToken.orNull?.let { systemProperty("spfn.integrationControlToken", it) }
    outputs.upToDateWhen { false }

    // A suite that matched nothing is a suite that proved nothing.
    filter { isFailOnNoMatchingTests = true }
}

/// Writes the runtime classpath the integration runner launches the server with.
///
/// The runner starts a plain `java` process rather than a Gradle `JavaExec`: it needs
/// the server's own PID so a trap can kill it, and a forked JavaExec leaves a JVM behind
/// when the Gradle client it was started from goes away.
val launchClasspath: Provider<String> =
    sourceSets["main"].runtimeClasspath.elements.map { elements ->
        elements.joinToString(File.pathSeparator) { it.asFile.absolutePath }
    }

tasks.register("spfnReferenceServerLaunchInfo") {
    group = "build"
    description = "Writes the runtime classpath tools/reference-server/run-integration.sh launches the server with."

    val destination = layout.buildDirectory.file("reference-server-launch.txt")
    val classpath = launchClasspath

    inputs.files(sourceSets["main"].runtimeClasspath)
    outputs.file(destination)

    doLast {
        destination.get().asFile.writeText(classpath.get() + "\n")
    }
}
