// SPFN Mobile — what this client says about itself, and what it does with the answer.
//
// Counterpart of Sources/SPFNClient/SPFNClientIdentity.swift; the reasoning is there and
// is not repeated. The one difference is the app's own release version. iOS reads it from
// the bundle with nothing to be given; Android's lives in the package manager, which
// needs a Context, and this SDK is constructed without one. So the header is filled when
// an app hands its version over and omitted when it does not — which the decision behind
// this file allows in as many words: if the platform cannot answer, the header is omitted
// rather than guessed. Neither the kind nor the contract version depends on that, and
// those are the two the server's gate judges.

package xyz.superfunction.spfn.client

import xyz.superfunction.spfn.core.SpfnContractBinding
import xyz.superfunction.spfn.core.SpfnSemVer
import xyz.superfunction.spfn.generated.SpfnGeneratedContract

/** What this client says about itself on every request. */
object SpfnClientIdentity
{
    /** The kind this build reports. */
    const val KIND: String = "android"

    /** The contract version these sources were generated from. */
    val contractVersion: String
        get() = SpfnGeneratedContract.BINDING.importedVersion

    /**
     * The host app's own release, when the app has supplied it.
     *
     * Nothing is authorized by this value: the contract calls it unauthenticated, and the
     * server's refusal rule names only the contract version, so an absent one refuses
     * nothing. It is diagnostic, which is why leaving it unset is a supported state
     * rather than a misconfiguration.
     */
    @Volatile
    var appVersion: String? = null

    /** The identity headers, in the order a request carries them. */
    val headers: List<Pair<String, String>>
        get()
        {
            val headers = mutableListOf(
                SpfnWireHeaders.CLIENT_KIND to KIND,
                SpfnWireHeaders.CLIENT_CONTRACT_VERSION to contractVersion
            );
            appVersion?.let { headers.add(SpfnWireHeaders.CLIENT_VERSION to it) };
            return headers;
        }

    /**
     * Reads a response's announcement and returns the mismatch it reveals, or null.
     *
     * Runs before a response is classified, on every read path. A server that refuses
     * with `CONTRACT_UNSUPPORTED` announces its version on that refusal, and reporting it
     * as a generic refusal would throw away the one thing that says which end is stale.
     */
    @JvmStatic
    fun mismatchIn(
        response: SpfnTransportResponse,
        binding: SpfnContractBinding = SpfnGeneratedContract.BINDING
    ): SpfnContractMismatch?
    {
        // Field names are case-insensitive on the wire, and this compares them that way
        // rather than trusting a server to have chosen the same spelling this file did.
        val announced = response.headers
            .firstOrNull { it.first.equals(SpfnWireHeaders.SERVER_CONTRACT_VERSION, ignoreCase = true) }
            ?.second
            ?: return SpfnContractMismatch(
                SpfnContractMismatch.Reason.UNANNOUNCED,
                null,
                binding.admittedRange
            );

        if (SpfnSemVer.parse(announced) == null)
        {
            return SpfnContractMismatch(
                SpfnContractMismatch.Reason.UNREADABLE,
                null,
                binding.admittedRange
            );
        }

        return try
        {
            binding.requireSupported(announced);
            null;
        }
        catch (refusal: Exception)
        {
            SpfnContractMismatch(
                SpfnContractMismatch.Reason.OUTSIDE_ADMITTED_RANGE,
                announced,
                binding.admittedRange
            );
        };
    }
}

/**
 * Why this client and the server that answered do not hold the same contract.
 *
 * [serverVersion] is present only when the announced value parsed as a version. A value
 * that did not is dropped rather than carried: it is text the server chose, an error
 * value reaches logs and crash reports, and the responder may not be the server at all.
 */
data class SpfnContractMismatch(
    val reason: Reason,
    val serverVersion: String?,
    val admittedRange: String
)
{
    enum class Reason
    {
        /**
         * The response announced no contract version. Contract 0.8.0 puts the
         * announcement on every response including a refusal, so its absence is a server
         * older than that mechanism, or something between that removed it.
         */
        UNANNOUNCED,

        /** A version was announced and is not a version this SDK can read. */
        UNREADABLE,

        /** A version was announced, read, and is outside the window this SDK admits. */
        OUTSIDE_ADMITTED_RANGE
    }
}
