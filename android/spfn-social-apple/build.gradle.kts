import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// SPFN Mobile — Android Sign in with Apple adapter.
//
// Apple ships no Android sign-in SDK, so the identity token can only come out of Apple's
// web flow. That flow ends on the server, in a `form_post` to its Return URL, and a
// browser session an Android app cannot read. This module is the Android half of the
// relay that closes the gap: it opens the authorization URL in a Custom Tab and receives
// the link the server redirects to with the token in it. Both artifacts are declared in
// tools/module-graph.json, and tools/validate/validate.sh fails on any external
// dependency that is not — and on any that is declared and unused.
//
// Not AppAuth: its management/redirect-receiver activity pair is the proven shape and is
// what this module follows, but the protocol it implements (code exchange at the app) is
// not this one, and depending on it would bring a token endpoint client nobody calls.
//
// Android has no equivalent of the iOS traits: a consumer selects this adapter by
// depending on its artifact, and one that does not depend on it links nothing of it.

plugins {
    // AGP 9 compiles Kotlin itself; applying org.jetbrains.kotlin.android is an error.
    alias(libs.plugins.android.library)
}

description = "SPFN Android Sign in with Apple adapter: Apple's web flow in a Custom Tab and the relayed identity token."

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

    testOptions {
        unitTests {
            // The T6 row raises the platform's own ActivityNotFoundException, which is a
            // framework stub on a host JVM. Without this its constructor throws "not
            // mocked" and the row could only assert against a stand-in this module made
            // up. Nothing else here touches the framework: the relay is classified from
            // plain strings precisely because android.net.Uri's stubs answer defaults.
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
    // The Custom Tab the authorization runs in. `implementation`: no browser type is part
    // of this module's surface — the app hands over a Uri and gets a String back.
    implementation(libs.androidx.browser)
    // The pending call is a CompletableDeferred, because a call whose caller is cancelled
    // while the tab is open has to stop waiting (P16) and the standard library's
    // suspendCoroutine cannot be told. spfn-client keeps it `implementation`, so it is
    // not on this module's compile classpath unless it is named here.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
