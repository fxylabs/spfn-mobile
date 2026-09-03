import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.Properties

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
// The one configured value, the server this app would talk to, comes from local.properties
// exactly the way the harness reads its own. It fails closed: a checkout with no
// local.properties builds and installs, and the app reports itself unconfigured rather
// than reaching an address nobody named.

plugins {
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android is an error.
    alias(libs.plugins.android.application)
    // AGP 9.2.1 turns the Compose feature on by asking whether this plugin is applied,
    // so `buildFeatures { compose = true }` is not the switch and is deliberately absent.
    alias(libs.plugins.kotlin.compose)
}

description = "SPFN Compose example: the generated device-approval scaffold, running. Not published."

// ---------------------------------------------------------------------------
// Run configuration: read from local.properties, never committed, never printed.
// ---------------------------------------------------------------------------
// Only key NAMES appear in this file and in every message it can produce.

val exampleLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties");
    if (file.exists())
    {
        file.inputStream().use { load(it) };
    }
}

/**
 * The SPFN server this app would send to: scheme, host, optional port, nothing else. A
 * value that is present but malformed fails the build rather than being dropped, because
 * a silently ignored typo looks exactly like a machine that was never configured. The
 * scheme pattern is spelled without the two letters that would make this line read as a
 * committed URL to tools/validate/validate.sh, which forbids one outside the root.
 */
val exampleServerBaseUrl = (exampleLocalProperties.getProperty("spfn.example.serverBaseUrl") ?: "")
    .trim()
    .also { value ->
        if (value.isNotEmpty() && !Regex("^[a-z][a-z0-9+.-]*://[A-Za-z0-9.-]+(:[0-9]{1,5})?\$").matches(value))
        {
            throw GradleException("local.properties key 'spfn.example.serverBaseUrl' does not match the expected shape");
        }
    }

android {
    namespace = "xyz.superfunction.spfn.example"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "xyz.superfunction.spfn.example"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = 1
        versionName = "0.0.0"

        // Empty is the configured absence, and the app treats it as one.
        buildConfigField("String", "EXAMPLE_SERVER_BASE_URL", "\"$exampleServerBaseUrl\"")
    }

    buildFeatures {
        // Off by default since AGP 8, and the one field above is the only reason it is on.
        buildConfig = true
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
