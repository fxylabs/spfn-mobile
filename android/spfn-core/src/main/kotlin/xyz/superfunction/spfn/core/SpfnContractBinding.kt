// SPFN Mobile — what this build claims about the contract it was generated from.
//
// Counterpart of Sources/SPFNCore/SPFNContractBinding.swift. The values are not here:
// they are generated into xyz.superfunction.spfn.generated from the bundle pinned in
// Contracts/upstream.lock.json, because a hand-written constant is exactly the thing a
// reader cannot distinguish from a verified one.

package xyz.superfunction.spfn.core

/** The contract a generated client was produced from. */
data class SpfnContractBinding(
    /** Contract SemVer, e.g. `1.0.0-dev.1`. */
    val importedVersion: String,

    /** SHA-256 of the vendored bundle the generator read. */
    val importedManifestSha256: String,

    /** The SemVer range this SDK declares support for, for display and diagnostics. */
    val supportedRange: String,

    /** The contract major this SDK links against. */
    val supportedMajor: Int,

    /**
     * Where the bundle came from. `spfn-mobile-step2-dev-bundle` means it was
     * hand-authored in this repository and has NOT been exported by SPFN primitives.
     */
    val origin: String
)
{
    /**
     * True only when the bundle came from SPFN primitives CI rather than a local
     * stand-in. False for every build produced so far.
     */
    val isUpstreamExport: Boolean
        get() = origin == "spfn-primitives-ci-export"

    /**
     * Rejects a server contract outside the supported major.
     *
     * There is no fallback and no partial-compatibility mode: an unsupported contract
     * surfaces as an upgrade error rather than as a decoding failure much later.
     */
    fun requireSupported(serverContractVersion: String)
    {
        val major = majorOf(serverContractVersion);
        if (major == null || major != supportedMajor)
        {
            throw SpfnDecodingException(
                "CONTRACT_UNSUPPORTED",
                "server contract '$serverContractVersion' is outside the supported range '$supportedRange'"
            );
        }
    }

    companion object
    {
        fun majorOf(version: String): Int?
        {
            val head = version.takeWhile { it.isDigit() };
            return if (head.isEmpty()) null else head.toIntOrNull();
        }
    }
}
