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

    /// The SemVer range the pinned contract declares, verbatim from the bundle. It is
    /// what the contract says, not what this SDK will accept: `admittedRange` is the
    /// enforced window, and the two differ when the pin is a pre-release.
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
    ///   bound. When the pin itself is a pre-release, it accepts that exact version and
    ///   nothing else: `0.1.1-rc.1` sorts above `0.1.0-rc.1` and inside the range by
    ///   SemVer arithmetic, but it is a pre-release of a version nobody pinned, and an
    ///   SDK that decodes it is guessing.
    ///
    /// There is no fallback and no partial-compatibility mode: an unsupported contract
    /// surfaces as an upgrade error rather than as a decoding failure much later. The
    /// refusal reports `admittedRange`, not `supportedRange`, because those are the same
    /// string only for a release pin.
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
                admittedRange: admittedRange
            )
        }
    }

    /// The window `requireSupported` actually admits.
    ///
    /// For a release pin this is `supportedRange`. For a pre-release pin it is the pinned
    /// version alone: the declared range would promise every core below the next breaking
    /// version, this SDK refuses all of them, and printing that range would advertise a
    /// window it will not honour. A pin this SDK cannot parse admits nothing, and says so.
    public var admittedRange: String
    {
        guard let pinned = SPFNSemVer.parse(importedVersion)
        else
        {
            return "<none: '\(importedVersion)' is not a version this SDK can parse>"
        }
        return pinned.preRelease != nil
            ? "==\(importedVersion)"
            : ">=\(importedVersion) <\(upperBound)"
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
