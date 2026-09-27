import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// SPFN Mobile — Android event stream lifecycle module.
//
// Wires the client module's SpfnEventStream to the app: the root host that observes the
// process's foreground, the key lifecycle's signed-in value and the default network, and
// the screen-level SpfnEventEffect. The connection itself is the client module's object;
// this module only carries platform facts into it (docs/architecture/event-stream-design.md
// §3-3, §3-7).
//
// It depends on spfn-client and nothing else in this repository. The design proposed an
// edge to spfn-ui as well; nothing here uses a ui type, so the edge was dropped (§10 Q-K)
// and an app that renders state without listening to events links neither module.

plugins {
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android is an error.
    alias(libs.plugins.android.library)
    // AGP 9.2.1 turns the Compose feature on by asking whether this plugin is applied.
    alias(libs.plugins.kotlin.compose)
}

description = "SPFN Android event stream lifecycle: SpfnEventStreamHost, SpfnEventEffect and the three observers."

extra["spfnModuleDependsOn"] = listOf("spfn-client")
extra["spfnSwiftCounterpart"] = "SPFNEvents"

android {
    namespace = "xyz.superfunction.spfn.events"
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
    // The host and the effect take and find a SpfnEventStream: a public signature's type.
    api(project(":spfn-client"))
    // `listen` answers a Flow, collected here and by a screen.
    api(libs.kotlinx.coroutines.core)
    // @Composable is on every public signature.
    api(libs.androidx.compose.runtime)
    // LocalContext, for the ConnectivityManager and the debuggable flag.
    implementation(libs.androidx.compose.ui)
    // ProcessLifecycleOwner: the process's foreground, not an activity's (§8 H-7).
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit)
}
