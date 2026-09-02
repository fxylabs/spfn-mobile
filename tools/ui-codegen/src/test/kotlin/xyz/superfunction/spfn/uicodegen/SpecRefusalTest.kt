// SPFN Mobile — the screen generator must refuse a spec it cannot honestly read.
//
// The style is tools/contract-codegen's BundleSectionTest: read the real spec, then break
// exactly one thing and require generation to refuse. P8 is the pattern under test — a
// generator that lets an unrecognised value fall through an else-branch does not fail, it
// emits a plausible app from a spec nobody wrote.
//
// The last two cases are not refusals. One is determinism, which is the property every
// header in the output claims; the other is discriminating power (P10): a table that did
// not move when the spec moved would be a table that proves nothing about the spec.

package xyz.superfunction.spfn.uicodegen

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpecRefusalTest
{
    private val repoRoot = File("../..")
    private val specPath = "examples/ui-spec/device-approval.json"
    private val specText: String = File(repoRoot, specPath).readText(Charsets.UTF_8)

    /** Writes a mutated spec under the module's build directory and answers its repo path. */
    private fun withSpec(name: String, text: String): String
    {
        val directory = File(repoRoot, "tools/ui-codegen/build/test-specs");
        directory.mkdirs();
        File(directory, name).writeText(text);
        return "tools/ui-codegen/build/test-specs/$name";
    }

    private fun replaceOnce(needle: String, replacement: String): String
    {
        assertTrue("the spec no longer carries '$needle'", specText.contains(needle));
        return specText.replaceFirst(needle, replacement);
    }

    private fun assertRefused(name: String, text: String, expected: String)
    {
        val path = withSpec(name, text);
        try
        {
            generate(repoRoot, path);
            fail("generation accepted a spec it must refuse: $expected");
        }
        catch (failure: RuntimeException)
        {
            val message = failure.message ?: "";
            assertTrue("refused, but not on '$expected': $message", message.contains(expected));
        }
    }

    @Test
    fun `a spec pinned to another bundle is refused`()
    {
        assertRefused(
            "digest.json",
            replaceOnce("\"manifestSha256\": \"", "\"manifestSha256\": \"00"),
            "spec digest mismatch"
        );
    }

    @Test
    fun `an operation the contract does not declare is refused`()
    {
        assertRefused(
            "operation.json",
            replaceOnce("authDeviceInfo", "authDeviceInformation"),
            "which the pinned contract does not declare"
        );
    }

    @Test
    fun `a then that pushes a screen outside the flow is refused`()
    {
        assertRefused(
            "then.json",
            replaceOnce("\"push\": \"reviewDevice\"", "\"push\": \"somewhereElse\""),
            "which is not a screen"
        );
    }

    @Test
    fun `a start that is not a screen is refused`()
    {
        assertRefused(
            "start.json",
            replaceOnce("\"start\": \"enterCode\"", "\"start\": \"nowhere\""),
            "which is not a screen"
        );
    }

    @Test
    fun `a call naming a method no service declares is refused`()
    {
        assertRefused(
            "call.json",
            replaceOnce("deviceApproval.approve", "deviceApproval.accept"),
            "which no service declares"
        );
    }

    @Test
    fun `generation is a pure function of its two inputs`()
    {
        val first = generate(repoRoot, specPath);
        val second = generate(repoRoot, specPath);
        assertEquals("the two runs wrote different files", first.keys, second.keys);
        first.forEach { (path, content) ->
            assertEquals("$path differs between two runs of the same generator", content, second[path]);
        };
    }

    /**
     * P10: the table has to move when the behaviour it describes moves.
     *
     * `approve`'s `then` goes from `close` to `pop`, which changes what the stack is after
     * the write. A table that still read `stack=0` for that cell would be a table derived
     * from something other than the spec.
     */
    @Test
    fun `changing one then changes at least one cell`()
    {
        val table = "examples/ui-spec/generated/device-approval.cases.json";
        val before = generate(repoRoot, specPath).getValue(table);
        val mutated = withSpec(
            "discriminate.json",
            replaceOnce(
                "\"approve\": { \"call\": \"deviceApproval.approve\", \"then\": \"close\" }",
                "\"approve\": { \"call\": \"deviceApproval.approve\", \"then\": \"pop\" }"
            )
        );
        val after = generate(repoRoot, mutated).getValue(table);
        assertNotEquals("the case table did not move when the spec did", before, after);
    }
}
