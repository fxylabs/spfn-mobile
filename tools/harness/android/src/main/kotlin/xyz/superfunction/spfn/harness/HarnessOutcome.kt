package xyz.superfunction.spfn.harness

import xyz.superfunction.spfn.client.SpfnClientError
import xyz.superfunction.spfn.client.SpfnClockSynchronizationException
import xyz.superfunction.spfn.client.SpfnKeyLifecycleException
import xyz.superfunction.spfn.client.SpfnTransportError
import xyz.superfunction.spfn.social.google.SpfnSocialGoogleException

/**
 * One short stable name per failure.
 *
 * A Maestro flow asserts on text, so the text has to be a name rather than a sentence.
 * Every name here comes from the SDK's own vocabulary: a lifecycle refusal is its class
 * name, a server refusal is the contract's error code, and a transport failure is its
 * class. Nothing is invented and nothing is translated, so a flow that asserts
 * `err:SESSION_REVOKED` is asserting on the contract.
 *
 * The names are IDENTICAL to the Swift half's, which is what lets one flow file drive
 * both platforms. Where the two SDKs spell a thing differently — Kotlin's
 * `MalformedProviderId` against Swift's `malformedProviderID` — this file spells it the
 * Swift way, because a flow can only assert one string.
 *
 * An error this file does not recognise becomes `unclassified`, never a guess. A name
 * that quietly covered an unknown error would let a flow pass on the wrong refusal.
 */
object HarnessOutcome
{
    fun name(error: Throwable): String = when (error)
    {
        is SpfnKeyLifecycleException -> lifecycleName(error)
        is SpfnClientError -> clientName(error)
        is SpfnClockSynchronizationException -> clockName(error)
        is SpfnTransportError -> transportName(error)
        is SpfnSocialGoogleException -> socialGoogleName(error)
        is HarnessException -> harnessName(error)
        else -> "unclassified"
    };

    /**
     * The Google adapter's own vocabulary, unaltered.
     *
     * These names are Android-only and have no Swift twin, because the two platforms reach
     * Google through different SDKs and classify with different words. That is a difference
     * the shared spec allows: its case table asks a cancel to carry "the SDK cancel
     * classification", not one fixed string across platforms.
     *
     * `Failed.type` is Credential Manager's own type CONSTANT — a fixed identifier such as
     * `android.credentials.GetCredentialException.TYPE_NO_CREDENTIAL`. The adapter has
     * already dropped the provider's message, which is where an account identifier would
     * otherwise reach a receipt.
     */
    private fun socialGoogleName(error: SpfnSocialGoogleException): String = when (error)
    {
        is SpfnSocialGoogleException.Cancelled -> "social:cancelled"
        is SpfnSocialGoogleException.IdentityTokenMissing -> "social:identityTokenMissing"
        is SpfnSocialGoogleException.Failed -> "social:failed:${error.type}"
        is SpfnSocialGoogleException.NonceProviderMismatch -> "social:nonceProviderMismatch"
    };

    private fun clockName(error: SpfnClockSynchronizationException): String = when (error)
    {
        is SpfnClockSynchronizationException.ContractIncompatible -> "clockSynchronization:contractIncompatible"
        is SpfnClockSynchronizationException.UntrustedBaseUrl -> "clockSynchronization:untrustedBaseURL"
        is SpfnClockSynchronizationException.RequestFailed -> "clockSynchronization:requestFailed"
        is SpfnClockSynchronizationException.InvalidResponse -> "clockSynchronization:invalidResponse"
        is SpfnClockSynchronizationException.MonotonicClockInvalid -> "clockSynchronization:monotonicClockInvalid"
        is SpfnClockSynchronizationException.ClockOverflow -> "clockSynchronization:clockOverflow"
    };

    private fun lifecycleName(error: SpfnKeyLifecycleException): String = when (error)
    {
        is SpfnKeyLifecycleException.AlreadyEnrolled -> "alreadyEnrolled"
        is SpfnKeyLifecycleException.NotEnrolled -> "notEnrolled"
        is SpfnKeyLifecycleException.RotationUnresolved -> "rotationUnresolved"
        is SpfnKeyLifecycleException.EnrollmentInFlight -> "enrollmentInFlight"
        is SpfnKeyLifecycleException.IdTokenMissing -> "idTokenMissing"
        is SpfnKeyLifecycleException.MalformedProviderId -> "malformedProviderID"
        is SpfnKeyLifecycleException.ServerNamedAnotherKey -> "serverNamedAnotherKey"
        is SpfnKeyLifecycleException.KeyUnloadable -> "keyUnloadable"
    };

    /**
     * A server refusal reports the contract's own code, which is the thing worth
     * asserting on: `SESSION_REVOKED` after a revocation is the contract behaving.
     */
    private fun clientName(error: SpfnClientError): String = when (error)
    {
        is SpfnClientError.Transport -> transportName(error.error)
        is SpfnClientError.Auth -> error.failure.code.wireCode
        is SpfnClientError.Server -> error.failure.code.wireCode
        is SpfnClientError.Decoding -> "decoding:${error.failure.name}"
        // The reason alone. The server's version is in the error and is deliberately not
        // put on a readout a flow asserts on: a readout that carried it would make every
        // assertion depend on which server answered.
        is SpfnClientError.Contract -> "contract:${error.mismatch.reason.name}"
        is SpfnClientError.UnsupportedOperation -> "unsupportedOperation"
        is SpfnClientError.UndeclaredAuthClass -> "undeclaredAuthClass"
    };

    private fun transportName(error: SpfnTransportError): String = when (error)
    {
        is SpfnTransportError.Connectivity -> "connectivity"
        is SpfnTransportError.TimedOut -> "timedOut"
        is SpfnTransportError.Cancelled -> "cancelled"
        is SpfnTransportError.InvalidResponse -> "invalidResponse"
    };

    private fun harnessName(error: HarnessException): String = when (error)
    {
        is HarnessException.NoCannedToken -> "harness:noCannedToken"
        is HarnessException.NoActiveKey -> "harness:noActiveKey"
        is HarnessException.SocialNotConfigured -> "harness:socialNotConfigured"
        is HarnessException.ReceiptDirectoryUnavailable -> "harness:receiptDirectoryUnavailable"
    };
}

/** What the harness itself refuses, as opposed to what the SDK refuses. */
sealed class HarnessException(message: String) : IllegalStateException(message)
{
    /**
     * A flow asked for enrolment without supplying a token and this build has no provider
     * SDK to obtain one. A device run supplies the sheet instead.
     */
    class NoCannedToken : HarnessException("no canned id_token was supplied to this launch");

    class NoActiveKey : HarnessException("no active key to sign with");

    /**
     * A device sign-in was asked for on a build that has no client id or no server
     * address. The button is disabled in this state, so reaching this is a bug rather
     * than a mistake a person can make — it exists so the bug reports itself instead of
     * sending an empty client id to Credential Manager.
     */
    class SocialNotConfigured : HarnessException("this build carries no social sign-in configuration");

    /**
     * The external files directory does not exist and could not be made, so the run has
     * nowhere to leave its receipt. Raised rather than ignored: a run whose receipt went
     * missing must not look like a run that was never made (P7).
     */
    class ReceiptDirectoryUnavailable : HarnessException("the external files directory is unavailable");
}
