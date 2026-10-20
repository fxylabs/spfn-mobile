// SPFN Mobile — the Sign in with Apple half of a native enrollment, on Android.
//
// Same shape as the Google adapter and the iOS counterpart: a nonce goes in, an identity
// token comes out, and `SpfnKeyLifecycle.enroll(provider = "apple", …)` takes it from
// there exactly as on iOS. Two rules carry over unchanged from
// Sources/SPFNSocialApple/SPFNSocialApple.swift:
//
//   - The request carries the nonce's `requestValue`, which for an apple-minted nonce is
//     the SHA-256 of the key fingerprint, because Apple signs the hash of whatever it is
//     given. A nonce minted for another provider is refused rather than sent.
//   - Nothing here reads, stores or logs anything but the token (decision 1). A refusal
//     carries a fixed reason code and never provider text — Apple's `error_description`
//     and the relay link itself are dropped where they are read.
//
// What differs is where the token comes from. Apple ships no Android SDK, so the flow is
// Apple's web authorization in a Custom Tab, ending at the SPFN server's Return URL, and
// that server answers Apple's `form_post` by redirecting to a link the app receives. The
// web flow's own ending — a session in browser cookies — is one an Android app cannot
// read, which is why this module exists at all. The tab and the link are
// SpfnSocialAppleCustomTabDriver; this file is only what is true of any driver.

package xyz.superfunction.spfn.social.apple

import xyz.superfunction.spfn.client.SpfnSocialNonce
import kotlin.coroutines.cancellation.CancellationException

/**
 * Why an Apple sign-in did not produce a token. A closed tab is separate for the same
 * reason as on iOS: it is an outcome, not a failure — the app shows nothing and waits.
 */
sealed class SpfnSocialAppleException(message: String) : Exception(message)
{
    /** The user closed the tab, or Apple reported that they cancelled. */
    class Cancelled : SpfnSocialAppleException("the user dismissed the Apple sign-in")

    /** The flow completed but the relay carried no identity token. */
    class IdentityTokenMissing : SpfnSocialAppleException("the Apple relay carried no identity token")

    /**
     * Any other refusal, carrying a fixed reason: Apple's own error code when it is one
     * (`[a-z_]{1,64}`, such as `invalid_request`), or one of this module's dotted
     * constants, which that pattern cannot produce. Never Apple's description text.
     */
    class Failed(val reason: String) : SpfnSocialAppleException("the Apple sign-in failed with reason $reason")

    /**
     * The nonce was minted for a different provider. See the guard in
     * [SpfnSocialApple.idToken].
     */
    class NonceProviderMismatch(val provider: String) :
        SpfnSocialAppleException("this nonce was minted for '$provider', not for Apple")
}

/**
 * The authorization flow, behind a seam. [SpfnSocialAppleCustomTabDriver] is the real
 * one; a suite, or an app that runs the flow some other way, fills it in instead.
 */
fun interface SpfnSocialAppleDriver
{
    suspend fun identityToken(requestNonce: String): String?
}

/** The one thing this module offers an app: a nonce goes in, a provider token comes out. */
class SpfnSocialApple(private val driver: SpfnSocialAppleDriver)
{
    /** The token to answer `SpfnKeyLifecycle.enroll` with, from inside its closure. */
    suspend fun idToken(nonce: SpfnSocialNonce): String
    {
        // A nonce minted for another provider carries the raw fingerprint, and Apple
        // signs the hash of whatever it is given — so the token would come back bound to
        // a value the SPFN server never compares against.
        if (nonce.provider != PROVIDER)
        {
            throw SpfnSocialAppleException.NonceProviderMismatch(nonce.provider);
        }

        val token: String?;
        try
        {
            // `requestValue` is already the SHA-256 here: Apple is the one provider whose
            // request carries the hash rather than the fingerprint.
            token = driver.identityToken(nonce.requestValue);
        }
        catch (failure: Throwable)
        {
            throw classify(failure);
        }
        if (token.isNullOrEmpty())
        {
            throw SpfnSocialAppleException.IdentityTokenMissing();
        }
        return token;
    }

    companion object
    {
        /**
         * The provider name an enrollment for this adapter is started under, and the one
         * a nonce must have been minted for.
         */
        const val PROVIDER = SpfnSocialNonce.APPLE_PROVIDER

        /**
         * This module's own refusals pass through, and everything else loses its type
         * and its message: an exception from an app's URL seam or a browser is where a
         * URL with a token in it reaches a log.
         *
         * A `CancellationException` is returned untouched and never becomes a refusal
         * (P16). It is how a coroutine is told to stop, not how a sign-in fails, and
         * turning it into [SpfnSocialAppleException.Failed] would report a failure the
         * user never had and leave the caller's scope believing it was never cancelled.
         * A closed tab is unrelated to it and stays a [SpfnSocialAppleException.Cancelled].
         */
        internal fun classify(failure: Throwable): Throwable = when (failure)
        {
            is CancellationException -> failure
            is SpfnSocialAppleException -> failure
            else -> SpfnSocialAppleException.Failed(UNCLASSIFIED_REASON)
        }

        /** What a refusal this module could not name is reported as. */
        const val UNCLASSIFIED_REASON = "xyz.superfunction.spfn.SIGN_IN_FAILED"

        /** What an Apple `error` outside `[a-z_]{1,64}` is reported as; its text is dropped. */
        const val UNRECOGNIZED_REASON = "xyz.superfunction.spfn.UNRECOGNIZED_ERROR"

        /** A second sign-in started while one is still waiting for its relay. */
        const val SIGN_IN_IN_PROGRESS_REASON = "xyz.superfunction.spfn.SIGN_IN_IN_PROGRESS"

        /** No installed app can open the authorization URL. */
        const val NO_BROWSER_REASON = "xyz.superfunction.spfn.NO_BROWSER"
    }
}
