// SPFN Mobile — the nonce value type, case table cells 13–16 in Kotlin.
//
// SPFNSocialNonceTests.swift is the counterpart and asserts the same cells with the same
// expected values. The values come from the design's table, written by hand rather than
// read out of either implementation (P10), and the guard vector at the bottom is what
// pins the two platforms' character classification to each other (P9). The byte-to-hex
// vector lives in SpfnCoreTest, beside the encoder it asserts against.

package xyz.superfunction.spfn.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnDigest
import java.io.File

class SpfnSocialNonceTest
{
    /** Cell 13: apple's request value is the SHA-256 of the fingerprint, in hex. */
    @Test
    fun c13_appleRequestValueIsTheSha256HexOfTheFingerprint()
    {
        val nonce = SpfnSocialNonce(fingerprint = FINGERPRINT, provider = "apple");
        assertEquals(SpfnDigest.sha256Hex(FINGERPRINT), nonce.requestValue);
        assertNotEquals(
            "apple's request carries the hash, not the pre-image",
            FINGERPRINT,
            nonce.requestValue
        );
    }

    /**
     * Cell 14: every other provider's request value is the fingerprint itself.
     *
     * kakao and naver are listed because they are the providers an app reaches through
     * its own SDK — this SDK ships no adapter for them, and the whole reason
     * `requestValue` is public is that such an app needs the value.
     */
    @Test
    fun c14_everyOtherProviderCarriesTheFingerprintItself()
    {
        for (provider in listOf("google", "kakao", "naver", "github"))
        {
            val nonce = SpfnSocialNonce(fingerprint = FINGERPRINT, provider = provider);
            assertEquals("$provider must carry the raw fingerprint", FINGERPRINT, nonce.requestValue);
            assertEquals(provider, nonce.provider);
        }
    }

    /**
     * Cell 15: whatever the provider, the value the enrollment body carries is the
     * fingerprint. Asserted on the type rather than only through a flow, because this is
     * the equality the server checks and the one an app can never see fail.
     */
    @Test
    fun c15_theBodyValueIsAlwaysTheFingerprint()
    {
        for (provider in listOf("apple", "google", "kakao", "naver"))
        {
            assertEquals(FINGERPRINT, SpfnSocialNonce(fingerprint = FINGERPRINT, provider = provider).fingerprint);
        }
    }

    /**
     * Cell 16: 64 characters, lowercase hex, whichever shape the provider gets — and no
     * base64 alphabet in either. `+`, `/` and `=` would not survive a URL round trip, and
     * a trailing `A` is the character Naver drops from a nonce it echoes back
     * (spfn-primitives #57), which lowercase hex cannot produce.
     */
    @Test
    fun c16_bothShapesAre64LowercaseHexCharacters()
    {
        for (provider in listOf("apple", "google", "kakao", "naver"))
        {
            val value = SpfnSocialNonce(fingerprint = FINGERPRINT, provider = provider).requestValue;
            assertEquals("$provider: expected 64 characters", 64, value.length);
            assertTrue("$provider: '$value' is not lowercase hex", SpfnSocialNonce.isLowercaseHex(value));
            assertFalse(value.contains("+"));
            assertFalse(value.contains("/"));
            assertFalse(value.contains("="));
            assertFalse(value.endsWith("A"));
        }
    }

    /** One instance read twice answers the same thing both times. */
    @Test
    fun readingOneInstanceTwiceGivesTheSameValues()
    {
        val nonce = SpfnSocialNonce(fingerprint = FINGERPRINT, provider = "apple");
        assertEquals(nonce.requestValue, nonce.requestValue);
        assertEquals(nonce.fingerprint, nonce.fingerprint);
        assertEquals(nonce.toString(), nonce.toString());
    }

