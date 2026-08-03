// SPFN Mobile — the raw ↔ DER conversion, pinned at its edge cases.
//
// The two ways this conversion goes wrong silently are a DER INTEGER that carries a
// 0x00 sign pad (33 bytes for a high-bit value) and one that dropped leading zero
// bytes (31 or fewer). Both are exercised with fixed bytes here, not left to the
// 1-in-2 / 1-in-256 chance a random signature exercises them.

package xyz.superfunction.spfn.auth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class SpfnEcdsaTest
{
    // ---- the two padding cases, as fixed bytes -----------------------------

    @Test
    fun aHighBitIntegerCarriesOneSignPadInDer()
    {
        // r starts 0x80 → DER needs a 0x00 pad (33-byte INTEGER); s stays 32 bytes.
        val raw = ByteArray(64);
        raw[0] = 0x80.toByte();
        raw[31] = 0x01;
        raw[32] = 0x7F;
        raw[63] = 0x02;

        val der = SpfnEcdsa.rawToDer(raw);
        assertEquals("SEQUENCE length", (2 + 33 + 2 + 32 + 2).toLong(), der.size.toLong());
        assertEquals("INTEGER r length", 33L, der[3].toLong());
        assertEquals("the sign pad", 0L, der[4].toLong());
        assertEquals("the padded high byte", 0x80L, der[5].toLong() and 0xFF);
        assertArrayEquals("the round trip restores the fixed 32-byte form", raw, SpfnEcdsa.derToRaw(der));
    }

    @Test
    fun leadingZeroBytesAreStrippedInDerAndRestoredInRaw()
    {
        // r has two leading zero bytes and a low third byte → a 30-byte DER INTEGER.
        val raw = ByteArray(64);
        raw[2] = 0x37;
        raw[31] = 0x05;
        raw[32] = 0x01;
        raw[63] = 0x09;

        val der = SpfnEcdsa.rawToDer(raw);
        assertEquals("INTEGER r length", 30L, der[3].toLong());
        assertArrayEquals("the round trip restores the stripped zeros", raw, SpfnEcdsa.derToRaw(der));
    }

    @Test
    fun aZeroIntegerIsOneZeroByteInDer()
    {
        val raw = ByteArray(64);
        raw[63] = 0x01;

        val der = SpfnEcdsa.rawToDer(raw);
        assertEquals("INTEGER r length", 1L, der[3].toLong());
        assertArrayEquals(raw, SpfnEcdsa.derToRaw(der));
    }

    // ---- malformed DER is refused, not repaired ----------------------------

    @Test
    fun malformedDerIsRefused()
    {
        val valid = SpfnEcdsa.rawToDer(ByteArray(64).also { it[0] = 0x7F; it[63] = 0x01 });

        val trailing = valid + byteArrayOf(0x00);
        assertRefused("trailing bytes", trailing);

        val notASequence = valid.copyOf().also { it[0] = 0x31 };
        assertRefused("wrong outer tag", notASequence);

        val truncated = valid.copyOf(valid.size - 1);
        assertRefused("length past the input", truncated);

        // A 0x00 pad in front of a low-bit byte is non-minimal.
        val nonMinimal = byteArrayOf(
            0x30, 0x08,
            0x02, 0x02, 0x00, 0x37,
            0x02, 0x02, 0x00, 0x42
        );
        assertRefused("non-minimal INTEGER", nonMinimal);

        // A negative INTEGER is not a P-256 scalar.
        val negative = byteArrayOf(
            0x30, 0x06,
            0x02, 0x01, 0x80.toByte(),
            0x02, 0x01, 0x01
        );
        assertRefused("negative INTEGER", negative);
    }

    @Test
    fun aRawSignatureMustBeExactly64Bytes()
    {
        assertRefused("63 bytes") { SpfnEcdsa.rawToDer(ByteArray(63)) };
        assertRefused("65 bytes") { SpfnEcdsa.rawToDer(ByteArray(65)) };
    }

    // ---- against the real JCA ----------------------------------------------

    /**
     * Round-trips real JCA signatures. 64 signatures make a missed sign pad a
     * one-in-2^64 silence rather than a coin flip: each of r and s has the high bit
     * set half the time.
     */
    @Test
    fun jcaSignaturesRoundTripThroughRawAndBack()
    {
        val generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(ECGenParameterSpec("secp256r1"));
        val pair = generator.generateKeyPair();
        var sawHighBit = false;

        for (index in 0 until 64)
        {
            val message = "spfn-ecdsa-round-trip-$index".toByteArray(Charsets.UTF_8);
            val signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(pair.private);
            signer.update(message);
            val der = signer.sign();

            val raw = SpfnEcdsa.derToRaw(der);
            assertEquals(64L, raw.size.toLong());
            sawHighBit = sawHighBit || raw[0].toInt() and 0x80 != 0 || raw[32].toInt() and 0x80 != 0;

            val verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(pair.public);
            verifier.update(message);
            assertTrue("round-tripped signature $index no longer verifies", verifier.verify(SpfnEcdsa.rawToDer(raw)));
        }

        assertTrue("64 signatures never exercised the sign-pad case", sawHighBit);
    }

    // ---- helpers -----------------------------------------------------------

    private fun assertRefused(name: String, der: ByteArray)
    {
        assertRefused(name) { SpfnEcdsa.derToRaw(der) };
    }

    private fun assertRefused(name: String, action: () -> Unit)
    {
        val refused = runCatching(action).exceptionOrNull();
        assertFalse("'$name' was accepted but must be refused", refused == null);
        assertTrue("'$name' failed with ${refused!!::class}", refused is IllegalArgumentException);
    }
}
