// SPFN Mobile — client generator for an app's own contract document.
//
// Usage (from Gradle, which passes every argument; see tools/contract-codegen/README.md):
//   spfnAppContractGenerate   # rewrite the generated sources
//   spfnAppContractVerify     # fail if they are stale or hand-edited
//
// Same two properties as the core generator: zero network — the document, the selection
// and the SDK's pinned contract are read from disk — and deterministic output, a pure
// function of the document bytes, the selection and the names the consumer passes.

package xyz.superfunction.spfn.codegen

import java.io.File
import java.security.MessageDigest

object AppHeader
{
    const val DIGEST_LABEL = "documentSha256:"

    fun lines(contract: AppContract): List<String> = listOf(
        "GENERATED FILE — DO NOT EDIT.",
        "",
        "generator:       ${Header.GENERATOR} (app contract)",
        "$DIGEST_LABEL  ${contract.sha256}",
        "documentVersion: ${contract.documentVersion}",
        "operations:      ${contract.operationNames.joinToString(", ")}",
        "",
        "Regenerate with the consumer's spfnAppContractGenerate run. Its spfnAppContractVerify",
        "fails the build when the document changed or this file was edited."
    )
}

/** What the consumer passes. Every value is required; none has a default. */
data class AppContractOptions(
    val mode: String,
    val document: File,
    val operations: File,
    val swiftOut: File,
    val swiftNamespace: String,
    val kotlinOut: File,
    val kotlinPackage: String,
    val sdkRoot: File
)
{
    companion object
    {
        private val KEYS = listOf(
            "mode", "document", "operations", "swift-out", "swift-namespace", "kotlin-out", "kotlin-package", "sdk-root"
        );

        fun parse(args: Array<String>): AppContractOptions
        {
            val values = pairs(args);
            val missing = KEYS.filter { values[it].isNullOrBlank() };
            if (missing.isNotEmpty())
            {
                throw JsonException("missing ${missing.joinToString(", ") { "--$it" }}");
            }
            return AppContractOptions(
                mode = values.getValue("mode"),
                document = absolute(values.getValue("document"), "document"),
                operations = absolute(values.getValue("operations"), "operations"),
                swiftOut = absolute(values.getValue("swift-out"), "swift-out"),
                swiftNamespace = namespace(values.getValue("swift-namespace")),
                kotlinOut = absolute(values.getValue("kotlin-out"), "kotlin-out"),
                kotlinPackage = packageName(values.getValue("kotlin-package")),
                sdkRoot = absolute(values.getValue("sdk-root"), "sdk-root")
            );
        }

        private fun pairs(args: Array<String>): Map<String, String>
        {
            if (args.size % 2 != 0)
            {
                throw JsonException("arguments come in '--name value' pairs");
            }
            return args.toList().chunked(2).associate { (name, value) ->
                val key = name.removePrefix("--");
                if (!name.startsWith("--") || key !in KEYS)
                {
                    throw JsonException("unknown argument '$name'; expected ${KEYS.joinToString(", ") { "--$it" }}");
                }
                key to value
            };
        }

        /** Absolute only: a relative path would be read against whichever directory Gradle ran in. */
        private fun absolute(path: String, name: String): File
        {
            val file = File(path);
            if (!file.isAbsolute)
            {
                throw JsonException("--$name must be an absolute path, got '$path'");
            }
            return file;
        }

        private fun namespace(name: String): String
        {
            if (!Regex("[A-Z][A-Za-z0-9]*").matches(name))
            {
                throw JsonException("--swift-namespace '$name' is not an ASCII type name ([A-Z][A-Za-z0-9]*)");
            }
            return name;
        }

        private fun packageName(name: String): String
        {
            if (!Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*").matches(name))
            {
                throw JsonException("--kotlin-package '$name' is not a lower-case Kotlin package name");
            }
            return name;
        }
    }
}

private class AppGenerationFailure(message: String) : RuntimeException(message)

fun main(args: Array<String>)
{
    try
    {
        val options = AppContractOptions.parse(args);
        val documentBytes = options.document.readBytes();
        val contract = AppContractGeneration.read(options, documentBytes);
        val outputs = AppContractGeneration.outputs(contract, options);
        when (options.mode)
        {
            "write" -> outputs.forEach { it.write() }
            "verify" -> AppContractGeneration.verify(outputs, contract.sha256)
            else -> throw AppGenerationFailure("unknown --mode '${options.mode}'; expected write or verify")
        }
    }
    catch (failure: RuntimeException)
    {
        System.err.println("contract-codegen (app contract): ${failure.message}");
        kotlin.system.exitProcess(1);
    }
}

object AppContractGeneration
{
    fun read(options: AppContractOptions, documentBytes: ByteArray): AppContract = AppContractReader.read(
        documentText = documentBytes.toString(Charsets.UTF_8),
        sha256 = sha256Hex(documentBytes),
        selectionText = options.operations.readText(Charsets.UTF_8),
        authClasses = ContractPin.loadBundle(options.sdkRoot).authClasses.toSet()
    )

    fun outputs(contract: AppContract, options: AppContractOptions): List<OutputDirectory>
    {
        checkNamespace(contract, options.swiftNamespace);
        return listOf(
            OutputDirectory(options.swiftOut, AppSwiftEmitter(contract, options.swiftNamespace).emit()),
            OutputDirectory(options.kotlinOut, AppKotlinEmitter(contract, options.swiftNamespace, options.kotlinPackage).emit())
        );
    }

    /** The namespace and its helper object are names too, and a generated type must not take either (R9). */
    private fun checkNamespace(contract: AppContract, namespace: String)
    {
        val taken = contract.types.map { AppSwiftTypes.typeName(it) }.toSet();
        listOf(namespace, "${namespace}Support").filter { it in taken }.forEach {
            throw JsonException("the generated type '$it' collides with the namespace the consumer named (R9)");
        };
    }

    /**
     * Digest first, then bytes, then leftovers — so the message names the likeliest cause:
     * a changed document without regeneration, then a hand edit, then a ghost file.
     */
    fun verify(outputs: List<OutputDirectory>, sha256: String)
    {
        val problems = outputs.flatMap { it.staleDigests(sha256) }.ifEmpty { outputs.flatMap { it.differences() } };
        if (problems.isNotEmpty())
        {
            throw AppGenerationFailure(
                "generated sources are not up to date:\n  " + problems.joinToString("\n  ") +
                    "\nRun spfnAppContractGenerate with the same properties."
            );
        }
        println("contract-codegen (app contract): ${outputs.sumOf { it.files.size }} generated files match document $sha256");
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** One output directory, which holds generated files and nothing else. */
class OutputDirectory(private val directory: File, val files: Map<String, String>)
{
    fun write()
    {
        stale().forEach { name ->
            File(directory, name).delete();
            println("removed   ${File(directory, name)}");
        };
        directory.mkdirs();
        files.toSortedMap().forEach { (name, content) ->
            val target = File(directory, name);
            val changed = !target.isFile || target.readText() != content;
            if (changed)
            {
                target.writeText(content);
            }
            println("${if (changed) "wrote    " else "unchanged"} $target");
        };
    }

    /** Files whose header names a document other than the one read now. */
    fun staleDigests(sha256: String): List<String> = files.keys.sorted().mapNotNull { name ->
        val target = File(directory, name);
        val recorded = if (target.isFile) recordedDigest(target) else null;
        if (recorded != null && recorded != sha256)
            "$target was generated from document $recorded; the document is now $sha256"
        else null
    }

    fun differences(): List<String>
    {
        val problems = files.keys.sorted().mapNotNull { name ->
            val target = File(directory, name);
            when
            {
                !target.isFile -> "$target is missing"
                target.readText() != files.getValue(name) -> "$target differs from freshly generated output"
                else -> null
            }
        };
        return problems + stale().map { "${File(directory, it)} is not a generated file" };
    }

    private fun recordedDigest(file: File): String? =
        file.useLines { lines -> lines.take(12).firstOrNull { it.contains(AppHeader.DIGEST_LABEL) } }
            ?.substringAfter(AppHeader.DIGEST_LABEL)?.trim()

    /** Hidden files are skipped: Finder writes `.DS_Store` into any folder it has shown. */
    private fun stale(): List<String> =
        directory.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && it.name !in files }
            ?.map { it.name }?.sorted() ?: emptyList()
}
