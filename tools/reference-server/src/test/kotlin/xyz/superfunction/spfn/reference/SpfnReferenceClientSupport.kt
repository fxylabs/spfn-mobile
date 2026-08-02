// SPFN Mobile — the Android SDK, wired to a reference server.
//
// The integration suite uses the shipped client rather than a copy of it: the same
// `SpfnClient`, `SpfnSession` and `SpfnOkHttpTransport` an app would link. The only thing
// assembled here is the per-operation call descriptor, which the contract generator does
// not emit yet (docs/SCAFFOLD-STATUS.md), and the clock, which every test injects.

package xyz.superfunction.spfn.reference

import okhttp3.OkHttpClient
import xyz.superfunction.spfn.client.SpfnCall
import xyz.superfunction.spfn.client.SpfnClient
import xyz.superfunction.spfn.client.SpfnClock
import xyz.superfunction.spfn.client.SpfnInMemoryKeyProvider
import xyz.superfunction.spfn.client.SpfnOkHttpTransport
import xyz.superfunction.spfn.client.SpfnSession
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
 * One SDK client pointed at a reference server, with everything a test needs to observe.
 *
 * The client clock is frozen by default at the instant the server's test clock starts.
 * That is what makes the expiry case reachable: the client goes on believing its session
 * is alive while the server's clock moves past the expiry it advertised, which is exactly
 * the situation a re-handshake exists for. Both clocks start at the same instant, so the
 * proof a frozen client mints is still inside the server's replay window.
 */
class SpfnReferenceClientHarness(
    sessionTtlMillis: Long = SpfnReferenceState.DEFAULT_SESSION_TTL_MILLIS,
    timeoutMillis: Long = 5_000
) : AutoCloseable
{
    val server: SpfnReferenceHarness = SpfnReferenceHarness(sessionTtlMillis)

    val clientClock: SpfnClock = SpfnClock { SpfnReferenceTestClock.DEFAULT_START_MILLIS }

    private val okHttpClient = OkHttpClient()

    val transport: SpfnTransport = SpfnOkHttpTransport(okHttpClient)

    val session: SpfnSession = SpfnSession(
        transport = transport,
        keyProvider = SpfnInMemoryKeyProvider(
            clientId = SpfnReferenceTestKeys.CLIENT_ID,
            keyId = SpfnReferenceTestKeys.KEY_ID,
            key = SpfnReferenceTestKeys.KEY_BYTES
        ),
        baseUrl = server.baseUrl,
        clock = clientClock,
        timeoutMillis = timeoutMillis
    )

    val client: SpfnClient = SpfnClient(transport, session, timeoutMillis = timeoutMillis)

    fun stats(): SpfnReferenceStats = server.server.state.stats()

    override fun close()
    {
        okHttpClient.dispatcher.executorService.shutdown();
        okHttpClient.connectionPool.evictAll();
        okHttpClient.dispatcher.executorService.awaitTermination(2, TimeUnit.SECONDS);
        server.close();
    }
}
