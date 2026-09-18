// SPFN Mobile — auth profile policy. v1 is single-profile by decision.
//
// The allowlist is two constants and no resolver. There used to be a `resolve(profileName:)`
// that turned a wire name into a profile or refused it, and nothing in the SDK ever called
// it: no operation names its class as free text — `SPFNGeneratedOperations.authClass(of:)`
// reads the pinned bundle and `execute` refuses a class it does not know — so the function
// was answered only by its own test. What holds the boundary is not a function anyway:
// tools/validate/validate.sh pins the allowlist literal and the profile enum's case count,
// and neither can be widened by editing one call site.

/// Fixed policy around profile selection.
public enum SPFNAuthPolicy
{
    /// The complete allowlist. Must stay equal to `SPFNAuthProfile.allCases`.
    public static let allowedProfiles: [SPFNAuthProfile] = [.clientProofV1]

    /// The only profile a client may default to.
    public static let defaultProfile: SPFNAuthProfile = .clientProofV1

}

/// Auth failures, each carrying the error code it surfaces as — which is not the same as
/// each carrying a code the contract declares.
///
/// `controlCharacterInProofField` is the exception and stays one. Its `PROOF_INPUT_INVALID`
/// is a LOCAL code: the pinned bundle does not declare it, no server ever sends it, and
/// nothing reaches a server to be refused by it, because the refusal happens while the
/// proof input is being assembled. It is spelled in the contract's style so a log reads
/// the same either way; a conformance fixture will not find it, and should not look.
///
/// Every other case does name a code the bundle declares, and is the client-side spelling
/// of the refusal a server answers with.
public enum SPFNAuthError: Error, Equatable, Sendable
{
    /// The server or caller named a profile outside the allowlist.
    case unknownProfileRejected(String)

    /// A proof-input field contained a C0 control character, which would make the
    /// newline-separated canonical form ambiguous.
    case controlCharacterInProofField(String)

    /// The presented proof did not verify: not a 128-hex raw r ‖ s signature, or a
    /// signature the named public key rejects.
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
