import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// SPFN Mobile — Android state client module.
//
// `@spfn/core/state/client` on the native SDK: the server's states, kept current over the
// app's one event stream (docs/architecture/state-client-design.md). The stream, `execute`
// and the key lifecycle are the client module's; this module writes the stream's connection
// query and nothing else of it.
//
// No toolkit: the Compose adapters (`rememberServerState`, `rememberConnectionStatus`) are
// in spfn-events, which depends on this module (§2-12). An app that only uses the state
// router links coroutines and the client module through here, never Compose.

plugins {
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android is an error.
    alias(libs.plugins.android.library)
}

description = "SPFN Android state client: the server's states over the app's event stream."

extra["spfnModuleDependsOn"] = listOf("spfn-client")
extra["spfnSwiftCounterpart"] = "SPFNState"

android {
    namespace = "xyz.superfunction.spfn.state"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }

    // AGP 9.2.1 defaults source/target compatibility to Java 11. D5 requires the AAR
    // bytecode target to be pinned rather than inherited, so the default is restated.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // The case table is Contracts/fixtures/state/cases.json, which the Swift suite reads too.
    testOptions {
        unitTests.all {
            it.systemProperty("spfn.repoRoot", rootDir.absolutePath)
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.toolchain.get().toInt())

    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        // Same floor as every other module (docs/OPEN-DECISIONS.md D16).
        apiVersion = KotlinVersion.KOTLIN_2_2
        allWarningsAsErrors = true
    }
}

dependencies {
    // The configuration takes a SpfnEventStream and a SpfnKeyLifecycle, and a descriptor
    // carries SpfnCall values: public signatures' types.
    api(project(":spfn-client"))
    // `subscribe` answers a Flow, `subscribeConnection` a StateFlow.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    // `runTest`: the runtime suite drives the client's engine on the test scheduler.
    testImplementation(libs.kotlinx.coroutines.test)
}
