import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// SPFN Mobile — the Android harness application.
//
// The one application module in this repository, and it is never published: it exists so
// a Maestro flow has an app to tap. The iOS half is tools/harness/ios.
//
// No signing configuration is declared. A debug build signs with the machine's own
// ~/.android/debug.keystore, which is outside this tree — and it has to be, because the
// validator fails on a keystore in the committed tree and .gitignore refuses to stage
// one. There is no release build here to need anything more.
//
// No UI toolkit dependency either. The screen is a LinearLayout built in code, which
// costs nothing: gradle/verification-metadata.xml pins every artifact with a real
// checksum, and adding Compose would mean recording a hundred more of them for a screen
// that is nine buttons and three labels (decision 01kzb8tjxp, D-5).

plugins {
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android is an error.
    alias(libs.plugins.android.application)
}

description = "SPFN Android harness: the app a Maestro flow drives. Not published."

android {
    namespace = "xyz.superfunction.spfn.harness"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "xyz.superfunction.spfn.harness"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = 1
        versionName = "0.0.0"
    }

    // Restated rather than inherited, for the same reason every library module restates
    // it: an AGP upgrade that moves the default cannot silently move ours.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        // Debug only. A release build would need a signing identity this repository is
        // not allowed to hold.
        getByName("debug") {
            isMinifyEnabled = false
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.toolchain.get().toInt())

    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        apiVersion = KotlinVersion.KOTLIN_2_2
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(project(":spfn-client"))
    // HarnessActivity launches its button actions on Dispatchers.Main. The core
    // artifact contains the coroutine machinery but no Android Main dispatcher.
    implementation(libs.kotlinx.coroutines.android)
}
