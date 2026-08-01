import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// SPFN Mobile — Android persistence and sync module.
//
// Name asymmetry with the Swift side (SPFNPersistence <-> spfn-sync) is carried
// verbatim from the approved topology artifact §3. Reconciling it is open decision D10.

plugins {
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android is an error.
    alias(libs.plugins.android.library)
}

description = "SPFN Android persistence and sync."

extra["spfnModuleDependsOn"] = listOf("spfn-core")
extra["spfnSwiftCounterpart"] = "SPFNPersistence"

android {
    namespace = "xyz.superfunction.spfn.sync"
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
    api(project(":spfn-core"))
    testImplementation(libs.junit)
}
