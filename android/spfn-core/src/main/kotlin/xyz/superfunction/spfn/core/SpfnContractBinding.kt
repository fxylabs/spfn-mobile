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
     * The SemVer range this SDK declares support for, for display and diagnostics.
     * `requireSupported` enforces this range; it does not parse this string, it derives
     * the same bounds from the pinned version, and the validator asserts the two agree.
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
     *   bound, and a pre-release of any other version is a contract nobody has pinned.
     *
     * There is no fallback and no partial-compatibility mode: an unsupported contract
     * surfaces as an upgrade error rather than as a decoding failure much later.
     */
    fun requireSupported(serverContractVersion: String)
    {
        val supported = SpfnSemVer.satisfies(serverContractVersion, importedVersion, upperBound);
        if (!supported)
        {
            throw SpfnDecodingException(
                "CONTRACT_UNSUPPORTED",
                "server contract '$serverContractVersion' is outside the supported range '$supportedRange'"
            );
        }
    }
}

/**
 * Strict SemVer parsing and comparison, limited to what the contract range needs.
 *
 * Written here rather than pulled in: the Swift side has the same 90 lines, and two small
 * implementations that agree on a shared vector table are easier to keep honest than two
 * dependencies that drift.
 */
object SpfnSemVer
{
    /**
     * `major.minor.patch` plus an optional pre-release, with build metadata dropped.
     * Numeric components are kept as digit strings so a version with more digits than
     * `Int` can hold compares correctly instead of overflowing.
     */
    data class Version(val core: List<String>, val preRelease: String?)

    /**
     * Parses strict SemVer. Returns null for anything else, and "anything else" is
     * deliberately wide: a missing patch, a sign, a leading zero, surrounding whitespace,
     * an empty component, a non-ASCII digit, or an empty identifier inside the
     * pre-release. A parser that guesses at a malformed version is a parser that admits a
     * server nobody verified.
     */
    fun parse(text: String): Version?
    {
        if (text.isEmpty())
        {
            return null;
        }

        // Build metadata carries no precedence, so it is dropped — but only after the
        // rest has been checked, and an empty metadata segment is still malformed.
        var body = text;
        val plus = body.indexOf('+');
        if (plus >= 0)
        {
            if (!isIdentifierSequence(body.substring(plus + 1)))
            {
                return null;
            }
            body = body.substring(0, plus);
        }

        var preRelease: String? = null;
        val dash = body.indexOf('-');
        if (dash >= 0)
        {
            val tail = body.substring(dash + 1);
            if (!isIdentifierSequence(tail))
            {
                return null;
            }
            preRelease = tail;
            body = body.substring(0, dash);
        }

        val core = body.split('.');
        if (core.size != 3 || !core.all { isNumericIdentifier(it) })
        {
            return null;
        }

        return Version(core, preRelease);
    }

    /**
     * True when [candidate] is at or above [lower] and strictly below [upper], and carries
     * the same pre-release as [lower] (usually none).
     */
    fun satisfies(candidate: String, lower: String, upper: String): Boolean
    {
        val parsedCandidate = parse(candidate) ?: return false;
        val parsedLower = parse(lower) ?: return false;
        val parsedUpper = parse(upper) ?: return false;

        if (parsedCandidate.preRelease != parsedLower.preRelease)
        {
            return false;
        }

        return compareCore(parsedCandidate.core, parsedLower.core) >= 0 &&
            compareCore(parsedCandidate.core, parsedUpper.core) < 0;
    }

    /**
     * Numeric comparison without parsing to an integer: leading zeros are already
     * refused, so a longer digit string is always the larger number.
     */
    fun compareCore(left: List<String>, right: List<String>): Int
    {
        for (index in left.indices)
        {
            val l = left[index];
            val r = right[index];
            if (l.length != r.length)
            {
                return if (l.length < r.length) -1 else 1;
            }
            if (l != r)
            {
                return if (l < r) -1 else 1;
            }
        }
        return 0;
    }

    /**
     * A SemVer numeric identifier: ASCII digits, non-empty, no leading zero unless the
     * whole component is `0`.
     */
    private fun isNumericIdentifier(text: String): Boolean
    {
        if (text.isEmpty() || !text.all { it in '0'..'9' })
        {
            return false;
        }
        return text == "0" || !text.startsWith("0");
    }

    /** Dot-separated identifiers of `[0-9A-Za-z-]`, each non-empty. */
    private fun isIdentifierSequence(text: String): Boolean
    {
        val parts = text.split('.');
        return parts.isNotEmpty() && parts.all { part ->
            part.isNotEmpty() && part.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '-' }
        };
    }
}
