// SPFN Mobile — screen scaffold generator.
//
// Usage (from Gradle, which supplies the repository root):
//   ./gradlew :ui-codegen:spfnGenerateUi   # rewrite the scaffolds, the table and the flows
//   ./gradlew :ui-codegen:spfnUiVerify     # fail if any of them is not up to date
//
// The same two properties the contract generator holds to:
//   - Zero network. The inputs are the vendored bundle and the spec, both on disk.
//   - Deterministic. Output is a pure function of the SPEC BYTES, the BUNDLE BYTES, the
//     spec's repository-relative PATH and the lock's CONTRACT BLOCK: no timestamp, no
//     host name, no absolute path, no unordered iteration.
//
// The last two of those four are named because they are real and easy to miss. The path
// is in every generated header and in the case table's `spec` field, which is what makes
// it an input rather than an invocation detail — so it is kept repository-relative, and
// generating the same spec through a path with a `../` in it is a different output. The
// lock's contract block decides which file the bundle bytes are read from and refuses the
// whole run when its digest disagrees with them; nothing else of it reaches the output,
// and the `contractVersion` a header carries is the bundle's own field.
//
// And one more of its own: verification covers the FLOWS and the CASE TABLE as well as
// the sources. The table is the artefact both runners read, and an unverified table is not
// evidence of anything.

package xyz.superfunction.spfn.uicodegen

import java.io.File
import java.security.MessageDigest
import xyz.superfunction.spfn.codegen.Bundle
import xyz.superfunction.spfn.codegen.Json
import xyz.superfunction.spfn.codegen.number
import xyz.superfunction.spfn.codegen.obj
import xyz.superfunction.spfn.codegen.required
import xyz.superfunction.spfn.codegen.text

private class GenerationFailure(message: String) : RuntimeException(message)

fun main(args: Array<String>)
{
    if (args.size < 3)
    {
        System.err.println("usage: ui-codegen <repoRoot> <specPath> <write|verify>");
        kotlin.system.exitProcess(2);
    }

    val repoRoot = File(args[0]);
    val specPath = args[1];
    val mode = args[2];

    try
    {
        val generated = generate(repoRoot, specPath);
        when (mode)
        {
            "write" -> write(repoRoot, generated)
            "verify" -> verify(repoRoot, generated)
            else -> throw GenerationFailure("unknown mode '$mode'; expected write or verify")
        }
    }
    catch (failure: RuntimeException)
    {
        System.err.println("ui-codegen: ${failure.message}");
        kotlin.system.exitProcess(1);
    }
}

/** Every file this generator owns, by repository-relative path. */
fun generate(repoRoot: File, specPath: String): Map<String, String>
{
    val specFile = File(repoRoot, specPath);
    if (!specFile.isFile)
    {
        throw GenerationFailure("missing $specPath");
    }
    val specBytes = specFile.readBytes();
    val bundle = loadBundle(repoRoot);
    val spec = Spec.read(String(specBytes, Charsets.UTF_8), bundle);

    // The digest gate, in the direction the spec adds. `loadBundle` already refused a
    // bundle whose bytes disagree with the lock; this refuses a SPEC written against a
    // different bundle than the one pinned now. Both are needed: they are different
    // mistakes, and only the second one can arrive with the lock untouched.
    if (spec.manifestSha256 != bundle.sha256)
    {
        throw GenerationFailure(
            "spec digest mismatch for $specPath\n" +
                "  spec says:  ${spec.manifestSha256}\n" +
                "  bundle is:  ${bundle.sha256}\n" +
                "Refusing to generate. The spec was written against a different contract bundle."
        );
    }

    val inputs = Inputs(
        specPath = specPath,
        specSha256 = sha256Hex(specBytes),
        bundleSha256 = bundle.sha256,
        contractVersion = bundle.contractVersion
    );
    val cells = Rules.cells(spec, bundle);

    return KotlinEmitter.emit(spec, bundle, inputs) +
        SwiftEmitter.emit(spec, bundle, inputs) +
        CaseTable.emit(spec, cells, inputs);
}

/**
 * Reads the lock, recomputes the bundle digest and refuses to continue when they
 * disagree — the same gate `tools/contract-codegen` opens with, for the same reason: a
 * generated header that names a digest has to have been produced from a file with it.
 *
 * This is the fourth reader of the pinned digest and the second with a ROLE
 * (docs/IMPLEMENTATION-PITFALLS.md P2): like the contract generator, it is a consumer
 * that recomputes and compares, never a place the value is edited.
 */
private fun loadBundle(repoRoot: File): Bundle
{
    val lockFile = File(repoRoot, "Contracts/upstream.lock.json");
    if (!lockFile.isFile)
    {
        throw GenerationFailure("missing ${lockFile.path}");
    }

    val lock = Json.parse(lockFile.readText()).obj();
    val contract = lock.required("contract").obj();
    val bundlePath = contract.required("bundlePath").text();
    val expectedDigest = contract.required("manifestSha256").text();

    val bundleFile = File(repoRoot, bundlePath);
    if (!bundleFile.isFile)
    {
        throw GenerationFailure("lock points at $bundlePath, which does not exist");
    }

    val bytes = bundleFile.readBytes();
    val actualDigest = sha256Hex(bytes);
    if (actualDigest != expectedDigest)
    {
        throw GenerationFailure(
            "bundle digest mismatch for $bundlePath\n" +
                "  lock says: $expectedDigest\n" +
                "  file is:   $actualDigest\n" +
                "Refusing to generate."
        );
    }

    return Bundle.read(
        bundleText = String(bytes, Charsets.UTF_8),
        sha256 = actualDigest,
        supportedRange = contract.required("supportedRange").text(),
        contractMajor = contract.required("major").number().toInt(),
        contractMinor = contract.required("minor").number().toInt()
    );
}

private fun write(repoRoot: File, generated: Map<String, String>)
{
    staleOutputs(repoRoot, generated.keys).forEach { relative ->
        File(repoRoot, relative).delete();
        println("removed  $relative");
    };

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
    };
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
    };

    staleOutputs(repoRoot, generated.keys).forEach { problems += "$it is a stale generated file" };

    if (problems.isNotEmpty())
    {
        throw GenerationFailure(
            "generated screen scaffolds are not up to date:\n  " + problems.joinToString("\n  ") +
                "\nRun ./gradlew :ui-codegen:spfnGenerateUi"
        );
    }
    println("ui-codegen: ${generated.size} generated files match the pinned bundle and the spec");
}

/**
 * Generated directories hold nothing but generated files, so a leftover from an earlier
 * spec has to disappear rather than linger as a compiling ghost — the rule
 * tools/contract-codegen already applies to its own output.
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
        };
    };
    return stale;
}

private fun sha256Hex(bytes: ByteArray): String
{
    val digits = "0123456789abcdef";
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes);
    val out = StringBuilder(digest.size * 2);
    for (byte in digest)
    {
        val value = byte.toInt() and 0xFF;
        out.append(digits[value shr 4]);
        out.append(digits[value and 0x0F]);
    }
    return out.toString();
}
