// SPFN Mobile — the Apple adapter on Android, case table rows A1–A7.
//
// SpfnSocialGoogleTest.kt is the shape and SPFNSocialAppleTests.swift the counterpart.
// Every row runs against a scripted driver: what is tested here is what the adapter does
// with whatever a driver answers, not the tab — that is SpfnSocialAppleCustomTabDriverTest.

package xyz.superfunction.spfn.social.apple

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
import xyz.superfunction.spfn.client.SpfnInternalNonceAccess
import xyz.superfunction.spfn.client.SpfnSocialNonce

@OptIn(SpfnInternalNonceAccess::class)
class SpfnSocialAppleTest
{
    /**
     * A1: a nonce minted for Google carries the raw fingerprint, and Apple would sign its
     * hash — so it is refused, naming the provider it was minted for, before the flow.
     */
    @Test
    fun a1_aGoogleNonceIsRefusedBeforeTheFlow()
    {
        val driver = RecordingAppleDriver(token = "apple-token-0001");
        val thrown = assertThrows(SpfnSocialAppleException.NonceProviderMismatch::class.java)
        {
            runBlocking { SpfnSocialApple(driver).idToken(SpfnSocialNonce.forProvider(FINGERPRINT, "google")) };
        }

        assertEquals("google", thrown.provider);
        assertNull("the platform flow must not have been reached", driver.requestedNonce);
    }

    /** A2: the driver is handed the nonce's request value — for Apple, the hash. */
    @Test
    fun a2_theDriverReceivesTheRequestValue() = runBlocking {
        val driver = RecordingAppleDriver(token = "apple-token-0001");
        val nonce = appleNonce();

        SpfnSocialApple(driver).idToken(nonce);

        assertEquals(nonce.requestValue, driver.requestedNonce);
        assertTrue("Apple's request carries the hash, never the fingerprint", driver.requestedNonce != FINGERPRINT);
    }

    /** A3: the token a driver answers with comes back untouched. */
    @Test
    fun a3_aCompletedSignInReturnsTheToken() = runBlocking {
        val adapter = SpfnSocialApple(RecordingAppleDriver(token = "apple-token-0001"));

        assertEquals("apple-token-0001", adapter.idToken(appleNonce()));
    }

    /** A4: no token, or an empty one, is a named refusal rather than a string to send on. */
    @Test
    fun a4_aSignInWithoutATokenFailsExplicitly()
    {
        for (token in listOf(null, ""))
        {
            val adapter = SpfnSocialApple(RecordingAppleDriver(token = token));
            assertThrows("token '$token'", SpfnSocialAppleException.IdentityTokenMissing::class.java)
            {
                runBlocking { adapter.idToken(appleNonce()) };
            }
        }
    }

    /**
     * A5: a cancelled coroutine is not a refused sign-in (P16). It passes through the
     * adapter unwrapped and unchanged, and a closed tab stays a separate outcome.
     */
    @Test
    fun a5_aCancelledCoroutineIsNotReportedAsASignInFailure()
    {
        val cancellation = CancellationException("the caller's scope was cancelled");
        assertSame(cancellation, SpfnSocialApple.classify(cancellation));

        val adapter = SpfnSocialApple(RecordingAppleDriver(failure = cancellation));
        val thrown = assertThrows(CancellationException::class.java)
        {
            runBlocking { adapter.idToken(appleNonce()) };
        }
        assertSame("the adapter must not wrap or replace it", cancellation, thrown);
    }

    /** A6: this module's own refusals pass through as they were raised. */
    @Test
    fun a6_theModulesOwnRefusalsPassThroughUnchanged()
    {
        val refusals = listOf(
            SpfnSocialAppleException.Cancelled(),
            SpfnSocialAppleException.IdentityTokenMissing(),
            SpfnSocialAppleException.Failed("invalid_request"),
            SpfnSocialAppleException.Failed(SpfnSocialApple.NO_BROWSER_REASON)
        );
        for (refusal in refusals)
        {
            val adapter = SpfnSocialApple(RecordingAppleDriver(failure = refusal));
            val thrown = assertThrows(SpfnSocialAppleException::class.java)
            {
                runBlocking { adapter.idToken(appleNonce()) };
            }
            assertSame(refusal, thrown);
        }
    }

    /**
     * A7: anything else loses its type and its text. An exception out of an app's URL seam
     * or a browser is where a link with a token in it would reach a log.
     */
    @Test
    fun a7_anythingElseIsUnclassifiedAndCarriesNoText()
    {
        val leaked = "https://app.example.com/_auth/oauth/apple/android?id_token=apple-token-leak-0001";
        val adapter = SpfnSocialApple(RecordingAppleDriver(failure = IllegalStateException(leaked)));

        val thrown = assertThrows(SpfnSocialAppleException.Failed::class.java)
        {
            runBlocking { adapter.idToken(appleNonce()) };
        }
        assertEquals(SpfnSocialApple.UNCLASSIFIED_REASON, thrown.reason);
        assertTrue(thrown.message?.contains("apple-token-leak-0001") != true);
        assertNull("the original must not ride along as a cause", thrown.cause);
    }

    /** A nonce minted the way an enrollment for this adapter mints one. */
    private fun appleNonce(): SpfnSocialNonce =
        SpfnSocialNonce.forProvider(FINGERPRINT, SpfnSocialApple.PROVIDER)

    private companion object
    {
        /** A fingerprint shaped as one taken over a real key: 64 lowercase hex characters. */
        const val FINGERPRINT = "aa919f16ced3a7bae097e8fde574681a9184cbc53ba1dd9ab43fa716774b690a"
    }
}

/**
 * The launched flow, scripted: it records the value it was asked to put in the request
 * and answers with the outcome the row under test needs.
 */
class RecordingAppleDriver(
    private val token: String? = null,
    private val failure: Throwable? = null
) : SpfnSocialAppleDriver
{
    var requestedNonce: String? = null
        private set

    override suspend fun identityToken(requestNonce: String): String?
    {
        this.requestedNonce = requestNonce;
        failure?.let { throw it };
        return token;
    }
}
