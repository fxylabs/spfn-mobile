// SPFN Mobile — the transport contract, independent of any HTTP stack.
//
// A test double implements the interface so the contract the layers above depend on —
// one call per execute, a non-2xx is a value, a failure is one of four cases — is pinned
// without OkHttp in the picture. SPFNTransportContractTests.swift is the counterpart.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A transport that answers from a script and counts what it was asked. */
class RecordingTransport(private val answer: () -> SpfnTransportResponse) : SpfnTransport
{
    val received: MutableList<SpfnTransportRequest> = mutableListOf()

    override suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse
    {
        received.add(request)
        return answer()
    }
}

class SpfnTransportContractTest
{
    private fun request(): SpfnTransportRequest =
        SpfnTransportRequest(
            method = "POST",
            url = "https://example.invalid/v1/thing",
            headers = listOf("X-Spfn-Client" to "c1"),
            body = byteArrayOf(0x7B, 0x7D),
            timeoutMillis = 5_000
        )

    @Test
    fun nonSuccessStatusIsReturnedNotThrown() = runBlocking {
        val transport: SpfnTransport = RecordingTransport {
            SpfnTransportResponse(401, listOf("WWW-Authenticate" to "spfn"), ByteArray(0))
        }

        val response = transport.execute(request())

        assertEquals(401, response.statusCode)
        assertEquals(0, response.body.size)
    }

    @Test
    fun eachExecuteSendsExactlyOneRequest() = runBlocking {
        val transport = RecordingTransport { throw SpfnTransportError.Connectivity("java.net.ConnectException") }

        repeat(3) {
            try
            {
                transport.execute(request())
            }
            catch (e: SpfnTransportError)
            {
                assertTrue(e is SpfnTransportError.Connectivity)
            }
        }

        assertEquals("the transport must not retry on its own", 3, transport.received.size)
    }

    @Test
    fun everyFailureIsOneOfTheFourCases() = runBlocking {
        val cases: List<SpfnTransportError> = listOf(
            SpfnTransportError.Connectivity("java.net.ConnectException"),
            SpfnTransportError.TimedOut(),
            SpfnTransportError.Cancelled(),
            SpfnTransportError.InvalidResponse("response is not an HTTP response")
        )

        for (expected in cases)
        {
            val transport = RecordingTransport { throw expected }
            try
            {
                transport.execute(request())
                throw AssertionError("expected $expected")
            }
            catch (e: SpfnTransportError)
            {
                assertEquals(expected.javaClass, e.javaClass)
            }
        }
    }

    @Test
    fun requestReachesTheTransportUnchanged() = runBlocking {
        val transport = RecordingTransport { SpfnTransportResponse(200, emptyList(), ByteArray(0)) }

        transport.execute(request())

        val received = transport.received.single()
        assertEquals("POST", received.method)
        assertEquals("https://example.invalid/v1/thing", received.url)
        assertEquals(listOf("X-Spfn-Client"), received.headers.map { it.first })
        assertTrue(byteArrayOf(0x7B, 0x7D).contentEquals(received.body))
        assertEquals(5_000L, received.timeoutMillis)
    }
}
