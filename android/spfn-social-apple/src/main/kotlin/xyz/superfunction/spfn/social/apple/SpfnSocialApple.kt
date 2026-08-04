// SPFN Mobile — the Sign in with Apple half of a native enrollment, on Android.
//
// Sources/SPFNSocialApple/SPFNSocialApple.swift is the counterpart, and the two differ
// in exactly one way: iOS has a platform flow to drive and Android has none, because
// Apple ships no native SDK here. So the driver seam that exists on iOS for testability
// exists on Android as the actual entry point — the app runs the flow it already has,
// and this module owns the parts that are the SDK's on both platforms:
//
//   - The request's nonce field carries appleRequestValue, never the raw value. Apple
//     follows the OIDC rule: the request nonce is hashed with SHA-256 and the id_token
//     it signs carries that hash, so the SPFN server — which sees the raw value in the
//     enrollment body — can only match if the request carried the hash.
//   - A flow that completes without a token is a named refusal, not an empty string
//     sent onward as if it were one.
//   - A dismissal stays distinguishable from every other refusal.
//
// Nothing here reads or logs anything but the token (decision 1).

package xyz.superfunction.spfn.social.apple

import xyz.superfunction.spfn.client.SpfnSocialNonce

/**
 * Why an Apple sign-in did not produce a token.
 *
 * A dismissal is its own case because it is the one outcome that is not a failure: the
 * app shows nothing and waits. [Failed] carries the platform's numeric code and never
 * its message text — a provider's message is the fastest way for a token or an account
 * identifier to end up in a log.
 */
sealed class SpfnSocialAppleException(message: String) : Exception(message)
{
    /** The user dismissed the flow. Not an error to report, only one to stop on. */
    class Cancelled : SpfnSocialAppleException("the user dismissed the Apple sign-in")

    /** The flow completed but carried no identity token. */
    class IdentityTokenMissing : SpfnSocialAppleException("the Apple authorization carried no identity token")

    /** Any other refusal, carrying the flow's own numeric code. */
    class Failed(val code: Int) : SpfnSocialAppleException("the Apple sign-in failed with code $code")
}

/**
 * The flow itself. On Android the app supplies it, because the platform does not.
 * `requestNonce` is what the authorization request's nonce field must carry.
 */
fun interface SpfnSocialAppleDriver
{
    suspend fun identityToken(requestNonce: String): String?
}

/** The one thing this module offers an app: a nonce goes in, a provider token comes out. */
class SpfnSocialApple(private val driver: SpfnSocialAppleDriver)
{
    /**
     * The token to hand `SpfnKeyLifecycle.enroll` together with the same nonce.
     *
     * The nonce is passed as the value type rather than as a String precisely so this
     * call cannot put the wrong shape in the request: it reads [appleRequestValue] and
     * has no way to reach the raw value at all.
     */
    suspend fun idToken(nonce: SpfnSocialNonce): String
    {
        val token: String?;
        try
        {
            token = driver.identityToken(nonce.appleRequestValue);
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
         * A dismissal the app's flow reports stays a dismissal; every other refusal
         * keeps a number and loses its text. A dismissal is recognised by type rather
         * than by a numeric code, because the code would be the platform flow's and
         * Android has no platform flow here to issue one.
         */
        internal fun classify(failure: Throwable): Throwable = when (failure)
        {
            is SpfnSocialAppleException -> failure
            else -> SpfnSocialAppleException.Failed(CODE_UNCLASSIFIED)
        }

        /** What a refusal that names no code of its own is reported as. */
        internal const val CODE_UNCLASSIFIED = -1
    }
}

/**
 * The nonce shape an Apple authorization request must carry, for an app that builds the
 * request itself rather than handing this module a driver. It is the same value
 * [SpfnSocialApple.idToken] passes, exposed so the two can never drift apart.
 */
fun appleRequestNonce(nonce: SpfnSocialNonce): String = nonce.appleRequestValue
