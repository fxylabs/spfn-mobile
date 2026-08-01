// SPFN Mobile — digests and message authentication.
//
// Counterpart of Sources/SPFNCore/SPFNDigest.swift, which uses CryptoKit. Both sides
// use the platform primitive; neither hand-rolls one.

package xyz.superfunction.spfn.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SpfnDigest
{
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    /**
     * The digest of an empty body, as the proof input spells it out: 64 zeroes.
     * Written as a literal rather than computed so a body-less operation can never
     * accidentally carry the digest of the empty string instead.
     */
    const val ABSENT_BODY_DIGEST: String = "0000000000000000000000000000000000000000000000000000000000000000"

    /** Lowercase base16 SHA-256. */
    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Lowercase base16 SHA-256 of a UTF-8 string. */
    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /** Lowercase base16 HMAC-SHA-256. */
    fun hmacSha256Hex(key: ByteArray, message: ByteArray): String
    {
        val mac = Mac.getInstance("HmacSHA256");
        mac.init(SecretKeySpec(key, "HmacSHA256"));
        return hex(mac.doFinal(message));
    }

    /**
     * Constant-time comparison of two hex digests.
     *
     * Proof verification compares values derived from a secret, so the comparison must
     * not leak where two digests first differ.
     */
    fun constantTimeEquals(lhs: String, rhs: String): Boolean
    {
        val left = lhs.toByteArray(Charsets.UTF_8);
        val right = rhs.toByteArray(Charsets.UTF_8);
        if (left.size != right.size)
        {
            return false;
        }
        var difference = 0;
        for (index in left.indices)
        {
            difference = difference or (left[index].toInt() xor right[index].toInt());
        }
        return difference == 0;
    }

    private fun hex(bytes: ByteArray): String
    {
        val out = StringBuilder(bytes.size * 2);
        for (byte in bytes)
        {
            val value = byte.toInt() and 0xFF;
            out.append(HEX_DIGITS[value shr 4]);
            out.append(HEX_DIGITS[value and 0x0F]);
        }
        return out.toString();
    }
}
