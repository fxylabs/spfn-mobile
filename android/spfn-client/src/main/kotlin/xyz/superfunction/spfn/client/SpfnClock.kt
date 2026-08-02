// SPFN Mobile — the two ambient inputs a proof depends on.
//
// Counterpart of Sources/SPFNClient/SPFNClock.swift. A proof carries a timestamp and a
// nonce, so a session that read the wall clock and the system random generator directly
// would be untestable: no test could assert that two consecutive proofs carry different
// nonces, or that a session expires exactly at its expiry instant. Both are injected
// instead, and every test injects a fake.

package xyz.superfunction.spfn.client

import java.security.SecureRandom

/** Milliseconds since the Unix epoch. */
fun interface SpfnClock
{
    fun nowMillis(): Long
}

/**
 * The system wall clock.
 *
 * Deliberately not corrected for server skew. The alpha has no skew margin at all —
 * expiry is judged against this clock as the server reported it (D23).
 */
class SpfnSystemClock : SpfnClock
{
    override fun nowMillis(): Long = System.currentTimeMillis()
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
