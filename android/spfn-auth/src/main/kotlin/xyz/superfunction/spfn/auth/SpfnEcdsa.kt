// SPFN Mobile — the raw ↔ DER seam between the contract and the JCA.
//
// The contract's wire form is raw r ‖ s: two 32-byte big-endian integers, 64 bytes.
// `java.security.Signature` produces and consumes DER `SEQUENCE { INTEGER r, INTEGER s }`
// instead, and the two differ in exactly the places that corrupt signatures silently:
// a DER INTEGER whose high bit is set carries a leading 0x00 (33 bytes for a 32-byte
// value), and one with leading zero bytes drops them (31 or fewer). Both directions
// here restore the fixed 32-byte form, and SpfnEcdsaTest pins both cases.
//
// Swift never comes through here: CryptoKit's `rawRepresentation` is already r ‖ s,
// so a conversion there would be a second encoding of an already-raw value.

package xyz.superfunction.spfn.auth

import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

object SpfnEcdsa
{
    /** A raw ECDSA P-256 signature: r ‖ s, two 32-byte big-endian integers. */
    const val RAW_SIGNATURE_BYTES: Int = 64

    private const val INTEGER_BYTES = 32
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_INTEGER = 0x02

    /**
     * The DER form the JCA verifies, from the contract's raw form.
     *
     * Each integer is minimally encoded: leading zero bytes are stripped, then one
     * 0x00 is restored if the remaining high bit is set, because a DER INTEGER is
     * signed and r and s never are.
     */
    fun rawToDer(raw: ByteArray): ByteArray
    {
        require(raw.size == RAW_SIGNATURE_BYTES) { "a raw signature is $RAW_SIGNATURE_BYTES bytes, got ${raw.size}" };

        val r = derInteger(raw, 0);
        val s = derInteger(raw, INTEGER_BYTES);
        val length = r.size + s.size;
        // Both integers are at most 33 bytes + 2 header bytes each, so the sequence
        // length always fits one byte and no long-form length is ever needed here.
        return byteArrayOf(TAG_SEQUENCE.toByte(), length.toByte()) + r + s;
    }

    /**
     * The contract's raw form, from the DER a JCA signer produced.
     *
     * Strict: exactly one SEQUENCE of exactly two INTEGERs, nothing before, between
     * or after, each integer minimal and at most 32 significant bytes. Anything else
     * is refused rather than repaired — a signer that emits it is broken, and a
     * repaired signature is a value nobody signed.
     */
    fun derToRaw(der: ByteArray): ByteArray
    {
        require(der.size >= 2 && der[0].toInt() and 0xFF == TAG_SEQUENCE) { "not a DER SEQUENCE" };
        val length = der[1].toInt() and 0xFF;
        require(length < 0x80) { "a P-256 signature never needs a long-form length" };
        require(length == der.size - 2) { "DER length does not match the input" };

        val raw = ByteArray(RAW_SIGNATURE_BYTES);
        val afterR = readInteger(der, 2, raw, 0);
        val afterS = readInteger(der, afterR, raw, INTEGER_BYTES);
        require(afterS == der.size) { "trailing bytes after the two INTEGERs" };
        return raw;
    }

    /** Parses a registered public key from the contract's representation, SPKI DER. */
    fun publicKeyFromSpki(spkiDer: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spkiDer))

    // ---- the two integer codecs -------------------------------------------

    private fun derInteger(raw: ByteArray, offset: Int): ByteArray
    {
        var start = offset;
        val end = offset + INTEGER_BYTES;
        while (start < end - 1 && raw[start].toInt() == 0)
        {
            start += 1;
        }

        val needsPad = raw[start].toInt() and 0x80 != 0;
        val bodySize = (end - start) + if (needsPad) 1 else 0;
        val out = ByteArray(2 + bodySize);
        out[0] = TAG_INTEGER.toByte();
        out[1] = bodySize.toByte();
        raw.copyInto(out, destinationOffset = 2 + if (needsPad) 1 else 0, startIndex = start, endIndex = end);
        return out;
    }

    private fun readInteger(der: ByteArray, offset: Int, into: ByteArray, at: Int): Int
    {
        require(offset + 2 <= der.size && der[offset].toInt() and 0xFF == TAG_INTEGER) { "expected a DER INTEGER" };
        val length = der[offset + 1].toInt() and 0xFF;
        require(length in 1..INTEGER_BYTES + 1) { "INTEGER length $length is not a P-256 scalar" };

        var start = offset + 2;
        var remaining = length;
        require(start + remaining <= der.size) { "INTEGER runs past the input" };
        require(der[start].toInt() and 0x80 == 0) { "a negative INTEGER is not a P-256 scalar" };

        if (remaining == INTEGER_BYTES + 1)
        {
            // The one legal 33-byte case: a 0x00 pad in front of a high-bit value.
            require(der[start].toInt() == 0 && der[start + 1].toInt() and 0x80 != 0) { "non-minimal INTEGER" };
            start += 1;
            remaining -= 1;
        }
        else if (remaining > 1)
        {
            // Minimality below 33 bytes: a 0x00 lead is legal only as a sign pad.
            require(!(der[start].toInt() == 0 && der[start + 1].toInt() and 0x80 == 0)) { "non-minimal INTEGER" };
        }

        der.copyInto(into, destinationOffset = at + INTEGER_BYTES - remaining, startIndex = start, endIndex = start + remaining);
        return start + remaining;
    }
}
