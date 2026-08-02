import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// SPFN Mobile — Android client module.
//
// Hosts the transport boundary, its OkHttp adapter and the session that assembles
// clientProofV1 requests over it. This is the module that makes the repository's
// dependency count non-zero on the Android side, and OkHttp is the whole of it: every
// artifact it drags in carries a network-fetched checksum in
// gradle/verification-metadata.xml.

plugins {
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android is an error.
    alias(libs.plugins.android.library)
}

description = "SPFN Android client: the one-call transport boundary, its OkHttp adapter and the clientProofV1 session."

extra["spfnModuleDependsOn"] = listOf("spfn-core", "spfn-auth", "spfn-generated")
extra["spfnSwiftCounterpart"] = "SPFNClient"

android {
    namespace = "xyz.superfunction.spfn.client"
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
    api(project(":spfn-core"))
    // The session assembles a clientProofV1 proof over a generated operation, and hands
    // both types back to callers, so neither edge can be `implementation`.
    api(project(":spfn-auth"))
    api(project(":spfn-generated"))
    // `api`, not `implementation`: the constructor accepts a caller-supplied OkHttpClient
    // so an app can share one connection pool instead of running two.
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
