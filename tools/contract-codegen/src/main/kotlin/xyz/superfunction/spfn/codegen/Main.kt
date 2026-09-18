// SPFN Mobile — contract client generator.
//
// Usage (from Gradle, which supplies the repository root):
//   ./gradlew :contract-codegen:spfnGenerateClients   # rewrite the generated sources
//   ./gradlew :contract-codegen:spfnCodegenVerify     # fail if they are not up to date
//
// Two properties hold this together:
//   - Zero network. The only input is the vendored bundle on disk.
//   - Deterministic. Output is a pure function of the bundle bytes: no timestamp, no
//     host name, no absolute path, no unordered iteration.

package xyz.superfunction.spfn.codegen

import java.io.File

object Header
{
    const val GENERATOR: String = "spfn-contract-codegen 0.2.0-dev"

    fun lines(bundle: Bundle): List<String> = listOf(
        "GENERATED FILE — DO NOT EDIT.",
        "",
        "generator:       $GENERATOR",
        "bundle:          Contracts/spfn-mobile-contract.json",
        "bundleSha256:    ${bundle.sha256}",
        "contractVersion: ${bundle.contractVersion}",
        "origin:          ${bundle.origin}",
        "",
        originCaveat(bundle.origin),
        "",
        "Regenerate with: ./gradlew :contract-codegen:spfnGenerateClients",
        "Verified by:     ./gradlew :contract-codegen:spfnCodegenVerify"
    )

    private fun originCaveat(origin: String): String = when (origin)
    {
        "spfn-mobile-step2-dev-bundle" ->
            "The bundle was hand-authored in this repository and was NOT exported by SPFN primitives CI."
        else -> "Bundle origin: $origin."
    }
}

private class GenerationFailure(message: String) : RuntimeException(message)

fun main(args: Array<String>)
{
    if (args.size < 2)
    {
        System.err.println("usage: codegen <repoRoot> <write|verify>");
        kotlin.system.exitProcess(2);
    }

    val repoRoot = File(args[0]);
    val mode = args[1];

    try
    {
        val bundle = ContractPin.loadBundle(repoRoot);
        val generated = SwiftEmitter.emit(bundle) + KotlinEmitter.emit(bundle);

        when (mode)
        {
            "write" -> write(repoRoot, generated)
            "verify" -> verify(repoRoot, generated)
            else -> throw GenerationFailure("unknown mode '$mode'; expected write or verify")
        }
    }
    catch (failure: RuntimeException)
    {
        System.err.println("contract-codegen: ${failure.message}");
        kotlin.system.exitProcess(1);
    }
}

private fun write(repoRoot: File, generated: Map<String, String>)
{
    removeStaleOutputs(repoRoot, generated.keys);

    generated.toSortedMap().forEach { (path, content) ->
        val target = File(repoRoot, path);
        target.parentFile?.mkdirs();
        val existing = if (target.isFile) target.readText() else null;
        if (existing != content)
        {
            target.writeText(content);
            println("wrote    $path");
        }
        else
        {
            println("unchanged $path");
        }
    }
}

private fun verify(repoRoot: File, generated: Map<String, String>)
{
    val problems = mutableListOf<String>();

    generated.toSortedMap().forEach { (path, content) ->
        val target = File(repoRoot, path);
        if (!target.isFile)
        {
            problems += "$path is missing";
            return@forEach;
        }
        if (target.readText() != content)
        {
            problems += "$path differs from freshly generated output";
        }
    }

    staleOutputs(repoRoot, generated.keys).forEach { problems += "${it} is a stale generated file" }

    if (problems.isNotEmpty())
    {
        throw GenerationFailure(
            "generated sources are not up to date:\n  " + problems.joinToString("\n  ") +
                "\nRun ./gradlew :contract-codegen:spfnGenerateClients"
        );
    }
    println("contract-codegen: ${generated.size} generated files match the pinned bundle");
}

/**
 * Generated directories hold nothing but generated files, so a leftover from an
 * earlier contract has to disappear rather than linger as a compiling ghost.
 */
private fun staleOutputs(repoRoot: File, expected: Set<String>): List<String>
{
    val directories = expected.map { it.substringBeforeLast('/') }.toSortedSet();
    val stale = mutableListOf<String>();

    directories.forEach { directory ->
        val dir = File(repoRoot, directory);
        if (!dir.isDirectory)
        {
            return@forEach;
        }
        dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
            val relative = "$directory/${file.name}";
            if (file.isFile && relative !in expected)
            {
                stale += relative;
            }
        }
    }
    return stale;
}

private fun removeStaleOutputs(repoRoot: File, expected: Set<String>)
{
    staleOutputs(repoRoot, expected).forEach { relative ->
        File(repoRoot, relative).delete();
        println("removed  $relative");
    }
}

