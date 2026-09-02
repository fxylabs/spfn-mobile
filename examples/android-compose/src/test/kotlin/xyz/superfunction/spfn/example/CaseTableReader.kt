// SPFN Mobile — the case table, read by the suite that is checked against it.
//
// The expectations the tests assert are NOT written in the tests. They are read out of
// `examples/ui-spec/generated/device-approval.cases.json`, which is derived from the rule
// table in the generator; the models the tests drive are derived from the spec. That is
// what makes the suite a comparison of two derivations rather than of one with itself
// (docs/IMPLEMENTATION-PITFALLS.md P10).
//
// Reading is deliberately crude — one regular expression per field — because the table's
// canonical format puts one cell per object with no nested braces, and a JSON parser here
// would be a dependency this module does not otherwise need. It is not tolerant: a cell
// the table does not carry, or a field it does not carry, fails the test rather than
// answering an empty list, because an empty expectation matches everything (P7).

package xyz.superfunction.spfn.example

import java.io.File

object CaseTableReader
{
    private const val PATH: String = "examples/ui-spec/generated/device-approval.cases.json";

    private val text: String by lazy {
        val root = System.getProperty("spfn.repoRoot")
            ?: error("spfn.repoRoot is not set; the suite cannot find $PATH");
        File(root, PATH).readText(Charsets.UTF_8);
    }

    /** Every cell id the table declares, in the table's own order. */
    fun ids(): List<String> = Regex("\"id\": \"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()

    /** The readouts a runner must see once the cell's action has settled. */
    fun expect(cell: String): List<String> = list(cell, "expect")

    /** The seeding the cell runs under, which `Fixtures.forCell` has to agree with. */
    fun fixture(cell: String): String = field(cell, "fixture")

    /** `unit`, `maestro` or `both`. */
    fun runner(cell: String): String = field(cell, "runner")

    private fun block(cell: String): String =
        Regex("\\{[^{}]*\"id\": \"$cell\"[^{}]*\\}").find(text)?.value
            ?: error("the case table declares no cell '$cell'")

    private fun field(cell: String, name: String): String =
        Regex("\"$name\": \"([^\"]*)\"").find(block(cell))?.groupValues?.get(1)
            ?: error("cell '$cell' declares no '$name'")

    private fun list(cell: String, name: String): List<String>
    {
        val array = Regex("\"$name\": \\[([^\\]]*)\\]").find(block(cell))?.groupValues?.get(1)
            ?: error("cell '$cell' declares no '$name'");
        val values = array.split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() };
        if (values.isEmpty())
        {
            error("cell '$cell' declares an empty '$name'; an empty expectation matches everything");
        }
        return values;
    }
}
