// SPFN Mobile — what the suite accepts as a target.
//
// A unit test rather than an integration one: nothing here binds a socket, and the
// question is what the suite refuses before it ever reaches a server. That is exactly the
// part of the external mode a passing integration run cannot exercise, because a run that
// reaches a server has already been given something usable.
//
// The control token is the subject. It ends up in a header field value, so a token holding
// a colon, a space or a line break is not a bad token — it is a second header field, in a
// request nobody wrote. There is more than one way to hand one in, and a rule enforced on
// some of them holds until somebody uses the other door.

package xyz.superfunction.spfn.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import java.io.File

class SpfnReferenceTargetTest
{
    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    // ---- what a launch file is allowed to say -------------------------------

    @Test
    fun `a launch file is read as the base URL and the token it names`()
    {
        val target = SpfnIntegrationTarget.fromLaunchFile(
            launchFile("http://127.0.0.1:8791", "0f1e2d3c4b5a6978")
        );

        assertEquals("http://127.0.0.1:8791", target.baseUrl);
        assertEquals("0f1e2d3c4b5a6978", target.controlToken);
    }

    /** The three punctuation marks the set allows, so the rule is not "hex only" by accident. */
    @Test
    fun `a token carrying a dot, an underscore and a hyphen is accepted`()
    {
        val token = "spfn.control_token-0001";

        assertEquals(token, SpfnIntegrationTarget.fromLaunchFile(launchFile(token = token)).controlToken);
    }

    @Test
    fun `a launch file token carrying a colon is refused`()
    {
        assertRefused("token:with-a-colon");
    }

    @Test
    fun `a launch file token carrying a line break is refused`()
    {
        assertRefused("token\nx-spfn-reference-control: forged");
    }

    @Test
    fun `a launch file token carrying a space is refused`()
    {
        assertRefused("two words");
    }

    @Test
    fun `a launch file token carrying a non-ASCII character is refused`()
    {
        assertRefused("token-é");
    }

    // ---- the same rule on the way in from a property ------------------------

    @Test
    fun `a token given as a property is refused on the same set`()
    {
        withProperties(
            SpfnIntegrationTarget.URL_PROPERTY to "http://127.0.0.1:8791",
            SpfnIntegrationTarget.TOKEN_PROPERTY to "token:with-a-colon"
        ) {
            val failure = assertThrows(IllegalStateException::class.java) { SpfnIntegrationTarget.resolve() };
            assertTrue(failure.message, failure.message!!.contains("HTTP header field"));
        }
    }

    /**
     * The launch file supplies the token and the property supplies the URL, which is what
     * `run-integration.sh` does. The token still has to pass: the file it came out of is
     * not a reason to trust it.
     */
    @Test
    fun `a launch file token is refused even when the URL came from a property`()
    {
        val file = launchFile(token = "token with a space");

        withProperties(
            SpfnIntegrationTarget.URL_PROPERTY to "http://127.0.0.1:8791",
            SpfnIntegrationTarget.LAUNCH_FILE_PROPERTY to file.path
        ) {
            val failure = assertThrows(IllegalStateException::class.java) { SpfnIntegrationTarget.resolve() };
            assertTrue(failure.message, failure.message!!.contains("HTTP header field"));
        }
    }

    /** No target named, no target resolved: the suite starts its own server. */
    @Test
    fun `no target property resolves to no target`()
    {
        withProperties { assertEquals(null, SpfnIntegrationTarget.resolve()) };
    }

    // ---- plumbing -----------------------------------------------------------

    private fun assertRefused(token: String)
    {
        val failure = assertThrows(IllegalStateException::class.java)
        {
            SpfnIntegrationTarget.fromLaunchFile(launchFile(token = token))
        };
        assertTrue(failure.message, failure.message!!.contains("HTTP header field"));
    }

    private fun launchFile(
        baseUrl: String = "http://127.0.0.1:8791",
        token: String = "0f1e2d3c4b5a6978"
    ): File
    {
        // Written the way the server writes it, through the canonical encoder, so a token
        // needing JSON escaping is escaped rather than turned into an unreadable file. The
        // point of these cases is that the token is refused, not that the file is broken.
        val file = folder.newFile();
        file.writeBytes(
            SpfnCanonicalJson.encode(
                SpfnCanonicalValue.Obj(
                    mapOf(
                        "baseUrl" to SpfnCanonicalValue.Text(baseUrl),
                        "controlToken" to SpfnCanonicalValue.Text(token)
                    )
                )
            )
        );
        return file;
    }

    /**
     * Runs [body] with exactly the given target properties set and every other one cleared.
     *
     * Restored afterwards whatever happens: these are JVM-wide, and a test that left one
     * behind would point every case that ran after it at a server that is not there.
     */
    private fun withProperties(vararg properties: Pair<String, String>, body: () -> Unit)
    {
        val names = listOf(
            SpfnIntegrationTarget.URL_PROPERTY,
            SpfnIntegrationTarget.LAUNCH_FILE_PROPERTY,
            SpfnIntegrationTarget.TOKEN_PROPERTY
        );
        val saved = names.associateWith { System.getProperty(it) };
        try
        {
            names.forEach { System.clearProperty(it) };
            properties.forEach { (name, value) -> System.setProperty(name, value) };
            body();
        }
        finally
        {
            names.forEach { name ->
                saved[name]?.let { System.setProperty(name, it) } ?: System.clearProperty(name);
            };
        }
    }
}
