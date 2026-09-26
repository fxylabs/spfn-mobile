// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    8cce6d896e200a18e1312f23ed63ff4fc36b4ff6ea484f576c3de824be3b589e
// contractVersion: 0.13.2
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
    case keyAlgorithmMismatchError = "KeyAlgorithmMismatchError"
    case unverifiedEmailLinkError = "UnverifiedEmailLinkError"
    case invalidSocialTokenError = "InvalidSocialTokenError"
    case accountDisabledError = "AccountDisabledError"
    case accountPendingDeletionError = "AccountPendingDeletionError"
    case registrationRejectedError = "RegistrationRejectedError"
    case keyIdAlreadyRegisteredError = "KeyIdAlreadyRegisteredError"
    case mfaVerificationFailedError = "MfaVerificationFailedError"
    case tooManyRequestsError = "TooManyRequestsError"
    case error = "Error"
    case deviceAuthExpiredError = "DeviceAuthExpiredError"
    case deviceAuthDeniedError = "DeviceAuthDeniedError"
    case deviceAuthNotFoundError = "DeviceAuthNotFoundError"
    case deviceAuthAlreadyHandledError = "DeviceAuthAlreadyHandledError"
    case deviceLinkExpiredError = "DeviceLinkExpiredError"
    case deviceLinkDeniedError = "DeviceLinkDeniedError"
    case deviceLinkNotFoundError = "DeviceLinkNotFoundError"

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
        case .keyAlgorithmMismatchError:
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
        case .mfaVerificationFailedError:
            return .rest
        case .tooManyRequestsError:
            return .rest
        case .error:
            return .rest
        case .deviceAuthExpiredError:
            return .rest
        case .deviceAuthDeniedError:
            return .rest
        case .deviceAuthNotFoundError:
            return .rest
        case .deviceAuthAlreadyHandledError:
            return .rest
        case .deviceLinkExpiredError:
            return .rest
        case .deviceLinkDeniedError:
            return .rest
        case .deviceLinkNotFoundError:
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
        case .keyAlgorithmMismatchError:
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
        case .mfaVerificationFailedError:
            return 401
        case .tooManyRequestsError:
            return 429
        case .error:
            return 500
        case .deviceAuthExpiredError:
            return 400
        case .deviceAuthDeniedError:
            return 403
        case .deviceAuthNotFoundError:
            return 404
        case .deviceAuthAlreadyHandledError:
            return 409
        case .deviceLinkExpiredError:
            return 400
        case .deviceLinkDeniedError:
            return 403
        case .deviceLinkNotFoundError:
            return 404
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
        case .keyAlgorithmMismatchError:
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
        case .mfaVerificationFailedError:
            return false
        case .tooManyRequestsError:
            return true
        case .error:
            return false
        case .deviceAuthExpiredError:
            return false
        case .deviceAuthDeniedError:
            return false
        case .deviceAuthNotFoundError:
            return false
        case .deviceAuthAlreadyHandledError:
            return false
        case .deviceLinkExpiredError:
            return false
        case .deviceLinkDeniedError:
            return false
        case .deviceLinkNotFoundError:
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
