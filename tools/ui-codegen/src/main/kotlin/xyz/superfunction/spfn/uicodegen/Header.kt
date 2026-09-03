// What every generated file says about where it came from.
//
// The shape is tools/contract-codegen's `Header.lines`: a generator name, the inputs by
// repository-relative path, and the digest of each of them. Nothing here is a timestamp, a
// host name or an absolute path — the header is part of the output, so anything in it that
// varied between two runs would make the generator non-deterministic (P8).

package xyz.superfunction.spfn.uicodegen

object Header
{
    const val GENERATOR: String = "spfn-ui-codegen 0.1.0-dev";

    fun lines(inputs: Inputs): List<String> = listOf(
        "GENERATED FILE — DO NOT EDIT.",
        "",
        "generator:       $GENERATOR",
        "spec:            ${inputs.specPath}",
        "specSha256:      ${inputs.specSha256}",
        "bundleSha256:    ${inputs.bundleSha256}",
        "contractVersion: ${inputs.contractVersion}",
        "",
        "Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi",
        "Verified by:     ./gradlew :ui-codegen:spfnUiVerify"
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
 * repository-relative for that reason.
 */
data class Inputs(
    val specPath: String,
    val specSha256: String,
    val bundleSha256: String,
    val contractVersion: String
)
