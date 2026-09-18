// SPFN Mobile — screen scaffold generator.
//
// Usage (from Gradle, which supplies the repository root and the target):
//   ./gradlew :ui-codegen:spfnGenerateUi         # the example apps, the table and the flows
//   ./gradlew :ui-codegen:spfnGenerateHarnessUi  # the harness apps' scaffolds
//   ./gradlew :ui-codegen:spfnUiVerify           # fail if either is not up to date
//
// A flow whose `views` are `authored` is the one thing a run leaves alone: its view files
// are written by hand from a contract document, so they are neither emitted nor deleted as
// stale, and `verify` has nothing to compare them against (`Generated.authoredViews`).
//
// One spec, one or more CONSUMERS. Which app a run writes into is a `Target` the caller
// supplies — output roots, Kotlin package and application id — so this generator names no
// app of its own and a second consumer costs a task rather than an edit here.
//
// The same two properties the contract generator holds to:
//   - Zero network. The inputs are the vendored bundle and the spec, both on disk.
//   - Deterministic. Output is a pure function of the SPEC BYTES, the BUNDLE BYTES, the
//     spec's repository-relative PATH and the PINNED CONTRACT: no timestamp, no host
//     name, no absolute path, no unordered iteration.
//
// The spec is a PLACE rather than a file. `<specPath>` is either one JSON file or a
// directory holding the JSON beside the contract documents whose machine blocks carry the
// rest, and `SpecInput` is where the pieces are read, refused and merged. "The spec bytes"
// above is therefore the digest of the pieces, which `SpecInput.digest` states exactly.
//
// And of a contract document, THE PROSE IS NOT AN INPUT TO THE GENERATOR; THE BLOCK IS. A
// document's `json spfn-ui` block is what is read and what is digested; the prose around it
// is what a person rewrites while the screens stay what they were, so rewording a sentence
// leaves every generated header where it stood (`SpecInput.digestInput`).
//
// The last two of those four are named because they are real and easy to miss. The path
// is in every generated header and in the case table's `spec` field, which is what makes
// it an input rather than an invocation detail — so it is kept repository-relative, and
// generating the same spec through a path with a `../` in it is a different output. The
// pin decides which file the bundle bytes are read from — the lock's `contract.bundlePath`
// — and refuses the whole run when `Contracts/upstream-provenance.json`'s digest disagrees
// with them; nothing else of either file reaches the output, and the `contractVersion` a
// header carries is the bundle's own field.
//
// And one more of its own: verification covers the FLOWS and the CASE TABLE as well as
// the sources. The table is the artefact both runners read, and an unverified table is not
// evidence of anything. Those two artefacts belong to the target that declares a table
// root, which is the app whose fixtures the cells name — see `Target.tableRoot`.

package xyz.superfunction.spfn.uicodegen

import java.io.File
import xyz.superfunction.spfn.codegen.ContractPin

private class GenerationFailure(message: String) : RuntimeException(message)

/** An invocation this program cannot read at all, which is a different exit code. */
private class UsageFailure(message: String) : RuntimeException(message)

fun main(args: Array<String>)
{
    try
    {
        execute(args);
    }
    catch (usage: UsageFailure)
    {
        System.err.println(usage.message);
        kotlin.system.exitProcess(2);
    }
    catch (failure: RuntimeException)
    {
        System.err.println("ui-codegen: ${failure.message}");
        kotlin.system.exitProcess(1);
    }
}

/**
 * One invocation, as something that REFUSES rather than exits.
 *
 * Split from `main` so every refusal on this path can be read by a test: `exitProcess` ends
 * the JVM, so a suite calling `main` would end itself, and an unknown mode, a missing
 * argument and a spec path that is not there went untested for exactly that reason. `main`
 * is now the two lines that turn a refusal into an exit code, and everything a run decides
 * is here.
 */
internal fun execute(args: Array<String>)
{
    if (args.size < 3)
    {
        throw UsageFailure(
            "usage: ui-codegen <repoRoot> <specFileOrDirectory> <write|verify> --target=<name> " +
                "--swift-root=<dir> --kotlin-root=<dir> --kotlin-package=<pkg> --app-id=<id> " +
                "--generate-task=<task> --verify-task=<task> [--table-root=<dir>] [--flows=<a,b>] " +
                "[--runner-readouts=<true|false>]"
        );
    }

    val repoRoot = File(args[0]);
    val specPath = args[1];
    val mode = args[2];

    val target = Target.parse(args.drop(3));
    val generated = generate(repoRoot, specPath, target);
    when (mode)
    {
        "write" -> write(repoRoot, generated)
        "verify" -> verify(repoRoot, target, generated)
        else -> throw GenerationFailure("unknown mode '$mode'; expected write or verify")
    }
}

/**
 * What one run produced: the files it owns, and the ones it must not touch.
 *
 * [authoredViews] is the second half because "a generated directory holds nothing but
 * generated files" is otherwise a rule that eats a person's work. A flow whose `views` are
 * `authored` has its screens written by hand from a contract document, so this run neither
 * writes those files nor counts them stale — and `verify` has nothing to compare them
 * against, which is the whole point of the switch.
 */
data class Generated(val files: Map<String, String>, val authoredViews: Set<String>)

