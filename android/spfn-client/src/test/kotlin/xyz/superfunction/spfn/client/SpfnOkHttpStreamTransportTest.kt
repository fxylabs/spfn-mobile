// SPFN Mobile — the OkHttp stream adapter over a real local socket (design §9-3).
//
// MockWebServer is already this module's test dependency. What is held here is the
// hardening the design names — no call timeout, no redirect, no cookie, no library retry
// with a consumed token (§8 H-1, H-5) — and that the body arrives as chunks in order.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.CookieJar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class SpfnOkHttpStreamTransportTest
{
    private lateinit var server: MockWebServer

    @Before
    fun startServer()
    {
        server = MockWebServer();
        server.start();
    }

    @After
    fun stopServer()
    {
        server.close();
    }

    private fun request(path: String = "/events/stream?token=t&events=a", timeoutMillis: Long = 5_000) =
        SpfnTransportRequest(method = "GET", url = server.url(path).toString(), headers = listOf("accept" to "text/event-stream"), timeoutMillis = timeoutMillis);

    @Test
    fun adapter_deliversChunksInOrder() = runBlocking {
        val body = ServerFrames.CONNECTED + ServerFrames.activity(1, "s-1", "w-1") + ServerFrames.PING;
        server.enqueue(MockResponse.Builder().code(200).addHeader("content-type", "text/event-stream").chunkedBody(body, 7).build());

        val response = SpfnOkHttpStreamTransport().open(request());
        val parser = SpfnSseLineParser();
        val events = response.chunks.toList().flatMap { parser.feed(it) };
        response.cancel();

        assertEquals(200, response.statusCode);
        assertEquals(listOf("connected", "sessionActivity", "ping"), events.map { it.name });
        assertEquals("text/event-stream", server.takeRequest(5, TimeUnit.SECONDS)?.headers?.get("accept"));
    }

    @Test
    fun adapter_callTimeoutIsZero_andHardeningHolds()
    {
        val client = SpfnOkHttpStreamTransport().streamClient;
        assertEquals(0, client.callTimeoutMillis);
        assertEquals(0, client.readTimeoutMillis);
        assertFalse(client.followRedirects);
        assertFalse(client.followSslRedirects);
        assertFalse(client.retryOnConnectionFailure);
        assertEquals(CookieJar.NO_COOKIES, client.cookieJar);
        assertNull(client.cache);
    }

    @Test
    fun adapter_doesNotFollowRedirects() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/elsewhere").toString()).build());
        server.enqueue(MockResponse.Builder().code(200).build());

        val response = SpfnOkHttpStreamTransport().open(request());
        response.cancel();

        assertEquals(302, response.statusCode);
        assertEquals(1, server.requestCount);
    }

    @Test
    fun adapter_sendsNoCookies() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "session=abc; Path=/").build());
        server.enqueue(MockResponse.Builder().code(200).build());
        val transport = SpfnOkHttpStreamTransport();

        transport.open(request()).cancel();
        transport.open(request()).cancel();

        server.takeRequest(5, TimeUnit.SECONDS);
        assertNull(server.takeRequest(5, TimeUnit.SECONDS)?.headers?.get("Cookie"));
    }

    @Test
    fun adapter_droppedConnection_isNotRetried() = runBlocking {
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build());
        server.enqueue(MockResponse.Builder().code(200).build());

        try
        {
            SpfnOkHttpStreamTransport().open(request());
            fail("a dropped connection must surface, not be retried with the same token");
        }
        catch (_: IOException)
        {
            // Expected: the adapter reports the failure; the state machine mints a new token.
        }
        assertEquals(1, server.requestCount);
    }

    @Test
    fun adapter_headersDeadline_isTheOnlyTimeout() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).headersDelay(2, TimeUnit.SECONDS).build());
        val started = System.nanoTime();
        try
        {
            SpfnOkHttpStreamTransport().open(request(timeoutMillis = 200));
            fail("headers that never come must time out");
        }
        catch (_: SpfnTransportError.TimedOut)
        {
            // Expected.
        }
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2));
    }
}
