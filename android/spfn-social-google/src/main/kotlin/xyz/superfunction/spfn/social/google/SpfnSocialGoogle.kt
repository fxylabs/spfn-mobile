// SPFN Mobile — the Google Sign-In half of a native enrollment, on Android.
//
// Same shape as the Apple adapter and one deliberate difference: Google's request
// carries the RAW nonce, not its hash. Apple is the exception in this SDK, and the
// exception lives in one place — SpfnSocialNonce.appleRequestValue — so an adapter that
// reaches for the raw value is doing the ordinary thing rather than the risky one.
//
// Two things here are Google's rather than this SDK's, and both are why the artifact is
// a dependency instead of a hand-rolled request: the sign-in request type that carries a
// nonce at all, and the credential the launched flow returns. What the SDK owns is which
// value goes in that request, and what a credential without a token means.
//
// Sources/SPFNSocialGoogle/SPFNSocialGoogle.swift is the counterpart. Nothing here reads
// or logs anything but the token (decision 1).

package xyz.superfunction.spfn.social.google

import com.google.android.gms.auth.api.identity.GetSignInIntentRequest
import com.google.android.gms.auth.api.identity.SignInCredential
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import xyz.superfunction.spfn.client.SpfnInternalNonceAccess
import xyz.superfunction.spfn.client.SpfnSocialNonce

/**
 * Why a Google sign-in did not produce a token. A dismissal is separate for the same
 * reason as on Apple: it is an outcome, not a failure. Google's numeric status code is
 * kept; its message text is not.
 */
sealed class SpfnSocialGoogleException(message: String) : Exception(message)
{
    /** The user dismissed the flow. */
    class Cancelled : SpfnSocialGoogleException("the user dismissed the Google sign-in")

    /** The flow completed but carried no identity token. */
    class IdentityTokenMissing : SpfnSocialGoogleException("the Google credential carried no identity token")

    /** Any other refusal, carrying Google's own status code. */
    class Failed(val code: Int) : SpfnSocialGoogleException("the Google sign-in failed with code $code")
}

/**
 * The launched flow, behind a seam. An app hands over whatever it uses to launch an
 * intent and read its result; this module decides what goes in and what comes out.
 */
fun interface SpfnSocialGoogleDriver
{
    suspend fun identityToken(requestNonce: String): String?
}

/** The one thing this module offers an app: a nonce goes in, a provider token comes out. */
class SpfnSocialGoogle(private val driver: SpfnSocialGoogleDriver)
{
    /** The token to hand `SpfnKeyLifecycle.enroll` together with the same nonce. */
    @OptIn(SpfnInternalNonceAccess::class)
    suspend fun idToken(nonce: SpfnSocialNonce): String
    {
        val token: String?;
        try
        {
            // The raw value, not the hash: Apple is the only provider that hashes.
            token = driver.identityToken(nonce.rawValue);
        }
        catch (failure: Throwable)
        {
            throw classify(failure);
        }
        if (token.isNullOrEmpty())
        {
            throw SpfnSocialGoogleException.IdentityTokenMissing();
        }
        return token;
    }

    companion object
    {
        /**
         * The request Google's launcher signs in with. The nonce field carries the raw
         * value, and the server client id is the app's — this module never holds one.
         *
         * Google has deprecated this request type in favour of Credential Manager. The
         * suppression is deliberate and narrow: `play-services-auth` is the artifact the
         * approved design names, and moving to another one is a decision to take rather
         * than a warning to silence by rewriting the module.
         */
        @Suppress("DEPRECATION")
        @OptIn(SpfnInternalNonceAccess::class)
        fun signInRequest(serverClientId: String, nonce: SpfnSocialNonce): GetSignInIntentRequest =
            GetSignInIntentRequest.builder()
                .setServerClientId(serverClientId)
                .setNonce(nonce.rawValue)
                .build()

        /**
         * The token out of the credential the launched flow returned, or a named
         * refusal. A credential without one is not an empty string to send onward.
         */
        @Suppress("DEPRECATION")
        fun idToken(credential: SignInCredential): String
        {
            val token = credential.googleIdToken;
            if (token.isNullOrEmpty())
            {
                throw SpfnSocialGoogleException.IdentityTokenMissing();
            }
            return token;
        }

        /**
         * Google reports a dismissal as one status code; every other refusal keeps its
         * code and loses its text.
         */
        internal fun classify(failure: Throwable): Throwable = when
        {
            failure is SpfnSocialGoogleException -> failure
            failure is ApiException && failure.statusCode == CommonStatusCodes.CANCELED ->
                SpfnSocialGoogleException.Cancelled()
            failure is ApiException -> SpfnSocialGoogleException.Failed(failure.statusCode)
            else -> SpfnSocialGoogleException.Failed(CommonStatusCodes.INTERNAL_ERROR)
        }
    }
}
