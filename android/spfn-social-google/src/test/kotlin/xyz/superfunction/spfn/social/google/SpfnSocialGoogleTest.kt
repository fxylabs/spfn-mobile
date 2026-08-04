// SPFN Mobile — the Google adapter, case table rows C5–C7 in Kotlin.
//
// SPFNSocialGoogleTests.swift is the counterpart. The row that matters most is C7:
// Google's request carries the raw value where Apple's carries the hash, and the
// request built here is Google's own type, so the assertion is made against the value
// the launcher would really send.

package xyz.superfunction.spfn.social.google

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.client.SpfnInternalNonceAccess
import xyz.superfunction.spfn.client.SpfnSocialNonce

@OptIn(SpfnInternalNonceAccess::class)
class SpfnSocialGoogleTest
{
    /** C5: the flow completes — the token comes back untouched. */
    @Test
    fun c5_aCompletedSignInReturnsTheToken() = runBlocking {
        val adapter = SpfnSocialGoogle(RecordingGoogleDriver(token = "google-token-0001"));

        assertEquals("google-token-0001", adapter.idToken(SpfnSocialNonce.make()));
    }

    /**
     * C6: a dismissal stays a dismissal, and every other refusal keeps its code. The
     * dismissal is raised the way the platform raises it — Google's own exception with
     * its own status code — so the classification is judged against the real thing.
     */
    @Test
    fun c6_aDismissalIsACancellation()
    {
        val cancelled = ApiException(Status(CommonStatusCodes.CANCELED));
        val dismissed = SpfnSocialGoogle(RecordingGoogleDriver(failure = cancelled));
        assertThrows(SpfnSocialGoogleException.Cancelled::class.java)
        {
            runBlocking { dismissed.idToken(SpfnSocialNonce.make()) };
        }

        val refused = ApiException(Status(CommonStatusCodes.DEVELOPER_ERROR));
        val failed = SpfnSocialGoogle(RecordingGoogleDriver(failure = refused));
        val thrown = assertThrows(SpfnSocialGoogleException.Failed::class.java)
        {
            runBlocking { failed.idToken(SpfnSocialNonce.make()) };
        }
        assertEquals(CommonStatusCodes.DEVELOPER_ERROR, thrown.code);

        val unknown = SpfnSocialGoogle(RecordingGoogleDriver(failure = IllegalStateException("flow broke")));
        val internal = assertThrows(SpfnSocialGoogleException.Failed::class.java)
        {
            runBlocking { unknown.idToken(SpfnSocialNonce.make()) };
        }
        assertEquals(CommonStatusCodes.INTERNAL_ERROR, internal.code);
    }

    /** C7: the request's nonce field carries the RAW value, never the Apple hash. */
    @Test
    fun c7_theRequestNonceIsTheRawValueNotTheHash() = runBlocking {
        val driver = RecordingGoogleDriver(token = "google-token-0001");
        val nonce = SpfnSocialNonce.make();

        SpfnSocialGoogle(driver).idToken(nonce);

        assertEquals(nonce.rawValue, driver.requestedNonce);
        assertNotEquals(nonce.appleRequestValue, driver.requestedNonce);

        // The same value in the request Google's launcher would really be handed.
        val request = SpfnSocialGoogle.signInRequest("server-client-id-0001", nonce);
        assertEquals(nonce.rawValue, request.nonce);
        assertEquals("server-client-id-0001", request.serverClientId);
    }

    /** A sign-in that answers with no token fails explicitly (the C3 rule, other half). */
    @Test
    fun aSignInWithoutATokenFailsExplicitly() = runBlocking {
        for (token in listOf(null, ""))
        {
            val adapter = SpfnSocialGoogle(RecordingGoogleDriver(token = token));
            assertThrows(SpfnSocialGoogleException.IdentityTokenMissing::class.java)
            {
                runBlocking { adapter.idToken(SpfnSocialNonce.make()) };
            }
        }
    }

    /** C8, Kotlin half: no refusal this module raises carries the token. */
    @Test
    fun c8_noRefusalCarriesTheToken() = runBlocking {
        val leaked = "google-token-leak-0001";
        val adapter = SpfnSocialGoogle(RecordingGoogleDriver(failure = IllegalStateException(leaked)));

        val thrown = assertThrows(SpfnSocialGoogleException.Failed::class.java)
        {
            runBlocking { adapter.idToken(SpfnSocialNonce.make()) };
        }
        assertTrue(thrown.message?.contains(leaked) != true);
        assertTrue(thrown.cause == null);
    }
}

/**
 * The launched flow, scripted: it records the value it was asked to put in the request
 * and answers with the outcome the row under test needs.
 */
class RecordingGoogleDriver(
    private val token: String? = null,
    private val failure: Throwable? = null
) : SpfnSocialGoogleDriver
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
