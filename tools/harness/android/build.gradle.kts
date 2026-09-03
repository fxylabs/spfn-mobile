import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.Properties

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
// The screen is Compose, and it costs this module no checksum. What made a UI toolkit
// expensive here was gradle/verification-metadata.xml — every artifact pinned by hand —
// and examples/android-compose already pays that price for the same four artifacts at the
// same versions (decision 01kzb8tjxp, D-5, superseded by the ui module). What is left is
// the reason to move: the screen a flow drives and the screens the generator emits are now
// the same kind of thing, found the same way, so `testTagsAsResourceId` is one rule for
// this repository rather than one rule per app.
//
// The device social-login mode added two things this script has to carry: the run's own
// client id and server address, which are NOT committed, and a cleartext exception for
// the machine the server runs on. Both come from `local.properties`, which .gitignore
// already refuses to stage, and both fail closed — a checkout with no local.properties
// builds and installs, and the sign-in button on the screen is disabled rather than the
// app crashing at the tap.

plugins {
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android is an error.
    alias(libs.plugins.android.application)
    // AGP 9.2.1 turns the Compose feature on by asking whether this plugin is applied,
    // so `buildFeatures { compose = true }` is not the switch and is deliberately absent.
    alias(libs.plugins.kotlin.compose)
}

description = "SPFN Android harness: the app a Maestro flow drives. Not published."

// ---------------------------------------------------------------------------
// Run configuration: read from local.properties, never committed, never printed.
// ---------------------------------------------------------------------------
// Only key NAMES appear in this file and in every message it can produce. A build that
// echoed a client id would put it in every CI log that ever ran the harness.

val harnessLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties");
    if (file.exists())
    {
        file.inputStream().use { load(it) };
    }
}

/**
 * One configured value, or the empty string when the key is absent.
 *
 * A value that is present but malformed fails the build instead of being dropped: a
 * silently ignored typo would disable the sign-in button and look exactly like a machine
 * that was never configured. The message names the key and the shape, never the value.
 */
fun harnessProperty(key: String, shape: Regex): String
{
    val value = (harnessLocalProperties.getProperty(key) ?: "").trim();
    if (value.isEmpty())
    {
        return "";
    }
    if (!shape.matches(value))
    {
        throw GradleException("local.properties key '$key' does not match ${shape.pattern}");
    }
    return value;
}

// A Google web client id: dot-separated ASCII, as issued. The Credential Manager request
// carries it as `serverClientId`, which is the WEB client id and not the Android one.
val harnessGoogleServerClientId = harnessProperty(
    "spfn.harness.google.serverClientId",
    Regex("^[A-Za-z0-9._-]+$")
);

// The SPFN server the device enrolls against: scheme, host, optional port, nothing else.
// A path or a query is refused here rather than trimmed, because a receipt records this
// value and a receipt that quietly differs from what was configured is not evidence.
// The scheme pattern is spelled without the two letters that would make this line read as
// a committed URL to tools/validate/validate.sh, which forbids one outside the root.
val harnessServerBaseUrl = harnessProperty(
    "spfn.harness.serverBaseUrl",
    Regex("^[a-z][a-z0-9+.-]*://[A-Za-z0-9.-]+(:[0-9]{1,5})?$")
);

/**
 * The one host the app may reach over plain HTTP, or nothing at all.
 *
 * Exactly the configured server and no convenience hosts. An earlier version also permitted
 * the emulator's alias for the host loopback and the two spellings of the device's own
 * loopback, on the grounds that the Maestro runner uses them — and that made the exception
 * a standing one, granted to addresses no run had named. A build now permits what it was
 * told to permit: an emulator run configures `10.0.2.2` as the host, a device run behind
 * `adb reverse` configures `127.0.0.1`, and an unconfigured checkout permits cleartext to
 * nothing.
 */
val harnessCleartextHost = harnessServerBaseUrl.substringAfter("://").substringBefore(":");

/**
 * Writes the network security configuration this build permits cleartext through.
 *
 * Generated rather than committed because the one host that matters is the developer
 * machine's LAN address, which is different on every machine and belongs in no commit.
 * The hosts are an input, so a changed local.properties regenerates the file.
 */
