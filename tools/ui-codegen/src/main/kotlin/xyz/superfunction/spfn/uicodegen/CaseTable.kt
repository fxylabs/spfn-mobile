// The case table and the Maestro flows, written from the rules.
//
// Three artefacts out of one list of cells: the machine-readable table both runners read,
// the same table for a person, and one flow file per cell a runner can drive. The
// expectations in all three come from Rules.kt — the rule table — and never from the
// emitted models, so the unit suite that drives the models against this table is comparing
// two derivations rather than one derivation with itself (P10).

package xyz.superfunction.spfn.uicodegen

object CaseTable
{
    const val DIRECTORY: String = "examples/ui-spec/generated";

    /** Every file this emitter owns, by repository-relative path. */
    fun emit(spec: Spec, cells: List<Cell>, inputs: Inputs): Map<String, String>
    {
        val files = mutableMapOf(
            "$DIRECTORY/device-approval.cases.json" to json(cells, inputs),
            "$DIRECTORY/device-approval.cases.md" to markdown(spec, cells, inputs)
        );
        cells.filter { it.runsOnMaestro }.forEach { cell ->
            files["$DIRECTORY/flows/${cell.id}.yaml"] = flow(cell, inputs);
        };
        return files;
    }

    // ---- the table ---------------------------------------------------------

    /**
     * JSON has no comments, so what a header would have said is carried as fields. The
     * `expectationsFrom` field is the one that matters: it names the file the expected
     * values were derived from, so a reader can check the claim rather than trust it.
     */
    private fun json(cells: List<Cell>, inputs: Inputs): String = buildString {
        appendLine("{");
        appendLine("  \"comment\": \"GENERATED FILE — DO NOT EDIT. ${Header.GENERATOR}.\",");
        appendLine("  \"expectationsFrom\": \"tools/ui-codegen/src/main/kotlin/xyz/superfunction/spfn/uicodegen/Rules.kt\",");
        appendLine("  \"spec\": \"${inputs.specPath}\",");
        appendLine("  \"specSha256\": \"${inputs.specSha256}\",");
        appendLine("  \"bundleSha256\": \"${inputs.bundleSha256}\",");
        appendLine("  \"contractVersion\": \"${inputs.contractVersion}\",");
        appendLine("  \"cells\": [");
        cells.forEachIndexed { index, cell ->
            append(cellJson(cell, last = index == cells.size - 1));
        };
        appendLine("  ]");
        appendLine("}");
    }

    private fun cellJson(cell: Cell, last: Boolean): String = buildString {
        appendLine("    {");
        appendLine("      \"id\": ${quote(cell.id)},");
        appendLine("      \"screen\": ${quote(cell.screen)},");
        appendLine("      \"state\": ${quote(cell.state)},");
        appendLine("      \"action\": ${quote(cell.action)},");
        appendLine("      \"rule\": ${quote(cell.rule)},");
        appendLine("      \"runner\": ${quote(cell.runner)},");
        appendLine("      \"fixture\": ${quote(cell.fixture)},");
        appendLine("      \"steps\": ${array(cell.steps.map { describe(it) })},");
        appendLine("      \"expect\": ${array(cell.expect)},");
        val flow = if (cell.runsOnMaestro) quote("flows/${cell.id}.yaml") else "null";
        appendLine("      \"flow\": $flow");
        appendLine(if (last) "    }" else "    },");
    }

    private fun markdown(spec: Spec, cells: List<Cell>, inputs: Inputs): String = buildString {
        appendLine("<!--");
        appendLine(Header.lines(inputs).joinToString("\n"));
        appendLine("-->");
        appendLine();
        appendLine("# Device approval — the case table");
        appendLine();
        appendLine("One row per cell of the screen table for the `${spec.flows.single().name}` flow.");
        appendLine("Every expectation is a READOUT, because a readout is the only thing both runners can");
        appendLine("read and neither can guess: `state=<…>` is the screen model's own state and");
        appendLine("`stack=<depth>` is the flow's.");
        appendLine();
        appendLine("**Where the expectations come from.** They are derived from the rule table in");
        appendLine("`tools/ui-codegen/src/main/kotlin/xyz/superfunction/spfn/uicodegen/Rules.kt`, not from");
        appendLine("the generated models — the models are derived from the spec, and a table derived from");
        appendLine("the code it checks proves only that the code equals itself");
        appendLine("(`docs/IMPLEMENTATION-PITFALLS.md` P10).");
        appendLine();
        appendLine("A cell whose runner is `unit` is about a moment a device runner cannot hold still —");
        appendLine("a press during a call in flight, an answer arriving after its flow closed — so it is");
        appendLine("proven on the JVM against the models and has no flow file.");
        appendLine();
        appendLine("| Cell | Screen | State | Action | Runner | Fixture | Expect | Rule |");
        appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |");
        cells.forEach { cell ->
            appendLine(
                "| `${cell.id}` | `${cell.screen}` | `${cell.state}` | `${cell.action}` | ${cell.runner} " +
                    "| `${cell.fixture}` | ${cell.expect.joinToString(", ") { "`$it`" }} | ${cell.rule} |"
            );
        };
        appendLine();
        appendLine("## Running one");
        appendLine();
        appendLine("```");
        appendLine("maestro test -e APP_ID=xyz.superfunction.spfn.example \\");
        appendLine("    examples/ui-spec/generated/flows/u1.yaml");
        appendLine("```");
        appendLine();
        appendLine("The launch carries `SPFN_UI_FIXTURE=<cell>`, which is the only thing that installs a");
        appendLine("fake service. Without it the app builds its client against the configured server and");
        appendLine("no fixture exists at all.");
    }

