// SPFN Mobile — the Android SDK, wired to a reference server.
//
// The integration suite uses the shipped client rather than a copy of it: the same
// `SpfnClient`, `SpfnSession` and `SpfnOkHttpTransport` an app would link. The only thing
// assembled here is the per-operation call descriptor, which the contract generator does
// not emit yet (docs/SCAFFOLD-STATUS.md), and the clock, which every test injects.
//
// The reference server is started in process by default and left alone when the run names
// an external one; see `SpfnReferenceTarget.kt` for how that is chosen and why a named
// target is never allowed to fall back to a local server.

package xyz.superfunction.spfn.reference

import okhttp3.OkHttpClient
import xyz.superfunction.spfn.client.SpfnCall
import xyz.superfunction.spfn.client.SpfnClient
import xyz.superfunction.spfn.client.SpfnClock
import xyz.superfunction.spfn.client.SpfnInMemoryKeyProvider
import xyz.superfunction.spfn.client.SpfnOkHttpTransport
import xyz.superfunction.spfn.client.SpfnSession
import xyz.superfunction.spfn.client.SpfnSystemClock
import xyz.superfunction.spfn.client.SpfnTransport
import xyz.superfunction.spfn.generated.SpfnEchoRequest
import xyz.superfunction.spfn.generated.SpfnEchoResponse
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnListItemsRequest
import xyz.superfunction.spfn.generated.SpfnListItemsResponse
import java.util.concurrent.TimeUnit

/** The call descriptors the three operations need, spelled out once. */
object SpfnReferenceCalls
{
    val echo: SpfnCall<SpfnEchoRequest, SpfnEchoResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.echoSend,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnEchoResponse.decode(value) }
    )

    val listItems: SpfnCall<SpfnListItemsRequest, SpfnListItemsResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.itemsList,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnListItemsResponse.decode(value) }
    )
}

/**
 * Which server this run is talking to, and everything that differs because of it.
 *
 * In process the client clock is frozen at the instant the server's test clock starts, so
 * a proof a frozen client mints is inside the server's replay window and the server's own
 * time is a value the case can assert exactly. Against an external server neither is true:
 * it runs on the wall clock, so the client has to as well, and the instant it answers with
 * is only knowable as "a real one".
 */
private class SpfnReferenceMode(
    val local: SpfnReferenceHarness?,
    val baseUrl: String,
    val control: SpfnReferenceControlSurface,
    val expectedServerTimeMillis: Long?,
    val clientClock: SpfnClock
)
{
    companion object
    {
        fun resolve(): SpfnReferenceMode
        {
            val target = SpfnIntegrationTarget.resolve() ?: return inProcess();
            return SpfnReferenceMode(
                local = null,
                baseUrl = target.baseUrl,
                control = SpfnHttpControl(target),
                expectedServerTimeMillis = null,
                clientClock = SpfnSystemClock()
            );
        }

        private fun inProcess(): SpfnReferenceMode
        {
            val server = SpfnReferenceHarness();
            return SpfnReferenceMode(
                local = server,
                baseUrl = server.baseUrl,
                control = SpfnInProcessControl(server.server.state),
                expectedServerTimeMillis = SpfnReferenceTestClock.DEFAULT_START_MILLIS,
                clientClock = SpfnClock { SpfnReferenceTestClock.DEFAULT_START_MILLIS }
            );
        }
    }
}

/**
 * One SDK client pointed at a reference server, with everything a test needs to observe.
 *
 * The server is started in process unless the run named an external one, and the case
 * bodies cannot tell the difference: every state a case arranges goes through [control],
 * which is a method call on one side and `/control` over HTTP on the other.
 *
 * The server is reset on construction, so a case that revoked a key cannot decide the
 * outcome of the case that runs after it. In process that is a fresh server anyway; against
 * a long-lived external one it is the only thing that keeps the cases independent.
 */
class SpfnReferenceClientHarness(timeoutMillis: Long = 5_000) : AutoCloseable
{
    private val mode = SpfnReferenceMode.resolve()

    val baseUrl: String get() = mode.baseUrl

    val control: SpfnReferenceControlSurface get() = mode.control

    /** The instant the server answers with, when this run is in a position to know it. */
    val expectedServerTimeMillis: Long? get() = mode.expectedServerTimeMillis

    val clientClock: SpfnClock get() = mode.clientClock

    private val okHttpClient = OkHttpClient()

    val transport: SpfnTransport = SpfnOkHttpTransport(okHttpClient)

    val session: SpfnSession = SpfnSession(
        transport = transport,
        keyProvider = SpfnInMemoryKeyProvider(
            clientId = SpfnReferenceTestKeys.CLIENT_ID,
            keyId = SpfnReferenceTestKeys.KEY_ID,
            key = SpfnReferenceTestKeys.KEY_BYTES
        ),
        baseUrl = mode.baseUrl,
        clock = mode.clientClock,
        timeoutMillis = timeoutMillis
    )

    val client: SpfnClient = SpfnClient(transport, session, timeoutMillis = timeoutMillis)

    init
    {
        control.reset();
    }

    fun stats(): SpfnReferenceStats = control.stats()

    override fun close()
    {
        okHttpClient.dispatcher.executorService.shutdown();
        okHttpClient.connectionPool.evictAll();
        okHttpClient.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS);
        // Only ever the server this harness started. An external target outlives the run
        // that used it, and stopping one would be this suite reaching outside itself.
        mode.local?.close();
    }
}
