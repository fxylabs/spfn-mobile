import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// SPFN Mobile — the Compose example application.
//
// The second application module in this repository and, like the first, never published:
// it exists so the generated screen scaffolds have somewhere to compile and so a Maestro
// cell has something to tap. The iOS half is examples/ios-swiftui.
//
// It lives under examples/ rather than android/ for the reason the harness lives under
// tools/: the validator counts the directories under android/ and settings.gradle.kts's
// `file("android/…")` mappings against tools/module-graph.json, and an application there
// would read as an undeclared SDK module (decision 01kzb8tjxp, D-1). examples/android-compose
// was already the validator's declared home for this app.
//
// No signing configuration is declared. A debug build signs with the machine's own
// ~/.android/debug.keystore, which is outside this tree — the validator fails on a keystore
// in the committed tree — and there is no release build here to need anything more.
//
// It reads no configuration at all, and that is the shape rather than an omission: this app
// has no enrolment path of its own — enrolment is what tools/harness exists to drive — so a
// client it built against a configured server would refuse every call for want of a key.
// Every screen here runs against a launch fixture, the menu included, and the manifest
// declares no INTERNET permission.

plugins {
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android is an error.
    alias(libs.plugins.android.application)
    // AGP 9.2.1 turns the Compose feature on by asking whether this plugin is applied,
    // so `buildFeatures { compose = true }` is not the switch and is deliberately absent.
    alias(libs.plugins.kotlin.compose)
}

description = "SPFN Compose example: the generated device-approval scaffold, running. Not published."

// ---------------------------------------------------------------------------
// No run configuration
// ---------------------------------------------------------------------------
// There used to be one: a server address read from local.properties, and a client this app
// built against it when no launch fixture named a cell. Nothing reached it. This app has no
// enrolment path — enrolment is tools/harness's whole subject — so every call that client
// made was refused for want of a key, and the branch existed to produce a refusal from an
// address nobody had configured. The menu replaced it, on the same fake every cell runs on,
// and the INTERNET permission went with it.

android {
    namespace = "xyz.superfunction.spfn.example"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "xyz.superfunction.spfn.example"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = 1
        versionName = "0.0.0"
    }

    // Restated rather than inherited, for the same reason every other module restates it:
    // an AGP upgrade that moves the default cannot silently move ours.
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

    testOptions {
        unitTests.all {
            // The cell suite reads examples/ui-spec/generated/device-approval.cases.json,
            // which is the shared table it is supposed to be checked against.
            it.systemProperty("spfn.repoRoot", rootDir.absolutePath)
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
    implementation(project(":spfn-core"))
    implementation(project(":spfn-generated"))
    implementation(project(":spfn-client"))
    implementation(project(":spfn-ui"))

    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    // Column, BasicText and BasicTextField. The generated views use nothing above
    // foundation on purpose: Material would add a design this repository has not chosen
    // and a hundred more artifacts to gradle/verification-metadata.xml for a screen that
    // is two labels, a field and four buttons.
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // One test per cell of the case table. It is a JVM suite and not an instrumented one
    // because every rule it checks is a rule about a screen model and a list of routes,
    // and none of them needs a device (the same reason spfn-ui's Flow suite is one).
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