/**
 * Every file this generator owns for [target], by repository-relative path.
 *
 * The target decides WHERE the scaffold lands and which app id the table prints; it
 * decides nothing about what the scaffold says. Two targets generated from one spec
 * differ in their paths, their Kotlin package and — for a target that emits the table at
 * all — that one printed id, and in nothing else.
 */
fun generate(repoRoot: File, specPath: String, target: Target): Generated
{
    val bundle = ContractPin.loadBundle(repoRoot);
    val source = SpecInput.read(repoRoot, specPath, bundle);
    val whole = source.spec;
    // Read whole and narrowed after, so a flow this target does not want is still checked
    // before it is dropped: a target cannot hide a broken flow by not asking for it.
    val spec = whole.narrowedTo(target.flows);

    // The digest gate, in the direction the spec adds. `ContractPin.loadBundle` already
    // refused a bundle whose bytes disagree with the provenance digest; this refuses a
    // SPEC written against a different bundle than the one pinned now. Both are needed:
    // they are different mistakes, and only the second one can arrive with the pin
    // untouched.
    if (whole.manifestSha256 != bundle.sha256)
    {
        throw GenerationFailure(
            "spec digest mismatch for $specPath\n" +
                "  spec says:  ${whole.manifestSha256}\n" +
                "  bundle is:  ${bundle.sha256}\n" +
                "Refusing to generate. The spec was written against a different contract bundle."
        );
    }

    val inputs = Inputs(
        specPath = specPath,
        specSha256 = source.sha256,
        bundleSha256 = bundle.sha256,
        contractVersion = bundle.contractVersion,
        generateTask = target.generateTask,
        verifyTask = target.verifyTask
    );
    val kotlin = KotlinEmitter(target);
    val swift = SwiftEmitter(target);
    val scaffolds = kotlin.emit(spec, bundle, inputs) + swift.emit(spec, bundle, inputs);

    // The table and the flows are the SPEC's artefacts and belong to the one app that
    // installs the fixtures their cells name, so a target that declares no table root
    // gets the scaffolds and nothing else (decision E6).
    val files = if (target.tableRoot == null)
    {
        scaffolds
    }
    else
    {
        scaffolds + CaseTable(target).emit(spec, Rules.cells(spec, bundle), inputs)
    };
    // The exemptions come from the WHOLE spec and not from the narrowed one. A flow this
    // target dropped is a flow this run neither writes nor knows, and an authored flow's
    // views sit in a directory this run owns: computed from the narrowed spec, they are
    // neither generated nor exempt, which is precisely the state `staleOutputs` deletes.
    return Generated(files, kotlin.authoredViews(whole) + swift.authoredViews(whole));
}

private fun write(repoRoot: File, generated: Generated)
{
    staleOutputs(repoRoot, generated).forEach { relative ->
        // The answer is read. `delete` returns false for a file it did not remove — a
        // read-only directory, a file another process holds open — and printing `removed`
        // over that is this run reporting a tree it did not produce: the next `verify`
        // fails on the same leftover, naming a file the generator said it had deleted.
        if (!File(repoRoot, relative).delete())
        {
            throw GenerationFailure(
                "could not remove $relative, which is a stale generated file; the run stops here " +
                    "rather than reporting a tree it did not write"
            );
        }
        println("removed  $relative");
    };

    generated.files.toSortedMap().forEach { (path, content) ->
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

private fun verify(repoRoot: File, target: Target, generated: Generated)
{
    val problems = mutableListOf<String>();

    generated.files.toSortedMap().forEach { (path, content) ->
        val checked = File(repoRoot, path);
        if (!checked.isFile)
        {
            problems += "$path is missing";
            return@forEach;
        }
        if (checked.readText() != content)
        {
            problems += "$path differs from freshly generated output";
        }
    };

    staleOutputs(repoRoot, generated).forEach { problems += "$it is a stale generated file" };

    if (problems.isNotEmpty())
    {
        throw GenerationFailure(
            "the ${target.name} target's screen scaffolds are not up to date:\n  " +
                problems.joinToString("\n  ") +
                "\nRun ./gradlew ${target.generateTask}"
        );
    }
    println(
        "ui-codegen: ${generated.files.size} generated files for the ${target.name} target " +
            "match the pinned bundle and the spec"
    );
}

/**
 * Generated directories hold nothing but generated files, so a leftover from an earlier
 * spec has to disappear rather than linger as a compiling ghost — the rule
 * tools/contract-codegen already applies to its own output.
 *
 * The one exception is an AUTHORED flow's views. Those files sit in a directory this
 * generator owns and are written by a person, so the rule above would delete them on the
 * next run and `verify` would report them as leftovers from a spec nobody has. They are
 * named by the emitters rather than recognised by their contents: a file exempted because
 * it lacks a generated header would exempt a generated file somebody had edited the header
 * out of, which is the drift this whole gate exists to catch.
 *
 * Not-writable rather than not-written: a screen of an authored flow whose file is MISSING
 * is not a problem this generator can see, and section 21 of tools/validate/validate.sh is
 * where it is caught.
 */
internal fun staleOutputs(repoRoot: File, generated: Generated): List<String>
{
    val expected = generated.files.keys;
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
            if (file.isFile && relative !in expected && relative !in generated.authoredViews)
            {
                stale += relative;
            }
        };
    };
    return stale;
}

