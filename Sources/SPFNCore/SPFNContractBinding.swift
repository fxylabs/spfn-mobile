// SPFN Mobile — what this build claims about the contract it was generated from.
//
// The values are not here. They are generated into SPFNGenerated from the bundle
// pinned in Contracts/upstream.lock.json, because a hand-written constant is exactly
// the thing a reader cannot distinguish from a verified one. This file holds only the
// shape and the range rule.

/// The contract a generated client was produced from.
public struct SPFNContractBinding: Equatable, Sendable
{
    /// Contract SemVer, e.g. `0.1.0`. This is the lower bound of the supported range.
    public let importedVersion: String

    /// SHA-256 of the vendored bundle the generator read.
    public let importedManifestSha256: String

    /// The SemVer range this SDK declares support for, for display and diagnostics.
    /// `requireSupported` enforces this range; it does not parse this string, it derives
    /// the same bounds from the pinned version, and the validator asserts the two agree.
    public let supportedRange: String

    /// The contract major this SDK links against.
    public let supportedMajor: Int

    /// The contract minor this SDK links against. It is the compatibility axis while
    /// the major is 0; above that it bounds nothing.
    public let supportedMinor: Int

    /// Where the bundle came from. `spfn-primitives-ci-export` means SPFN primitives
    /// generated it; `spfn-mobile-step2-dev-bundle` means it was hand-authored here.
    public let origin: String

    public init(
        importedVersion: String,
        importedManifestSha256: String,
        supportedRange: String,
        supportedMajor: Int,
        supportedMinor: Int,
        origin: String
    )
    {
        self.importedVersion = importedVersion
        self.importedManifestSha256 = importedManifestSha256
        self.supportedRange = supportedRange
        self.supportedMajor = supportedMajor
        self.supportedMinor = supportedMinor
        self.origin = origin
    }

    /// True only when the bundle came from SPFN primitives CI rather than a local
    /// stand-in.
    public var isUpstreamExport: Bool
    {
        origin == "spfn-primitives-ci-export"
    }

    /// Rejects a server contract this SDK does not implement.
    ///
    /// The rule is the declared range, enforced rather than approximated:
    ///
    /// - the server version must parse as strict SemVer. Anything else refuses, because
    ///   a version this SDK cannot read is not one it can claim to support;
    /// - it must be at or above the pinned version. A `0.1.0` server does not satisfy a
    ///   client pinned at `0.1.5`, which needs operations `0.1.0` never carried;
    /// - it must be below the next breaking version, which is the next minor while the
    ///   major is 0 and the next major above that. SemVer puts breaking changes in the
    ///   minor below 1.0.0, so `0.2.0` is as incompatible with `0.1.0` as `2.0.0` is
    ///   with `1.0.0`;
    /// - a pre-release is accepted only when it is exactly the pinned pre-release.
    ///   `0.1.0-rc.1` precedes `0.1.0` in SemVer precedence, so it is below the lower
    ///   bound, and a pre-release of any other version is a contract nobody has pinned.
    ///
    /// There is no fallback and no partial-compatibility mode: an unsupported contract
    /// surfaces as an upgrade error rather than as a decoding failure much later.
    public func requireSupported(serverContractVersion: String) throws
    {
        guard SPFNSemVer.satisfies(
            candidate: serverContractVersion,
            atOrAbove: importedVersion,
            below: upperBound
        )
        else
        {
            throw SPFNDecodingError.unsupportedContractVersion(
                found: serverContractVersion,
                supportedRange: supportedRange
            )
        }
    }

    /// The exclusive upper bound the declared range carries. Derived from the pinned
    /// major and minor so it cannot disagree with `supportedRange`; the validator
    /// asserts the printed string equals `>=<version> <upper>`.
    var upperBound: String
    {
        supportedMajor == 0 ? "0.\(supportedMinor + 1).0" : "\(supportedMajor + 1).0.0"
    }
}

/// Strict SemVer parsing and comparison, limited to what the contract range needs.
///
/// Written here rather than pulled in: the Kotlin side has the same 90 lines, and two
/// small implementations that agree on a shared vector table are easier to keep honest
/// than two dependencies that drift.
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
            guard isIdentifierSequence(metadata)
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
            guard isIdentifierSequence(tail)
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

    /// Dot-separated identifiers of `[0-9A-Za-z-]`, each non-empty.
    private static func isIdentifierSequence(_ text: Substring) -> Bool
    {
        let parts = text.split(separator: ".", omittingEmptySubsequences: false)
        guard !parts.isEmpty
        else
        {
            return false
        }
        return parts.allSatisfy { part in
            !part.isEmpty && part.allSatisfy { $0.isASCII && ($0.isNumber || $0.isLetter || $0 == "-") }
        }
    }
}
