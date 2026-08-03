// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.1.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    28f2fd4cf37ef903dd9746d4058d510435b3905b9b94312f6e95120ad3603084
// contractVersion: 0.2.0
// origin:          spfn-primitives-ci-export
//
// Bundle origin: spfn-primitives-ci-export.
//
// Regenerate with: ./gradlew :contract-codegen:spfnGenerateClients
// Verified by:     ./gradlew :contract-codegen:spfnCodegenVerify

import SPFNCore

/// Every error code the contract declares. A code outside this list is rejected
/// rather than mapped onto a neighbouring one.
public enum SPFNGeneratedErrorCode: String, CaseIterable, Sendable
{
    case proofInvalid = "PROOF_INVALID"
    case proofReplayed = "PROOF_REPLAYED"
    case proofExpired = "PROOF_EXPIRED"
    case sessionRevoked = "SESSION_REVOKED"
    case profileRejected = "PROFILE_REJECTED"
    case contractUnsupported = "CONTRACT_UNSUPPORTED"

    public var httpStatus: Int
    {
        switch self
        {
        case .proofInvalid:
            return 401
        case .proofReplayed:
            return 401
        case .proofExpired:
            return 401
        case .sessionRevoked:
            return 401
        case .profileRejected:
            return 400
        case .contractUnsupported:
            return 409
        }
    }

    public var isRetryable: Bool
    {
        switch self
        {
        case .proofInvalid:
            return false
        case .proofReplayed:
            return false
        case .proofExpired:
            return false
        case .sessionRevoked:
            return false
        case .profileRejected:
            return false
        case .contractUnsupported:
            return false
        }
    }

    /// Resolves a wire code, or throws with the raw string preserved.
    public static func decode(_ raw: String) throws -> SPFNGeneratedErrorCode
    {
        guard let code = SPFNGeneratedErrorCode(rawValue: raw)
        else
        {
            throw SPFNDecodingError.unknownErrorCode(raw)
        }
        return code
    }
}
