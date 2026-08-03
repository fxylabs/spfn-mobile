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
//
// The CycloneDX plugin IS applied: it registers `cyclonedxDirectBom` / `cyclonedxBom`
// as on-demand verification tasks (decision D7). Nothing wires them into assemble,
// build or check, and `tools/rc-verify/rc-verify.sh` asserts that stays true.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.cyclonedx.bom)
}

val spfnVersion: String = providers.gradleProperty("spfn.version").get()
val publishingEnabled: Boolean =
    providers.gradleProperty("spfn.publishing.enabled").get().toBoolean()
val mavenGroupVerified: Boolean =
    providers.gradleProperty("spfn.maven.group.verified").get().toBoolean()

allprojects {
    version = spfnVersion
}

// The publication gate, rewritten for Step 5 (decision D3: no-publish candidate only).
//
// The COMMITTED gradle.properties value must always be `false`. It is read from the
// file directly rather than from `providers`, because a `-P` command-line override
// shadows the file value and the whole point of this check is that the tree can never
// be committed with publication on. Flipping the committed value fails every build,
// with or without an override.
val committedPublishingEnabled: String = java.util.Properties()
    .apply { file("gradle.properties").inputStream().use { load(it) } }
    .getProperty("spfn.publishing.enabled", "MISSING")
require(committedPublishingEnabled == "false")
{
    "gradle.properties commits spfn.publishing.enabled=$committedPublishingEnabled. " +
        "The committed value must stay false: publication may only be enabled per-run " +
        "with -Pspfn.publishing.enabled=true, and only towards a local staging " +
        "directory outside this repository. Publishing to any registry remains a " +
        "separate, unapproved decision."
}

// Enabling publication for a run is legal only as a CLI override AND only towards a
// local staging directory outside this repository, named by -Pspfn.staging.dir as an
// absolute path. `tools/validate/probe-publishing-gate.sh` proves each refusal below.
val stagingDir: File? =
    if (publishingEnabled)
    {
        val raw = providers.gradleProperty("spfn.staging.dir").orNull
        require(!raw.isNullOrBlank())
        {
            "Publication is enabled for this run but -Pspfn.staging.dir names no " +
                "target. A run without an explicit local staging directory has " +
                "nowhere legal to publish to."
        }
        val candidate = File(raw)
        require(candidate.isAbsolute)
        {
            "spfn.staging.dir must be an absolute path, got '$raw'."
        }
        val canonical = candidate.canonicalFile
        val repoRoot = rootDir.canonicalFile
        require(canonical != repoRoot && !canonical.path.startsWith(repoRoot.path + File.separator))
        {
            "spfn.staging.dir '$raw' resolves inside this repository. Staged artifacts " +
                "must never enter the tree; use a directory under \$TMPDIR."
        }
        canonical
    }
    else
    {
        null
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

// ---------------------------------------------------------------------------
// RC staging publication (Step 5, decision D3: no-publish candidate only).
// ---------------------------------------------------------------------------
// Exists ONLY when the gate above admitted the run: a CLI override plus a staging
// directory outside the repository. In every committed configuration this block is
// dead code — no maven-publish plugin is applied, no publishing task exists, and
// `./gradlew build` is byte-identical to what it was before this block.
//
// Coordinates are `xyz.superfunction.spfn:<module>:<version>`. That group is the D4
// PROPOSED value and D4 is NOT resolved: gradle.properties records
// `spfn.maven.group.verified=false`, the gate above requires it to stay false, and the
// POM description of every staged artifact restates it. Staged artifacts are candidate
// evidence, never publishable artifacts.
if (publishingEnabled)
{
    val proposedGroup: String = providers.gradleProperty("spfn.maven.group.proposed").get()
    val stagingUri = stagingDir!!.toURI()

    sdkModules.forEach { module ->
        module.pluginManager.apply("maven-publish")

        // maven-publish also registers an implicit install into ~/.m2 — a publication
        // target OUTSIDE the staging directory the gate just proved. Both the typed
        // install tasks and the `publishToMavenLocal` aggregate are disabled, so the
        // staging repository below is the only target that can actually run, not just
        // the only one declared.
        module.tasks.withType(org.gradle.api.publish.maven.tasks.PublishToMavenLocal::class.java)
            .configureEach {
                enabled = false
            }
        module.tasks.matching { it.name == "publishToMavenLocal" }.configureEach {
            enabled = false
        }

        module.plugins.withId("com.android.library") {
            module.extensions.configure(com.android.build.api.dsl.LibraryExtension::class.java) {
                publishing {
                    singleVariant("release") {
                        withSourcesJar()
                    }
                }
            }
        }

        // The `release` software component appears only once AGP finishes evaluating
        // the module — later than any afterEvaluate this script could register — so
        // the publication is created in reaction to the component itself.
        module.components.matching { it.name == "release" }.all {
            val releaseComponent = this
            module.extensions.configure(org.gradle.api.publish.PublishingExtension::class.java) {
                publications {
                    create("spfnRcStaging", org.gradle.api.publish.maven.MavenPublication::class.java) {
                        from(releaseComponent)
                        groupId = proposedGroup
                        artifactId = module.name
                        version = spfnVersion
                        pom {
                            name.set(module.name)
                            description.set(
                                (module.description ?: module.name) +
                                    " | groupId '$proposedGroup' is the PROPOSED Maven group " +
                                    "(docs/OPEN-DECISIONS.md D4, namespace control NOT verified); " +
                                    "this artifact is a local release-candidate staging output " +
                                    "and is not published to any registry."
                            )
                            licenses {
                                license {
                                    name.set("MIT License")
                                    url.set("https://opensource.org/license/mit/")
                                }
                            }
                        }
                    }
                }
                repositories {
                    // The ONLY publication target that can exist: the per-run local
                    // staging directory the gate already proved is outside the tree.
                    maven {
                        name = "spfnRcStaging"
                        url = stagingUri
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// SBOM generation (decision D7: CycloneDX, on-demand only).
// ---------------------------------------------------------------------------
// `cyclonedxDirectBom` / `cyclonedxBom` run only when named on the command line;
// nothing here attaches them to assemble, build or check. The aggregate covers the
// SDK modules alone: the build tools (:contract-codegen, :reference-server) never
// ship, so their dependencies do not belong in a candidate SBOM.
subprojects {
    if (projectDir.parentFile.name != "android")
    {
        tasks.matching { it.name == "cyclonedxDirectBom" }.configureEach {
            enabled = false
        }
    }
}

allprojects {
    tasks.withType(org.cyclonedx.gradle.CyclonedxDirectTask::class.java).configureEach {
        // Runtime dependencies of the shipped AAR only; test and tooling
        // configurations describe this repository, not the artifact.
        includeConfigs.set(listOf("releaseRuntimeClasspath"))
        includeBuildSystem.set(false)
    }
}

tasks.withType(org.cyclonedx.gradle.CyclonedxAggregateTask::class.java).configureEach {
    includeBuildSystem.set(false)
    // On-demand output redirection for tools/rc-verify: the SBOM lands in the run's
    // $TMPDIR output directory, never in the tree.
    val sbomDir = providers.gradleProperty("spfn.sbom.dir")
    if (sbomDir.isPresent)
    {
        jsonOutput.set(File(sbomDir.get(), "spfn-mobile-android-${spfnVersion}.cdx.json"))
        xmlOutput.set(File(sbomDir.get(), "spfn-mobile-android-${spfnVersion}.cdx.xml"))
    }
}

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
