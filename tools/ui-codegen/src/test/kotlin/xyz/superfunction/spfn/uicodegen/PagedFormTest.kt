// SPFN Mobile — what specVersion 2 adds, and what it must leave exactly where it was.
//
// Two halves, and the second is the one that would hurt. The first reads the models a list
// screen and a form screen generate into, on both platforms, off a synthetic spec written
// against the REAL contract bundle — `items.list` and `echo.send`, so the field names,
// the optional cursor and the integer `sequence` are the ones a server really declares
// rather than ones a fixture invented to be convenient. The second is the regression: a
// version 1 spec generates what it generated before, byte for byte, which
// `:ui-codegen:spfnUiVerify` and `:ui-codegen:spfnHarnessUiVerify` are the hard gate for
// and which this suite states once more from the other side — none of the new vocabulary
// may appear in the example app's output at all.
//
// The synthetic spec is a DIRECTORY of two contract documents and no JSON, and that is not
// a style choice. A flow with a list or a form screen must declare `views: authored`
// (refusal 13), and `authored` is only writable in a contract document
// (`SpecInput.checkViewSource`) — so a flow of either kind can only live in a document, and
// a fixture that was a `.json` file could not have been written at all.
//
// What this suite CANNOT do is compile what it reads. The generated Swift is compiled on a
// Mac and nowhere else, as every Swift file under a generated directory in this repository
// is; the generated Kotlin is compiled by the example apps, and the example apps are built
// from the example spec, which is version 1. So the models below are read as text, the way
// the emitters' own header says they have to be on this host.

