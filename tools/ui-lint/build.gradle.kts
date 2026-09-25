import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// SPFN Mobile — Android Lint checks for the UI rules no compiler and no JVM test can hold.
//
// Not an SDK module and never published. Three rules used to be regular expressions in
// tools/validate/validate.sh, read over Kotlin as text; here they are read over the syntax
// tree lint builds, with calls resolved to the declarations they reach, so a rule stops
// depending on how a line happens to be spelled. A module applies them with
// `lintChecks(project(":ui-lint"))` and `lint` runs them with every other check.
//
// The checks are loaded by lint itself, on the Kotlin runtime lint ships. So the API level
// is held to the Kotlin line lint 32.2.1 carries (2.2), and the bytecode to the JVM lint
// runs on: a check that called past either would load on this machine and fail on another.

plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Android Lint checks for the SPFN UI module and the apps that draw it."

kotlin {
    jvmToolchain(libs.versions.jdk.toolchain.get().toInt())

    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // compileOnly: lint loads these checks beside its own API, so shipping a copy would put
    // two in one class loader. The tests run outside lint and need it on their own path.
    compileOnly(libs.lint.api)
    testImplementation(libs.lint.api)
    testImplementation(libs.lint.tests)
    testImplementation(libs.junit)
}
