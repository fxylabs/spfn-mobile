// SPFN Mobile — strict SemVer, limited to what the contract range needs.
//
// Written here rather than pulled in: the Kotlin side has the same 90 lines, and two
// small implementations that agree on a shared vector table are easier to keep honest
// than two dependencies that drift. The shared table is
// tools/conformance/semver-range-vectors.json, which both suites read.
//
// It sits in core because the contract range rule in SPFNContractBinding is what decides
// whether this SDK will talk to a server at all, and SPFNClient asks the same parser
// whether a version a server announced is a version in the first place.

/// Strict SemVer parsing and comparison, limited to what the contract range needs.
public enum SPFNSemVer
{
    /// `major.minor.patch` plus an optional pre-release, with build metadata dropped.
    /// Numeric components are kept as digit strings so a version with more digits than
    /// `Int` can hold compares correctly instead of overflowing.
    struct Version: Equatable
    {
        let core: [String]
        let preRelease: String?
    }

    /// Parses strict SemVer. Returns nil for anything else, and "anything else" is
    /// deliberately wide: a missing patch, a sign, a leading zero, surrounding
    /// whitespace, an empty component, a non-ASCII digit, or an empty identifier inside
    /// the pre-release. A parser that guesses at a malformed version is a parser that
    /// admits a server nobody verified.
    static func parse(_ text: String) -> Version?
    {
        guard !text.isEmpty
        else
        {
            return nil
        }

        // Build metadata carries no precedence, so it is dropped — but only after the
        // rest has been checked, and an empty metadata segment is still malformed.
        var body = Substring(text)
        if let plus = body.firstIndex(of: "+")
        {
            let metadata = body[body.index(after: plus)...]
            // Build metadata identifiers are alphanumeric; SemVer places no numeric
            // constraint on them, so 1.0.0+001 is valid where 1.0.0-001 is not.
            guard isIdentifierSequence(metadata, numericIdentifiersAreStrict: false)
            else
            {
                return nil
            }
            body = body[..<plus]
        }

        var preRelease: String?
        if let dash = body.firstIndex(of: "-")
        {
            let tail = body[body.index(after: dash)...]
            guard isIdentifierSequence(tail, numericIdentifiersAreStrict: true)
            else
            {
                return nil
            }
            preRelease = String(tail)
            body = body[..<dash]
        }

        let core = body.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard core.count == 3, core.allSatisfy(isNumericIdentifier)
        else
        {
            return nil
        }

        return Version(core: core, preRelease: preRelease)
    }

    /// Whether this text is a version at all, without saying which one.
    ///
    /// The parser and its result stay internal on purpose — nothing outside this module
    /// has business comparing versions itself. This asks the one question a caller
    /// outside it does have: a version string that arrived from a server is text the
    /// server chose, and `SPFNClientError` forbids carrying such text into a failure
    /// value. Asking here first is what makes carrying it afterwards safe, because what
    /// survives is a string this SDK validated rather than whatever arrived.
    public static func isVersion(_ text: String) -> Bool
    {
        parse(text) != nil
    }

    /// True when `candidate` is at or above `lower` and strictly below `upper`, and
    /// carries the same pre-release as `lower` (usually none).
    static func satisfies(candidate: String, atOrAbove lower: String, below upper: String) -> Bool
    {
        guard let candidate = parse(candidate),
              let lower = parse(lower),
              let upper = parse(upper)
        else
        {
            return false
        }

        guard candidate.preRelease == lower.preRelease
        else
        {
            return false
        }

        // A pinned pre-release names one version, not a window. Range arithmetic would
        // put 0.1.1-rc.1 inside [0.1.0-rc.1, 0.2.0), but that is a pre-release of a
        // version this SDK was never generated from.
        if lower.preRelease != nil
        {
            return compareCore(candidate.core, lower.core) == 0
        }

        return compareCore(candidate.core, lower.core) >= 0
            && compareCore(candidate.core, upper.core) < 0
    }

    /// Numeric comparison without parsing to an integer: leading zeros are already
    /// refused, so a longer digit string is always the larger number.
    static func compareCore(_ left: [String], _ right: [String]) -> Int
    {
        for (l, r) in zip(left, right)
        {
            if l.count != r.count
            {
                return l.count < r.count ? -1 : 1
            }
            if l != r
            {
                return l < r ? -1 : 1
            }
        }
        return 0
    }

    /// A SemVer numeric identifier: ASCII digits, non-empty, no leading zero unless the
    /// whole component is `0`.
    private static func isNumericIdentifier(_ text: String) -> Bool
    {
        guard !text.isEmpty, text.allSatisfy({ $0.isASCII && $0.isNumber })
        else
        {
            return false
        }
        return text == "0" || !text.hasPrefix("0")
    }

    /// Dot-separated identifiers of `[0-9A-Za-z-]`, each non-empty. When
    /// `numericIdentifiersAreStrict` is set — which is the pre-release case — an
    /// all-digit identifier may not carry a leading zero, because SemVer compares those
    /// numerically and `01` has no numeric meaning.
    private static func isIdentifierSequence(
        _ text: Substring,
        numericIdentifiersAreStrict: Bool
    ) -> Bool
    {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard !parts.isEmpty
        else
        {
            return false
        }
        return parts.allSatisfy { part in
            guard !part.isEmpty,
                  part.allSatisfy({ $0.isASCII && ($0.isNumber || $0.isLetter || $0 == "-") })
            else
            {
                return false
            }
            guard numericIdentifiersAreStrict, part.allSatisfy({ $0.isNumber })
            else
            {
                return true
            }
            return part == "0" || !part.hasPrefix("0")
        }
    }
}