    // ---- one flow ----------------------------------------------------------

    /**
     * One cell as a Maestro flow, in the shape `tools/harness/flows` established.
     *
     * `launchApp: arguments:` is what carries the fixture, because it is the one form
     * that reaches both platforms: on Android the pairs arrive as intent extras and on
     * iOS as `-key value` launch arguments, and therefore as UserDefaults entries. There
     * is deliberately no `env:` — that sets variables for the FLOW, not for the app.
     *
     * The flow ends by unwinding itself and writing a receipt. The unwind is not
     * decoration: the receipt control is on the app's root, which a modal flow covers on
     * iOS, so a flow that asserted and stopped could not reach it on one of the two
     * platforms it is supposed to be shared by.
     */
    private fun flow(cell: Cell, inputs: Inputs): String = buildString {
        appendLine("# SPFN Mobile — example cell ${cell.id}: ${cell.rule}");
        appendLine("#");
        appendLine(Header.hashes(inputs));
        appendLine();
        appendLine("appId: \${APP_ID}");
        appendLine("name: ${cell.id}");
        appendLine("---");
        appendLine("- launchApp:");
        appendLine("    clearState: true");
        appendLine("    arguments:");
        appendLine("      SPFN_UI_FIXTURE: \"${cell.id}\"");
        appendLine();
        appendLine("# The app is up once a screen is drawing its stack readout. That the right FIXTURE");
        appendLine("# is installed is proven at the end instead, by the receipt's own name: an app that");
        appendLine("# never got the launch argument writes no receipt for this cell at all.");
        appendLine("- extendedWaitUntil:");
        appendLine("    visible:");
        appendLine("      text: \"stack=.*\"");
        appendLine("    timeout: 20000");
        cell.steps.forEach { step ->
            appendLine();
            append(render(step));
        };
        cell.expect.forEach { readout ->
            appendLine();
            appendLine("- assertVisible:");
            appendLine("    text: \"$readout\"");
        };
        if (cell.teardown.isNotEmpty())
        {
            appendLine();
            appendLine("# The assertions are made. What follows only leaves the flow closed, so the");
            appendLine("# receipt control on the app's root is reachable on both platforms.");
            cell.teardown.forEach { step -> append(render(step)) };
        }
        appendLine();
        appendLine("- tapOn:");
        appendLine("    id: \"example.receipt\"");
        appendLine();
        appendLine("- assertVisible:");
        appendLine("    text: \"receipt=receipt-${cell.id}-.*\"");
    }

    private fun render(step: Step): String = when (step)
    {
        is Step.Type -> buildString {
            appendLine("- tapOn:");
            appendLine("    id: \"${step.id}\"");
            appendLine("- inputText: \"${step.value}\"");
        }
        is Step.Tap -> buildString {
            appendLine("- tapOn:");
            appendLine("    id: \"${step.id}\"");
        }
        // Android's own gesture; on iOS Maestro realises it as the edge swipe that means
        // the same thing. Nav 3's handling of it is what cell u7b exists to check.
        Step.SystemBack -> "- back\n"
        is Step.Await -> buildString {
            appendLine("- extendedWaitUntil:");
            appendLine("    visible:");
            appendLine("      text: \"${step.readout}\"");
            appendLine("    timeout: 20000");
        }
    }

    private fun describe(step: Step): String = when (step)
    {
        is Step.Type -> "type ${step.id} ${step.value}"
        is Step.Tap -> "tap ${step.id}"
        Step.SystemBack -> "systemBack"
        is Step.Await -> "await ${step.readout}"
    }

    private fun array(values: List<String>): String =
        values.joinToString(", ", "[", "]") { quote(it) }

    /**
     * One JSON string literal, escaped to printable ASCII.
     *
     * The escape is written out digit by digit rather than through a formatter, so no
     * locale is involved: a default locale can render digits in a non-ASCII script, and
     * that is not a table anything can read (docs/IMPLEMENTATION-PITFALLS.md P9).
     */
    private fun quote(value: String): String = buildString {
        val digits = "0123456789abcdef";
        append('"');
        value.forEach { character ->
            when
            {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character.code in 0x20..0x7e -> append(character)
                else ->
                {
                    append("\\u");
                    append(digits[(character.code shr 12) and 0xf]);
                    append(digits[(character.code shr 8) and 0xf]);
                    append(digits[(character.code shr 4) and 0xf]);
                    append(digits[character.code and 0xf]);
                }
            }
        };
        append('"');
    }
}
