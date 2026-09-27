// The app-contract input, naming, determinism and provenance rules
// (docs/architecture/app-contract-codegen.md §1, §3, §5).

package xyz.superfunction.spfn.codegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class AppContractInputTest
{
    private val fixtureDirectory = File("src/test/resources/app-contract");

    private val fixture: String = File(fixtureDirectory, "contract.json").readText();

    private val fixtureSelection: String = File(fixtureDirectory, "operations.json").readText();

    private fun refusedSaying(fragment: String, document: String, selection: String = AppDocuments.selection("getThing"))
    {
        val message = AppDocuments.refusal(document, selection);
        assertTrue("the refusal does not say '$fragment': $message", message.contains(fragment));
    }

    // ---- §1 the document and the selection ---------------------------------

    @Test
    fun `a document version other than 1 is refused by name`()
    {
        refusedSaying("documentVersion 2 is not supported", AppDocuments.document(AppDocuments.operation()).replace("\"documentVersion\":1", "\"documentVersion\":2"));
    }

    @Test
    fun `an unknown top-level key is refused`()
    {
        refusedSaying("extensions", AppDocuments.document(AppDocuments.operation()).replace("{\"compat", "{\"extensions\":{},\"compat"));
    }

    @Test
    fun `a selected name the document does not declare is refused`()
    {
        refusedSaying("getNothing, which the document does not declare", AppDocuments.document(AppDocuments.operation()), AppDocuments.selection("getThing", "getNothing"));
    }

    @Test
    fun `a repeated or empty selection is refused`()
    {
        refusedSaying("more than once", AppDocuments.document(AppDocuments.operation()), AppDocuments.selection("getThing", "getThing"));
        refusedSaying("names no operation", AppDocuments.document(AppDocuments.operation()), AppDocuments.selection());
    }

    @Test
    fun `an operation declared twice is refused`()
    {
        refusedSaying("declares operation 'getThing' twice", AppDocuments.document(AppDocuments.operation(), AppDocuments.operation()));
    }

    @Test
    fun `an unselected operation is not read deeply, and a fraction elsewhere in the document is accepted`()
    {
        val contract = AppContractReader.read(fixture, "0".repeat(64), fixtureSelection, AppDocuments.AUTH_CLASSES);
        assertEquals(listOf("getItem", "listItems", "putItemNote"), contract.operationNames);
    }

    // ---- §1-3 and §5: determinism -------------------------------------------

    @Test
    fun `output does not depend on the document's key order or the selection's order`()
    {
        val reordered = Json.parse(fixture, allowFractions = true).let { reversedKeys(it) }.let { write(it) };
        val selection = AppDocuments.selection("getItem", "putItemNote", "listItems");
        val one = AppSwiftEmitter(AppContractReader.read(fixture, "0".repeat(64), fixtureSelection, AppDocuments.AUTH_CLASSES), "X").emit();
        val two = AppSwiftEmitter(AppContractReader.read(reordered, "0".repeat(64), selection, AppDocuments.AUTH_CLASSES), "X").emit();
        assertEquals(one, two);
        val kotlinOne = AppKotlinEmitter(AppContractReader.read(fixture, "0".repeat(64), fixtureSelection, AppDocuments.AUTH_CLASSES), "X", "x").emit();
        val kotlinTwo = AppKotlinEmitter(AppContractReader.read(reordered, "0".repeat(64), selection, AppDocuments.AUTH_CLASSES), "X", "x").emit();
        assertEquals(kotlinOne, kotlinTwo);
    }

    private fun reversedKeys(value: JsonValue): JsonValue = when (value)
    {
        is JsonValue.Obj -> JsonValue.Obj(LinkedHashMap(value.members.entries.reversed().associate { it.key to reversedKeys(it.value) }))
        is JsonValue.Arr -> JsonValue.Arr(value.elements.map { reversedKeys(it) })
        else -> value
    }

    private fun write(value: JsonValue): String = when (value)
    {
        is JsonValue.Obj -> value.members.entries.joinToString(",", "{", "}") { "\"${it.key}\":${write(it.value)}" }
        is JsonValue.Arr -> value.elements.joinToString(",", "[", "]") { write(it) }
        is JsonValue.Text -> "\"${value.value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        is JsonValue.Number -> value.value.toString()
        is JsonValue.Fraction -> value.text
        is JsonValue.Bool -> value.value.toString()
        JsonValue.Null -> "null"
    }

    // ---- §3 naming -----------------------------------------------------------

    @Test
    fun `nested types are named from the property path with Item for an array element`()
    {
        val contract = AppContractReader.read(fixture, "0".repeat(64), fixtureSelection, AppDocuments.AUTH_CLASSES);
        val names = contract.types.map { AppSwiftTypes.typeName(it) };
        listOf(
            "ListItemsResponse", "ListItemsResponseItemsItem", "ListItemsResponseItemsItemOwner",
            "ListItemsResponseItemsItemReviewersItem", "ListItemsResponseItemsItemKind", "ListItemsQuerySort",
            "GetItemResponseItem", "PutItemNoteBody", "PutItemNoteBodyAttachmentsItem", "PutItemNoteBodyMode"
        ).forEach { assertTrue("no type $it in $names", it in names) };
        assertEquals(names.sorted(), names);
    }

    @Test
    fun `keyword property names are escaped, not renamed`()
    {
        val document = AppDocuments.response(""""default":{"type":"string"},"in":{"type":"boolean"},"object":{"type":"string"}""", "\"default\",\"in\",\"object\"");
        val swift = AppDocuments.swift(document);
        val kotlin = AppDocuments.kotlin(document);
        assertTrue(swift.contains("public var `default`: String") && swift.contains("public var `in`: Bool"));
        assertTrue(swift.contains("members[\"default\"]") && swift.contains("public var object: String"));
        assertTrue(kotlin.contains("val `in`: Boolean") && kotlin.contains("val `object`: String") && kotlin.contains("val default: String"));
    }

    @Test
    fun `enum cases split on separators and are escaped in Swift`()
    {
        assertEquals("inReview", Naming.swiftCase("in_review"));
        assertEquals("inReview", Naming.swiftCase("in-review"));
        assertEquals("InReview", Naming.kotlinCase("in.review"));
        assertEquals("proofInvalid", Naming.swiftCase("PROOF_INVALID"));
        assertEquals("`default`", Naming.swiftIdentifier(Naming.swiftCase("default")));
    }

    // ---- §5 provenance and verify ---------------------------------------------

    private val workDirectory = File("build/app-contract-verify-test");

    private fun outputs(document: String): Pair<AppContract, List<OutputDirectory>>
    {
        val contract = AppContractReader.read(document, AppContractGeneration.sha256Hex(document.toByteArray()), fixtureSelection, AppDocuments.AUTH_CLASSES);
        val swift = OutputDirectory(File(workDirectory, "swift"), AppSwiftEmitter(contract, "FixtureAPI").emit());
        val kotlin = OutputDirectory(File(workDirectory, "kotlin"), AppKotlinEmitter(contract, "FixtureAPI", "x.y").emit());
        return contract to listOf(swift, kotlin);
    }

    private fun verifyFailure(outputs: List<OutputDirectory>, sha256: String): String
    {
        try
        {
            AppContractGeneration.verify(outputs, sha256);
        }
        catch (failure: RuntimeException)
        {
            return failure.message ?: "";
        }
        fail("verify passed over sources it must refuse");
        return "";
    }

    private fun freshlyWritten(): Pair<AppContract, List<OutputDirectory>>
    {
        workDirectory.deleteRecursively();
        val generated = outputs(fixture);
        generated.second.forEach { it.write() };
        return generated;
    }

    @Test
    fun `G6 every file records the document digest and the selected operations`()
    {
        val (contract, directories) = freshlyWritten();
        File(workDirectory, "swift").listFiles()!!.plus(File(workDirectory, "kotlin").listFiles()!!).forEach { file ->
            val text = file.readText();
            assertTrue(file.name, text.contains("// documentSha256:  ${contract.sha256}\n"));
            assertTrue(file.name, text.contains("// operations:      getItem, listItems, putItemNote\n"));
        };
        AppContractGeneration.verify(directories, contract.sha256);
    }

    @Test
    fun `G6 verify names a changed document before anything else`()
    {
        freshlyWritten();
        val changed = fixture.replace("\"1.2.0\"", "\"1.2.1\"");
        val (contract, directories) = outputs(changed);
        val message = verifyFailure(directories, contract.sha256);
        assertTrue(message, message.contains("was generated from document") && message.contains("the document is now ${contract.sha256}"));
    }

    @Test
    fun `G6 verify refuses a hand-edited file and a leftover file`()
    {
        val (contract, directories) = freshlyWritten();
        val edited = File(workDirectory, "kotlin/FixtureAPICalls.kt");
        edited.writeText(edited.readText().replace("requiresSession = true", "requiresSession = false"));
        File(workDirectory, "swift/Leftover.swift").writeText("// not generated\n");
        val message = verifyFailure(directories, contract.sha256);
        assertTrue(message, message.contains("FixtureAPICalls.kt differs from freshly generated output"));
        assertTrue(message, message.contains("Leftover.swift is not a generated file"));
    }

    @Test
    fun `G6 a write removes the leftover and verify passes again`()
    {
        val (contract, directories) = freshlyWritten();
        File(workDirectory, "swift/Leftover.swift").writeText("// not generated\n");
        File(workDirectory, "swift/.DS_Store").writeText("");
        directories.forEach { it.write() };
        assertTrue(!File(workDirectory, "swift/Leftover.swift").exists());
        AppContractGeneration.verify(directories, contract.sha256);
    }

    @Test
    fun `the consumer's arguments are all required and absolute`()
    {
        fun refused(fragment: String, vararg args: String)
        {
            try
            {
                AppContractOptions.parse(arrayOf(*args));
                fail("parsed arguments that must be refused");
            }
            catch (refusal: JsonException)
            {
                assertTrue(refusal.message, refusal.message!!.contains(fragment));
            }
        }
        refused("missing --document", "--mode", "verify");
        refused("unknown argument '--documnet'", "--documnet", "/x");
        val complete = arrayOf(
            "--mode", "verify", "--document", "relative.json", "--operations", "/o", "--swift-out", "/s",
            "--swift-namespace", "API", "--kotlin-out", "/k", "--kotlin-package", "a.b", "--sdk-root", "/r"
        );
        refused("--document must be an absolute path", *complete);
    }
}
