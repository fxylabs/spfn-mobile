import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// SPFN Mobile — Android Sign in with Apple adapter.
//
// Zero external dependencies, and the reason is worth writing down: Apple ships no
// native sign-in SDK for Android, so there is no platform flow for this module to wrap.
// What it owns is the half that is the SDK's either way — which shape of the nonce goes
// into the authorization request, and how a completed flow without an identity token is
// told apart from a dismissal. The flow itself arrives through the same driver seam the
// iOS module uses, supplied by the app.

plugins {
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android is an error.
    alias(libs.plugins.android.library)
}

description = "SPFN Android Sign in with Apple adapter: nonce shaping and token classification over a caller-supplied flow."

extra["spfnModuleDependsOn"] = listOf("spfn-client")
extra["spfnSwiftCounterpart"] = "SPFNSocialApple"

android {
    namespace = "xyz.superfunction.spfn.social.apple"
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
