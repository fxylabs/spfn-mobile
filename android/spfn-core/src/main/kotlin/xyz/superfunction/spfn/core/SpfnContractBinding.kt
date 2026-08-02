// SPFN Mobile — what this build claims about the contract it was generated from.
//
// Counterpart of Sources/SPFNCore/SPFNContractBinding.swift. The values are not here:
// they are generated into xyz.superfunction.spfn.generated from the bundle pinned in
// Contracts/upstream.lock.json, because a hand-written constant is exactly the thing a
// reader cannot distinguish from a verified one.

package xyz.superfunction.spfn.core

/** The contract a generated client was produced from. */
data class SpfnContractBinding(
    /** Contract SemVer, e.g. `0.1.0`. */
    val importedVersion: String,

    /** SHA-256 of the vendored bundle the generator read. */
    val importedManifestSha256: String,

    /** The SemVer range this SDK declares support for, for display and diagnostics. */
    val supportedRange: String,

    /** The contract major this SDK links against. */
    val supportedMajor: Int,

    /**
     * The contract minor this SDK links against. It is the compatibility axis while the
     * major is 0; above that it is carried for diagnostics only.
     */
    val supportedMinor: Int,

    /**
     * Where the bundle came from. `spfn-primitives-ci-export` means SPFN primitives
     * generated it; `spfn-mobile-step2-dev-bundle` means it was hand-authored here.
     */
    val origin: String
)
{
    /** True only when the bundle came from SPFN primitives CI rather than a local stand-in. */
    val isUpstreamExport: Boolean
        get() = origin == "spfn-primitives-ci-export"

    /**
     * Rejects a server contract this SDK does not implement.
     *
     * Below 1.0.0 SemVer puts breaking changes in the minor, so `0.2.0` is as
     * incompatible with `0.1.0` as `2.0.0` is with `1.0.0`. Comparing majors alone would
     * admit it, and the SDK would then decode a contract it does not implement — the
     * check would be weaker than the range it prints. Above 0.x the minor is additive
     * and only the major decides.
     *
     * There is no fallback and no partial-compatibility mode: an unsupported contract
     * surfaces as an upgrade error rather than as a decoding failure much later.
     */
    fun requireSupported(serverContractVersion: String)
    {
        val server = majorMinorOf(serverContractVersion);
        val supported = server != null &&
            server.first == supportedMajor &&
            (supportedMajor > 0 || server.second == supportedMinor);
        if (!supported)
        {
            throw SpfnDecodingException(
                "CONTRACT_UNSUPPORTED",
                "server contract '$serverContractVersion' is outside the supported range '$supportedRange'"
            );
        }
    }

    companion object
    {
        /**
         * Parses the leading `major.minor` of a SemVer string. A version with no minor
         * component parses as minor 0, matching SemVer's own defaulting.
         */
        fun majorMinorOf(version: String): Pair<Int, Int>?
        {
            val core = version.takeWhile { it != '-' && it != '+' };
            val parts = core.split('.');
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return null;
            if (parts.size == 1)
            {
                return major to 0;
            }
            val minor = parts[1].toIntOrNull() ?: return null;
            return major to minor;
        }
    }
}
