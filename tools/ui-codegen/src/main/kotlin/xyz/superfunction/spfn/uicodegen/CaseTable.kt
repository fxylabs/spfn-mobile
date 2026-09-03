// The case table and the Maestro flows, written from the rules.
//
// Three artefacts out of one list of cells: the machine-readable table both runners read,
// the same table for a person, and one flow file per cell a runner can drive. They are
// emitted for the ONE target that declares a table root — the app whose fixtures the
// cells name — and constructing this for a target without one is a refusal rather than
// an empty directory (see `Target.tableRoot`). The
// expectations in all three come from Rules.kt — the rule table — and never from the
// emitted models, so the unit suite that drives the models against this table is comparing
// two derivations rather than one derivation with itself (P10).

package xyz.superfunction.spfn.uicodegen

class CaseTable(target: Target)
{
    private val directory: String = requireNotNull(target.tableRoot) { "the target emits no case table" };

    private val appId: String = target.appId;

    /**
     * How long a flow waits for a readout to arrive, in milliseconds.
     *
     * Two values, and the difference is a cold start. A flow's FIRST wait is the one that
     * stands between the launch and the app's first draw, and the first draw after an
     * install or a device wipe is slower than every draw after it — cell u14 timed out
     * once on a wiped Pixel 3a emulator on 2026-09-02 and passed twice on the same build
     * warm. Every later wait is about the app doing work it has already been asked to do,
     * so it keeps the shorter value: a stall there is a defect and should be reported as
     * one rather than waited out (docs/IMPLEMENTATION-PITFALLS.md P7).
     *
     * `examples/ui-spec/run-cells.sh` covers the same ground from the other side by
     * launching the app once before the cells run.
     */
    private val firstWaitMillis: Int = 45000;

    private val waitMillis: Int = 20000;

    /** Every file this emitter owns, by repository-relative path. */
    fun emit(spec: Spec, cells: List<Cell>, inputs: Inputs): Map<String, String>
    {
        val files = mutableMapOf(
            "$directory/device-approval.cases.json" to json(cells, inputs),
            "$directory/device-approval.cases.md" to markdown(spec, cells, inputs)
        );
        cells.filter { it.runsOnMaestro }.forEach { cell ->
            files["$directory/flows/${cell.id}.yaml"] = flow(cell, inputs);
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
        appendLine("# The showcase — the case table");
        appendLine();
        appendLine("One row per cell of the screen table, across the ${spec.flows.size} flows the spec declares.");
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
        cells.filterNot { it.runsByHand }.forEach { cell ->
            appendLine(
                "| `${cell.id}` | `${cell.screen}` | `${cell.state}` | `${cell.action}` | ${cell.runner} " +
                    "| `${cell.fixture}` | ${cell.expect.joinToString(", ") { "`$it`" }} | ${cell.rule} |"
            );
        };
        appendLine();
        appendLine("## Running one");
        appendLine();
        appendLine("```");
        appendLine("maestro test -e APP_ID=$appId \\");
        appendLine("    $directory/flows/${cells.first { it.runsOnMaestro }.id}.yaml");
        appendLine("```");
        appendLine();
        appendLine("The launch carries `SPFN_UI_FIXTURE=<cell>`, which is what says WHICH cell this run is");
        appendLine("and therefore which flow opens and what its fake service answers. A launch that names");
        appendLine("no cell opens the menu instead, on the same fake.");
        append(checklist(spec, cells));
    }

    /**
     * The cells a person checks, as a checklist with somewhere to write the answer.
     *
     * A section rather than a file of its own, because a manual cell is a cell: it comes out
     * of the same rule table, names the same flows and fixtures, and splitting it out would
     * make the case table a document that quietly covers less than it appears to. What IS
     * separate is where the answers go — a checklist that was written on would stop being
     * generated output.
     *
     * Two result columns and not one. `custody=secureEnclave` in tools/harness/README.md is
     * the precedent: a gesture answer with no phone beside it is not evidence, and iOS and
     * Android read the same gesture through different platform machinery — a predictive back
     * is Android's alone, an interactive-pop swipe is Apple's.
     */
    private fun checklist(spec: Spec, cells: List<Cell>): String = buildString {
        val manual = cells.filter { it.runsByHand };
        appendLine();
        appendLine("## What a person checks");
        appendLine();
        appendLine("${manual.size} cells with no runner. Every one of them is a GESTURE or a resting");
        appendLine("height, which is the class of thing a device runner reports success for whether or");
        appendLine("not the platform read it as the gesture it meant — cells u7b and u10b spent a Mac");
        appendLine("round on exactly that (`docs/IMPLEMENTATION-PITFALLS.md` P22). So these are checked");
        appendLine("by a person on a real phone, and the answers are written down.");
        appendLine();
        appendLine("Launch the app with `SPFN_UI_FIXTURE=<cell>` to arrive on the right flow, do what the");
        appendLine("**Do** column says, and record what happened. Copy");
        appendLine("`examples/ui-spec/receipts/manual/TEMPLATE.md` to");
        appendLine("`examples/ui-spec/receipts/manual/<date>.md` and fill it in there; this table is");
        appendLine("generated and anything written into it is lost on the next generation.");
        appendLine();
        appendLine("| Cell | Flow | Screen | Do | Expect | iPhone | Android |");
        appendLine("| --- | --- | --- | --- | --- | --- | --- |");
        manual.forEach { cell ->
            appendLine(
                "| `${cell.id}` | `${spec.screenNamed(cell.screen).flow}` | `${cell.screen}` " +
                    "| ${gesture(cell)} " +
                    "| ${cell.rule} (${cell.expect.joinToString(", ") { "`$it`" }}) |  |  |"
            );
        };
        appendLine();
        appendLine("Where a cell has to be walked to before the gesture, the walk is a tap on the");
        appendLine("controls named in the table's JSON `steps` — the same ids a flow file would use.");
    }

    /** The one thing a person does in a manual cell, out of its by-hand step. */
    private fun gesture(cell: Cell): String = cell.steps
        .filterIsInstance<Step.ByHand>()
        .joinToString("; ") { it.description }

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
        appendLine("#");
        appendLine("# This first wait is the long one. The FIRST launch after an install or a wipe draws");
        appendLine("# later than every launch after it, and a cold start that outran this wait is a");
        appendLine("# stall reported as a cell failure — cell u14 did exactly that on a wiped emulator");
        appendLine("# on 2026-09-02 and passed twice warm. Every later wait stays at ${waitMillis}.");
        appendLine("- extendedWaitUntil:");
        appendLine("    visible:");
        appendLine("      text: \"stack=.*\"");
        appendLine("    timeout: $firstWaitMillis");
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
        Step.SystemBack -> systemBack()
        is Step.Await -> buildString {
            appendLine("- extendedWaitUntil:");
            appendLine("    visible:");
            appendLine("      text: \"${step.readout}\"");
            appendLine("    timeout: $waitMillis");
        }
        // Typed into whatever holds the focus. No `tapOn` before it, and that absence IS the
        // assertion: a step that tapped the field first would pass whether `autofocus` put
        // the focus there or not.
        is Step.TypeFocused -> "- inputText: \"${step.value}\"\n"
        // `pressKey` and `hideKeyboard` are Maestro commands for both platforms, unlike
        // `back`, which is Android's alone (see `systemBack` below and P22). Both are still
        // run on a Mac before the cells that use them are believed: a command that completes
        // without doing anything fails the flow at the next assertion rather than at itself,
        // which is what makes that class of defect expensive to read.
        Step.Return -> "- pressKey: Enter\n"
        Step.HideKeyboard -> "- hideKeyboard\n"
        is Step.SeeId -> buildString {
            appendLine("- assertVisible:");
            appendLine("    id: \"${step.id}\"");
        }
        // Both platforms, like `hideKeyboard` and unlike `back`, and a no-op when the
        // element is already on screen — which is why a screen that declares a body gets
        // one whether or not today's body is long enough to need it.
        is Step.ScrollTo -> buildString {
            appendLine("- scrollUntilVisible:");
            appendLine("    element:");
            appendLine("      id: \"${step.id}\"");
        }
        // Unreachable: a cell carrying one of these has the `manual` runner and no flow file
        // is written for it. Stated as a refusal rather than as a blank line, because a
        // gesture silently dropped from a flow is exactly the failure P22 is about.
        is Step.ByHand -> throw SpecException(
            "a by-hand step has no Maestro command; '${step.description}' belongs to a manual cell"
        )
    }

