package xyz.superfunction.spfn.client

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpfnProcessServerClockTest
{
    private val baseUrl = "https://example.invalid"

    @Test
    fun unsynchronizedFirstProofFetchesCoreTimeThenMintsTheProof() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                answer(timeResponse(1_750_000_000_000)),
                answer(SessionFixtureValues.HANDSHAKE_RESPONSE_BODY)
            )
        )
        val clock = SpfnProcessServerClock(FakeMonotonicClock(10)) { generatedClockOperation() }
        val session = SpfnSession(
            transport = transport,
            keyProvider = SpfnSoftwareKeyProvider.generate(
                clientId = SessionFixtureValues.CLIENT_ID,
                keyId = SessionFixtureValues.KEY_ID
            ),
            baseUrl = baseUrl,
            clock = clock,
            nonceGenerator = ScriptedNonceGenerator(listOf("nonce-000000000001"))
        )

        session.handshake()

        assertEquals(2, transport.received.size)
        assertEquals("GET", transport.received[0].method)
        assertEquals("$baseUrl/_core/time", transport.received[0].url)
        assertNull(transport.received[0].body)
        assertTrue(transport.received[0].headers.isEmpty())
        assertEquals(
            "1750000000000",
            transport.received[1].headers.first { it.first == SpfnWireHeaders.ISSUED_AT_MILLIS }.second
        )
    }

    @Test
    fun unsynchronizedConcurrentFirstReadersShareOneSynchronization() = runBlocking {
        val transport = ScriptedTransport(listOf(answer(timeResponse(1_000))), holdMillis = 50)
        val clock = SpfnProcessServerClock(FakeMonotonicClock(10)) { generatedClockOperation() }

        val values = listOf(
            async { clock.nowMillis(transport, baseUrl, 1_000) },
            async { clock.nowMillis(transport, baseUrl, 1_000) }
        ).awaitAll()

        assertEquals(listOf(1_000L, 1_000L), values)
        assertEquals(1, transport.callCount)
    }

    @Test
    fun unsynchronizedRequestFailureIsExplicitAndNoProofIsSent() = runBlocking {
        val transport = ScriptedTransport(
            listOf(ScriptedTransport.Outcome.Failure(SpfnTransportError.Connectivity("offline")))
        )
        val session = SpfnSession(
            transport = transport,
            keyProvider = SpfnSoftwareKeyProvider.generate(
                clientId = SessionFixtureValues.CLIENT_ID,
                keyId = SessionFixtureValues.KEY_ID
            ),
            baseUrl = baseUrl,
            clock = SpfnProcessServerClock(FakeMonotonicClock(10)) { generatedClockOperation() },
            nonceGenerator = ScriptedNonceGenerator(listOf("nonce-never-used"))
        )

        val error = failureOf { session.handshake() }
        assertTrue(error is SpfnClockSynchronizationException.RequestFailed)
        assertEquals(1, transport.callCount)
        assertEquals("$baseUrl/_core/time", transport.received.single().url)
    }

    @Test
    fun undecodableSynchronizationResponseFailsClosed() = runBlocking {
        val transport = ScriptedTransport(listOf(answer("{\"wrong\":1}")))
        val clock = SpfnProcessServerClock(FakeMonotonicClock(10)) { generatedClockOperation() }

        val error = failureOf { clock.nowMillis(transport, baseUrl, 1_000) }
        assertTrue(error is SpfnClockSynchronizationException.InvalidResponse)
        assertEquals(1, transport.callCount)
    }

    @Test
    fun synchronizedProofTimeIsServerEpochPlusMonotonicElapsed() = runBlocking {
        val monotonic = FakeMonotonicClock(100)
        val transport = ScriptedTransport(listOf(answer(timeResponse(10_000))))
        val clock = SpfnProcessServerClock(monotonic) { generatedClockOperation() }

        assertEquals(10_000, clock.nowMillis(transport, baseUrl, 1_000))
        monotonic.set(175)
        assertEquals(10_075, clock.nowMillis(transport, baseUrl, 1_000))
        assertEquals(1, transport.callCount)
    }

    @Test
    fun subMillisecondMonotonicPhaseDoesNotRoundElapsedUp() = runBlocking {
        val monotonic = FakeMonotonicClock(10_900_000, rawNanos = true)
        val transport = ScriptedTransport(listOf(answer(timeResponse(10_000))))
        val clock = SpfnProcessServerClock(monotonic) { generatedClockOperation() }

        clock.nowMillis(transport, baseUrl, 1_000)
        monotonic.setRawNanos(11_100_000)
        val derived = clock.nowMillis(transport, baseUrl, 1_000)

        assertEquals("0.2ms elapsed must not round up", 10_000, derived)
    }

    @Test
    fun deviceWallClockJumpDoesNotMoveSynchronizedProofTime() = runBlocking {
        val deviceWallClock = FakeClock(1_000)
        val monotonic = FakeMonotonicClock(20)
        val transport = ScriptedTransport(listOf(answer(timeResponse(50_000))))
        val clock = SpfnProcessServerClock(monotonic) { generatedClockOperation() }

        val before = clock.nowMillis(transport, baseUrl, 1_000)
        deviceWallClock.set(Long.MAX_VALUE)
        val after = clock.nowMillis(transport, baseUrl, 1_000)

        assertEquals(50_000, before)
        assertEquals(before, after)
        assertEquals(Long.MAX_VALUE, deviceWallClock.nowMillis())
    }

    @Test
    fun synchronizedClockOverflowIsExplicit() = runBlocking {
        val monotonic = FakeMonotonicClock(10)
        val transport = ScriptedTransport(listOf(answer(timeResponse(Long.MAX_VALUE))))
        val clock = SpfnProcessServerClock(monotonic) { generatedClockOperation() }

        clock.nowMillis(transport, baseUrl, 1_000)
        monotonic.set(11)
        val error = failureOf { clock.nowMillis(transport, baseUrl, 1_000) }
        assertTrue(error is SpfnClockSynchronizationException.ClockOverflow)
    }

    @Test
    fun newProcessClockHasNoPersistedAnchorAndSynchronizesAgain() = runBlocking {
        val transport = ScriptedTransport(
            listOf(answer(timeResponse(1_000)), answer(timeResponse(2_000)))
        )

        val first = SpfnProcessServerClock(FakeMonotonicClock(10)) { generatedClockOperation() }
        val second = SpfnProcessServerClock(FakeMonotonicClock(20)) { generatedClockOperation() }
        assertEquals(1_000, first.nowMillis(transport, baseUrl, 1_000))
        assertEquals(2_000, second.nowMillis(transport, baseUrl, 1_000))
        assertEquals(2, transport.callCount)
    }

    @Test
    fun missingContractOperationIsAnExplicitIncompatibility() = runBlocking {
        val transport = ScriptedTransport(emptyList())
        val clock = SpfnProcessServerClock(FakeMonotonicClock(10)) { null }

        val error = failureOf { clock.nowMillis(transport, baseUrl, 1_000) }
        assertTrue(error is SpfnClockSynchronizationException.ContractIncompatible)
        assertEquals(0, transport.callCount)
    }

    @Test
    fun nonLoopbackCleartextIsRejectedBeforeTheNetwork() = runBlocking {
        val transport = ScriptedTransport(emptyList())
        val clock = SpfnProcessServerClock(FakeMonotonicClock(10)) { generatedClockOperation() }

        val error = failureOf { clock.nowMillis(transport, "http://example.invalid", 1_000) }
        assertTrue(error is SpfnClockSynchronizationException.UntrustedBaseUrl)
        assertEquals(0, transport.callCount)
    }

    private fun answer(body: String): ScriptedTransport.Outcome =
        ScriptedTransport.Outcome.Answer(jsonResponse(200, body))

    private fun timeResponse(millis: Long): String = "{\"serverTimeMillis\":$millis}"

    private fun generatedClockOperation() =
        xyz.superfunction.spfn.generated.SpfnGeneratedOperations.operation(
            xyz.superfunction.spfn.generated.SpfnGeneratedContract.CLOCK_SYNCHRONIZATION_OPERATION_ID
        )

    private suspend fun failureOf(block: suspend () -> Unit): Throwable
    {
        try
        {
            block();
        }
        catch (failure: Throwable)
        {
            return failure;
        }
        fail("expected a failure");
        throw AssertionError("unreachable")
    }
}

private class FakeMonotonicClock(value: Long, rawNanos: Boolean = false) : SpfnMonotonicClock
{
    @Volatile
    private var nanos: Long = if (rawNanos) value else value * 1_000_000

    override fun nowNanos(): Long = nanos

    fun set(value: Long)
    {
        nanos = value * 1_000_000;
    }

    fun setRawNanos(value: Long)
    {
        nanos = value;
    }
}
