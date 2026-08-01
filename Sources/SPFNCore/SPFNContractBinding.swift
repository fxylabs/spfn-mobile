// SPFN Mobile — what this build claims about the contract it was generated from.
//
// The values are not here. They are generated into SPFNGenerated from the bundle
// pinned in Contracts/upstream.lock.json, because a hand-written constant is exactly
// the thing a reader cannot distinguish from a verified one. This file holds only the
// shape and the range rule.

/// The contract a generated client was produced from.
public struct SPFNContractBinding: Equatable, Sendable
{
    /// Contract SemVer, e.g. `1.0.0-dev.1`.
    public let importedVersion: String

    /// SHA-256 of the vendored bundle the generator read.
    public let importedManifestSha256: String

    /// The SemVer range this SDK declares support for, for display and diagnostics.
    public let supportedRange: String

    /// The contract major this SDK links against. A server on any other major is an
    /// explicit upgrade error, never a best-effort attempt.
    public let supportedMajor: Int

    /// Where the bundle came from. `spfn-mobile-step2-dev-bundle` means it was
    /// hand-authored in this repository and has NOT been exported by SPFN primitives.
    public let origin: String

    public init(
        importedVersion: String,
        importedManifestSha256: String,
        supportedRange: String,
        supportedMajor: Int,
        origin: String
    )
    {
        self.importedVersion = importedVersion
        self.importedManifestSha256 = importedManifestSha256
        self.supportedRange = supportedRange
        self.supportedMajor = supportedMajor
        self.origin = origin
    }

    /// True only when the bundle came from SPFN primitives CI rather than a local
    /// stand-in. False for every build produced so far.
    public var isUpstreamExport: Bool
    {
        origin == "spfn-primitives-ci-export"
    }

    /// Rejects a server contract outside the supported major.
    ///
    /// There is no fallback and no partial-compatibility mode: an unsupported contract
    /// surfaces as an upgrade error rather than as a decoding failure much later.
    public func requireSupported(serverContractVersion: String) throws
    {
        guard let major = Self.major(of: serverContractVersion), major == supportedMajor
        else
        {
            throw SPFNDecodingError.unsupportedContractVersion(
                found: serverContractVersion,
                supportedRange: supportedRange
            )
        }
    }

    static func major(of version: String) -> Int?
    {
        let head = version.prefix { $0.isNumber }
        guard !head.isEmpty
        else
        {
            return nil
        }
        return Int(head)
    }
}