    /**
     * Nothing mints a nonce from outside this module, and the rendering names only the
     * provider.
     *
     * Kotlin has no package visibility, so what stands in for it is `internal` on the
     * constructor and on the fingerprint. The declaration is read as text for the same
     * reason the Swift row is: what this prevents is a `public` member someone adds
     * later — a second value to choose between, or a way for an app to enrol a nonce
     * belonging to no key it holds — and reflection cannot see the difference.
     */
    @Test
    fun nothingOutsideThisModuleMintsANonceAndTheRenderingLeaksNothing()
    {
        val source = File(repoRoot(), NONCE_SOURCE).readText();
        val declarations = source.lines().map { it.trim() };

        assertTrue(
            "the constructor lost `internal`; an app can now mint a nonce for a key it does not hold",
            declarations.contains("class SpfnSocialNonce internal constructor(")
        );
        assertTrue(
            "the fingerprint lost `internal`; the body value is not an app's to read",
            declarations.contains("internal val fingerprint: String,")
        );
        assertTrue(
            "requestValue must stay public — an app driving kakao or naver needs it",
            declarations.contains("val requestValue: String =")
        );

        val nonce = SpfnSocialNonce(fingerprint = FINGERPRINT, provider = "apple");
        assertFalse("toString leaks the fingerprint", nonce.toString().contains(nonce.fingerprint));
        assertTrue(nonce.toString().contains("apple"));
    }

    /**
     * `internal` is a Kotlin rule, and a Java caller does not read Kotlin rules. What
     * carries it across the boundary is name mangling: the compiler renames an internal
     * member's JVM method, so `getFingerprint` does not exist for javac to resolve.
     *
     * Swift needs no counterpart — `package` visibility already removes the name outside
     * the package, and there is no second language reading the same class.
     */
    @Test
    fun theFingerprintIsNotReachableFromJavaByName()
    {
        try
        {
            SpfnSocialNonce::class.java.getDeclaredMethod("getFingerprint");
            fail("getFingerprint resolved by name; a Java caller reaches the body value directly");
        }
        catch (_: NoSuchMethodException)
        {
            // The name was mangled, which is the whole point.
        }

        assertFalse(
            "the provider-shaped value stays plainly callable from Java",
            SpfnSocialNonce::class.java.getDeclaredMethod("getRequestValue").isSynthetic
        );
    }

    /**
     * P9: the lowercase-hex guard refuses the non-ASCII digits a Unicode-aware
     * classification would accept. Kotlin's `Char.isLetterOrDigit` and Swift's
     * `Character.isHexDigit` disagree about Arabic-Indic and full-width digits, so the
     * refused list is the shared vector and SPFNSocialNonceTests.swift carries it too.
     *
     * The byte-to-hex vector that used to sit here moved to SpfnCoreTest: it now asserts
     * against [xyz.superfunction.spfn.core.SpfnDigest.hex], the encoder a fingerprint
     * actually goes through, rather than against the copy this type used to carry.
     */
    @Test
    fun p9_theAsciiGuardMatchesTheSharedVector()
    {
        assertTrue(SpfnSocialNonce.isLowercaseHex("0123456789abcdef"));
        // The NUL is an escape rather than the character itself. Written literally it
        // renders as a space, which is how this list came to carry "00 " twice and lose
        // the NUL case while the Swift suite kept it: the two sides stopped being each
        // other's check and nothing looked wrong.
        for (refused in listOf("", "ABCDEF", "0x1f", "g", "00 ", "٠١٢", "０１２", "ｆ", "00\u0000"))
        {
            assertFalse("'$refused' was accepted as lowercase hex", SpfnSocialNonce.isLowercaseHex(refused));
        }
    }

    /**
     * The Swift suite refuses the same characters, and a vector only pins the two
     * platforms to each other if both files really carry it. Read here rather than
     * trusted — including the NUL entry this list once lost.
     */
    @Test
    fun p9_theSwiftSuiteCarriesTheSameGuardVector()
    {
        val swift = File(repoRoot(), "Tests/SPFNClientTests/SPFNSocialNonceTests.swift").readText();
        for (expected in listOf("\"ABCDEF\"", "\"0x1f\"", "\"٠١٢\"", "\"０１２\"", "\"ｆ\"", "\\u{0000}"))
        {
            assertTrue("the Swift suite lost the guard vector entry $expected", swift.contains(expected));
        }
    }

    private fun repoRoot(): File = File(System.getProperty("spfn.repoRoot") ?: ".")

    private companion object
    {
        /**
         * A fingerprint shaped exactly as one taken over a real SPKI DER: 64 lowercase
         * hex characters. Fixed so every row reads the same input, and the same value the
         * enrollment fixture carries.
         */
        const val FINGERPRINT = "aa919f16ced3a7bae097e8fde574681a9184cbc53ba1dd9ab43fa716774b690a"

        const val NONCE_SOURCE =
            "android/spfn-client/src/main/kotlin/xyz/superfunction/spfn/client/SpfnSocialNonce.kt"
    }
}
