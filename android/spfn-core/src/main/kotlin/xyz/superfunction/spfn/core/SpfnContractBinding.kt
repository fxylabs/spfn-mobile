// SPFN Mobile — what this build claims about the contract it was generated from.
//
// Counterpart of Sources/SPFNCore/SPFNContractBinding.swift. The values are not here:
// they are generated into xyz.superfunction.spfn.generated from the bundle pinned in
// Contracts/upstream.lock.json, because a hand-written constant is exactly the thing a
// reader cannot distinguish from a verified one.

package xyz.superfunction.spfn.core

/** The contract a generated client was produced from. */
data class SpfnContractBinding(
    /** Contract SemVer, e.g. `0.1.0`. This is the lower bound of the supported range. */
    val importedVersion: String,

    /** SHA-256 of the vendored bundle the generator read. */
    val importedManifestSha256: String,

    /**
     * The SemVer range the pinned contract declares, verbatim from the bundle. It is what
     * the contract says, not what this SDK will accept: [admittedRange] is the enforced
     * window, and the two differ when the pin is a pre-release.
     */
    val supportedRange: String,

    /** The contract major this SDK links against. */
    val supportedMajor: Int,

    /**
     * The contract minor this SDK links against. It is the compatibility axis while the
     * major is 0; above that it bounds nothing.
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
     * The exclusive upper bound the declared range carries. Derived from the pinned major
     * and minor so it cannot disagree with [supportedRange]; the validator asserts the
     * printed string equals `>=<version> <upper>`.
     */
    val upperBound: String
        get() = if (supportedMajor == 0) "0.${supportedMinor + 1}.0" else "${supportedMajor + 1}.0.0"

    /**
     * Rejects a server contract this SDK does not implement.
     *
     * The rule is the declared range, enforced rather than approximated:
     *
     * - the server version must parse as strict SemVer. Anything else refuses, because a
     *   version this SDK cannot read is not one it can claim to support;
     * - it must be at or above the pinned version. A `0.1.0` server does not satisfy a
     *   client pinned at `0.1.5`, which needs operations `0.1.0` never carried;
     * - it must be below the next breaking version, which is the next minor while the
     *   major is 0 and the next major above that. SemVer puts breaking changes in the
     *   minor below 1.0.0, so `0.2.0` is as incompatible with `0.1.0` as `2.0.0` is with
     *   `1.0.0`;
     * - a pre-release is accepted only when it is exactly the pinned pre-release.
     *   `0.1.0-rc.1` precedes `0.1.0` in SemVer precedence, so it is below the lower
     *   bound. When the pin itself is a pre-release, it accepts that exact version and
     *   nothing else: `0.1.1-rc.1` sorts above `0.1.0-rc.1` and inside the range by
     *   SemVer arithmetic, but it is a pre-release of a version nobody pinned, and an
     *   SDK that decodes it is guessing.
     *
     * There is no fallback and no partial-compatibility mode: an unsupported contract
     * surfaces as an upgrade error rather than as a decoding failure much later. The
     * refusal reports [admittedRange], not [supportedRange], because those are the same
     * string only for a release pin that is its minor's first release.
     */
    fun requireSupported(serverContractVersion: String)
    {
        val supported = SpfnSemVer.satisfies(serverContractVersion, importedVersion, upperBound);
        if (!supported)
        {
            throw SpfnDecodingException(
                "CONTRACT_UNSUPPORTED",
                "server contract '$serverContractVersion' is outside the admitted range '$admittedRange'"
            );
        }
    }

    /**
     * The window [requireSupported] actually admits.
     *
     * For a release pin this is the pinned version up to the next breaking one. That is
     * [supportedRange] when the pin is its minor's first release, and narrower once a later
     * patch is pinned: the declared range still starts at the minor's floor, and this SDK
     * calls operations only the pinned patch serves. For a pre-release pin it is the
     * pinned version alone: the declared range would promise every core below the next breaking
     * version, this SDK refuses all of them, and printing that range would advertise a
     * window it will not honour. A pin this SDK cannot parse admits nothing, and says so.
     */
    val admittedRange: String
        get()
        {
            val pinned = SpfnSemVer.parse(importedVersion)
                ?: return "<none: '$importedVersion' is not a version this SDK can parse>";
            return if (pinned.preRelease != null) "==$importedVersion"
                else ">=$importedVersion <$upperBound";
        }
}
