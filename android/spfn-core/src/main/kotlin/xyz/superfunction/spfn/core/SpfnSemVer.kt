// SPFN Mobile — strict SemVer, limited to what the contract range needs.
//
// Counterpart of Sources/SPFNCore/SPFNSemVer.swift. Written here rather than pulled in:
// the Swift side has the same 90 lines, and two small implementations that agree on a
// shared vector table are easier to keep honest than two dependencies that drift. The
// shared table is tools/conformance/semver-range-vectors.json, which both suites read.
//
// It sits in core because the contract range rule in SpfnContractBinding is what decides
// whether this SDK will talk to a server at all, and SpfnClientIdentity asks the same
// parser whether a version a server announced is a version in the first place.

package xyz.superfunction.spfn.core

/** Strict SemVer parsing and comparison, limited to what the contract range needs. */
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
            // Build metadata identifiers are alphanumeric; SemVer places no numeric
            // constraint on them, so 1.0.0+001 is valid where 1.0.0-001 is not.
            if (!isIdentifierSequence(body.substring(plus + 1), numericIdentifiersAreStrict = false))
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
            if (!isIdentifierSequence(tail, numericIdentifiersAreStrict = true))
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

        // A pinned pre-release names one version, not a window. Range arithmetic would
        // put 0.1.1-rc.1 inside [0.1.0-rc.1, 0.2.0), but that is a pre-release of a
        // version this SDK was never generated from.
        if (parsedLower.preRelease != null)
        {
            return compareCore(parsedCandidate.core, parsedLower.core) == 0;
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

    /**
     * Dot-separated identifiers of `[0-9A-Za-z-]`, each non-empty. When
     * [numericIdentifiersAreStrict] is set — which is the pre-release case — an all-digit
     * identifier may not carry a leading zero, because SemVer compares those numerically
     * and `01` has no numeric meaning.
     */
    private fun isIdentifierSequence(text: String, numericIdentifiersAreStrict: Boolean): Boolean
    {
        val parts = text.split('.');
        return parts.isNotEmpty() && parts.all { part ->
            if (part.isEmpty() ||
                !part.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '-' })
            {
                false
            }
            else if (!numericIdentifiersAreStrict || !part.all { it in '0'..'9' })
            {
                true
            }
            else
            {
                part == "0" || !part.startsWith("0")
            }
        };
    }
}
