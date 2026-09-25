// What every generated file says about where it came from.
//
// The shape is tools/contract-codegen's `Header.lines`: a generator name, the inputs by
// repository-relative path, and the digest of each of them. Nothing here is a timestamp, a
// host name or an absolute path — the header is part of the output, so anything in it that
// varied between two runs would make the generator non-deterministic (P8).

package xyz.superfunction.spfn.uicodegen

object Header
{
    /** The system property the Gradle tasks hand this generator its version in. */
    const val VERSION_PROPERTY: String = "spfn.ui-codegen.version";

    /**
     * What every header names as its generator, version and all.
     *
     * The version used to be `0.1.0-dev`, written here, while `gradle.properties` said
     * `0.1.0-alpha.3` — so every generated file claimed to come from a generator this
     * repository does not ship, and the two would go on disagreeing because nothing reads
     * both. It is now the repository's own version, handed over by the Gradle task that runs
     * this program.
     *
     * That makes the version an INPUT, exactly like the spec path: it reaches the output, so
     * a run that did not state it would be a run whose files claim a provenance nobody
     * supplied. Absent, it is a refusal rather than a default — a default would be the
     * `0.1.0-dev` problem again, wearing a fallback's clothes.
     */
    val GENERATOR: String get() = "spfn-ui-codegen " + version();

    private fun version(): String = System.getProperty(VERSION_PROPERTY)
        ?: throw IllegalStateException(
            "-D$VERSION_PROPERTY names no version; this generator's version is printed in every " +
                "file it writes, so it is an input the caller states — gradle.properties' " +
                "spfn.version is where the Gradle tasks read it from"
        );

    /** The first line of every header, and what tells a generated file from a written one. */
    const val MARK: String = "GENERATED FILE — DO NOT EDIT.";

    fun lines(inputs: Inputs): List<String> = listOf(
        MARK,
        "",
        "generator:       $GENERATOR",
        "spec:            ${inputs.specPath}",
        "specSha256:      ${inputs.specSha256}",
        "bundleSha256:    ${inputs.bundleSha256}",
        "contractVersion: ${inputs.contractVersion}",
        "",
        "Regenerate with: ./gradlew ${inputs.generateTask}",
        "Verified by:     ./gradlew ${inputs.verifyTask}"
    );

    /** The header as a `//` comment block, which is both languages' spelling. */
    fun slashes(inputs: Inputs): String =
        lines(inputs).joinToString("\n") { if (it.isEmpty()) "//" else "// $it" }

    /** The header as a `#` comment block, for YAML. */
    fun hashes(inputs: Inputs): String =
        lines(inputs).joinToString("\n") { if (it.isEmpty()) "#" else "# $it" }
}

/**
 * What a generated file is a pure function of, named exactly.
 *
 * The two digests are the bytes; [specPath] is here because the header prints it, which
 * makes the path an input to the output and not merely how the run was invoked. It is
 * repository-relative for that reason. The two task names are here for the same reason and
 * are the one thing a header takes from the target rather than from the spec.
 */
data class Inputs(
    val specPath: String,
    val specSha256: String,
    val bundleSha256: String,
    val contractVersion: String,

    /**
     * The two Gradle tasks the header tells its reader to run.
     *
     * They belong to the TARGET rather than to the spec, and they are the only thing in
     * a header that does. A header that named the example app's task inside the harness's
     * scaffold would send a reader to run something that rewrites another app and reports
     * nothing changed here (`Target.generateTask`).
     */
    val generateTask: String,
    val verifyTask: String
)
