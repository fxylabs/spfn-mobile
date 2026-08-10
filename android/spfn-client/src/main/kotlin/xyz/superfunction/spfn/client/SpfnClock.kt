// SPFN Mobile — the two ambient inputs a proof depends on.
//
// Counterpart of Sources/SPFNClient/SPFNClock.swift. A proof carries a timestamp and a
// nonce, so a session that read the wall clock and the system random generator directly
// would be untestable: no test could assert that two consecutive proofs carry different
// nonces, or that a session expires exactly at its expiry instant. Both are injected
// instead, and every test injects a fake.

package xyz.superfunction.spfn.client

import android.os.SystemClock
import java.net.URI
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnOperation
import xyz.superfunction.spfn.generated.SpfnGeneratedContract
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnServerTimeResponse

/** Milliseconds since the Unix epoch. */
fun interface SpfnClock
{
    fun nowMillis(): Long
}

/**
 * The system wall clock used for local key-lifecycle timestamps, never for proofs.
 */
class SpfnSystemClock : SpfnClock
{
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** A process-local, fail-closed source of clientProofV1 timestamps. */
fun interface SpfnProofClock
{
    suspend fun nowMillis(transport: SpfnTransport, baseUrl: String, timeoutMillis: Long): Long
}

/** Fixed, non-sensitive reasons the proof clock could not synchronize or advance. */
sealed class SpfnClockSynchronizationException(message: String) : IllegalStateException(message)
{
    class ContractIncompatible :
        SpfnClockSynchronizationException("the contract does not declare a usable clock operation")

    class UntrustedBaseUrl :
        SpfnClockSynchronizationException("clock synchronization requires HTTPS or loopback HTTP")

    class RequestFailed :
        SpfnClockSynchronizationException("the clock synchronization request failed")

    class InvalidResponse :
        SpfnClockSynchronizationException("the clock synchronization response is invalid")

    class MonotonicClockInvalid :
        SpfnClockSynchronizationException("the monotonic clock moved backwards")

