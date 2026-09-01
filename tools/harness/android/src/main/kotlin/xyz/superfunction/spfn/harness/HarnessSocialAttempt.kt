package xyz.superfunction.spfn.harness

import android.app.Activity
import java.security.KeyStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.superfunction.spfn.client.SpfnKeyLifecycle
import xyz.superfunction.spfn.client.SpfnSocialNonce
import xyz.superfunction.spfn.core.SpfnVersion
import xyz.superfunction.spfn.generated.SpfnGeneratedContract
import xyz.superfunction.spfn.social.google.SpfnSocialGoogle
import xyz.superfunction.spfn.social.google.SpfnSocialGoogleCredentialDriver
import xyz.superfunction.spfn.social.google.SpfnSocialGoogleException

/**
 * One device sign-in attempt, from the tap to the receipt.
 *
 * The provider logic is not here and must not be: the sheet, the nonce and the credential
 * belong to `spfn-social-google`, and this class hands that adapter an Activity and a
 * client id and answers `SpfnKeyLifecycle.enroll` with whatever comes back. A harness that
 * built its own Credential Manager request would be proving its own request works.
 *
 * What IS here is the part only a harness has: which case the person declared, whether a
 * Keystore entry outlived a failure, and what the wire said.
 */
class HarnessSocialAttempt(
    private val lifecycle: SpfnKeyLifecycle,
    private val transport: HarnessTransport,
    private val serverBaseUrl: String
)
{
    /**
     * Runs the attempt and answers the receipt it earned.
     *
     * The Activity is a parameter rather than a field for the reason the adapter takes one
     * at all: it is what puts an account picker on the screen, and an object that held one
     * would hold it after the screen is gone.
     */
    suspend fun run(activity: Activity, case: HarnessSocialCase): HarnessReceipt
    {
        if (!HarnessSocialConfiguration.isConfigured)
        {
            throw HarnessException.SocialNotConfigured();
        }

        val observation = transport.observe();
        val aliasesBefore = keystoreAliases();
        val social = SpfnSocialGoogle(
            SpfnSocialGoogleCredentialDriver(activity, HarnessSocialConfiguration.googleServerClientId)
        );

        var outcome = OUTCOME_FAILED;
        var errorCode: String? = null;
        var isNewUser: Boolean? = null;
        var keyIdMatch: Boolean? = null;

        // Restored to what it WAS, not to open. The screen has a network switch of its
        // own, and an attempt that ended by opening a network the person had deliberately
        // shut would leave the app disagreeing with its own label.
        val blockedBefore = transport.isBlocked;
        if (case.blocksNetwork)
        {
            transport.setBlocked(true);
        }
        try
        {
            val result = lifecycle.enroll(SpfnSocialGoogle.PROVIDER) { nonce -> idToken(social, nonce, case) };
            outcome = OUTCOME_ENROLLED;
            isNewUser = result.isNewUser;
            // The SDK already refuses a server that names a different key. Recomputing it
            // from what this install now holds is the receipt's own check rather than a
            // restatement of the SDK's, which is what makes the field worth recording.
            keyIdMatch = result.keyId == lifecycle.activeProvider()?.keyId;
        }
        catch (cancellation: CancellationException)
        {
            // The coroutine was cancelled — the screen went away, not the sign-in. It is
            // rethrown untouched: classifying it would report a refusal nobody met and
            // would leave the enclosing scope believing it was never cancelled (P16).
            throw cancellation;
        }
        catch (dismissal: SpfnSocialGoogleException.Cancelled)
        {
            // The OTHER cancellation, and a different type entirely: the person closed the
            // provider sheet. That is an outcome, not a failure, so it is neither counted
            // as one nor folded into the catch below (P16).
            outcome = OUTCOME_CANCELLED;
            errorCode = HarnessOutcome.name(dismissal);
        }
        catch (failure: Throwable)
        {
            outcome = OUTCOME_FAILED;
            errorCode = HarnessOutcome.name(failure);
        }
        finally
        {
            if (case.blocksNetwork)
            {
                transport.setBlocked(blockedBefore);
            }
        }

        return receipt(case, outcome, errorCode, isNewUser, keyIdMatch, aliasesBefore, observation);
    }

    /**
     * The token the SDK's sign-in closure is answered with.
     *
     * Two things happen here and nothing else. The sheet runs on the main thread, because
     * that is where an Activity may present one, and `withContext` is what carries a
     * cancellation of this coroutine into it rather than leaving a sheet nobody closes.
     * And the server-reject case damages the token AFTER the provider issued it, which is
     * how a real refusal is provoked without a server mode, a stub or a second endpoint.
     */
    private suspend fun idToken(social: SpfnSocialGoogle, nonce: SpfnSocialNonce, case: HarnessSocialCase): String
    {
        val token = withContext(Dispatchers.Main) { social.idToken(nonce) };
        return if (case.damagesToken) token + REJECTED_TOKEN_SUFFIX else token;
    }

    private fun receipt(
        case: HarnessSocialCase,
        outcome: String,
        errorCode: String?,
        isNewUser: Boolean?,
        keyIdMatch: Boolean?,
        aliasesBefore: Set<String>,
        observation: HarnessObservation
    ): HarnessReceipt = HarnessReceipt(
        provider = SpfnSocialGoogle.PROVIDER,
        case = case,
        outcome = outcome,
        // This attempt's own observation, not whatever the transport last saw. The two
        // differ the moment a second attempt starts, and the receipt has to be about the
        // attempt that earned it.
        responseCode = observation.statusCode,
        errorCode = errorCode,
        isNewUser = isNewUser,
        keyIdMatch = keyIdMatch,
        keyRemainsAfterFailure = keyRemains(outcome, aliasesBefore),
        timestampMillis = System.currentTimeMillis(),
        serverBaseUrl = HarnessSocialConfiguration.origin(serverBaseUrl),
        serverCommit = observation.serverCommit,
        sdkVersion = SpfnVersion.CURRENT,
        contractVersion = SpfnGeneratedContract.BINDING.importedVersion
    );

    /**
     * Whether a key outlived an attempt that did not enrol.
     *
     * Measured against the KEYSTORE, not against the SDK's metadata, and the difference is
     * the whole point on this platform. A failed enrolment on Apple has a value to drop; on
     * Android the alias already exists in the Keystore by the time the sign-in is asked
     * for, so "no key survives a failure" is a claim about what is left in the Keystore
     * and only a Keystore reading can check it (P15).
     *
     * A successful enrolment leaves a new alias on purpose, so the field is false there:
     * the question is about failures, and answering it for a success would report the
     * design working as the design breaking.
     */
    private fun keyRemains(outcome: String, aliasesBefore: Set<String>): Boolean
    {
        if (outcome == OUTCOME_ENROLLED)
        {
            return false;
        }
        return (keystoreAliases() - aliasesBefore).isNotEmpty();
    }

    /**
     * Every alias this app holds in the Android Keystore.
     *
     * The Keystore is per-uid, so this is exactly this app's keys and nothing else. A
     * failure to read is NOT swallowed into an empty set: an empty set would read as "no
     * key survived", which is the answer the check exists to earn rather than to assume
     * (P7). `java.security.KeyStore` predates every API level this app supports, and the
     * `AndroidKeyStore` provider has existed since API 18 against a floor of 24 (P14).
     */
    private fun keystoreAliases(): Set<String>
    {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keystore.load(null);
        return keystore.aliases().toList().toSet();
    }

    companion object
    {
        const val OUTCOME_ENROLLED: String = "enrolled";
        const val OUTCOME_CANCELLED: String = "cancelled";
        const val OUTCOME_FAILED: String = "failed";

        private const val ANDROID_KEYSTORE: String = "AndroidKeyStore";

        /**
         * What turns a good provider token into one the server refuses.
         *
         * A provider token is dot-separated segments, so an extra segment leaves a value
         * the server cannot verify however it chooses to read it. The damage happens on
         * this side of the wire and nothing in the SDK or the server is configured for it,
         * which is what makes the refusal a real one.
         */
        private const val REJECTED_TOKEN_SUFFIX: String = ".spfn-harness-server-reject";
    }
}
