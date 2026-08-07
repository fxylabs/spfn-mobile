package xyz.superfunction.spfn.harness

import android.content.Intent
import xyz.superfunction.spfn.client.SpfnSocialNonce

/**
 * What the harness reads from its launch.
 *
 * A Maestro flow starts the app with `launchApp: arguments:`, which arrive on Android as
 * intent extras. Nothing here is typed into a field: a flow that had to type a base URL
 * would be a flow about typing.
 *
 * The id token is a launch argument for the same reason the sign-in is a lambda in the
 * SDK. Maestro drives the app under test, and the Google sign-in sheet is system UI
 * outside it, so an automated flow substitutes a canned token and a device run leaves the
 * argument out and takes the real sheet (decision 01kzb8tjxp, D-4).
 *
 * tools/harness/ios/Sources/HarnessConfiguration.swift is the same reading in Swift.
 */
class HarnessConfiguration(
    /** Where the SDK sends. No default: an app that guessed would report a refusal from somewhere nobody named. */
    val baseUrl: String,

    /** The provider id `enroll` rides in. */
    val provider: String,

    /**
     * A real provider token, used verbatim. Only a device run against a real server has
     * one, because a real server verifies it against the provider's own keys.
     */
    val cannedIdToken: String?,

    /**
     * The user id the reference server's test token names. A fixed token cannot serve that
     * server: it checks that the token's nonce is the fingerprint of the key being
     * enrolled, and the fingerprint is not known until the key exists. So the harness is
     * given the user id and composes the token around whatever nonce the SDK hands its
     * sign-in lambda — which is the shape the lambda exists for.
     */
    val testUser: String?
)
{
    /**
     * The token the sign-in lambda returns, or null when this launch supplied neither a
     * real token nor a test user. A verbatim token wins: a run that supplied one meant to
     * use it.
     */
    fun idToken(nonce: SpfnSocialNonce): String?
    {
        if (cannedIdToken != null)
        {
            return cannedIdToken;
        }
        val user = testUser ?: return null;
        return "spfn-test-idtoken.$provider.$user.${nonce.requestValue}";
    }

    companion object
    {
        fun fromLaunch(intent: Intent?): HarnessConfiguration = HarnessConfiguration(
            baseUrl = intent?.getStringExtra("SPFN_HARNESS_BASE_URL").orEmpty(),
            provider = nonEmpty(intent?.getStringExtra("SPFN_HARNESS_PROVIDER")) ?: "google",
            cannedIdToken = nonEmpty(intent?.getStringExtra("SPFN_HARNESS_ID_TOKEN")),
            testUser = nonEmpty(intent?.getStringExtra("SPFN_HARNESS_TEST_USER"))
        );

        /**
         * An extra passed as an empty string is an absent extra. Maestro writes one when a
         * flow leaves a variable unset, and an empty token would otherwise reach `enroll`
         * and be refused as `idTokenMissing` — a refusal about the harness rather than
         * about the SDK.
         */
        private fun nonEmpty(value: String?): String? = if (value.isNullOrEmpty()) null else value;
    }
}
