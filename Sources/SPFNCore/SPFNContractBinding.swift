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
