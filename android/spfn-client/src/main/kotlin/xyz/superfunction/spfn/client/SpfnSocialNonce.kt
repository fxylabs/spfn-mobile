// SPFN Mobile — the one-time value that binds a provider sign-in to this enrollment.
//
// The same nonce is written into two places in two different shapes, and an app that
// gets the asymmetry wrong sees the server refuse the enrollment with nothing in any
// log saying why. So the shapes are not the app's to choose:
//
//   Apple's authorization request  ->  appleRequestValue, the SHA-256 of the raw value
//   every other provider's request ->  the raw value
//   this SDK's enrollment body     ->  the raw value
//
// The raw value is lowercase hex and deliberately NOT base64. A base64url value's last
// character carries fewer than 6 bits, and a provider that re-encodes the value can
// return a different last character than it was given — measured against Naver, which
// drops a trailing `A` (spfn-primitives issue #57). Hex has a fixed meaning per
// position, so a round trip through a provider is either identical or obviously broken.
//
// Sources/SPFNClient/SPFNSocialNonce.swift is the same value in Swift. The one place
// the two differ is how the raw value is kept out of an app's reach: Swift has package
// visibility, Kotlin does not, so here the accessor is public and gated twice — an
// opt-in an app has to write out before Kotlin will compile against it, and
// `@JvmSynthetic` so the getter is not in the class Java sees at all.

package xyz.superfunction.spfn.client

import xyz.superfunction.spfn.core.SpfnDigest
import java.security.SecureRandom

/**
 * Marks the raw nonce accessor. Reaching for it inside an app means putting the wrong
 * shape in a provider request, which the server answers with a refusal that names
 * nothing. The provider adapter modules opt in; nothing else has a reason to.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "The raw nonce belongs to the SDK: providers other than Apple receive it through their adapter."
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class SpfnInternalNonceAccess

class SpfnSocialNonce private constructor(private val raw: String)
{
    /**
     * What goes in Apple's authorization request: the SHA-256 of the raw value, in
     * lowercase hex.
     */
    val appleRequestValue: String = SpfnDigest.sha256Hex(raw)

    /**
     * The value the SPFN server compares against, and the value every provider other
     * than Apple puts in its own request.
     *
     * `@RequiresOptIn` is a Kotlin compiler rule and stops at the language boundary: a
     * Java caller sees a plain `getRawValue()` and no opt-in to write. `@JvmSynthetic`
     * removes the getter from the class's Java-visible surface, so the two languages
     * refuse the same reach.
     */
    @SpfnInternalNonceAccess
    @get:JvmSynthetic
    val rawValue: String
        get() = raw

    /**
     * The default rendering of a class prints its properties, which would put the raw
     * value in every log line that ever interpolates a nonce. Written out, and naming
     * only the public value.
     */
    override fun toString(): String = "SpfnSocialNonce(appleRequestValue=$appleRequestValue)"

    companion object
    {
        /** 32 bytes, rendered as 64 hex characters: a digest pre-image, not an id. */
        internal const val BYTE_COUNT = 32

        /**
         * A fresh nonce. Every call returns a different raw value; nothing here is
         * derived from device state, the clock or a counter.
         */
        fun make(): SpfnSocialNonce
        {
            val bytes = ByteArray(BYTE_COUNT);
            SecureRandom().nextBytes(bytes);
            return SpfnSocialNonce(hex(bytes));
        }

        /**
         * Module-visible so the conformance suites can pin the exact wire bytes a flow
         * produces against the fixtures, which name a fixed nonce. It accepts any string
         * because the fixture's nonce is contract data rather than something this SDK
         * minted, and rejecting it here would make the fixture unusable as evidence.
         */
        internal fun of(raw: String): SpfnSocialNonce = SpfnSocialNonce(raw)

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