package xyz.superfunction.spfn.uicodegen

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PagedFormTest
{
    private val repoRoot = File("../..")

    /** The synthetic spec: one list screen, one form screen, against the real bundle. */
    private val specPath = "tools/ui-codegen/src/test/resources/paged-form"

    private val listDocument = "contracts/browseItems.md"

    private val formDocument = "contracts/sendEcho.md"

    /** The shipped spec, which is version 1 and must stay exactly what it was. */
    private val examplePath = "examples/ui-spec"

    /**
     * The consumer every case here generates for, and deliberately neither shipped target —
     * the same reasoning `SpecRefusalTest` gives: a target this suite invented is what proves
     * the fields are read rather than remembered.
     */
    private val target = Target(
        name = "paged",
        swiftRoot = "Paged/Generated",
        kotlinRoot = "paged/kotlin/probe/generated",
        kotlinPackage = "probe.generated",
        appId = "probe.app",
        tableRoot = "paged/cases",
        flows = null,
        runnerReadouts = true,
        // Neither name carries a word the regression case below looks for. A generated
        // header prints the task that rewrites it, so a target called `paged` would put the
        // word `Paged` into every file of a version 1 run and fail that case on its own name.
        generateTask = ":ui-codegen:spfnGenerateSuiteTwoUi",
        verifyTask = ":ui-codegen:spfnSuiteTwoUiVerify"
    )

    private val kotlinModel = "${target.kotlinRoot}/screens"

    private val swiftModel = "${target.swiftRoot}/Screens"

    private fun generate(path: String): Map<String, String> = generate(repoRoot, path, target).files

    private fun pieces(path: String, names: List<String>): Map<String, String> =
        names.associateWith { File(repoRoot, "$path/$it").readText(Charsets.UTF_8) }

    /** Writes a mutated spec as a directory under the module's build directory. */
    private fun withSpec(name: String, parts: Map<String, String>): String
    {
        val relative = "tools/ui-codegen/build/test-specs/$name";
        val directory = File(repoRoot, relative);
        directory.deleteRecursively();
        parts.forEach { (piece, text) ->
            val file = File(directory, piece);
            file.parentFile?.mkdirs();
            file.writeText(text);
        };
        return relative;
    }

    private fun assertEmits(generated: Map<String, String>, path: String, expected: String)
    {
        val content = generated[path];
        assertTrue("the generator wrote no $path", content != null);
        assertTrue(
            "$path does not carry:\n$expected\n\nit carries:\n$content",
            requireNotNull(content).contains(expected)
        );
    }

    private fun assertRefused(name: String, parts: Map<String, String>, expected: String)
    {
        val path = withSpec(name, parts);
        try
        {
            generate(path);
            fail("generation accepted a spec it must refuse: $expected");
        }
        catch (failure: RuntimeException)
        {
            val message = failure.message ?: "";
            assertTrue("refused, but not on '$expected': $message", message.contains(expected));
        }
    }

    // ---- the paged model ----------------------------------------------------

    /**
     * The five transitions, called in the order `Paged` declares them.
     *
     * Read as TEXT and by name, because the arithmetic itself is already proven by the
     * vocabulary's own suites on both platforms (`PagedTest.kt`, `PagedTests.swift`). What is
     * left for a reader of the generator is that the model reaches for the right one: an
     * append written as `firstPage` would compile, pass every type check, and silently throw
     * away every row read before it.
     */
    @Test
    fun `a paged model drives Paged's own transitions on both platforms`()
    {
        val generated = generate(specPath);

        assertEmits(generated, "$kotlinModel/ItemsModel.kt", "MutableStateFlow(Paged.loading);");
        assertEmits(
            generated,
            "$kotlinModel/ItemsModel.kt",
            "mutableState.value = mutableState.value.firstPage(page.items, page.nextCursor);"
        );
        assertEmits(
            generated,
            "$kotlinModel/ItemsModel.kt",
            "mutableState.value = mutableState.value.firstPageFailed(ScreenFailure.envelope(failure));"
        );
        assertEmits(generated, "$kotlinModel/ItemsModel.kt", "mutableState.value = mutableState.value.appending();");
        assertEmits(
            generated,
            "$kotlinModel/ItemsModel.kt",
            "mutableState.value = mutableState.value.appended(page.items, page.nextCursor);"
        );
        assertEmits(
            generated,
            "$kotlinModel/ItemsModel.kt",
            "mutableState.value = mutableState.value.appendFailed(ScreenFailure.envelope(failure));"
        );

        assertEmits(generated, "$swiftModel/ItemsModel.swift", "public private(set) var state: Paged<SPFNItem> = .loading");
        assertEmits(generated, "$swiftModel/ItemsModel.swift", "state = state.firstPage(page.items, next: page.nextCursor)");
        assertEmits(generated, "$swiftModel/ItemsModel.swift", "state = state.firstPageFailed(ScreenFailure.envelope(error))");
        assertEmits(generated, "$swiftModel/ItemsModel.swift", "state = state.appending()");
        assertEmits(generated, "$swiftModel/ItemsModel.swift", "state = state.appended(page.items, next: page.nextCursor)");
        assertEmits(generated, "$swiftModel/ItemsModel.swift", "state = state.appendFailed(ScreenFailure.envelope(error))");
    }

    /**
     * The page size is the SPEC's and the cursor is the model's, and neither is the route's.
     *
     * `ListItemsRequest.limit` is a required integer, so without the exclusion in
     * `RouteParameters.of` it would become a route payload — and two pushes of one screen
     * could then disagree about how long its pages are. The route being a `data object` is
     * what states that it does not.
     */
    @Test
    fun `a paged read asks with the spec's size and the model's cursor`()
    {
        val generated = generate(specPath);

        assertEmits(
            generated,
            "$kotlinModel/ItemsModel.kt",
            "catalogue.list(SpfnListItemsRequest(limit = 20, cursor = null));"
        );
        assertEmits(
            generated,
            "$kotlinModel/ItemsModel.kt",
            "catalogue.list(SpfnListItemsRequest(limit = 20, cursor = from));"
        );
        assertEmits(
            generated,
            "$swiftModel/ItemsModel.swift",
            "page = try await catalogue.list(SPFNListItemsRequest(limit: 20, cursor: nil))"
        );
        assertEmits(
            generated,
            "$swiftModel/ItemsModel.swift",
            "page = try await catalogue.list(SPFNListItemsRequest(limit: 20, cursor: from))"
        );

        assertEmits(
            generated,
            "${target.kotlinRoot}/flows/BrowseItemsFlow.kt",
            "data object Items : BrowseItemsRoute"
        );
        assertEmits(generated, "${target.swiftRoot}/Flows/BrowseItemsFlow.swift", "    case items\n");
    }

    /**
     * The defect this is here for: a reload that asked for page two.
     *
     * `load` is what `reload` calls, so a cursor left over from the last append would make a
     * reload answer the SECOND page and then set it as the first — a list that shortened and
     * changed its rows for no reason a person could see. The reset is written before the call
     * rather than after it, so an answer that never arrives leaves nothing stale behind.
     */
    @Test
    fun `load clears the cursor before it asks, so a reload reads the first page`()
    {
        val generated = generate(specPath);

        assertEmits(
            generated,
            "$kotlinModel/ItemsModel.kt",
            "        val token = ++generation;\n" +
                "        cursor = null;\n" +
                "        mutableState.value = Paged.loading;"
        );
        assertEmits(
            generated,
            "$swiftModel/ItemsModel.swift",
            "        generation += 1\n" +
                "        let token = generation\n" +
                "        cursor = nil\n" +
                "        state = .loading"
        );

        // And the two aliases really are aliases, so there is one place for the rule to be in.
        assertEmits(generated, "$kotlinModel/ItemsModel.kt", "    suspend fun reload()\n    {\n        load();\n    }");
        assertEmits(generated, "$kotlinModel/ItemsModel.kt", "    suspend fun retryMore()\n    {\n        loadMore();\n    }");
        assertEmits(generated, "$swiftModel/ItemsModel.swift", "    public func reload() async\n    {\n        await load()\n    }");
        assertEmits(
            generated,
            "$swiftModel/ItemsModel.swift",
            "    public func retryMore() async\n    {\n        await loadMore()\n    }"
        );
    }

    /**
     * A late first page is dropped, which is the generation token doing the same job it does
     * on a `Loadable` screen.
     *
     * Every one of the model's four entry points bumps it and every answer is checked against
     * it, so a slow first page that arrives after a reload has started belongs to an
     * appearance that is over (P24).
     */
    @Test
    fun `every paged answer passes the generation guard`()
    {
        val kotlin = generate(specPath).getValue("$kotlinModel/ItemsModel.kt");

        assertEquals("a paged method starts a call without taking a token", 2, kotlin.split("val token = ++generation;").size - 1);
        assertEquals("a paged answer is written without being checked", 4, kotlin.split("isCurrent(token)").size - 1);
        assertTrue(
            "a paged model does not rethrow the cancellation it caught",
            kotlin.contains("catch (cancelled: CancellationException)")
        );
        assertTrue(
            "a paged model catches only the client's own hierarchy",
            kotlin.contains("catch (failure: Exception)")
        );
    }

    /** The five readouts E10 asks a paged screen for, in the order the case table asserts them. */
    @Test
    fun `a paged model publishes the five readouts a cell asserts on`()
    {
        val generated = generate(specPath);

        assertEmits(
            generated,
            "$kotlinModel/ItemsModel.kt",
            "            \"stack=\" + flow.stack.value.size,\n" +
                "            \"state=\" + pageName(paged.page),\n" +
                "            \"more=\" + moreName(paged.more),\n" +
                "            \"count=\" + rowCount(paged.page),\n" +
                "            \"hasMore=\" + paged.hasMore"
        );
        assertEmits(
            generated,
            "$swiftModel/ItemsModel.swift",
            "            \"stack=\\(flow.stack.count)\",\n" +
                "            \"state=\\(pageName(state.page))\",\n" +
                "            \"more=\\(moreName(state.more))\",\n" +
                "            \"count=\\(rowCount(state.page))\",\n" +
                "            \"hasMore=\\(state.hasMore)\""
        );
    }

    /** A paged screen that asks for a use-case seam gets one that takes a size and a cursor. */
    @Test
    fun `a paged use case is asked for a page rather than for the whole read`()
    {
        val withSeam = withSpec(
            "paged-usecase",
            pieces(specPath, listOf(listDocument, formDocument)) + (listDocument to
                File(repoRoot, "$specPath/$listDocument").readText(Charsets.UTF_8)
                    .replaceFirst("\"source\": \"catalogue.list\",", "\"source\": \"catalogue.list\", \"usecase\": true,"))
        );
        val generated = generate(withSeam);

        assertEmits(
            generated,
            "$kotlinModel/ItemsUseCase.kt",
            "    suspend fun list(limit: Long, cursor: String?): SpfnListItemsResponse"
        );
        assertEmits(
            generated,
            "$kotlinModel/ItemsUseCase.kt",
            "        service.list(SpfnListItemsRequest(limit = limit, cursor = cursor))"
        );
        assertEmits(generated, "$kotlinModel/ItemsModel.kt", "useCase.list(limit = 20, cursor = null);");
        assertEmits(
            generated,
            "$swiftModel/ItemsUseCase.swift",
            "    func list(limit: Int64, cursor: String?) async throws -> SPFNListItemsResponse"
        );
        assertEmits(
            generated,
            "$swiftModel/ItemsUseCase.swift",
            "        try await service.list(SPFNListItemsRequest(limit: limit, cursor: cursor))"
        );
        assertEmits(generated, "$swiftModel/ItemsModel.swift", "useCase.list(limit: 20, cursor: nil)");
    }

    // ---- the form model -----------------------------------------------------

    /**
     * Every field is checked at once, and the check is `Form`'s own.
     *
     * The rules table is what decides which fields exist, so the assertion is on the whole
     * table and not on one entry: a field left out of it is a field `Form.check` never looks
     * at, which is a screen that accepts an empty value and reports nothing.
     */
    @Test
    fun `a form model checks every field through Form-check on both platforms`()
    {
        val generated = generate(specPath);

        assertEmits(
            generated,
            "$kotlinModel/ComposeModel.kt",
            "    val rules: Map<String, FieldRules> = mapOf(\n" +
                "        \"message\" to FieldRules(required = true, minLength = 2, maxLength = 140, kind = FieldKind.Text),\n" +
                "        \"sequence\" to FieldRules(required = true, kind = FieldKind.Number)\n" +
                "    );"
        );
        assertEmits(
            generated,
            "$kotlinModel/ComposeModel.kt",
            "        val checked = Form.check(\n" +
                "            mapOf(\"message\" to message, \"sequence\" to sequence),\n" +
                "            rules,\n" +
                "            validator\n" +
                "        );"
        );
        assertEmits(generated, "$kotlinModel/ComposeModel.kt", "    suspend fun send(message: String, sequence: String)");
        assertEmits(generated, "$kotlinModel/ComposeModel.kt", "mutableState.value = checked.submitting();");
        assertEmits(generated, "$kotlinModel/ComposeModel.kt", "mutableState.value = mutableState.value.submitted();");
        assertEmits(
            generated,
            "$kotlinModel/ComposeModel.kt",
            "mutableState.value = mutableState.value.submitFailed(ScreenFailure.envelope(failure));"
        );
        assertEmits(
            generated,
            "$kotlinModel/ComposeModel.kt",
            "    fun edit(field: String)\n    {\n        mutableState.value = mutableState.value.edited(field);\n    }"
        );

        assertEmits(
            generated,
            "$swiftModel/ComposeModel.swift",
            "    public let rules: [String: FieldRules] = [\n" +
                "        \"message\": FieldRules(required: true, minLength: 2, maxLength: 140, kind: .text),\n" +
                "        \"sequence\": FieldRules(required: true, kind: .number)\n" +
                "    ]"
        );
        assertEmits(
            generated,
            "$swiftModel/ComposeModel.swift",
            "        let checked = Form.check(\n" +
                "            values: [\"message\": message, \"sequence\": sequence],\n" +
                "            rules: rules,\n" +
                "            custom: validator\n" +
                "        )"
        );
        assertEmits(generated, "$swiftModel/ComposeModel.swift", "    public func send(message: String, sequence: String) async");
        assertEmits(generated, "$swiftModel/ComposeModel.swift", "state = checked.submitting()");
        assertEmits(generated, "$swiftModel/ComposeModel.swift", "state = state.submitted()");
        assertEmits(generated, "$swiftModel/ComposeModel.swift", "state = state.submitFailed(ScreenFailure.envelope(error))");
        assertEmits(
            generated,
            "$swiftModel/ComposeModel.swift",
            "    public func edit(_ field: String)\n    {\n        state = state.edited(field)\n    }"
        );
    }

    /**
     * A contract integer arrives as TEXT and is converted to 32 bits on both platforms.
     *
     * Both halves matter. The parameter being a `String` is what lets `Form.check` look at
     * what a person typed rather than at what a parser already accepted; the width being 32
     * on both is what stops `3000000000` from being a request on one phone and a refusal on
     * the other, which is the difference `Form`'s own `Number` kind refuses to decide (P9).
     */
    @Test
    fun `an integer field is converted after the check and refused by width`()
    {
        val generated = generate(specPath);

        assertEmits(
            generated,
            "$kotlinModel/ComposeModel.kt",
            "        val sequenceValue = sequence.toIntOrNull();\n" +
                "        if (sequenceValue == null)\n" +
                "        {\n" +
                "            mutableState.value = Form(\n" +
                "                fields = checked.fields + (\"sequence\" to FieldError.Kind(FieldKind.Number)),\n" +
                "                submit = checked.submit\n" +
                "            );\n" +
                "            return;\n" +
                "        }"
        );
        assertEmits(
            generated,
            "$kotlinModel/ComposeModel.kt",
            "echo.send(SpfnEchoRequest(message = message, sequence = sequenceValue.toLong()));"
        );
        assertEmits(
            generated,
            "$swiftModel/ComposeModel.swift",
            "        guard let sequenceValue = Int32(sequence)\n" +
                "        else\n" +
                "        {\n" +
                "            var refused = checked.fields\n" +
                "            let refusal: FieldError = .kind(.number)\n" +
                "            refused.updateValue(refusal, forKey: \"sequence\")\n" +
                "            state = Form(fields: refused, submit: checked.submit)\n" +
                "            return\n" +
                "        }"
        );
        assertEmits(
            generated,
            "$swiftModel/ComposeModel.swift",
            "try await echo.send(SPFNEchoRequest(message: message, sequence: Int64(sequenceValue)))"
        );
    }

    /**
     * The three vectors the conversion exists for, asked of the function the model calls.
     *
     * `Form`'s `Number` kind accepts all three — it checks the SHAPE and says nothing about
     * width — so these are exactly the values that reach the conversion having passed the
     * check, and exactly the ones a 64-bit parse would have let through. The Swift half is
     * `Int32(_: String)`, which answers the same for the same three; nothing on this host can
     * run it, which is why the Kotlin half is pinned here and the Swift half is read.
     */
    @Test
    fun `the width the conversion imposes is the one the emitted model asks for`()
    {
        assertNull("a value wider than 32 bits was carried", "3000000000".toIntOrNull());
        assertNull("a value with a leading space was carried", " 12".toIntOrNull());
        assertEquals("a signed value the contract accepts was refused", 3, "+3".toIntOrNull());
        assertEquals("a plain value was refused", 12, "12".toIntOrNull());
    }

    /** The three readouts E10 asks a form for, sorted so that two platforms answer alike. */
    @Test
    fun `a form model publishes the three readouts a cell asserts on`()
    {
        val generated = generate(specPath);

        assertEmits(
            generated,
            "$kotlinModel/ComposeModel.kt",
            "            \"stack=\" + flow.stack.value.size,\n" +
                "            \"state=\" + submitName(form.submit),\n" +
                "            \"fields=\" + refusedFields(form)"
        );
        assertEmits(
            generated,
            "$kotlinModel/ComposeModel.kt",
            "        form.fields.entries.sortedBy { it.key }"
        );
        assertEmits(
            generated,
            "$swiftModel/ComposeModel.swift",
            "            \"stack=\\(flow.stack.count)\",\n" +
                "            \"state=\\(submitName(state.submit))\",\n" +
                "            \"fields=\\(refusedFields(state))\""
        );
        assertEmits(generated, "$swiftModel/ComposeModel.swift", "        form.fields.keys.sorted().compactMap");
    }

    /**
     * A one-field screen with rules keeps the `Busy` model and gains the check.
     *
     * This is R1 said more precisely rather than R1 changed: the input is still refused before
     * anything is sent and the screen still carries the refusal, and what moves is that the
     * envelope now names the RULE beside the field. The example spec writes no rules at all,
     * so its own screens keep the blank check byte for byte — which the case below is the
     * other half of.
     */
    @Test
    fun `a one-field screen with rules checks through Form and names the rule that refused`()
    {
        val checked = withSpec("busy-rules", versionTwoExample());
        val generated = generate(checked);

        assertEmits(
            generated,
            "$kotlinModel/EnterCodeModel.kt",
            "        val checked = Form.check(mapOf(\"userCode\" to userCode), rules, validator);\n" +
                "        val refusal = refusals(checked).firstOrNull();\n" +
                "        if (refusal != null)\n" +
                "        {\n" +
                "            mutableState.value =\n" +
                "                Busy.Error(ScreenFailure.validation(refusal.first, refusal.second));\n" +
                "            return;\n" +
                "        }"
        );
        assertEmits(
            generated,
            "$kotlinModel/EnterCodeModel.kt",
            "    val rules: Map<String, FieldRules> = mapOf(\n" +
                "        \"userCode\" to FieldRules(required = true, minLength = 4, kind = FieldKind.Code)\n" +
                "    );"
        );
        assertEmits(
            generated,
            "$swiftModel/EnterCodeModel.swift",
            "        if let refusal = refusals(checked).first\n" +
                "        {\n" +
                "            state = .error(ScreenFailure.validation(refusal.field, rule: refusal.rule))\n" +
                "            return\n" +
                "        }"
        );

        // The envelope carries `<field>:<rule>`, so the line still lands under the right field.
        assertEmits(
            generated,
            "$kotlinModel/ScreenFailure.kt",
            "    fun validation(field: String, rule: String): SpfnErrorEnvelope =\n" +
                "        SpfnErrorEnvelope(code = VALIDATION, message = \"\$field:\$rule\", requestId = \"\");"
        );
        assertEmits(
            generated,
            "$kotlinModel/ScreenFailure.kt",
            "envelope.message.substringBefore(':') == field)"
        );
        assertEmits(
            generated,
            "$swiftModel/ScreenFailure.swift",
            "            String(envelope.message.prefix(while: { character in character != \":\" })) == field"
        );
    }

    // ---- the refusals -------------------------------------------------------

    /** Refusal 10: a page is a page OF a read, and a screen with no source performs none. */
    @Test
    fun `a list on a screen with no source is refused`()
    {
        assertRefused(
            "list-no-source",
            formPieces("\"source\": null,", "\"source\": null, \"list\": { \"items\": \"items\", " +
                "\"next\": \"nextCursor\", \"cursor\": \"cursor\", \"limit\": { \"field\": \"limit\", \"value\": 20 } },"),
            "screens.compose.list is written on a screen whose source is null"
        );
    }

    /** Refusal 11, by name: a field the contract type does not declare. */
    @Test
    fun `a list field the contract does not declare is refused, by its path`()
    {
        assertRefused(
            "list-unknown-field",
            listPieces("\"next\": \"nextCursor\"", "\"next\": \"nextCursur\""),
            "screens.items.list.next names 'nextCursur', which ListItemsResponse does not declare"
        );
    }

    /** Refusal 11, by type: the right field, the wrong shape. */
    @Test
    fun `a list field of the wrong type is refused, naming the type it is`()
    {
        assertRefused(
            "list-items-not-an-array",
            listPieces("\"items\": \"items\"", "\"items\": \"nextCursor\""),
            "the rows of a paged read are an array<T>"
        );

        assertRefused(
            "list-cursor-not-optional",
            listPieces("\"cursor\": \"cursor\"", "\"cursor\": \"limit\""),
            "a cursor is an optional string"
        );

        assertRefused(
            "list-limit-not-an-integer",
            listPieces("\"limit\": { \"field\": \"limit\", \"value\": 20 }", "\"limit\": { \"field\": \"cursor\", \"value\": 20 }"),
            "a page size is an integer"
        );
    }

    /** A version 1 file may not carry a version 2 key, and is told which version it said. */
    @Test
    fun `a version 1 spec carrying list or rules is refused`()
    {
        assertRefused(
            "v1-list",
            listPieces("\"specVersion\": 2", "\"specVersion\": 1"),
            "screens.items.list is a specVersion 2 key and this spec says 1"
        );

        assertRefused(
            "v1-rules",
            formPieces("\"specVersion\": 2", "\"specVersion\": 1"),
            "screens.compose.inputs.message.rules is a specVersion 2 key and this spec says 1"
        );
    }

    /**
     * Refusal 13: a flow with a list or a form screen has no reference view.
     *
     * The refusal names which screen made it a document's flow, because that is the thing the
     * author has to look at: `views: reference` is the default, so a flow that grew a list
     * screen is a flow whose views quietly stopped being generable.
     */
    @Test
    fun `a list or form flow whose views are reference is refused`()
    {
        assertRefused(
            "list-reference-views",
            listPieces("\"views\": \"authored\"", "\"views\": \"reference\""),
            "'items' is a list screen; a list or a form screen has no reference view"
        );

        assertRefused(
            "form-reference-views",
            formPieces("\"views\": \"authored\"", "\"views\": \"reference\""),
            "'compose' is a form screen; a list or a form screen has no reference view"
        );
    }

    // ---- the case table -----------------------------------------------------

    /**
     * P1–P9 and F1–F8 are in the table, each with the runner its subject allows.
     *
     * The runners are asserted rather than merely the ids, because a cell's runner is a claim
     * about what its evidence IS. P6, P7 and F6 assert a COUNT OF CALLS, which no readout on
     * any screen states — a device runner pointed at one of them would be asserting something
     * that is true whether or not the ask was ignored.
     */
    @Test
    fun `the table carries the nine paged cells and the eight form cells`()
    {
        val table = generate(specPath).getValue("${target.tableRoot}/device-approval.cases.json");
        val runners = mutableMapOf<String, String>();
        var id: String? = null;
        table.lines().forEach { line ->
            Regex("\"id\": \"([PF][0-9])\"").find(line)?.let { id = it.groupValues[1] };
            Regex("\"runner\": \"([a-z]+)\"").find(line)?.let { match ->
                id?.let { runners[it] = match.groupValues[1] };
                id = null;
            };
        };

        assertEquals(
            "the table is not the seventeen cells this suite reads",
            (1..9).map { "P$it" }.toSet() + (1..8).map { "F$it" }.toSet(),
            runners.keys
        );
        listOf("P6", "P7", "F6").forEach { unit ->
            assertEquals("$unit asserts a count of calls, which no screen states", "unit", runners[unit]);
        };
        (runners.keys - setOf("P6", "P7", "F6")).forEach { driven ->
            assertEquals("$driven is a cell a device can hold still", "both", runners[driven]);
        };
    }

    /**
     * A paged cell asks for its next page the way a person does, and reads `count=`.
     *
     * There is no control to press: `PagedView` asks when the end of the rows is laid out, so
     * a cell that tapped something would be proving a path no finger takes. And the scroll
     * that reaches the count is by TEXT, because a readout is found by its text on both
     * platforms while a row's own name is the contract document's to settle.
     */
    @Test
    fun `a paged flow scrolls for its page and waits on the count`()
    {
        val flow = generate(specPath).getValue("${target.tableRoot}/flows/P4.yaml");

        assertTrue("P4 does not scroll for its second page:\n$flow", flow.contains("\n- scroll\n"));
        assertTrue(
            "P4 does not scroll to the count readout:\n$flow",
            flow.contains("- scrollUntilVisible:\n    element:\n      text: \"count=.*\"")
        );
        assertTrue("P4 does not wait for the count it expects:\n$flow", flow.contains("text: \"count=5\""));
        assertTrue("P4 taps a control to ask for a page:\n$flow", !flow.contains("id: \"items.loadMore\""));

        val retry = generate(specPath).getValue("${target.tableRoot}/flows/P8.yaml");
        assertTrue("P8 does not press the footer's own control:\n$retry", retry.contains("id: \"items.retryMore\""));
    }

    /** A form cell types into `<screen>.<field>` and presses `<screen>.<action>`. */
    @Test
    fun `a form flow types into the fields the spec names`()
    {
        val flow = generate(specPath).getValue("${target.tableRoot}/flows/F3.yaml");

        assertTrue("F3 does not type into the message field:\n$flow", flow.contains("id: \"compose.message\""));
        assertTrue("F3 does not type into the sequence field:\n$flow", flow.contains("id: \"compose.sequence\""));
        assertTrue("F3 does not press the write:\n$flow", flow.contains("id: \"compose.send\""));
        assertTrue(
            "F3 does not assert the refusal the kind makes:\n$flow",
            flow.contains("text: \"fields=sequence:kind\"")
        );
    }

    // ---- the regression -----------------------------------------------------

    /**
     * Version 1 generates what version 1 generated, and none of version 2 leaks into it.
     *
     * `:ui-codegen:spfnUiVerify` and `:ui-codegen:spfnHarnessUiVerify` are the hard gate —
     * they compare a fresh generation against every checked-in byte. This says the same thing
     * from the other side and says it about the CODE PATHS: a version 1 spec must not reach
     * `Paged`, `Form`, a rules table or a readouts property at all, so a refactor that
     * defaulted one of them on would fail here with a name rather than as a wall of diff.
     */
    @Test
    fun `no version 2 vocabulary reaches a version 1 spec's output`()
    {
        val generated = generate(examplePath);
        assertTrue("the example spec generated nothing to read", generated.size > 100);

        val forbidden = listOf(
            "Paged<",
            "Paged.loading",
            "firstPage(",
            "Form.check",
            "FieldRules",
            "FieldValidator",
            "val readouts: List<String>",
            // Not a bare `var readouts`: a generated v1 Swift VIEW already has one, which is
            // the slot it draws its two readouts into. What a v1 spec may not have is a MODEL
            // that publishes them as strings.
            "var readouts: [String]",
            "rule: String",
            "substringBefore(':')",
            "prefix(while:"
        );
        generated.forEach { (path, content) ->
            forbidden.forEach { word ->
                assertTrue("$path carries '$word', which a version 1 spec cannot ask for", !content.contains(word));
            };
        };
    }

    /**
     * The example spec is still read, and still reads as version 1.
     *
     * A floor rather than a formality: the case above is a list of absences, and a run that
     * generated nothing would satisfy every one of them (P7).
     */
    @Test
    fun `the shipped spec still generates its own screens`()
    {
        val generated = generate(examplePath);

        assertEmits(
            generated,
            "$kotlinModel/EnterCodeModel.kt",
            "        if (userCode.isBlank())\n" +
                "        {\n" +
                "            mutableState.value = Busy.Error(ScreenFailure.validation(\"userCode\"));\n" +
                "            return;\n" +
                "        }"
        );
        assertEmits(
            generated,
            "$swiftModel/EnterCodeModel.swift",
            "            state = .error(ScreenFailure.validation(\"userCode\"))"
        );
        assertEmits(
            generated,
            "$kotlinModel/ScreenFailure.kt",
            "        if (envelope != null && envelope.code == VALIDATION && envelope.message == field)"
        );
    }

    // ---- the fixtures the cases above mutate --------------------------------

    private fun listPieces(needle: String, replacement: String): Map<String, String> =
        replaceIn(listDocument, needle, replacement)

    private fun formPieces(needle: String, replacement: String): Map<String, String> =
        replaceIn(formDocument, needle, replacement)

    /** The synthetic spec with [needle] replaced once in the named document. */
    private fun replaceIn(document: String, needle: String, replacement: String): Map<String, String>
    {
        val parts = pieces(specPath, listOf(listDocument, formDocument));
        val text = parts.getValue(document);
        assertTrue("$document does not carry '$needle'", text.contains(needle));
        return parts + (document to text.replaceFirst(needle, replacement));
    }

    /**
     * The shipped spec as a version 2 one whose single-field screen carries rules.
     *
     * Built from the real documents rather than invented, because the claim is about THAT
     * screen: `enterCode` is the one-field `Busy` screen every u-cell and k-cell in the
     * repository stands on, and what this asks is what happens to it when a rule is written
     * beside the field it already collects.
     */
    private fun versionTwoExample(): Map<String, String>
    {
        val parts = pieces(examplePath, listOf("device-approval.json", "contracts/approveDevice.md"));
        return parts.mapValues { (_, text) ->
            text.replaceFirst("\"specVersion\": 1", "\"specVersion\": 2")
                .replaceFirst(
                    "\"userCode\": { \"kind\": \"code\", \"label\": \"Code from the device\", " +
                        "\"submitOnReturn\": true, \"autofocus\": true }",
                    "\"userCode\": { \"kind\": \"code\", \"label\": \"Code from the device\", " +
                        "\"submitOnReturn\": true, \"autofocus\": true, \"rules\": { \"minLength\": 4 } }"
                )
        };
    }
}
