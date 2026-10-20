// SPFN Mobile — the Google Sign-In half of a native enrollment, on Android.
//
// Same shape as the Apple adapter and one deliberate difference: Google's request
// carries the key fingerprint itself, not its hash. Apple is the exception in this SDK,
// and the exception lives in one place — `SpfnSocialNonce.requestValue`, which the nonce
// computed from the provider it was minted for. So both adapters read the same member and
// neither picks a shape; what an adapter does check is that the nonce it was handed was
// minted for its own provider.
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

import android.app.Activity
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import xyz.superfunction.spfn.client.SpfnSocialNonce
import kotlin.coroutines.cancellation.CancellationException

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

    /**
     * The nonce was minted for a different provider. See the guard in
     * [SpfnSocialGoogle.idToken].
     */
    class NonceProviderMismatch(val provider: String) :
        SpfnSocialGoogleException("this nonce was minted for '$provider', not for Google")
}

/**
 * Which of Credential Manager's two Google requests a sign-in is asked with. The app
 * chooses; this module never moves from one to the other on its own, because only the app
 * knows whether the user asked to sign in or is being offered it.
 *
 * Google's guidance is to offer both: the bottom sheet as the offer, a "Sign in with
 * Google" button as the persistent way in. Google names the cases the bottom sheet does not
 * cover, and those are when an app asks with [BUTTON_FLOW]:
 * - there is no Google account on the device — the bottom sheet does not appear and the
 *   request ends in `NoCredentialException`, while the button flow lets the user add one;
 * - the accounts on the device need re-authentication — the bottom sheet leaves them out;
 * - the user dismissed the bottom sheet, or turned off "Sign-in prompts" in their Google
 *   Account settings, which suppresses the bottom sheet for every account on the device.
 *
 * Both answer with the same credential type and carry the nonce the same way, so nothing
 * after the request depends on the style.
 */
enum class SpfnSocialGoogleRequestStyle
{
    /** `GetGoogleIdOption`: the account list sliding up from the bottom. The default. */
    BOTTOM_SHEET,

