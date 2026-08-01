// SPFN Mobile — Step 1 scaffold. No behaviour, no release promise.

/// The auth profiles this SDK is permitted to expose.
///
/// v1 is single-profile by decision. Adding a case here is a security-boundary
/// change, not a refactor: `tools/validate/validate.sh` asserts this enum has
/// exactly one case, and fails if redirect-based auth vocabulary appears anywhere
/// in the API surface.
public enum SPFNAuthProfile: String, CaseIterable, Sendable
{
    case clientProofV1
}
