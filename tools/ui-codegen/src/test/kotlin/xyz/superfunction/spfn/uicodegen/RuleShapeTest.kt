// SPFN Mobile — the shapes the case rules cover, and what they say about every other one.
//
// `Rules.kt` is the half of this generator that is not derived from the spec: it is what a
// person decided is worth asserting about a flow that reads on the screen it pushes, and it
// only knows how to say that about a spec of that shape. A spec of another shape has to be a
// refusal, because a case table that quietly skipped a screen would report full coverage of
// a flow nobody exercised (P7) — and until now not one of those refusals had a reader.
//
// The fixture is `rule-shapes.json`: the smallest spec the rules accept whole — an entry
// screen that submits into a detail screen that reads, and one showcase flow of two screens
// — written against the REAL pinned bundle, so the operations, the request fields and the
// route it can carry are a server's rather than a fixture's invention. Every case below
// breaks exactly one thing in it, which is what makes the control run the evidence that the
// refusal is about the thing it names.

package xyz.superfunction.spfn.uicodegen

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuleShapeTest
{
    private val repoRoot = File("../..")

    private val shapes = "tools/ui-codegen/src/test/resources/rule-shapes.json"

    private val fixture: String = File(repoRoot, shapes).readText(Charsets.UTF_8)

    /**
     * A consumer that asks for the table, which is what puts the rules in the path at all.
     *
     * A target with no table root gets the scaffolds and nothing else (E6), so a suite that
     * used one would generate every case here without ever reaching `Rules.cells`.
     */
    private val target = Target(
        name = "shapes",
        swiftRoot = "Shapes/Generated",
        kotlinRoot = "shapes/kotlin/probe/generated",
        kotlinPackage = "probe.generated",
        appId = "probe.app",
        tableRoot = "shapes/cases",
        flows = null,
        runnerReadouts = true,
        generateTask = ":ui-codegen:spfnGenerateShapesUi",
        verifyTask = ":ui-codegen:spfnShapesUiVerify"
    )

    /** Writes a mutated fixture under the module's build directory and answers its path. */
    private fun withSpec(name: String, text: String): String
    {
        val relative = "tools/ui-codegen/build/test-specs/$name.json";
        val file = File(repoRoot, relative);
        file.parentFile?.mkdirs();
        file.writeText(text, Charsets.UTF_8);
        return relative;
    }

    private fun assertRefused(name: String, text: String, expected: String)
    {
        assertTrue("the mutation changed nothing: $name", text != fixture);
        try
        {
            generate(repoRoot, withSpec(name, text), target);
            fail("generation accepted a spec shape the rules cannot cover: $expected");
        }
        catch (failure: RuntimeException)
        {
            val message = failure.message ?: "";
            assertTrue("refused, but not on '$expected': $message", message.contains(expected));
        }
    }

    /**
     * The control: the fixture generates, and the table it produced has cells in it.
     *
     * Read first and floored rather than merely run, because every case below is "the same
     * spec, one thing broken" and a fixture that refused on its own would make all five of
     * them pass for the wrong reason.
     */
    @Test
    fun `the smallest spec the rules cover generates a table`()
    {
        val generated = generate(repoRoot, shapes, target).files;
        val table = generated.getValue("${target.tableRoot}/device-approval.cases.json");

        assertTrue("the table names no cell of the flow that reads", table.contains("\"id\": \"u1\""));
        assertTrue("the table names no cell of the showcase flow", table.contains("pushTour-"));
        assertTrue(
            "no Maestro flow file was written for a runnable cell",
            generated.keys.any { it.startsWith("${target.tableRoot}/flows/") }
        );
    }

    /**
     * Exactly one flow reads, because everything from u1 to s2 is written about that one.
     *
     * A second reading flow is not a spec with more coverage: it is a spec the rules would
     * cover half of, silently, by taking whichever flow the filter answered first.
     */
    @Test
    fun `a second flow that reads is refused`()
    {
        assertRefused(
            "two-reading-flows",
            fixture.replace(
                "\"flow\": \"pushTour\",\n      \"source\": null,\n      \"body\": \"lorem.short\",",
                "\"flow\": \"pushTour\",\n      \"source\": \"deviceApproval.lookup\","
            ),
            "the case rules cover exactly one flow that reads; this spec declares 2"
        );
    }

    /**
     * The read happens on the screen that is PUSHED, not on the one that pushes.
     *
     * u1 asserts that a successful submit pushes a screen which then loads (R6), and s1–s2
     * are about a read failing and being retried. On a spec where the entry screen is the one
     * that reads, every one of those cells asserts about a screen the person never reaches
     * that way.
     */
    @Test
    fun `an entry screen that reads is refused as the wrong way round`()
    {
        assertRefused(
            "entry-reads",
            fixture.replaceFirst("\"source\": null,", "\"source\": \"deviceApproval.lookup\","),
            "the case rules cover a flow that reads on the screen it pushes; 'enterCode' and " +
                "'reviewDevice' are the wrong way round"
        );
    }

    /**
     * Two writes on the detail screen, because the cells are written about two of them.
     *
     * u7 and u8 are approve and deny — one closing write and one destructive closing write —
     * and a spec with one write has a table with a cell for a control that is not there.
     */
    @Test
    fun `a detail screen with one write instead of two is refused`()
    {
        assertRefused(
            "one-write",
            fixture.replace(
                "        \"deny\": { \"call\": \"deviceApproval.deny\", \"then\": \"close\", \"role\": \"destructive\" },\n",
                ""
            ),
            "the case rules expect two writes on 'reviewDevice'; the spec declares 1"
        );
    }

    /**
     * A showcase flow has a way out, because a runner has to be able to leave it.
     *
     * The receipt is written from the app's root, so a flow a runner can enter and not leave
     * is a cell that passes and a suite that stops: everything after it runs against a screen
     * nobody expected to be there.
     */
    @Test
    fun `a showcase flow with no way out is refused`()
    {
        assertRefused(
            "no-way-out",
            fixture.replace(
                "\"done\": { \"then\": \"close\", \"role\": \"primary\" }",
                "\"done\": { \"then\": \"pop\", \"role\": \"primary\" }"
            ),
            "the case rules need a way out of 'pushTour': 'tourTwo' is the screen it ends on"
        );
    }

    /**
     * A showcase flow is a chain and not a loop.
     *
     * The walk that builds the chain follows a push at a time, so a screen that pushes back
     * to one already in it is a walk that does not end — a generator that hung rather than
     * refused, on a spec that is legal everywhere else (a push within a flow is exactly what
     * refusal 3 permits).
     */
    @Test
    fun `a showcase flow that pushes back onto itself is refused`()
    {
        assertRefused(
            "cycle",
            fixture.replace(
                "        \"done\": { \"then\": \"close\", \"role\": \"primary\" },\n" +
                    "        \"back\": { \"then\": \"pop\", \"role\": \"text\" }",
                "        \"done\": { \"then\": \"close\", \"role\": \"primary\" },\n" +
                    "        \"back\": { \"then\": { \"push\": \"tourOne\" }, \"role\": \"text\" }"
            ),
            "the case rules walk 'pushTour' from its start and 'tourOne' is pushed twice"
        );
    }

    /**
     * Two actions in one role is a refusal that names the screen and both of them.
     *
     * `single {}` is what these roles were read with, and its answer to two matches is
     * `IllegalArgumentException("Collection contains more than one matching element")` — no
     * screen, no action, no file. The spec that produces it is an ordinary-looking one: an
     * entry screen with two controls that both submit.
     */
    @Test
    fun `two actions in one role are refused, naming the screen and the actions`()
    {
        assertRefused(
            "two-submits",
            fixture.replace(
                "        \"cancel\": { \"then\": \"close\", \"role\": \"text\" }",
                "        \"resubmit\": { \"call\": \"deviceApproval.lookup\", \"then\": { \"push\": \"reviewDevice\" }, \"role\": \"secondary\" },\n" +
                    "        \"cancel\": { \"then\": \"close\", \"role\": \"text\" }"
            ),
            "the case rules need exactly one action on 'enterCode' that calls a service and pushes, " +
                "and it declares 2: resubmit, submit"
        );
    }

    // ---- the table's own two refusals ----------------------------------------

    private val inputs = Inputs(
        specPath = shapes,
        specSha256 = "0".repeat(64),
        bundleSha256 = "0".repeat(64),
        contractVersion = "0.1.0",
        generateTask = ":ui-codegen:spfnGenerateShapesUi",
        verifyTask = ":ui-codegen:spfnShapesUiVerify"
    )

    /** An empty spec is enough: the two cases below are about the CELLS, not about a spec. */
    private val emptySpec = Spec(
        specVersion = 1,
        manifestSha256 = "0".repeat(64),
        services = emptyList(),
        flows = emptyList(),
        screens = emptyList()
    )

    private fun assertTableRefused(cells: List<Cell>, expected: String)
    {
        try
        {
            CaseTable(target).emit(emptySpec, cells, inputs);
            fail("the case table accepted cells it must refuse: $expected");
        }
        catch (failure: RuntimeException)
        {
            val message = failure.message ?: "";
            assertTrue("refused, but not on '$expected': $message", message.contains(expected));
        }
    }

    /**
     * A gesture a person performs has no Maestro command, and a cell claiming one is refused.
     *
     * The rules give every by-hand step the `manual` runner, so no flow file is written for
     * one and this branch is unreachable from `Rules.cells` — which is exactly why it is worth
     * a reader here. A gesture silently dropped from a flow file is the failure P22 is about:
     * the flow runs, reports success, and proves nothing about the drag it was written for.
     */
    @Test
    fun `a by-hand step that reaches the Maestro renderer is refused`()
    {
        assertTableRefused(
            listOf(
                Cell(
                    "g1", "tourTwo", "idle", "dragAway",
                    "a sheet is dismissed by dragging it", "maestro", Fixtures.READY,
                    listOf(Step.ByHand("drag the sheet's handle down past half its height")),
                    listOf("stack=0")
                )
            ),
            "a by-hand step has no Maestro command; 'drag the sheet's handle down past half its " +
                "height' belongs to a manual cell"
        );
    }

    /**
     * The table tells a reader how to run one cell, so there has to be one to run.
     *
     * `first {}` answered that with `NoSuchElementException` and no table, no target and no
     * count in it. A table of nothing but unit and manual cells is a real state — it is what
     * taking the runnable cells out of the rules produces — and the honest answer is that a
     * document instructing a reader to run Maestro against it should not be written.
     */
    @Test
    fun `a table with no runnable cell refuses to tell its reader to run one`()
    {
        assertTableRefused(
            listOf(
                Cell(
                    "u1c", "enterCode", "busy", "submit",
                    "an answer that arrives after the flow closed changes nothing", "unit",
                    Fixtures.READY, emptyList(), listOf("stack=1", "state=busy")
                )
            ),
            "the case table tells its reader how to run one cell and none of the 1 cells derived " +
                "from this spec runs on Maestro"
        );
    }
}
