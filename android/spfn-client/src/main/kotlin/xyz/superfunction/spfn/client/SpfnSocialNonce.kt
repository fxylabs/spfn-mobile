// SPFN Mobile — the value that ties a provider sign-in to the key being enrolled.
//
// The nonce is not a random number. It is the fingerprint of the public key this
// enrollment registers: the SHA-256 of that key's SPKI DER bytes, in lowercase hex.
// The contract's `nativeEnrollment.nonceRule` requires the body's nonce and fingerprint
// to be the same value, and the server refuses the call when they differ.
//
// Why the server wants it that way: an id_token is bearer-shaped, so whoever holds one
// can present it. If the server verified only the token, anyone who stole one could
// enroll their own key on the victim's account. Deriving the nonce from the key means a
// stolen token carries the victim's fingerprint and cannot be paired with another key.
//
// One consequence runs through this whole file: the key must exist before the provider
// is asked for a token, which is why SpfnKeyLifecycle.enroll takes a closure and mints
// this value itself. An app cannot construct one.
//
// The provider decides the shape of the value that goes into the provider's own request:
//
//   apple            ->  requestValue is the SHA-256 of the fingerprint, in lowercase hex
//   everyone else    ->  requestValue is the fingerprint itself
//   the SPFN body    ->  always the fingerprint, never requestValue
//
// Apple is the exception because it follows the OIDC rule literally: it hashes the nonce
// in the request and puts that hash in the token it signs, so the value the SPFN server
// compares against is the pre-image. Every other provider SPFN supports natively —
// google, kakao, naver, github — echoes the raw value back.
//
// There is exactly one public value, and the SDK picked it knowing the provider. That is
// what let the opt-in annotation this file used to carry disappear: when an app cannot
// choose the wrong shape, nothing has to be kept out of its reach.
//
// The fingerprint is lowercase hex and deliberately not base64. Naver drops a trailing
// `A` from a nonce it echoes back (spfn-primitives issue #57); lowercase hex has no `A`
// in its alphabet, so the round trip is either identical or obviously broken.
//
// Sources/SPFNClient/SPFNSocialNonce.swift is the same value in Swift.

package xyz.superfunction.spfn.client

import xyz.superfunction.spfn.core.SpfnDigest

/**
 * Marks the factory that mints a nonce outside the enrollment flow.
 *
 * Minting one is `SpfnKeyLifecycle.enroll`'s job: it holds the key the fingerprint comes
 * from, so a nonce it did not mint belongs to no key this device holds and the server can
 * only refuse it. Kotlin's `internal` stops at the Gradle module, so the adapter modules —
 * which have to drive their own rules in their own suites — reach it through this instead.
 *
 * Swift needs no counterpart: `package` visibility already spans the whole package there.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "A nonce is minted by SpfnKeyLifecycle.enroll, which holds the key its fingerprint comes from."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class SpfnInternalNonceAccess

class SpfnSocialNonce internal constructor(
    /**
     * The key's fingerprint: what the SPFN enrollment body carries as both `nonce` and
     * `fingerprint`. Module-visible because the body is assembled in this module and
     * nowhere else; an app has no reason to read it.
     */
    internal val fingerprint: String,

    /** The provider this nonce was minted for. Lowercase, as the enrollment path requires. */
    val provider: String,
)
{
    /**
     * The value to put in the provider's own authorization request.
     *
     * Public because an app may drive a provider this SDK ships no adapter for — kakao
     * and naver are the ordinary cases — and it needs the value to hand that provider's
     * SDK. There is only this one, so there is nothing to get wrong.
     *
     * The hash is taken over the fingerprint's text, not over the bytes it spells.
     * Upstream's `hashNonce` hashes the nonce string it received, so hashing the decoded
     * bytes here would produce a value that verifies nowhere.
     */
    val requestValue: String =
        if (provider == APPLE_PROVIDER) SpfnDigest.sha256Hex(fingerprint) else fingerprint

    /**
     * The default rendering of a class prints its properties, which would put the
     * fingerprint in every log line that ever interpolates a nonce. It is not a secret —
     * it is the hash of a public key — but it names the device's key across every log it
     * lands in, so this is written out and names only the provider.
     */
    override fun toString(): String = "SpfnSocialNonce(provider=$provider)"

    companion object
    {
        /** The provider name Apple's flow uses, and the only one whose request is hashed. */
        const val APPLE_PROVIDER = "apple"

        /**
         * Mints a nonce from outside this module. Gated: see [SpfnInternalNonceAccess].
         */
        @SpfnInternalNonceAccess
        fun forProvider(fingerprint: String, provider: String): SpfnSocialNonce =
            SpfnSocialNonce(fingerprint = fingerprint, provider = provider)

        /**
         * Lowercase base16, written out rather than taken from a character
         * classification API. Kotlin's `Char.isLetterOrDigit` and Swift's
         * `Character.isHexDigit` disagree about full-width and Arabic-Indic digits, and
         * two guards that disagree about a character stop being each other's check (P9).
         */
        internal fun isLowercaseHex(text: String): Boolean =
            text.isNotEmpty() && text.all { it.code < 0x80 && (it in '0'..'9' || it in 'a'..'f') }

        /** The hex encoder both platforms are pinned to by a shared vector in the suites. */
        internal fun hex(bytes: ByteArray): String
        {
            val digits = "0123456789abcdef";
            val out = StringBuilder(bytes.size * 2);
            for (byte in bytes)
            {
                val value = byte.toInt() and 0xFF;
                out.append(digits[value ushr 4]);
                out.append(digits[value and 0x0F]);
            }
            return out.toString();
        }
    }
}
