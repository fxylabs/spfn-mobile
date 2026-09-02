import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// SPFN Mobile — Android UI runtime module.
//
// Holds the vocabulary a screen needs before it holds a screen: one read's state
// (Loadable), one write's state (Busy), and a stack of routes with the host that binds
// it to a navigator (FlowRoute, Flow, FlowHost). It depends on spfn-core and nothing
// else in this repository — the error type the two state vocabularies carry is core's
// envelope, and nothing here needs a transport, a session or a generated operation.
//
// This is the first module in the repository that links a UI toolkit, and it is the
// reason gradle/verification-metadata.xml grew: Compose and Navigation 3 drag in a
// large transitive set and every artifact of it carries a network-fetched checksum.
// That cost was accepted with the module (w-w823n).

plugins {
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android is an error.
    alias(libs.plugins.android.library)
    // AGP 9.2.1 turns the Compose feature on by asking whether this plugin is applied,
    // so `buildFeatures { compose = true }` is not the switch and is deliberately absent.
    alias(libs.plugins.kotlin.compose)
}

description = "SPFN Android UI runtime: Loadable, Busy and the Flow/FlowHost navigation vocabulary."

extra["spfnModuleDependsOn"] = listOf("spfn-core")
extra["spfnSwiftCounterpart"] = "SPFNUI"

android {
    namespace = "xyz.superfunction.spfn.ui"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.get().toInt()
    }

    // AGP 9.2.1 defaults source/target compatibility to Java 11. D5 requires the AAR
    // bytecode target to be pinned rather than inherited, so the default is restated
    // here: an AGP upgrade that moves the default cannot silently move our AAR.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.all {
            it.systemProperty("spfn.repoRoot", rootDir.absolutePath)
        }
    }
}

kotlin {
    // Compiles on JDK 21 regardless of which JDK launched Gradle. The foojay resolver
    // in settings.gradle.kts provisions it when the machine has no JDK 21.
    jvmToolchain(libs.versions.jdk.toolchain.get().toInt())

    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        // Same floor as every other module: 2.2 is the lowest apiVersion Kotlin 2.4.10
        // accepts without a deprecation warning, and allWarningsAsErrors makes a warning
        // a build failure. See docs/OPEN-DECISIONS.md D16.
        apiVersion = KotlinVersion.KOTLIN_2_2
        allWarningsAsErrors = true
    }
}

dependencies {
    // `api`, not `implementation`: SpfnErrorEnvelope is the payload of Loadable.Error
    // and Busy.Error, so a consumer that reads either has to see core's types.
    api(project(":spfn-core"))
    // Flow exposes its stack and its presented flag as StateFlow, which is the type a
    // Compose screen collects. Both are public signatures, so this edge is `api` too.
    api(libs.kotlinx.coroutines.core)
    // @Composable is on FlowHost's signature.
    api(libs.androidx.compose.runtime)
    // The content lambda's routes are laid out by Navigation 3, which needs Compose UI;
    // it arrives transitively either way, and a module that compiles against a type
    // declares the artifact it comes from.
    implementation(libs.androidx.compose.ui)
    // BackHandler, for the one case Navigation 3's own back handling leaves alone: a
    // Modal flow standing on its last route, which closes rather than popping.
    implementation(libs.androidx.activity.compose)
    // NavDisplay and NavEntry are what FlowHost renders. Neither appears in a public
    // signature — the host takes a Flow and a content lambda — so these stay internal.
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    testImplementation(libs.junit)
}
