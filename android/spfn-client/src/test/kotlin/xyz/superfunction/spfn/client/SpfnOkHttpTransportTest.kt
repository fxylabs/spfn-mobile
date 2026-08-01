// SPFN Mobile — OkHttp adapter conformance to the transport contract.
//
// Every case here has a counterpart with the same name in
// Tests/SPFNClientTests/SPFNURLSessionTransportTests.swift. Where a platform cannot
// express the same assertion, the divergence is stated in the test's own comment rather
// than hidden by dropping the case.
//
// One Swift case has no counterpart: `nonHttpResponseSurfacesAsInvalidResponse`. OkHttp
// only ever produces an HTTP response, so `InvalidResponse` is unreachable here, whereas
// URLSession can hand back a plain URLResponse.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SpfnOkHttpTransportTest
{
    private lateinit var server: MockWebServer

    @Before
    fun startServer()
    {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer()
    {
        server.close()
    }

    private fun request(
        method: String = "GET",
        path: String = "/v1/thing?nonce=abc",
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        timeoutMillis: Long = 30_000
    ): SpfnTransportRequest =
        SpfnTransportRequest(
            method = method,
            url = server.url(path).toString(),
            headers = headers,
            body = body,
            timeoutMillis = timeoutMillis
        )

    // --- request mapping ----------------------------------------------------

    @Test
    fun requestMappingCarriesMethodUrlHeadersAndBody()
    {
        val mapped = toOkHttpRequest(
            request(
                method = "POST",
                headers = listOf("X-Spfn-Client" to "c1", "Content-Type" to "application/json"),
                body = byteArrayOf(0x7B, 0x7D)
            )
        )

        assertEquals("POST", mapped.method)
        assertEquals("/v1/thing", mapped.url.encodedPath)
        assertEquals("abc", mapped.url.queryParameter("nonce"))
        assertEquals("c1", mapped.header("X-Spfn-Client"))
        assertEquals("application/json", mapped.header("Content-Type"))
        assertEquals(2L, mapped.body?.contentLength())
        // The body carries no content type of its own, so the caller's explicit
        // Content-Type header is the one that reaches the wire.
        assertNull(mapped.body?.contentType())
    }

    @Test
    fun duplicateRequestHeadersAreCarriedInOrder()
    {
        val mapped = toOkHttpRequest(
            request(headers = listOf("X-Spfn-Trace" to "first", "X-Spfn-Trace" to "second"))
        )

        // OkHttp keeps repeated names as separate header lines, in order. URLRequest
        // cannot: the Swift counterpart asserts the comma-joined form instead.
        assertEquals(listOf("first", "second"), mapped.headers.values("X-Spfn-Trace"))
    }

    @Test
    fun absentBodyAndEmptyBodyAreDistinct()
    {
        // A method that permits no body keeps `null` as "no body at all".
        assertNull(toOkHttpRequest(request(method = "GET", body = null)).body)

        // A method that requires one gets a zero-length body rather than a rejection,
        // which is the same zero bytes URLSession puts on the wire for the same input.
        assertEquals(0L, toOkHttpRequest(request(method = "POST", body = null)).body?.contentLength())
        assertEquals(0L, toOkHttpRequest(request(method = "POST", body = ByteArray(0))).body?.contentLength())
        assertEquals(3L, toOkHttpRequest(request(method = "POST", body = ByteArray(3))).body?.contentLength())
    }

    @Test
    fun nonPositiveTimeoutIsRefused()
    {
        for (millis in listOf(0L, -1L))
        {
            val error = assertThrowsTransportError { toOkHttpRequest(request(timeoutMillis = millis)) }
            assertTrue(error is SpfnTransportError.Connectivity)
            assertEquals(
                "timeoutMillis must be positive",
                (error as SpfnTransportError.Connectivity).reason
            )
        }
    }

    @Test
    fun malformedUrlSurfacesAsConnectivity()
    {
        val error = assertThrowsTransportError { toOkHttpRequest(request().withUrl("/v1/thing")) }
        assertTrue(error is SpfnTransportError.Connectivity)
        assertEquals("request URL is not an absolute URL", (error as SpfnTransportError.Connectivity).reason)
    }

    @Test
    fun aBodyOnAMethodThatForbidsOneSurfacesAsConnectivity()
    {
        val error = assertThrowsTransportError {
            toOkHttpRequest(request(method = "GET", body = byteArrayOf(1)))
        }
        assertTrue(error is SpfnTransportError.Connectivity)
        assertFalse(
            "the reason must not repeat the request",
            (error as SpfnTransportError.Connectivity).reason.contains("nonce")
        )
    }

    // --- response mapping ---------------------------------------------------

    @Test
    fun responseMappingCarriesStatusHeadersAndBody() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("X-Spfn-Echo", "one")
                .addHeader("X-Spfn-Echo", "two")
                .body("12")
                .build()
        )

        val response = SpfnOkHttpTransport().execute(request())

        assertEquals(200, response.statusCode)
        assertEquals("12", String(response.body))
        // Repeated response headers survive on this platform, in wire order. The Swift
        // counterpart cannot assert this: allHeaderFields is a dictionary.
        assertEquals(
            listOf("one", "two"),
            response.headers.filter { it.first.equals("X-Spfn-Echo", ignoreCase = true) }.map { it.second }
        )
    }

    @Test
    fun nonSuccessStatusIsReturnedNotThrown() = runBlocking {
        for (status in listOf(401, 429, 500))
        {
            server.enqueue(MockResponse.Builder().code(status).build())
            val before = server.requestCount
            val response = SpfnOkHttpTransport().execute(request())
            assertEquals(status, response.statusCode)
            assertEquals("a failed status must not be retried by the transport", 1, server.requestCount - before)
        }
    }

    @Test
    fun redirectIsReturnedNotFollowed() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(302)
                .addHeader("Location", server.url("/v1/elsewhere").toString())
                .build()
        )

        val response = SpfnOkHttpTransport().execute(request())

        assertEquals(302, response.statusCode)
        assertEquals("the redirect target must not be requested", 1, server.requestCount)
        assertEquals(
            server.url("/v1/elsewhere").toString(),
            response.headers.first { it.first.equals("Location", ignoreCase = true) }.second
        )
    }

    @Test
    fun cookiesAndCachingAreOffOnEveryRequest() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Set-Cookie", "session=abc; Path=/").build())
        server.enqueue(MockResponse.Builder().code(200).build())

        val transport = SpfnOkHttpTransport()
        transport.execute(request())
        transport.execute(request())

        server.takeRequest(5, TimeUnit.SECONDS)
        val second = server.takeRequest(5, TimeUnit.SECONDS)
        assertNull("a cookie from one exchange must not reach the next", second?.headers?.get("Cookie"))
    }

    // --- failure classification ---------------------------------------------

    @Test
    fun timeoutSurfacesAsTimedOut()
    {
        server.enqueue(MockResponse.Builder().code(200).headersDelay(5, TimeUnit.SECONDS).build())

        val error = assertThrowsTransportError {
            SpfnOkHttpTransport().execute(request(timeoutMillis = 250))
        }
        assertTrue("expected TimedOut, got $error", error is SpfnTransportError.TimedOut)
    }

    @Test
    fun connectivityFailureSurfacesAsConnectivity()
    {
        val closed = MockWebServer()
        closed.start()
        val url = closed.url("/v1/thing").toString()
        closed.close()

        val error = assertThrowsTransportError {
            SpfnOkHttpTransport().execute(
                SpfnTransportRequest(method = "GET", url = url, timeoutMillis = 5_000)
            )
        }
        assertTrue("expected Connectivity, got $error", error is SpfnTransportError.Connectivity)
        // The reason names the failure class only. A message could embed the URL, and a
        // URL can carry a nonce.
        val reason = (error as SpfnTransportError.Connectivity).reason
        assertTrue("reason '$reason' is not a class name", reason.startsWith("java."))
    }

    @Test
    fun cancellationRethrowsCancellationException()
    {
        val reachedServer = CountDownLatch(1)
        val callCancelled = CountDownLatch(1)
        // Held until the assertions are done. A fixed sleep would still be running when
        // @After closes the server, and MockWebServer fails the test for that.
        val releaseServer = CountDownLatch(1)

        server.dispatcher = object : Dispatcher()
        {
            override fun dispatch(request: RecordedRequest): MockResponse
            {
                reachedServer.countDown()
                releaseServer.await(10, TimeUnit.SECONDS)
                return MockResponse.Builder().code(200).build()
            }
        }

        val watched = OkHttpClient.Builder()
            .eventListener(object : EventListener()
            {
                override fun canceled(call: Call)
                {
                    callCancelled.countDown()
                }
            })
            .build()

        var thrown: Throwable? = null
        runBlocking {
            val job = launch(Dispatchers.IO) {
                try
                {
                    SpfnOkHttpTransport(watched).execute(request(timeoutMillis = 60_000))
                }
                catch (e: Throwable)
                {
                    thrown = e
                    throw e
                }
            }

            withContext(Dispatchers.IO) { reachedServer.await(10, TimeUnit.SECONDS) }
            job.cancel()
            job.join()
        }

        val cancelReachedTheCall = callCancelled.await(10, TimeUnit.SECONDS)
        releaseServer.countDown()

        // Kotlin surfaces cancellation as CancellationException rather than as a transport
        // error: wrapping it would make a cancelled scope look like a network failure to
        // every enclosing coroutine. Swift does the opposite for the same reason — see the
        // Swift counterpart.
        assertTrue("expected CancellationException, got $thrown", thrown is CancellationException)
        assertFalse("cancellation must not be wrapped in a transport error", thrown is SpfnTransportError)
        assertTrue("the underlying HTTP call was not cancelled", cancelReachedTheCall)
    }

    @Test
    fun cancelledCallSurfacesAsCancelled()
    {
        val call = OkHttpClient().newCall(toOkHttpRequest(request()))
        call.cancel()

        // OkHttp reports cancellation as a plain IOException("Canceled"). Classifying that
        // as connectivity would make the layers above retry a call the caller stopped.
        assertTrue(transportErrorFor(call, IOException("Canceled")) is SpfnTransportError.Cancelled)

        // A call timeout also cancels the call, so the timeout question has to be asked
        // first or every timeout would report itself as a cancellation.
        assertTrue(
            transportErrorFor(call, InterruptedIOException("timeout")) is SpfnTransportError.TimedOut
        )
    }

    // --- redaction ----------------------------------------------------------

    @Test
    fun descriptionsCarryNoHeaderOrBodyMaterial()
    {
        val outbound = request(
            headers = listOf("Authorization" to "spfn-proof deadbeef"),
            body = "secret-payload".toByteArray()
        )
        val inbound = SpfnTransportResponse(
            statusCode = 200,
            headers = listOf("Set-Cookie" to "session=deadbeef"),
            body = "secret-response".toByteArray()
        )

        assertFalse(outbound.toString().contains("deadbeef"))
        assertFalse(outbound.toString().contains("secret-payload"))
        assertFalse("the URL can carry a nonce", outbound.toString().contains("nonce"))
        assertTrue(outbound.toString().contains("14 bytes"))

        assertFalse(inbound.toString().contains("deadbeef"))
        assertFalse(inbound.toString().contains("secret-response"))

        assertTrue(request(body = null).toString().contains("absent"))
        assertTrue(request(body = ByteArray(0)).toString().contains("0 bytes"))
    }

    // --- helpers ------------------------------------------------------------

    private fun SpfnTransportRequest.withUrl(url: String): SpfnTransportRequest =
        SpfnTransportRequest(method, url, headers, body, timeoutMillis)

    /**
     * Runs `body` to completion on the calling thread and requires it to fail with a
     * transport error. Suspending so the same helper covers the mapping cases, which throw
     * before any call starts, and the network cases, which throw from the callback.
     */
    private fun assertThrowsTransportError(body: suspend () -> Unit): SpfnTransportError
    {
        try
        {
            runBlocking { body() }
        }
        catch (e: SpfnTransportError)
        {
            return e
        }
        throw AssertionError("expected a SpfnTransportError, nothing was thrown")
    }
}
