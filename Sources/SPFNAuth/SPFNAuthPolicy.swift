// SPFN Mobile — auth profile policy. v1 is single-profile by decision.

/// Fixed policy around profile selection.
public enum SPFNAuthPolicy
{
    /// The complete allowlist. Must stay equal to `SPFNAuthProfile.allCases`.
    public static let allowedProfiles: [SPFNAuthProfile] = [.clientProofV1]

    /// The only profile a client may default to.
    public static let defaultProfile: SPFNAuthProfile = .clientProofV1

    /// An unrecognised profile name is rejected. There is no fallback profile and no
    /// mixing of profiles within a session (topology artifact §6).
    public static func resolve(profileName: String) throws -> SPFNAuthProfile
    {
        guard let profile = SPFNAuthProfile(rawValue: profileName),
              allowedProfiles.contains(profile)
        else
        {
            throw SPFNAuthError.unknownProfileRejected(profileName)
        }
        return profile
    }
}

/// Auth failures. Each carries the contract error code it surfaces as, so a client and
/// the conformance fixtures name the same outcome.
public enum SPFNAuthError: Error, Equatable, Sendable
{
    /// The server or caller named a profile outside the allowlist.
    case unknownProfileRejected(String)

    /// A proof-input field contained a C0 control character, which would make the
    /// newline-separated canonical form ambiguous.
    case controlCharacterInProofField(String)

    /// The presented proof did not match the expected MAC.
    case proofInvalid

    /// The (clientId, nonce) pair was already spent inside the replay window.
    case proofReplayed

    /// `issuedAtMillis` falls outside the replay window.
    case proofExpired

    /// The key or session was revoked. Checked before the proof, so revocation never
    /// masquerades as a bad proof.
    case sessionRevoked

    public var code: String
    {
        switch self
        {
        case .unknownProfileRejected:
            return "PROFILE_REJECTED"
        case .controlCharacterInProofField:
            return "PROOF_INPUT_INVALID"
        case .proofInvalid:
            return "PROOF_INVALID"
        case .proofReplayed:
            return "PROOF_REPLAYED"
        case .proofExpired:
            return "PROOF_EXPIRED"
        case .sessionRevoked:
            return "SESSION_REVOKED"
        }
    }
}
