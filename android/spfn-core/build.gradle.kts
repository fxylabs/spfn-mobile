import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// SPFN Mobile — Android core module.
//
// Toolchain baseline is decision D5 (2026-08-01), recorded in gradle/libs.versions.toml.
// No publication block: publishing stays disabled and unconfigured.

plugins {
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android is an error.
    alias(libs.plugins.android.library)
}

description = "SPFN Android core: canonical serialization, digests, errors, version and contract binding."

extra["spfnModuleDependsOn"] = listOf<String>()
extra["spfnSwiftCounterpart"] = "SPFNCore"

android {
    namespace = "xyz.superfunction.spfn.core"
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
        // Language features stay at the Kotlin 2.4 default; the API surface this module
        // compiles against is lowered so older consumers can link the AAR. 2.2 is the
        // floor: Kotlin 2.4.10 rejects apiVersion 2.0 as deprecated, and with
        // allWarningsAsErrors that rejection is a hard compile failure, not a warning.
        apiVersion = KotlinVersion.KOTLIN_2_2
        allWarningsAsErrors = true
    }
}

dependencies {
    testImplementation(libs.junit)
}
