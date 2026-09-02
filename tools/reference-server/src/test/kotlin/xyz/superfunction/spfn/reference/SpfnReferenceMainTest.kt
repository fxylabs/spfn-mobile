// SPFN Mobile — what the launch flags build.
//
// The Swift suite never constructs a server; it is handed one this entry point launched.
// So the questions here are about the launch and not about the server: does `--test-clock`
// reach the clock the server judges everything on, and does a launch without it still
// refuse to pretend its clock can be moved.
//
// The server is built through `serverFor(parseArguments(...))` — the same two calls `main`
// makes — because a test that constructed the server itself would prove only that the
// server honours a clock it was handed, which is not the thing that was broken.

package xyz.superfunction.spfn.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnServerTimeResponse
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

class SpfnReferenceMainTest
{
    // ---- the flags ---------------------------------------------------------

    @Test
    fun `every flag is read, and the defaults are what no flag means`()
    {
        val options = parseArguments(
            arrayOf(
                "--port", "8791",
                "--port-file", "/tmp/spfn-launch.json",
                "--session-ttl-millis", "1234",
                "--parent-pid", "99",
                "--test-clock", "1750000000000"
            )
        );

        assertEquals(8791, options.port);
        assertEquals(File("/tmp/spfn-launch.json"), options.portFile);
        assertEquals(1234L, options.sessionTtlMillis);
        assertEquals(99L, options.parentPid);
        assertEquals(1_750_000_000_000L, options.testClockStartMillis);

        val defaults = parseArguments(emptyArray());
        assertEquals(0, defaults.port);
        assertNull(defaults.portFile);
        assertEquals(SpfnReferenceState.DEFAULT_SESSION_TTL_MILLIS, defaults.sessionTtlMillis);
        assertNull(defaults.parentPid);
        assertNull("no flag means the wall clock, never a test clock", defaults.testClockStartMillis);
    }

    /**
     * A missing value is an error, as it is for every other flag. `--test-clock` last on
     * the command line with nothing after it must not read as "start wherever": a launch
     * that silently chose an instant would put the server and the suite's synchronised
     * proof clock in different epochs and refuse every proof as expired.
     */
    @Test
    fun `a flag with no value and a value that is not a number are usage errors`()
    {
        assertEquals(
            "--test-clock needs a value",
            usageErrorOf(arrayOf("--port", "0", "--test-clock"))
        );
        assertEquals(
            "--test-clock is not a number",
            usageErrorOf(arrayOf("--test-clock", "yesterday"))
        );
        assertEquals("--port needs a value", usageErrorOf(arrayOf("--port")));
        assertEquals("unknown option --clock", usageErrorOf(arrayOf("--clock", "1")));
    }

    /**
     * The runner writes the start instant out as a literal, because a shell script cannot
     * read a Kotlin constant. Nothing else would notice the two drifting apart: the server
     * would simply sit in a different epoch from the fixtures, and every proof would be
     * refused as expired for a reason no failure message names.
     */
    @Test
    fun `the runner launches the server at the instant the constant names`()
    {
        val runner = File(System.getProperty("spfn.repoRoot"), "tools/reference-server/run-integration.sh");
        val declared = Regex("TEST_CLOCK_START_MILLIS=(\\d+)").find(runner.readText())?.groupValues?.get(1);

        assertEquals(
            "run-integration.sh and SpfnReferenceTestClock.DEFAULT_START_MILLIS disagree",
            "$START_MILLIS",
            declared
        );
    }

    // ---- what the flag builds ----------------------------------------------

    /**
     * The whole point of the flag: the clock `/control/advance-clock` moves is the clock
     * `core.time` answers from. The SDKs anchor their proof clock to `core.time`, so a
     * clock that moved for the control route and not for the answer would leave every
     * proof outside the server's own replay window.
     */
    @Test
    fun `a launch with the flag moves its clock, and core time moves with it`()
    {
        serverFor(parseArguments(arrayOf("--test-clock", "$START_MILLIS"))).start().use { server ->
            assertEquals(START_MILLIS, serverTimeMillis(server));

            assertEquals(200, advanceClock(server, 60_000).statusCode);

            assertEquals(START_MILLIS + 60_000, serverTimeMillis(server));
        }
    }

    /**
     * And without it: refused, rather than accepted and ignored. A control route that
     * answered 200 and moved nothing is how case i passes while proving nothing.
     */
    @Test
    fun `a launch without the flag refuses to move its clock`()
    {
        serverFor(parseArguments(emptyArray())).start().use { server ->
            val refused = advanceClock(server, 60_000);

            assertEquals(409, refused.statusCode);
            assertTrue(refused.body, refused.body.contains("system clock"));

            // The wall clock, not the fixture instant the other case starts from.
            val now = serverTimeMillis(server);
            assertTrue("$now is not a real instant", now > START_MILLIS);
        }
    }

    // ---- plumbing ----------------------------------------------------------

    private class Answer(val statusCode: Int, val body: String)

    private fun usageErrorOf(args: Array<String>): String?
    {
        val thrown = runCatching { parseArguments(args) }.exceptionOrNull();
        assertNotNull("expected a usage error for ${args.joinToString(" ")}", thrown);
        assertTrue("$thrown", thrown is IllegalArgumentException);
        return thrown?.message;
    }

    private fun serverTimeMillis(server: SpfnReferenceServer): Long
    {
        val operation = SpfnGeneratedOperations.coreTime;
        val answer = send(server, operation.method, operation.path, body = null, token = null);
        assertEquals(answer.body, 200, answer.statusCode);
        return SpfnServerTimeResponse.decode(SpfnCanonicalJson.parse(answer.body.toByteArray())).serverTimeMillis;
    }

    private fun advanceClock(server: SpfnReferenceServer, millis: Long): Answer = send(
        server,
        "POST",
        SpfnReferenceControl.ADVANCE_CLOCK,
        SpfnCanonicalJson.encode(
            SpfnCanonicalValue.Obj(mapOf("millis" to SpfnCanonicalValue.Integer(millis)))
        ),
        server.controlToken
    )

    /**
     * One request over `HttpURLConnection`. The raw-socket harness the other suites use
     * exists to send what a client library prevents — a repeated header, a non-canonical
     * body — and nothing here needs that: these two routes are a bodyless GET and a
     * well-formed POST.
     */
    private fun send(
        server: SpfnReferenceServer,
        method: String,
        path: String,
        body: ByteArray?,
        token: String?
    ): Answer
    {
        val connection = URI(server.baseUrl + path).toURL().openConnection() as HttpURLConnection;
        connection.requestMethod = method;
        connection.connectTimeout = TIMEOUT_MILLIS;
        connection.readTimeout = TIMEOUT_MILLIS;
        token?.let { connection.setRequestProperty(SpfnReferenceControl.TOKEN_HEADER, it) };
        if (body != null)
        {
            connection.doOutput = true;
            connection.outputStream.use { it.write(body) };
        }
        val status = connection.responseCode;
        val stream = connection.errorStream ?: connection.inputStream;
        val answer = stream.use { String(it.readBytes(), Charsets.UTF_8) };
        connection.disconnect();
        return Answer(status, answer);
    }

    private companion object
    {
        /** What the runner passes, and what the fixtures are written around. */
        const val START_MILLIS = SpfnReferenceTestClock.DEFAULT_START_MILLIS

        const val TIMEOUT_MILLIS = 10_000
    }
}