    class ClockOverflow :
        SpfnClockSynchronizationException("the synchronized clock exceeded signed 64-bit milliseconds")
}

internal fun interface SpfnMonotonicClock
{
    fun nowNanos(): Long
}

internal object SpfnSystemMonotonicClock : SpfnMonotonicClock
{
    override fun nowNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

/**
 * The default clientProofV1 clock. [shared] retains one in-memory anchor per normalized
 * base URL. Concurrent first readers share one request; nothing is persisted.
 */
class SpfnProcessServerClock internal constructor(
    private val monotonicClock: SpfnMonotonicClock,
    private val operationResolver: () -> SpfnOperation?
) : SpfnProofClock
{
    constructor() : this(
        monotonicClock = SpfnSystemMonotonicClock,
        operationResolver = {
            SpfnGeneratedOperations.operation(SpfnGeneratedContract.CLOCK_SYNCHRONIZATION_OPERATION_ID)
        }
    )

    private class Anchor(val serverTimeMillis: Long, val monotonicReceiptNanos: Long)

    private val mutex = Mutex()
    private val anchors = mutableMapOf<String, Anchor>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<Anchor>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun nowMillis(transport: SpfnTransport, baseUrl: String, timeoutMillis: Long): Long
    {
        val key = baseUrl.trimEnd('/');
        val pending = mutex.withLock {
            anchors[key]?.let { return derivedTime(it) };
            inFlight[key]?.let { return@withLock it };

            if (!isTrusted(key))
            {
                throw SpfnClockSynchronizationException.UntrustedBaseUrl();
            }
            val operation = operationResolver()
                ?: throw SpfnClockSynchronizationException.ContractIncompatible();
            if (operation.authProfile != "none" || operation.requiresSession)
            {
                throw SpfnClockSynchronizationException.ContractIncompatible();
            }

            val created = CompletableDeferred<Anchor>();
            inFlight[key] = created;
            scope.launch { synchronize(key, operation, transport, timeoutMillis, created) };
            created;
        };
        return derivedTime(pending.await());
    }

    private suspend fun synchronize(
        key: String,
        operation: SpfnOperation,
        transport: SpfnTransport,
        timeoutMillis: Long,
        pending: CompletableDeferred<Anchor>
    )
    {
        try
        {
            val response = try
            {
                transport.execute(
                    SpfnTransportRequest(
                        method = operation.method,
                        url = key + operation.path,
                        headers = emptyList(),
                        body = null,
                        timeoutMillis = timeoutMillis
                    )
                )
            }
            catch (cancellation: kotlinx.coroutines.CancellationException)
            {
                throw cancellation;
            }
            catch (_: Exception)
            {
                throw SpfnClockSynchronizationException.RequestFailed();
            };

            val receipt = monotonicClock.nowNanos();
            if (receipt < 0 || response.statusCode !in 200..299)
            {
                throw SpfnClockSynchronizationException.InvalidResponse();
            }
            val decoded = try
            {
                SpfnServerTimeResponse.decode(SpfnCanonicalJson.parse(response.body));
            }
            catch (_: IllegalArgumentException)
            {
                throw SpfnClockSynchronizationException.InvalidResponse();
            };
            val anchor = Anchor(decoded.serverTimeMillis, receipt);
            mutex.withLock {
                if (inFlight[key] === pending)
                {
                    anchors[key] = anchor;
                    inFlight.remove(key);
                }
            };
            pending.complete(anchor);
        }
        catch (failure: Throwable)
        {
            mutex.withLock {
                if (inFlight[key] === pending)
                {
                    inFlight.remove(key);
                }
            };
            pending.completeExceptionally(failure);
        }
    }

    private fun derivedTime(anchor: Anchor): Long
    {
        val now = monotonicClock.nowNanos();
        if (now < anchor.monotonicReceiptNanos)
        {
            throw SpfnClockSynchronizationException.MonotonicClockInvalid();
        }
        val elapsed = (now - anchor.monotonicReceiptNanos) / 1_000_000;
        if (elapsed > 0 && anchor.serverTimeMillis > Long.MAX_VALUE - elapsed)
        {
            throw SpfnClockSynchronizationException.ClockOverflow();
        }
        return anchor.serverTimeMillis + elapsed;
    }

    private fun isTrusted(baseUrl: String): Boolean
    {
        val uri = try
        {
            URI(baseUrl);
        }
        catch (_: IllegalArgumentException)
        {
            return false;
        };
        val scheme = uri.scheme?.lowercase() ?: return false;
        val host = uri.host?.lowercase() ?: return false;
        if (scheme == "https")
        {
            return true;
        }
        return scheme == "http" && (host == "localhost" || host == "::1" || host.startsWith("127."));
    }

    companion object
    {
        /** The process-wide default used by every session and lifecycle. */
        val shared: SpfnProcessServerClock = SpfnProcessServerClock()
    }
}

/** Produces one fresh nonce per request. */
fun interface SpfnNonceGenerator
{
    fun nextNonce(): String
}

/**
 * 128 bits from the platform's cryptographic random source, as lowercase base16.
 *
 * Hex rather than any denser encoding because a proof field may not contain a C0 control
 * character and must survive an HTTP header value unchanged; hex satisfies both without
 * an escaping rule two platforms could implement differently.
 */
class SpfnRandomNonceGenerator : SpfnNonceGenerator
{
    private val random = SecureRandom()

    override fun nextNonce(): String
    {
        val bytes = ByteArray(BYTE_COUNT);
        random.nextBytes(bytes);

        val out = StringBuilder(BYTE_COUNT * 2);
        for (byte in bytes)
        {
            val value = byte.toInt() and 0xFF;
            out.append(HEX_DIGITS[value shr 4]);
            out.append(HEX_DIGITS[value and 0x0F]);
        }
        return out.toString();
    }

    private companion object
    {
        const val BYTE_COUNT = 16
        val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }
}
