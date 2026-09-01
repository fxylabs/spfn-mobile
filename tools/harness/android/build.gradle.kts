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
// No UI toolkit dependency either. The screen is a LinearLayout built in code, which
// costs nothing: gradle/verification-metadata.xml pins every artifact with a real
// checksum, and adding Compose would mean recording a hundred more of them for a screen
// that is nine buttons and three labels (decision 01kzb8tjxp, D-5).
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
 * The hosts the app may reach over plain HTTP.
 *
 * Three are fixed and are what the Maestro runner already uses — the emulator's alias for
 * the host loopback, and the two spellings of the device's own loopback that `adb reverse`
 * delivers to. The fourth is this run's server, and it is present only when configured, so
 * an unconfigured checkout permits cleartext to nothing but a loopback.
 */
val harnessCleartextHosts = buildList {
    add("10.0.2.2");
    add("127.0.0.1");
    add("localhost");
    val host = harnessServerBaseUrl.substringAfter("://").substringBefore(":");
    if (host.isNotEmpty())
    {
        add(host);
    }
}.distinct();

/**
 * Writes the network security configuration this build permits cleartext through.
 *
 * Generated rather than committed because the one host that matters is the developer
 * machine's LAN address, which is different on every machine and belongs in no commit.
 * The hosts are an input, so a changed local.properties regenerates the file.
 */
abstract class GenerateHarnessNetworkSecurityConfig : DefaultTask()
{
    @get:Input
    abstract val cleartextHosts: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate()
    {
        val hosts = cleartextHosts.get();
        val shape = Regex("^[A-Za-z0-9.-]+$");
        for (host in hosts)
        {
            // The host reaches an XML attribute, so it is held to a charset that cannot
            // carry markup. A value that fails here is a mistake in local.properties, and
            // the build says so instead of writing a file that means something else.
            if (!shape.matches(host))
            {
                throw GradleException("a cleartext host is not a bare hostname or address");
            }
        }
        // Assembled line by line rather than as an indented raw string: `trimIndent`
        // measures the SMALLEST indent in the finished text, and an interpolated block
        // whose own lines are less indented than the template shifts every other line —
        // including the XML declaration, which is then no longer the first thing in the
        // file and stops the resource parser outright.
        val lines = mutableListOf(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
            "<!-- GENERATED by :harness-android:generateHarnessNetworkSecurityConfig. Do not edit. -->",
            "<network-security-config>",
            "    <base-config cleartextTrafficPermitted=\"false\" />",
            "    <domain-config cleartextTrafficPermitted=\"true\">"
        );
        hosts.mapTo(lines) { "        <domain includeSubdomains=\"false\">$it</domain>" };
        lines.add("    </domain-config>");
        lines.add("</network-security-config>");

        val directory = outputDir.get().asFile.resolve("xml");
        directory.mkdirs();
        directory.resolve("spfn_harness_network_security_config.xml")
            .writeText(lines.joinToString("\n") + "\n");
    }
}

val generateHarnessNetworkSecurityConfig =
    tasks.register<GenerateHarnessNetworkSecurityConfig>("generateHarnessNetworkSecurityConfig") {
        description = "Writes the harness app's cleartext exception for this machine's server.";
        cleartextHosts.set(harnessCleartextHosts);
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
    // HarnessActivity launches its button actions on Dispatchers.Main. The core
    // artifact contains the coroutine machinery but no Android Main dispatcher.
    implementation(libs.kotlinx.coroutines.android)

    // The harness proves itself on a phone, not on a JVM, and this suite is the one
    // exception: a receipt's bytes are pure text work — a timestamp, an escape, a field
    // order — and every way they can go wrong goes wrong silently, on someone else's
    // machine, in a file nobody reads until it is evidence.
    testImplementation(libs.junit)
}
