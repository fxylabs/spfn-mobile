package xyz.superfunction.spfn.harness

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every control a Maestro flow taps is one the screen draws in the runner block.
 *
 * This is the JVM half of a defect a JVM cannot see. Compose's `verticalScroll` puts only
 * the nodes overlapping the viewport into the accessibility tree, so a control below the
 * fold is not there for uiautomator at all and `tapOn` does not scroll to look for it
 * (docs/IMPLEMENTATION-PITFALLS.md P25). On 2026-09-03 that failed all nine c-cells at
 * `Element not found: Id matching regex: btn_wipe`, on a screen that was on and correct.
 *
 * A JVM cannot measure the fold — a device run does that, and it is the only thing that
 * can. What it CAN hold is the membership: the ids the flows tap must be the ids the
 * runner block declares, so that "inside the first viewport" is a property of one named
 * block rather than of wherever a control happened to be written.
 *
 * The flow files are the definition and this test reads them. Nothing here is copied out
 * of [RunnerBlockTags] or out of the README, because a table read back out of its own
 * subject asserts only that the subject is self-consistent (P10). The flows are not
 * edited to agree with the screen; the screen is moved to agree with them.
 */
class HarnessRunnerBlockTest
{
    /**
     * Every `id:` selector in tools/harness/flows/, read off the files themselves.
     *
     * The whole directory and not the thirteen cells: `btn_wipe` is named by
     * `prelude-clean.yaml` and the two network buttons by `reach-rotation-pending.yaml`,
     * which every cell runs through `runFlow`. A test that read only the cells would have
     * had nothing to say about the id that actually failed.
     */
    private val flowIds: Set<String> = FLOWS.flatMap { file ->
        SELECTOR.findAll(file.readText(Charsets.UTF_8)).map { it.groupValues[1] }
    }.toSet()

    /**
     * That the files were found at all, before anything is concluded from them.
     *
     * An empty directory would make every assertion below vacuously true, and a test that
     * passes because it read nothing is worse than no test (P7). The counts are the two
     * facts a miss would break: sixteen files, of which thirteen are cells.
     */
    @Test
    fun theFlowsAreWhereThisTestLooksForThem()
    {
        assertEquals("flow files under $DIRECTORY", 16, FLOWS.size);
        assertEquals("cells among them", 13, FLOWS.count { CELL.matches(it.name) });
        assertTrue("id selectors found: $flowIds", flowIds.size >= 11);
    }

    /** The one that would have caught it: `btn_wipe` and the ten beside it, all up top. */
    @Test
    fun everyControlAFlowTapsOnThisScreenIsInTheRunnerBlock()
    {
        val ours = flowIds.filter { it.startsWith("btn_") }.sorted();
        val missing = ours.filterNot { it in RunnerBlockTags };
        assertEquals("ids the flows tap that the runner block does not draw", emptyList<String>(), missing);
    }

    /**
     * And the same fact from the other side.
     *
     * Moving a tag into the device-mode block is exactly the edit that broke the run, and
     * it is the edit the assertion above cannot see on its own: a tag drawn in both places
     * would satisfy it.
     */
    @Test
    fun noControlAFlowTapsIsInTheHalfAPersonScrollsTo()
    {
        val stranded = HumanBlockTags.filter { it in flowIds };
        assertEquals("ids the flows tap that are drawn below the fold", emptyList<String>(), stranded);
    }

    /** A tag in both lists would make either list a lie. */
    @Test
    fun theTwoBlocksShareNothing()
    {
        assertEquals(emptyList<String>(), RunnerBlockTags.filter { it in HumanBlockTags });
    }

    /**
     * What is left over belongs to the generated screens, and is spelled the way they
     * spell it — `enterCode.submit`, never `btn_`.
     *
     * Those are not this screen's to place. They are drawn by the flow host over it, which
     * is its own scroll container and its own first viewport, and a `btn_` id among them
     * would mean a control of this screen's went missing rather than that a d-cell reached
     * into the generator's.
     */
    @Test
    fun theRemainingIdsBelongToTheGeneratedScreens()
    {
        val theirs = flowIds.filterNot { it in RunnerBlockTags }.sorted();
        assertTrue("selectors left over: $theirs", theirs.isNotEmpty());
        assertEquals("`btn_` ids no block on this screen draws", emptyList<String>(), theirs.filter { it.startsWith("btn_") });
    }

    private companion object
    {
        const val DIRECTORY: String = "tools/harness/flows";

        /** `c1-….yaml` through `c10-…` and `d1-…` through `d3-…`, and nothing else. */
        val CELL: Regex = Regex("^[cd][0-9]+-.*\\.yaml$");

        /** A Maestro `id:` selector, quoted, wherever in a command it sits. */
        val SELECTOR: Regex = Regex("""\bid:\s*"([^"]+)"""");

        val FLOWS: List<File> by lazy {
            val root = System.getProperty("spfn.repoRoot")
                ?: error("spfn.repoRoot is not set; the suite cannot find $DIRECTORY");
            File(root, DIRECTORY).listFiles { file -> file.name.endsWith(".yaml") }
                ?.sortedBy { it.name }
                ?: error("$DIRECTORY holds no flow files");
        }
    }
}
