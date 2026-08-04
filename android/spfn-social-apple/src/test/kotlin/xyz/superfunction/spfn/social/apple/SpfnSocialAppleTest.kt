// SPFN Mobile — the Apple adapter, case table rows C1–C4 in Kotlin.
//
// SPFNSocialAppleTests.swift is the counterpart and asserts the same rows. The platform
// flow is driven through the same seam on both sides, so what is under test is the
// adapter's own rules rather than a provider's UI.

package xyz.superfunction.spfn.social.apple

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.client.SpfnInternalNonceAccess
import xyz.superfunction.spfn.client.SpfnSocialNonce

@OptIn(SpfnInternalNonceAccess::class)
class SpfnSocialAppleTest
{
    /** C1: the flow completes — the token comes back untouched. */
    @Test
    fun c1_aCompletedAuthorizationReturnsTheToken() = runBlocking {
        val adapter = SpfnSocialApple(RecordingAppleDriver(token = "apple-token-0001"));

        assertEquals("apple-token-0001", adapter.idToken(SpfnSocialNonce.make()));
    }

    /** C2: a dismissal stays a dismissal, and every other refusal stays distinguishable. */
    @Test
    fun c2_aDismissalIsACancellationDistinctFromAFailure() = runBlocking {
        val dismissed = SpfnSocialApple(
            RecordingAppleDriver(failure = SpfnSocialAppleException.Cancelled())
        );
        assertThrows(SpfnSocialAppleException.Cancelled::class.java)
        {
            runBlocking { dismissed.idToken(SpfnSocialNonce.make()) };
        }

        val failed = SpfnSocialApple(RecordingAppleDriver(failure = IllegalStateException("flow broke")));
        val thrown = assertThrows(SpfnSocialAppleException.Failed::class.java)
        {
            runBlocking { failed.idToken(SpfnSocialNonce.make()) };
        }
        assertEquals(SpfnSocialApple.CODE_UNCLASSIFIED, thrown.code);
    }

    /** C3: an authorization without an identity token fails explicitly. */
    @Test
    fun c3_anAuthorizationWithoutAnIdentityTokenFailsExplicitly() = runBlocking {
        for (token in listOf(null, ""))
        {
            val adapter = SpfnSocialApple(RecordingAppleDriver(token = token));
            assertThrows(SpfnSocialAppleException.IdentityTokenMissing::class.java)
            {
                runBlocking { adapter.idToken(SpfnSocialNonce.make()) };
            }
        }
    }

    /** C4: the request's nonce field carries the hash, never the raw value. */
    @Test
    fun c4_theRequestNonceIsTheAppleRequestValueNotTheRawValue() = runBlocking {
        val driver = RecordingAppleDriver(token = "apple-token-0001");
        val nonce = SpfnSocialNonce.make();

        SpfnSocialApple(driver).idToken(nonce);

        assertEquals(nonce.appleRequestValue, driver.requestedNonce);
        assertNotEquals(nonce.rawValue, driver.requestedNonce);
        // The same value an app building the request itself is given.
        assertEquals(driver.requestedNonce, appleRequestNonce(nonce));
    }

    /** C8, Kotlin half: no refusal this module raises carries the token. */
    @Test
    fun c8_noRefusalCarriesTheToken() = runBlocking {
        val leaked = "apple-token-leak-0001";
        val adapter = SpfnSocialApple(RecordingAppleDriver(failure = IllegalStateException(leaked)));

        val thrown = assertThrows(SpfnSocialAppleException.Failed::class.java)
        {
            runBlocking { adapter.idToken(SpfnSocialNonce.make()) };
        }
        assertTrue(thrown.message?.contains(leaked) != true);
        assertTrue(thrown.cause == null);
    }
}

/**
 * The flow, scripted: it records the value it was asked to put in the request and
 * answers with the outcome the row under test needs.
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
