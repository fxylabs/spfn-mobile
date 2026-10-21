// SPFN Mobile — the Google adapter, case table rows C5–C7 in Kotlin.
//
// SPFNSocialGoogleTests.swift is the counterpart. The row that matters most is C7:
// Google's request carries the raw value where Apple's carries the hash, and the
// request built here is Google's own type, so the assertion is made against the value
// the launcher would really send.
//
// Rows G1–G8 are the request style, which is Android's alone: GoogleSignIn-iOS has no
// bottom sheet, so they have no Swift counterpart.

package xyz.superfunction.spfn.social.google

import android.app.Activity
import android.os.Bundle
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

        assertEquals("google-token-0001", adapter.idToken(googleNonce()));
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
            runBlocking { dismissed.idToken(googleNonce()) };
        }

        val refusal = NoCredentialException("no credential");
        val failed = SpfnSocialGoogle(RecordingGoogleDriver(failure = refusal));
        val thrown = assertThrows(SpfnSocialGoogleException.Failed::class.java)
        {
            runBlocking { failed.idToken(googleNonce()) };
        }
        assertEquals(refusal.type, thrown.type);

        val unknown = SpfnSocialGoogle(RecordingGoogleDriver(failure = IllegalStateException("flow broke")));
        val unclassified = assertThrows(SpfnSocialGoogleException.Failed::class.java)
        {
            runBlocking { unknown.idToken(googleNonce()) };
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
            runBlocking { adapter.idToken(googleNonce()) };
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
        // Every constructor, because the style parameter's default adds overloads and each
        // of them is a way to build the driver.
        val presenting = SpfnSocialGoogleCredentialDriver::class.java
            .constructors
            .map { it.parameterTypes.first() }
            .toSet();

        assertEquals(setOf(android.app.Activity::class.java), presenting);
    }

    /**
     * C7 / cell 14: the request's nonce field carries the fingerprint itself, never the
     * hash that only Apple's flow expects.
     */
    @Test
    fun c7_theRequestNonceIsTheFingerprintNotTheHash() = runBlocking {
        val driver = RecordingGoogleDriver(token = "google-token-0001");
        val nonce = googleNonce();

        SpfnSocialGoogle(driver).idToken(nonce);

        assertEquals(FINGERPRINT, driver.requestedNonce);
        assertEquals(nonce.requestValue, driver.requestedNonce);
        assertNotEquals(
            SpfnSocialNonce.forProvider(FINGERPRINT, "apple").requestValue,
            driver.requestedNonce
        );

        // The same value in the request Credential Manager would really be handed. The
        // option is Google's own type, built by the one builder the driver itself calls,
        // and the nonce is read back off it rather than off anything this suite built.
        val option = SpfnSocialGoogle.googleIdOption("server-client-id-0001", nonce);
        assertEquals(FINGERPRINT, option.nonce);
        assertEquals("server-client-id-0001", option.serverClientId);
        assertFalse(
            "an enrollment is the first time this install meets the user",
            option.filterByAuthorizedAccounts
        );

        val request = SpfnSocialGoogle.signInRequest("server-client-id-0001", nonce);
        val carried = request.credentialOptions.filterIsInstance<GetGoogleIdOption>().single();
        assertEquals(FINGERPRINT, carried.nonce);

        // The driver holds a raw request nonce rather than the nonce object, and reaches
        // the same builder through the String overload. The two must agree, or "one
        // builder" is two builders with a shared name.
        val fromDriverSideValue = SpfnSocialGoogle.signInRequest("server-client-id-0001", nonce.requestValue)
            .credentialOptions
            .filterIsInstance<GetGoogleIdOption>()
            .single();
        assertEquals(carried.nonce, fromDriverSideValue.nonce);
        assertEquals(carried.serverClientId, fromDriverSideValue.serverClientId);
        assertEquals(carried.filterByAuthorizedAccounts, fromDriverSideValue.filterByAuthorizedAccounts);
    }

    /**
     * Cell 18: a nonce minted for another provider is refused, and the flow never runs.
     * An apple-minted nonce carries a hash, so Google would echo a value the SPFN server
     * never compares against.
     */
    @Test
    fun c18_aNonceMintedForAnotherProviderIsRefusedBeforeTheFlow() = runBlocking {
        for (provider in listOf("apple", "kakao", "naver"))
        {
            val driver = RecordingGoogleDriver(token = "google-token-0001");
            val thrown = failureOf {
                SpfnSocialGoogle(driver).idToken(SpfnSocialNonce.forProvider(FINGERPRINT, provider));
            };

            assertTrue(
                "'$provider' was accepted: $thrown",
                thrown is SpfnSocialGoogleException.NonceProviderMismatch
            );
            assertNull("'$provider': the platform flow must not have been reached", driver.requestedNonce);
        }
    }

    /** A sign-in that answers with no token fails explicitly (the C3 rule, other half). */
    @Test
    fun aSignInWithoutATokenFailsExplicitly() = runBlocking {
        for (token in listOf(null, ""))
        {
            val adapter = SpfnSocialGoogle(RecordingGoogleDriver(token = token));
            assertThrows(SpfnSocialGoogleException.IdentityTokenMissing::class.java)
            {
                runBlocking { adapter.idToken(googleNonce()) };
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
            runBlocking { adapter.idToken(googleNonce()) };
        }
        assertTrue(thrown.message?.contains(leaked) != true);
        assertTrue(thrown.cause == null);
    }

    /**
     * G1: a request that names no style is the bottom sheet request it was before there
     * was a choice — one `GetGoogleIdOption`, nothing beside it.
     */
    @Test
    fun g1_aRequestWithoutAStyleIsTheBottomSheet()
    {
        val nonce = googleNonce();

        assertBottomSheet(nonce, SpfnSocialGoogle.signInRequest(SERVER_CLIENT_ID, nonce));
        assertBottomSheet(nonce, SpfnSocialGoogle.signInRequest(SERVER_CLIENT_ID, nonce.requestValue));
    }

    /** G2: the button style asks with one `GetSignInWithGoogleOption`, nothing beside it. */
    @Test
    fun g2_theButtonStyleAsksWithSignInWithGoogleOption()
    {
        val nonce = googleNonce();

        assertButtonFlow(
            nonce,
            SpfnSocialGoogle.signInRequest(SERVER_CLIENT_ID, nonce, SpfnSocialGoogleRequestStyle.BUTTON_FLOW)
        );
    }

    /**
     * G3: the driver holds a raw request value rather than the nonce object, so the String
     * overload must build the same button request, or "one builder" is two.
     */
    @Test
    fun g3_theRawRequestValueOverloadBuildsTheSameButtonRequest()
    {
        val nonce = googleNonce();

        assertButtonFlow(
            nonce,
            SpfnSocialGoogle.signInRequest(SERVER_CLIENT_ID, nonce.requestValue, SpfnSocialGoogleRequestStyle.BUTTON_FLOW)
        );
    }

    /** G4: a driver built the way every app built one before there was a choice. */
    @Test
    fun g4_aDriverWithoutAStyleSendsTheBottomSheetRequest()
    {
        val nonce = googleNonce();
        val driver = SpfnSocialGoogleCredentialDriver(Activity(), SERVER_CLIENT_ID);

        assertBottomSheet(nonce, driver.request(nonce.requestValue));
    }

    /** G5: a driver built for the button flow asks with it. */
    @Test
    fun g5_aDriverWithTheButtonStyleSendsTheButtonRequest()
    {
        val nonce = googleNonce();
        val driver = SpfnSocialGoogleCredentialDriver(
            Activity(),
            SERVER_CLIENT_ID,
            SpfnSocialGoogleRequestStyle.BUTTON_FLOW
        );

        assertButtonFlow(nonce, driver.request(nonce.requestValue));
    }

    /**
     * G6: the button flow answers with the credential type `idToken(credential)` already
     * accepts. The type is read off Google's own option — it is what Credential Manager is
     * asked for — and a credential of that type gets past the type check into Google's
     * reader. On a host JVM the Bundle is a stub with no token in it, so the reader's own
     * parse failure is the proof it was reached rather than refused as another type.
     */
    @Test
    fun g6_theButtonFlowCredentialIsReadByTheSameTokenReader()
    {
        val option = SpfnSocialGoogle.signInWithGoogleOption(SERVER_CLIENT_ID, googleNonce());
        assertEquals(GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, option.type);

        assertThrows(GoogleIdTokenParsingException::class.java)
        {
            SpfnSocialGoogle.idToken(CustomCredential(option.type, Bundle()));
        };
    }

    /** G7: a refusal is classified the same way whichever style the request was asked with. */
    @Test
    fun g7_aRefusalIsClassifiedTheSameUnderEitherStyle()
    {
        for (style in SpfnSocialGoogleRequestStyle.entries)
        {
            assertThrows("$style", SpfnSocialGoogleException.Cancelled::class.java)
            {
                runBlocking { refusedUnder(style, GetCredentialCancellationException("dismissed")) };
            };

            val refusal = NoCredentialException("no credential");
            val failed = assertThrows("$style", SpfnSocialGoogleException.Failed::class.java)
            {
                runBlocking { refusedUnder(style, refusal) };
            };
            assertEquals("$style", refusal.type, failed.type);

            val cancellation = CancellationException("the caller's scope was cancelled");
            val thrown = assertThrows("$style", CancellationException::class.java)
            {
                runBlocking { refusedUnder(style, cancellation) };
            };
            assertSame("$style", cancellation, thrown);
        }
    }

    /** G8: the google-nonce check comes before the request, whichever style it would be. */
    @Test
    fun g8_aNonceMintedForAnotherProviderIsRefusedUnderEitherStyle() = runBlocking {
        for (style in SpfnSocialGoogleRequestStyle.entries)
        {
            val driver = StyledGoogleDriver(style, token = "google-token-0001");
            val thrown = failureOf {
                SpfnSocialGoogle(driver).idToken(SpfnSocialNonce.forProvider(FINGERPRINT, "apple"));
            };

            assertTrue("$style: $thrown", thrown is SpfnSocialGoogleException.NonceProviderMismatch);
            assertNull("$style: the request must not have been built", driver.request);
        }
    }

    /** The adapter run against a driver that builds [style]'s request and then fails. */
    private suspend fun refusedUnder(style: SpfnSocialGoogleRequestStyle, failure: Throwable)
    {
        val driver = StyledGoogleDriver(style, failure = failure);
        try
        {
            SpfnSocialGoogle(driver).idToken(googleNonce());
        }
        finally
        {
            assertTrue("$style: the request was never built", driver.request != null);
        }
    }

    /** The bottom sheet request as G1 states it: the C7 values, on the only option. */
    private fun assertBottomSheet(nonce: SpfnSocialNonce, request: GetCredentialRequest)
    {
        val option = request.credentialOptions.single() as GetGoogleIdOption;
        assertEquals(nonce.requestValue, option.nonce);
        assertEquals(SERVER_CLIENT_ID, option.serverClientId);
        assertFalse(option.filterByAuthorizedAccounts);
    }

    /** The button request as G2 states it: the request value and the app's id, on the only option. */
    private fun assertButtonFlow(nonce: SpfnSocialNonce, request: GetCredentialRequest)
    {
        val option = request.credentialOptions.single() as GetSignInWithGoogleOption;
        assertEquals(nonce.requestValue, option.nonce);
        assertEquals(FINGERPRINT, option.nonce);
        assertEquals(SERVER_CLIENT_ID, option.serverClientId);
    }

    /** A nonce minted the way an enrollment for this adapter mints one. */
    private fun googleNonce(): SpfnSocialNonce =
        SpfnSocialNonce.forProvider(FINGERPRINT, SpfnSocialGoogle.PROVIDER)

    private fun failureOf(body: suspend () -> Unit): Throwable?
    {
        return try
        {
            runBlocking { body() };
            null;
        }
        catch (failure: Throwable)
        {
            failure;
        }
    }

    private companion object
    {
        /**
         * A fingerprint shaped as one taken over a real key: 64 lowercase hex
         * characters. Fixed, because what these rows test is the adapter's handling of
         * it, not its value.
         */
        const val FINGERPRINT = "aa919f16ced3a7bae097e8fde574681a9184cbc53ba1dd9ab43fa716774b690a"

        /** The app's server client id; this module never holds one of its own. */
        const val SERVER_CLIENT_ID = "server-client-id-0001"
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

/**
 * The launched flow for one request style: it builds the request that style really sends,
 * keeps it, and answers with the outcome the row under test needs.
 */
class StyledGoogleDriver(
    private val style: SpfnSocialGoogleRequestStyle,
    private val token: String? = null,
    private val failure: Throwable? = null
) : SpfnSocialGoogleDriver
{
    var request: GetCredentialRequest? = null
        private set

    override suspend fun identityToken(requestNonce: String): String?
    {
        this.request = SpfnSocialGoogle.signInRequest("server-client-id-0001", requestNonce, style);
        failure?.let { throw it };
        return token;
    }
}