    /**
     * The platform's own back gesture, once per platform.
     *
     * Maestro's `back` is ANDROID'S command and Android's alone. On iOS it completes
     * without doing anything — measured on an iPhone 17 Pro simulator, iOS 26.3, on
     * 2026-09-02: the hierarchy after it still read `stack=2` / `state=ready`. A step that
     * does nothing and reports success fails the flow at the next ASSERTION rather than at
     * itself, which is what makes it expensive to read (docs/IMPLEMENTATION-PITFALLS.md
     * P22).
     *
     * So iOS gets the gesture that really means back there: the interactive-pop edge
     * swipe. `FlowHost`'s path binding is what reconciles it — SwiftUI shortens the
     * NavigationStack path itself and the binding's setter turns that into one `flow.pop()`
     * per dropped entry. Probed end to end on the simulator: `stack=1`, `state=idle`,
     * receipt written.
     */
    private fun systemBack(): String = buildString {
        appendLine("- runFlow:");
        appendLine("    when:");
        appendLine("      platform: Android");
        appendLine("    commands:");
        appendLine("      - back");
        appendLine("- runFlow:");
        appendLine("    when:");
        appendLine("      platform: iOS");
        appendLine("    commands:");
        appendLine("      - swipe:");
        appendLine("          start: \"1%, 50%\"");
        appendLine("          end: \"90%, 50%\"");
        appendLine("          duration: 600");
    }

    private fun describe(step: Step): String = when (step)
    {
        is Step.Type -> "type ${step.id} ${step.value}"
        is Step.Tap -> "tap ${step.id}"
        Step.SystemBack -> "systemBack"
        is Step.Await -> "await ${step.readout}"
        is Step.TypeFocused -> "typeFocused ${step.value}"
        Step.Return -> "return"
        Step.HideKeyboard -> "hideKeyboard"
        is Step.SeeId -> "see ${step.id}"
        is Step.ScrollTo -> "scrollTo ${step.id}"
        is Step.ByHand -> "byHand ${step.description}"
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
