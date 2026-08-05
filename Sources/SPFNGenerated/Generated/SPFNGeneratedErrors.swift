// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    0a91612158aaf9917be8487cf70e1df9ab4c12ac6c1106973afa99122e458795
// contractVersion: 0.6.0
// origin:          spfn-primitives-ci-export
//
// Bundle origin: spfn-primitives-ci-export.
//
// Regenerate with: ./gradlew :contract-codegen:spfnGenerateClients
// Verified by:     ./gradlew :contract-codegen:spfnCodegenVerify

import SPFNCore

/// Which surface answers with a given code.
///
/// The contract carries both sets in one list and they are not interchangeable: a
/// proven call can be met by a `clientProofV1` refusal and never by a `rest` one,
/// and a call to the /_auth surface is the reverse. Code that reasons about a
/// refusal reads this rather than the position a code happened to have.
public enum SPFNGeneratedErrorSurface: String, CaseIterable, Sendable
{
    case clientProofV1 = "clientProofV1"
    case rest = "rest"
}

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
    case validationError = "ValidationError"
    case nativeSignInUnsupportedError = "NativeSignInUnsupportedError"
    case nonceKeyBindingError = "NonceKeyBindingError"
    case invalidKeyFingerprintError = "InvalidKeyFingerprintError"
    case unverifiedEmailLinkError = "UnverifiedEmailLinkError"
    case invalidSocialTokenError = "InvalidSocialTokenError"
    case accountDisabledError = "AccountDisabledError"
    case accountPendingDeletionError = "AccountPendingDeletionError"
    case registrationRejectedError = "RegistrationRejectedError"
    case keyIdAlreadyRegisteredError = "KeyIdAlreadyRegisteredError"
    case tooManyRequestsError = "TooManyRequestsError"
    case error = "Error"

    /// The surface that answers with this code.
    public var surface: SPFNGeneratedErrorSurface
    {
        switch self
        {
        case .proofInvalid:
            return .clientProofV1
        case .proofReplayed:
            return .clientProofV1
        case .proofExpired:
            return .clientProofV1
        case .sessionRevoked:
            return .clientProofV1
        case .profileRejected:
            return .clientProofV1
        case .contractUnsupported:
            return .clientProofV1
        case .validationError:
            return .rest
        case .nativeSignInUnsupportedError:
            return .rest
        case .nonceKeyBindingError:
            return .rest
        case .invalidKeyFingerprintError:
            return .rest
        case .unverifiedEmailLinkError:
            return .rest
        case .invalidSocialTokenError:
            return .rest
        case .accountDisabledError:
            return .rest
        case .accountPendingDeletionError:
            return .rest
        case .registrationRejectedError:
            return .rest
        case .keyIdAlreadyRegisteredError:
            return .rest
        case .tooManyRequestsError:
            return .rest
        case .error:
            return .rest
        }
    }

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
        case .validationError:
            return 400
        case .nativeSignInUnsupportedError:
            return 400
        case .nonceKeyBindingError:
            return 400
        case .invalidKeyFingerprintError:
            return 400
        case .unverifiedEmailLinkError:
            return 400
        case .invalidSocialTokenError:
            return 401
        case .accountDisabledError:
            return 403
        case .accountPendingDeletionError:
            return 403
        case .registrationRejectedError:
            return 403
        case .keyIdAlreadyRegisteredError:
            return 409
        case .tooManyRequestsError:
            return 429
        case .error:
            return 500
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
        case .validationError:
            return false
        case .nativeSignInUnsupportedError:
            return false
        case .nonceKeyBindingError:
            return false
        case .invalidKeyFingerprintError:
            return false
        case .unverifiedEmailLinkError:
            return false
        case .invalidSocialTokenError:
            return false
        case .accountDisabledError:
            return false
        case .accountPendingDeletionError:
            return false
        case .registrationRejectedError:
            return false
        case .keyIdAlreadyRegisteredError:
            return false
        case .tooManyRequestsError:
            return true
        case .error:
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