    /** `GetSignInWithGoogleOption`: Google's own sign-in screen, behind the app's button. */
    BUTTON_FLOW
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
    /** The token to answer `SpfnKeyLifecycle.enroll` with, from inside its closure. */
    suspend fun idToken(nonce: SpfnSocialNonce): String
    {
        // Google's flow reads the raw fingerprint, and an apple-minted nonce carries a
        // hash instead — the token would come back bound to a value the SPFN server never
        // compares against, and its refusal is a code the app cannot read.
        if (nonce.provider != PROVIDER)
        {
            throw SpfnSocialGoogleException.NonceProviderMismatch(nonce.provider);
        }

        val token: String?;
        try
        {
            // `requestValue` is the raw fingerprint here: Apple is the only provider
            // whose request carries a hash instead.
            token = driver.identityToken(nonce.requestValue);
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
         * The provider name an enrollment for this adapter is started under, and the one
         * a nonce must have been minted for.
         */
        const val PROVIDER = "google"

        /**
         * The bottom sheet's request option, which Credential Manager is asked with when
         * the app names no style. The nonce field carries the fingerprint, and the server
         * client id is the app's — this module never holds one.
         *
         * Authorized accounts are not filtered: an enrollment is the first time this
         * install meets the user, so filtering to accounts already used with this app
         * would offer an empty list on the one flow that needs a full one.
         *
         * This is the only place in the module a bottom-sheet request is built. An app that
         * drives Credential Manager itself calls it, and so does
         * [SpfnSocialGoogleCredentialDriver] — the two used to build the same request
         * twice, which is two requests as soon as somebody edits one.
         */
        fun googleIdOption(serverClientId: String, requestNonce: String): GetGoogleIdOption =
            GetGoogleIdOption.Builder()
                .setServerClientId(serverClientId)
                .setNonce(requestNonce)
                .setFilterByAuthorizedAccounts(false)
                .build()

        /**
         * The same option from the nonce itself, which is what an app holds. `requestValue`
         * is the raw fingerprint here: Apple is the only provider whose request carries a
         * hash instead.
         */
        fun googleIdOption(serverClientId: String, nonce: SpfnSocialNonce): GetGoogleIdOption =
            googleIdOption(serverClientId, nonce.requestValue)

        /**
         * The button flow's request option. The nonce field carries the fingerprint and the
         * server client id is the app's, as in [googleIdOption]. There is no account
         * filter to turn off: Google's builder for this flow has none.
         *
         * This is the only place in the module a button-flow request is built, for the
         * reason [googleIdOption] gives.
         */
        fun signInWithGoogleOption(serverClientId: String, requestNonce: String): GetSignInWithGoogleOption =
            GetSignInWithGoogleOption.Builder(serverClientId)
                .setNonce(requestNonce)
                .build()

        /** The same option from the nonce itself. */
        fun signInWithGoogleOption(serverClientId: String, nonce: SpfnSocialNonce): GetSignInWithGoogleOption =
            signInWithGoogleOption(serverClientId, nonce.requestValue)

        /**
         * The option for [style] as the request Credential Manager takes. Without a style it
         * is the bottom sheet, the request this function built before there was a choice.
         */
        @JvmOverloads
        fun signInRequest(
            serverClientId: String,
            requestNonce: String,
            style: SpfnSocialGoogleRequestStyle = SpfnSocialGoogleRequestStyle.BOTTOM_SHEET
        ): GetCredentialRequest =
            GetCredentialRequest.Builder()
                .addCredentialOption(credentialOption(serverClientId, requestNonce, style))
                .build()

        /** The same request from the nonce itself. */
        @JvmOverloads
        fun signInRequest(
            serverClientId: String,
            nonce: SpfnSocialNonce,
            style: SpfnSocialGoogleRequestStyle = SpfnSocialGoogleRequestStyle.BOTTOM_SHEET
        ): GetCredentialRequest =
            signInRequest(serverClientId, nonce.requestValue, style)

        /** Which of the two builders a style is asked with; neither is built anywhere else. */
        private fun credentialOption(
            serverClientId: String,
            requestNonce: String,
            style: SpfnSocialGoogleRequestStyle
        ): CredentialOption = when (style)
        {
            SpfnSocialGoogleRequestStyle.BOTTOM_SHEET -> googleIdOption(serverClientId, requestNonce)
            SpfnSocialGoogleRequestStyle.BUTTON_FLOW -> signInWithGoogleOption(serverClientId, requestNonce)
        }

        /**
         * The token out of the credential Credential Manager returned, or a named
         * refusal. A credential of another type is not a token, and a credential
         * without one is not an empty string to send onward.
         *
         * Both request styles answer with the same type: `GetSignInWithGoogleOption` asks
         * Credential Manager for `TYPE_GOOGLE_ID_TOKEN_CREDENTIAL`, as `GetGoogleIdOption`
         * does. Its `TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL` is a subtype the request carries
         * to the provider, not the type of the credential that comes back.
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
         *
         * A `CancellationException` is returned untouched and never becomes a refusal.
         * It is how a coroutine is told to stop, not how a sign-in fails, and turning it
         * into [SpfnSocialGoogleException.Failed] would report a failure the user never
         * had and leave the caller's scope believing it was never cancelled. Credential
         * Manager's own dismissal type is unrelated to it and stays a [Cancelled].
         */
        internal fun classify(failure: Throwable): Throwable = when (failure)
        {
            is CancellationException -> failure
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
 * The flow itself: one Credential Manager call, one credential, one token. Nothing but
 * the token string comes back out.
 *
 * The parameter is an [Activity] rather than a `Context` because `getCredential` puts an
 * account picker on the screen, and Android's contract asks for an activity to present
 * it from. A `Context` parameter compiles against an application or service context and
 * then fails at the one moment the user is watching, which is the same shape of mistake
 * `SpfnSocialNonce` exists to prevent — so it is refused by the type here too.
 *
 * [style] is the app's choice between the bottom sheet and the button flow, and the
 * bottom sheet when the app names none. A driver asks with the one style it was built
 * with; an app that wants the button flow after a `NoCredentialException` builds a driver
 * for it when the user taps its button, rather than this driver retrying on its own.
 */
class SpfnSocialGoogleCredentialDriver @JvmOverloads constructor(
    private val activity: Activity,
    private val serverClientId: String,
    private val style: SpfnSocialGoogleRequestStyle = SpfnSocialGoogleRequestStyle.BOTTOM_SHEET
) : SpfnSocialGoogleDriver
{
    override suspend fun identityToken(requestNonce: String): String?
    {
        val response = CredentialManager.create(activity).getCredential(activity, request(requestNonce));
        return SpfnSocialGoogle.idToken(response.credential);
    }

    /** The request this driver hands Credential Manager, apart from the call so it can be read. */
    internal fun request(requestNonce: String): GetCredentialRequest =
        SpfnSocialGoogle.signInRequest(serverClientId, requestNonce, style)
}
