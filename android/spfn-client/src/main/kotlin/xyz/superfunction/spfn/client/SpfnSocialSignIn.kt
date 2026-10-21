// SPFN Mobile — what a provider sign-in hands back to `enroll`: the token, and the name.
//
// Apple sends a person's name once, on the first authorization, beside the id_token rather
// than inside it, and never again. Contract 0.13.5 declares `OauthNativeRequest.profile`
// so that name can reach the server, which keeps it as the display name when the social
// account is first attached. A sign-in closure that returns only a token has nowhere to
// put it; this value is that place.
//
// The name is one string the app already holds. The SDK does not compose one from a
// given and a family name, because the order of the two depends on the locale and the
// app knows the locale.
//
// What the SDK does with it is one decision, made here: a name the server's own trim
// would empty is not sent at all, so the body is byte for byte what the token-only form
// sends. Anything else is sent exactly as given. The contract declares no length for
// it, so none is enforced; a refusal is the server's and is classified like any other.
//
// The name is personal data. `toString` names neither it nor the token, which is why
// this is a plain class and not a data class, and nothing in the lifecycle stores it —
// it lives in the request body and nowhere else.
//
// Sources/SPFNClient/SPFNSocialSignIn.swift is the same value in Swift.

package xyz.superfunction.spfn.client

import xyz.superfunction.spfn.generated.SpfnOauthNativeProfile

class SpfnSocialSignIn(
    /** The provider's token, as the token-only form's closure returns it. */
    val idToken: String,

    /** The person's name as the app will show it, when the provider reported one. */
    val displayName: String? = null,
)
{
    /** The body's `profile`: absent unless the name survives the server's trim. */
    internal fun profile(): SpfnOauthNativeProfile?
    {
        val name = displayName;
        if (name == null || isBlank(name))
        {
            return null;
        }
        return SpfnOauthNativeProfile(name = name);
    }

    /** Names neither the token nor the person, so no log line that prints this can. */
    override fun toString(): String = "SpfnSocialSignIn"

    companion object
    {
        /**
         * Blank as the server judges it: nothing left once ECMAScript's WhiteSpace and
         * LineTerminator are trimmed from both ends. Written out rather than taken from
         * `isBlank()`, whose `Char.isWhitespace` drops U+001C..U+001F, which the server
         * keeps, and which the Swift counterpart could not match (P9).
         */
        internal fun isBlank(text: String): Boolean = text.all { it.code in TRIMMED_BY_THE_SERVER }

        private val TRIMMED_BY_THE_SERVER = setOf(
            0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x00A0, 0x1680,
            0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
            0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF,
        );
    }
}
