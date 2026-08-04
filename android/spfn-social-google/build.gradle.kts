import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// SPFN Mobile — Android Google Sign-In adapter.
//
// The one Android SDK module with provider dependencies: Credential Manager is where a
// sign-in request can carry a nonce, which is the whole reason this module exists rather
// than an app calling the API itself. All three artifacts are declared in
// tools/module-graph.json, and tools/validate/validate.sh fails on any external
// dependency that is not — and on any that is declared and unused.
//
// Not play-services-auth: Google deprecated that sign-in surface, and starting new code
// on it would only schedule the same migration for a worse moment.
//
// Android has no equivalent of the iOS traits: a consumer selects this adapter by
// depending on its artifact, and one that does not depend on it links nothing of it.

plugins {
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android is an error.
    alias(libs.plugins.android.library)
}

description = "SPFN Android Google Sign-In adapter: the nonce-carrying sign-in request and its identity token."

extra["spfnModuleDependsOn"] = listOf("spfn-client")
extra["spfnSwiftCounterpart"] = "SPFNSocialGoogle"

android {
    namespace = "xyz.superfunction.spfn.social.google"
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
        unitTests {
            // A Credential Manager request option carries its values in an android
            // Bundle, so building the real request on a host JVM touches framework
            // stubs. Without this the C7 row could only assert against a value this
            // module made up, instead of against the request Google would be handed.
            // It does NOT weaken what the row proves: the nonce is read back off the
            // option object, which holds it as a field of its own.
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    // Compiles on JDK 21 regardless of which JDK launched Gradle. The foojay resolver
    // in settings.gradle.kts provisions it when the machine has no JDK 21.
    jvmToolchain(libs.versions.jdk.toolchain.get().toInt())

    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        apiVersion = KotlinVersion.KOTLIN_2_2
        allWarningsAsErrors = true
    }
}

dependencies {
    // `api`: the adapter's entry point takes a SpfnSocialNonce and its result is handed
    // straight to SpfnKeyLifecycle.enroll, so a consumer sees both types.
    api(project(":spfn-client"))
    // `api`, not `implementation`: the request this module builds and the credential it
    // reads are Credential Manager types a caller sees, so they are part of the surface.
    api(libs.androidx.credentials)
    // The Google-specific request option and the credential it returns.
    api(libs.googleid)
    // Runtime only in effect — no symbol of it is named here — but a consumer without it
    // has no provider behind Credential Manager, so every sign-in would fail at runtime
    // with nothing in the build saying why.
    implementation(libs.androidx.credentials.play.services.auth)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
