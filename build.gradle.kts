// SPFN Mobile — Step 2 root build.
//
// Decision D5 fixed the toolchain baseline, so plugins are now applied in the module
// scripts. Publication is still disabled and still unconfigured: no registry, no
// coordinate, no credential, no signing identity. Enabling it is a Step 5 decision.

// AGP 9 compiles Kotlin itself and pins Kotlin Gradle Plugin 2.2.10 as a runtime
// dependency. D5 requires Kotlin 2.4.x, which AGP supports only as an explicit
// classpath upgrade in the root build file — the `org.jetbrains.kotlin.android` plugin
// is rejected outright by AGP 9. This block is the whole mechanism for that upgrade.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

// Declared but not applied: the root project builds nothing. This puts the AGP plugin
// types on the root script classpath so `spfnToolchainReport` can read the effective
// module baseline instead of restating it from the version catalogue.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

val spfnVersion: String = providers.gradleProperty("spfn.version").get()
val publishingEnabled: Boolean =
    providers.gradleProperty("spfn.publishing.enabled").get().toBoolean()
val mavenGroupVerified: Boolean =
    providers.gradleProperty("spfn.maven.group.verified").get().toBoolean()

allprojects {
    version = spfnVersion
}

require(!publishingEnabled)
{
    "Publication is disabled. Enabling it requires approved registry, coordinates, " +
        "credential and signing decisions."
}

require(!mavenGroupVerified)
{
    "spfn.maven.group.verified must stay false until control of the reverse-domain " +
        "namespace is actually verified."
}

/// The five SDK modules. `:contract-codegen` is a build tool, not an SDK module, so it
/// is excluded from every graph check and from the AAR baseline.
val sdkModules: List<Project>
    get() = subprojects.filter { it.projectDir.parentFile.name == "android" }

/// Structural check: every declared inter-module edge must name a real SDK module.
/// This is the Gradle-side half of the module-graph coherence gate;
/// `tools/validate/validate.sh` cross-checks it against tools/module-graph.json.
tasks.register("spfnScaffoldCheck")
{
    group = "verification"
    description = "Verifies the Android module graph without resolving any dependency."

    val moduleNames = sdkModules.map { it.name }.sorted()
    val edges = sdkModules.associate { subproject ->
        @Suppress("UNCHECKED_CAST")
        subproject.name to ((subproject.extra["spfnModuleDependsOn"] as? List<String>) ?: emptyList())
    }
    val projectDirs = sdkModules.associate { it.name to it.projectDir }

    doLast {
        val problems = mutableListOf<String>()

        projectDirs.forEach { (name, dir) ->
            if (!dir.resolve("build.gradle.kts").isFile)
            {
                problems += "$name: missing build.gradle.kts"
            }
            if (!dir.resolve("src/main/kotlin").isDirectory)
            {
                problems += "$name: missing src/main/kotlin"
            }
        }

        edges.forEach { (name, dependencies) ->
            dependencies.forEach { dependency ->
                if (dependency !in moduleNames)
                {
                    problems += "$name declares dependency on unknown module '$dependency'"
                }
                if (dependency == name)
                {
                    problems += "$name declares a dependency on itself"
                }
            }
        }

        if (problems.isNotEmpty())
        {
            throw GradleException("spfnScaffoldCheck failed:\n  " + problems.joinToString("\n  "))
        }

        logger.lifecycle("spfnScaffoldCheck OK — ${moduleNames.size} modules, version $spfnVersion, publishing disabled")
    }
}

/// Prints the effective toolchain baseline so evidence in a report is read off the
/// build rather than copied from a version catalogue by hand.
tasks.register("spfnToolchainReport")
{
    group = "verification"
    description = "Prints the resolved D5 toolchain baseline for every SDK module."

    val catalog = file("gradle/libs.versions.toml")
    val rows = sdkModules.map { subproject ->
        subproject.name to subproject.extensions.findByName("android")
    }

    doLast {
        logger.lifecycle("gradle           ${gradle.gradleVersion}")
        logger.lifecycle("daemon JVM       ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
        logger.lifecycle("catalog          ${catalog.absolutePath}")
        rows.forEach { (name, android) ->
            if (android is com.android.build.api.dsl.LibraryExtension)
            {
                logger.lifecycle(
                    "$name  namespace=${android.namespace}  compileSdk=${android.compileSdk} " +
                        "minSdk=${android.defaultConfig.minSdk} " +
                        "javac=${android.compileOptions.sourceCompatibility}/${android.compileOptions.targetCompatibility}"
                )
            }
        }
    }
}
