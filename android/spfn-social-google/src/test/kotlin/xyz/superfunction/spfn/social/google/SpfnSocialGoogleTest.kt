// SPFN Mobile — the Google adapter, case table rows C5–C7 in Kotlin.
//
// SPFNSocialGoogleTests.swift is the counterpart. The row that matters most is C7:
// Google's request carries the raw value where Apple's carries the hash, and the
// request built here is Google's own type, so the assertion is made against the value
// the launcher would really send.

package xyz.superfunction.spfn.social.google

import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
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
     * C6: a dismissal stays a dismissal, and every other refusal stays distinguishable.
     * Both are raised the way Credential Manager raises them — its own exception types —
     * so the classification is judged against the real thing rather than a stand-in.
     */
    @Test
    fun c6_aDismissalIsACancellation()
    {
        val dismissed = SpfnSocialGoogle(
            RecordingGoogleDriver(failure = GetCredentialCancellationException("dismissed"))
        );
        assertThrows(SpfnSocialGoogleException.Cancelled::class.java)
        {
            runBlocking { dismissed.idToken(SpfnSocialNonce.make()) };
        }

        val refusal = NoCredentialException("no credential");
        val failed = SpfnSocialGoogle(RecordingGoogleDriver(failure = refusal));
        val thrown = assertThrows(SpfnSocialGoogleException.Failed::class.java)
        {
            runBlocking { failed.idToken(SpfnSocialNonce.make()) };
        }
        assertEquals(refusal.type, thrown.type);

        val unknown = SpfnSocialGoogle(RecordingGoogleDriver(failure = IllegalStateException("flow broke")));
        val unclassified = assertThrows(SpfnSocialGoogleException.Failed::class.java)
        {
            runBlocking { unknown.idToken(SpfnSocialNonce.make()) };
        }
        assertEquals(SpfnSocialGoogle.UNCLASSIFIED_TYPE, unclassified.type);
    }

    /**
     * A cancelled coroutine is not a refused sign-in. Kotlin cancels a suspended call by
     * throwing `CancellationException` through it, so an adapter that catches everything
     * and renames it tells the caller's scope the sign-in failed while the scope believes
     * it was never cancelled. The exception passes through unchanged and unwrapped.
     *
     * Credential Manager's own dismissal type is checked alongside it, because the two
     * are unrelated types that both read as "cancelled" in English and only one of them
     * is a user-visible outcome.
     */
    @Test
    fun aCancelledCoroutineIsNotReportedAsASignInFailure()
    {
        val cancellation = CancellationException("the caller's scope was cancelled");
        assertSame(
            "classify must return a CancellationException untouched",
            cancellation,
            SpfnSocialGoogle.classify(cancellation)
        );

        val adapter = SpfnSocialGoogle(RecordingGoogleDriver(failure = cancellation));
        val thrown = assertThrows(CancellationException::class.java)
        {
            runBlocking { adapter.idToken(SpfnSocialNonce.make()) };
        }
        assertSame("the adapter must not wrap or replace it", cancellation, thrown);

        // The dismissal stays what it was: an outcome, not a cancellation.
        assertTrue(
            SpfnSocialGoogle.classify(GetCredentialCancellationException("dismissed"))
                is SpfnSocialGoogleException.Cancelled
        );
    }

    /**
     * `getCredential` puts an account picker on the screen, and Android asks for an
     * activity to present it from. A `Context` parameter compiles against an application
     * context and fails only when a user is watching, so the type is the check.
     */
    @Test
    fun theCredentialDriverTakesAnActivityRatherThanAnyContext()
    {
        val presenting = SpfnSocialGoogleCredentialDriver::class.java
            .constructors
            .single()
            .parameterTypes
            .first();

        assertEquals(android.app.Activity::class.java, presenting);
    }

    /** C7: the request's nonce field carries the RAW value, never the Apple hash. */
    @Test
    fun c7_theRequestNonceIsTheRawValueNotTheHash() = runBlocking {
        val driver = RecordingGoogleDriver(token = "google-token-0001");
        val nonce = SpfnSocialNonce.make();

        SpfnSocialGoogle(driver).idToken(nonce);

        assertEquals(nonce.rawValue, driver.requestedNonce);
        assertNotEquals(nonce.appleRequestValue, driver.requestedNonce);

        // The same value in the request Credential Manager would really be handed. The
        // option is Google's own type, built the way the driver builds it, and the
        // nonce is read back off it rather than off anything this suite constructed.
        val option = SpfnSocialGoogle.googleIdOption("server-client-id-0001", nonce);
        assertEquals(nonce.rawValue, option.nonce);
        assertEquals("server-client-id-0001", option.serverClientId);
        assertFalse(
            "an enrollment is the first time this install meets the user",
            option.filterByAuthorizedAccounts
        );

        val request = SpfnSocialGoogle.signInRequest("server-client-id-0001", nonce);
        val carried = request.credentialOptions.filterIsInstance<GetGoogleIdOption>().single();
        assertEquals(nonce.rawValue, carried.nonce);
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
