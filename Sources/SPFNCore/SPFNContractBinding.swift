// SPFN Mobile — what this build claims about the contract it was generated from.
//
// The values are not here. They are generated into SPFNGenerated from the bundle
// pinned in Contracts/upstream.lock.json, because a hand-written constant is exactly
// the thing a reader cannot distinguish from a verified one. This file holds only the
// shape and the range rule.

/// The contract a generated client was produced from.
public struct SPFNContractBinding: Equatable, Sendable
{
    /// Contract SemVer, e.g. `0.1.0`.
    public let importedVersion: String

    /// SHA-256 of the vendored bundle the generator read.
    public let importedManifestSha256: String

    /// The SemVer range this SDK declares support for, for display and diagnostics.
    public let supportedRange: String

    /// The contract major this SDK links against.
    public let supportedMajor: Int

    /// The contract minor this SDK links against. It is the compatibility axis while
    /// the major is 0; above that it is carried for diagnostics only.
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
    /// Below 1.0.0 SemVer puts breaking changes in the minor, so `0.2.0` is as
    /// incompatible with `0.1.0` as `2.0.0` is with `1.0.0`. Comparing majors alone
    /// would admit it, and the SDK would then decode a contract it does not implement —
    /// the check would be weaker than the range it prints. Above 0.x the minor is
    /// additive and only the major decides.
    ///
    /// There is no fallback and no partial-compatibility mode: an unsupported contract
    /// surfaces as an upgrade error rather than as a decoding failure much later.
    public func requireSupported(serverContractVersion: String) throws
    {
        guard let server = Self.majorMinor(of: serverContractVersion),
              server.major == supportedMajor,
              supportedMajor > 0 || server.minor == supportedMinor
        else
        {
            throw SPFNDecodingError.unsupportedContractVersion(
                found: serverContractVersion,
                supportedRange: supportedRange
            )
        }
    }

    /// Parses the leading `major.minor` of a SemVer string. A version with no minor
    /// component parses as minor 0, matching SemVer's own defaulting.
    static func majorMinor(of version: String) -> (major: Int, minor: Int)?
    {
        let core = version.prefix { $0 != "-" && $0 != "+" }
        let parts = core.split(separator: ".", omittingEmptySubsequences: false)
        guard let first = parts.first, let major = Int(first)
        else
        {
            return nil
        }
        guard parts.count > 1
        else
        {
            return (major, 0)
        }
        guard let minor = Int(parts[1])
        else
        {
            return nil
        }
        return (major, minor)
    }
}
