package xyz.superfunction.spfn.harness

import xyz.superfunction.spfn.client.SpfnClientError
import xyz.superfunction.spfn.client.SpfnKeyLifecycleException
import xyz.superfunction.spfn.client.SpfnTransportError

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
        is SpfnTransportError -> transportName(error)
        is HarnessException -> harnessName(error)
        else -> "unclassified"
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
}
