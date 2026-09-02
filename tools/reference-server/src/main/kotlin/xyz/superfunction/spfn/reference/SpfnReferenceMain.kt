// SPFN Mobile — launching the reference server as a process.
//
// The Kotlin suite runs the server in-process and never comes through here. This entry
// point exists for the Swift suite, which is another process and needs a real socket to
// talk to.
//
// Three things stop a launched server outliving the run that started it: a shutdown hook
// for a signal, a watchdog for a parent that died without sending one, and the trap in
// tools/reference-server/run-integration.sh. An orphaned server holding a port is the
// failure that makes the next run fail for a reason nobody can see.
//
// A launch runs on the wall clock unless `--test-clock` names a start instant. The flag
// exists because the Swift suite reaches its server only through this entry point: a case
// that has to arrange an expiry drives `/control/advance-clock`, and that route refuses a
// server on the wall clock rather than silently doing nothing.

package xyz.superfunction.spfn.reference

import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

private const val USAGE =
    "usage: SpfnReferenceMain [--port <n>] [--port-file <path>] [--session-ttl-millis <n>] " +
        "[--parent-pid <n>] [--test-clock <startMillis>]"

fun main(args: Array<String>)
{
    val options = try
    {
        parseArguments(args)
    }
    catch (failure: IllegalArgumentException)
    {
        System.err.println(failure.message);
        System.err.println(USAGE);
        exitProcess(2);
    };

    val server = serverFor(options).start();

    Runtime.getRuntime().addShutdownHook(Thread { shutDown(server, options.portFile) });
    options.portFile?.let { writeLaunchFile(it, server) };
    options.parentPid?.let { watchParent(it) };

    // The token is written to the launch file and nowhere else. Standard output is the
    // one place a developer copies text out of by hand.
    println("SPFN reference server listening on ${server.baseUrl}");
    println("This is a TEST FIXTURE with synthetic keys. It is not an SPFN endpoint.");
    options.testClockStartMillis?.let {
        println("Running on a test clock from $it ms; /control/advance-clock moves it.");
    };

    CountDownLatch(1).await();
}

internal class Options(
    val port: Int,
    val portFile: File?,
    val sessionTtlMillis: Long,
    val parentPid: Long?,
    /** The instant `--test-clock` named, or null for a launch on the wall clock. */
    val testClockStartMillis: Long?
)

/**
 * The server [main] launches, built out of nothing but [options].
 *
 * Separate from [main] so a test can drive the same construction the launch does: what
 * `--test-clock` builds is a property of this function, and a test that constructed the
 * server itself would prove only that the server honours a clock it was handed.
 */
internal fun serverFor(options: Options): SpfnReferenceServer = SpfnReferenceServer(
    requestedPort = options.port,
    clock = options.testClockStartMillis?.let { SpfnReferenceTestClock(it) } ?: SpfnReferenceClock.system(),
    sessionTtlMillis = options.sessionTtlMillis,
    log = { line -> println(line) }
)

internal fun parseArguments(args: Array<String>): Options
{
    var port = 0;
    var portFile: File? = null;
    var sessionTtlMillis = SpfnReferenceState.DEFAULT_SESSION_TTL_MILLIS;
    var parentPid: Long? = null;
    var testClockStartMillis: Long? = null;

    var index = 0;
    while (index < args.size)
    {
        val name = args[index];
        val value = args.getOrNull(index + 1) ?: throw IllegalArgumentException("$name needs a value");
        when (name)
        {
            "--port" -> port = value.toIntOrNull() ?: throw IllegalArgumentException("--port is not a number")
            "--port-file" -> portFile = File(value)
            "--session-ttl-millis" ->
                sessionTtlMillis = value.toLongOrNull() ?: throw IllegalArgumentException("--session-ttl-millis is not a number")
            "--parent-pid" -> parentPid = value.toLongOrNull() ?: throw IllegalArgumentException("--parent-pid is not a number")
            "--test-clock" ->
                testClockStartMillis = value.toLongOrNull() ?: throw IllegalArgumentException("--test-clock is not a number")
            else -> throw IllegalArgumentException("unknown option $name")
        }
        index += 2;
    }
    return Options(port, portFile, sessionTtlMillis, parentPid, testClockStartMillis);
}

/**
 * Writes the launch file the runner reads: the base URL, the port and the control token.
 *
 * Written to a neighbouring temporary file and moved into place, so a runner polling for
 * the file never reads a half-written one and concludes the server started with no token.
 */
private fun writeLaunchFile(destination: File, server: SpfnReferenceServer)
{
    val contents = SpfnCanonicalJson.encode(
        SpfnCanonicalValue.Obj(
            mapOf(
                "baseUrl" to SpfnCanonicalValue.Text(server.baseUrl),
                "controlToken" to SpfnCanonicalValue.Text(server.controlToken),
                "port" to SpfnCanonicalValue.Integer(server.port.toLong())
            )
        )
    );

    destination.absoluteFile.parentFile?.mkdirs();
    val staging = File(destination.absolutePath + ".partial");
    staging.writeBytes(contents);
    Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
}

/**
 * Shuts the server down when the process that launched it is gone.
 *
 * A parent killed with SIGKILL never runs its own trap, so nothing else would ever tell
 * this process to stop and the port would stay held until somebody noticed by hand.
 */
private fun watchParent(parentPid: Long)
{
    val watchdog = Thread {
        while (ProcessHandle.of(parentPid).map { it.isAlive }.orElse(false))
        {
            Thread.sleep(PARENT_POLL_MILLIS);
        }
        exitProcess(0);
    };
    watchdog.isDaemon = true;
    watchdog.name = "spfn-reference-parent-watchdog";
    watchdog.start();
}

private fun shutDown(server: SpfnReferenceServer, portFile: File?)
{
    server.close();
    portFile?.delete();
}

private const val PARENT_POLL_MILLIS = 500L
