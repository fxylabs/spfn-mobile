// SPFN Mobile — the app's fixture mapping must agree with the case table's.
//
// `Fixtures.forCell` is hand-written and `device-approval.cases.json` is generated, which
// is on purpose: what a cell's fixture ANSWERS is a decision about the table, and what it
// is CALLED is recorded in the table. Two places, so somebody has to compare them — a cell
// running under a seeding the table does not claim is a green run proving something else.
//
// The floor matters as much as the comparison: an empty id list would make every assertion
// below vacuous, and the suite would pass having read nothing (P7).

package xyz.superfunction.spfn.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixtureTableTest
{
    @Test
    fun `the table declares cells to check`()
    {
        assertTrue("the case table declares no cells at all", CaseTableReader.ids().size >= 18);
    }

    @Test
    fun `every cell in the table runs under the seeding the table names`()
    {
        CaseTableReader.ids().forEach { cell ->
            val fixture = Fixtures.forCell(cell);
            assertEquals("cell $cell", CaseTableReader.fixture(cell), fixture?.name);
        };
    }

    @Test
    fun `a launch that names no cell installs no fake at all`()
    {
        assertEquals(null, Fixtures.forCell(""));
        assertEquals(null, Fixtures.forCell("not-a-cell"));
    }
}