abstract class GenerateHarnessNetworkSecurityConfig : DefaultTask()
{
    /** The one configured host, or the empty string when this build has none. */
    @get:Input
    abstract val cleartextHost: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate()
    {
        val host = cleartextHost.get();
        // Assembled line by line rather than as an indented raw string: `trimIndent`
        // measures the SMALLEST indent in the finished text, and an interpolated block
        // whose own lines are less indented than the template shifts every other line —
        // including the XML declaration, which is then no longer the first thing in the
        // file and stops the resource parser outright.
        val lines = mutableListOf(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<!-- GENERATED by :harness-android:generateHarnessNetworkSecurityConfig. Do not edit. -->",
            "<network-security-config>",
            "    <base-config cleartextTrafficPermitted=\"false\" />"
        );
        if (host.isNotEmpty())
        {
            // The host reaches an XML attribute, so it is held to a charset that cannot
            // carry markup. A value that fails here is a mistake in local.properties, and
            // the build says so instead of writing a file that means something else.
            if (!Regex("^[A-Za-z0-9.-]+$").matches(host))
            {
                throw GradleException("the configured cleartext host is not a bare hostname or address");
            }
            lines.add("    <domain-config cleartextTrafficPermitted=\"true\">");
            lines.add("        <domain includeSubdomains=\"false\">$host</domain>");
            lines.add("    </domain-config>");
        }
        lines.add("</network-security-config>");

        // The file this writes is the whole cleartext exception, so what it contains is
        // checked here rather than trusted: one host earns one entry, no host earns none,
        // and any other count is a generator that stopped meaning what it says.
        val expected = if (host.isEmpty()) 0 else 1;
        val written = lines.count { it.contains("<domain ") };
        if (written != expected)
        {
            throw GradleException("the network security config names $written cleartext hosts, expected $expected");
        }

        val directory = outputDir.get().asFile.resolve("xml");
        directory.mkdirs();
        directory.resolve("spfn_harness_network_security_config.xml")
            .writeText(lines.joinToString("\n") + "\n");
    }
}

val generateHarnessNetworkSecurityConfig =
    tasks.register<GenerateHarnessNetworkSecurityConfig>("generateHarnessNetworkSecurityConfig") {
        description = "Writes the harness app's cleartext exception for this machine's server.";
        cleartextHost.set(harnessCleartextHost);
        outputDir.set(layout.buildDirectory.dir("generated/spfn-harness-res"));
    }

android {
    namespace = "xyz.superfunction.spfn.harness"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "xyz.superfunction.spfn.harness"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = 1
        versionName = "0.0.0"

        // Empty is the configured absence. HarnessSocialConfiguration reads exactly these
        // two fields and disables the sign-in button when either is empty.
        buildConfigField("String", "HARNESS_GOOGLE_SERVER_CLIENT_ID", "\"$harnessGoogleServerClientId\"")
        buildConfigField("String", "HARNESS_SERVER_BASE_URL", "\"$harnessServerBaseUrl\"")
    }

    buildFeatures {
        // Off by default since AGP 8, and the two fields above are the only reason it is on.
        buildConfig = true
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

    testOptions {
        unitTests.all {
            // HarnessRunnerBlockTest reads tools/harness/flows/, which is the definition
            // of what a runner taps and therefore of what the screen must draw above the
            // fold. The flows are not this module's source, so it is given the root.
            it.systemProperty("spfn.repoRoot", rootDir.absolutePath)
        }
    }
}

// The generated resource directory is registered through the variant API rather than by
// adding a source directory by path: this is the form that carries the task dependency, so
// a clean build cannot package a manifest reference to a file nothing generated yet.
androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateHarnessNetworkSecurityConfig,
            GenerateHarnessNetworkSecurityConfig::outputDir
        )
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
    // The real Google sign-in, through the SDK's own adapter. The harness holds no
    // provider logic of its own: it hands the adapter an Activity and a client id, and
    // answers SpfnKeyLifecycle.enroll with what comes back.
    implementation(project(":spfn-social-google"))
    // `Busy` for the screen's own readout, and `Flow`/`FlowHost` for the generated
    // approval screens under `src/main/kotlin/.../harness/generated`: the harness is the
    // second consumer of the one screen spec, and it drives those screens against a live
    // reference server rather than against a fixture.
    implementation(project(":spfn-ui"))
    // The request and response types the generated services name. Reachable through
    // :spfn-client already, and declared anyway: this module's own sources import it, and
    // a dependency that is used is a dependency that is stated.
    implementation(project(":spfn-generated"))
    // HarnessActivity launches its button actions on Dispatchers.Main. The core
    // artifact contains the coroutine machinery but no Android Main dispatcher.
    implementation(libs.kotlinx.coroutines.android)

    // The screen. Foundation and no higher: Material would add a design this repository
    // has not chosen, and every artifact here is one examples/android-compose already
    // pins at the same version, so this module's screen adds no entry to
    // gradle/verification-metadata.xml.
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.activity.compose)

    // The harness proves itself on a phone, not on a JVM, and two things here are the
    // exception. A receipt's bytes are pure text work — a timestamp, an escape, a field
    // order — and every way they can go wrong goes wrong silently, on someone else's
    // machine, in a file nobody reads until it is evidence. A readout's text is the same
    // kind of thing: a flow matches it as a regex, and a screen that draws `busy=idle`
    // builds, installs and shows a wrong word to a runner that waits out its timeout.
    testImplementation(libs.junit)
}
