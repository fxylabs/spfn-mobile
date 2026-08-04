// SPFN Mobile — the Google Sign-In half of a native enrollment, on Android.
//
// Same shape as the Apple adapter and one deliberate difference: Google's request
// carries the RAW nonce, not its hash. Apple is the exception in this SDK, and the
// exception lives in one place — SpfnSocialNonce.appleRequestValue — so an adapter that
// reaches for the raw value is doing the ordinary thing rather than the risky one.
//
// The API is Credential Manager, not the one-tap sign-in surface in play-services-auth,
// which Google has deprecated. New code on a deprecated API buys nothing and schedules
// the same migration for a worse moment; `androidx.credentials` is where a request
// carrying a nonce lives now.
//
// Three things here are Google's rather than this SDK's, and they are why the artifacts
// are dependencies instead of a hand-rolled request: the request option that carries a
// nonce, the credential the flow returns, and the provider that serves it. What the SDK
// owns is which value goes in that option, and what a credential without a token means.
//
// Sources/SPFNSocialGoogle/SPFNSocialGoogle.swift is the counterpart. Nothing here reads
// or logs anything but the token (decision 1) — in particular a refusal keeps Credential
// Manager's type constant and drops its message, which is provider text.

package xyz.superfunction.spfn.social.google

import android.content.Context
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import xyz.superfunction.spfn.client.SpfnInternalNonceAccess
import xyz.superfunction.spfn.client.SpfnSocialNonce

/**
 * Why a Google sign-in did not produce a token. A dismissal is separate for the same
 * reason as on Apple: it is an outcome, not a failure. Credential Manager's own type
 * constant is kept; its message is not, because a provider message is where a token or
 * an account identifier reaches a log.
 */
sealed class SpfnSocialGoogleException(message: String) : Exception(message)
{
    /** The user dismissed the flow. */
    class Cancelled : SpfnSocialGoogleException("the user dismissed the Google sign-in")

    /** The flow completed but carried no identity token. */
    class IdentityTokenMissing : SpfnSocialGoogleException("the Google credential carried no identity token")

    /**
     * Any other refusal, carrying Credential Manager's type constant — a fixed
     * identifier such as `android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL`,
     * never the provider's own text.
     */
    class Failed(val type: String) : SpfnSocialGoogleException("the Google sign-in failed with type $type")
}

/**
 * The credential request, behind a seam. An app hands over whatever it uses to run
 * Credential Manager; this module decides what goes in and what comes out.
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
         * The Google request option Credential Manager is asked with. The nonce field
         * carries the raw value, and the server client id is the app's — this module
         * never holds one.
         *
         * Authorized accounts are not filtered: an enrollment is the first time this
         * install meets the user, so filtering to accounts already used with this app
         * would offer an empty list on the one flow that needs a full one.
         */
        @OptIn(SpfnInternalNonceAccess::class)
        fun googleIdOption(serverClientId: String, nonce: SpfnSocialNonce): GetGoogleIdOption =
            GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setNonce(nonce.rawValue)
                .setFilterByAuthorizedAccounts(false)
                .build()

        /** That option as the request Credential Manager takes. */
        fun signInRequest(serverClientId: String, nonce: SpfnSocialNonce): GetCredentialRequest =
            GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption(serverClientId, nonce))
                .build()

        /**
         * The token out of the credential Credential Manager returned, or a named
         * refusal. A credential of another type is not a token, and a credential
         * without one is not an empty string to send onward.
         */
        fun idToken(credential: Credential): String
        {
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            {
                throw SpfnSocialGoogleException.IdentityTokenMissing();
            }
            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken;
            if (token.isEmpty())
            {
                throw SpfnSocialGoogleException.IdentityTokenMissing();
            }
            return token;
        }

        /**
         * Credential Manager reports a dismissal as its own exception type; every other
         * refusal keeps the type constant and loses the message.
         */
        internal fun classify(failure: Throwable): Throwable = when (failure)
        {
            is SpfnSocialGoogleException -> failure
            is GetCredentialCancellationException -> SpfnSocialGoogleException.Cancelled()
            is GetCredentialException -> SpfnSocialGoogleException.Failed(failure.type)
            else -> SpfnSocialGoogleException.Failed(UNCLASSIFIED_TYPE)
        }

        /** What a refusal that names no Credential Manager type is reported as. */
        internal const val UNCLASSIFIED_TYPE = "xyz.superfunction.spfn.SIGN_IN_FAILED"
    }
}

/**
 * The flow itself: one Credential Manager call, one credential, one token. The context
 * is the caller's activity — Credential Manager presents from it — and nothing but the
 * token string comes back out.
 */
class SpfnSocialGoogleCredentialDriver(
    private val context: Context,
    private val serverClientId: String
) : SpfnSocialGoogleDriver
{
    override suspend fun identityToken(requestNonce: String): String?
    {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetGoogleIdOption.Builder()
                    .setServerClientId(serverClientId)
                    .setNonce(requestNonce)
                    .setFilterByAuthorizedAccounts(false)
                    .build()
            )
            .build();
        val response = CredentialManager.create(context).getCredential(context, request);
        return SpfnSocialGoogle.idToken(response.credential);
    }
}
