// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    cf1b34a4081059c29f838b9e8b3a973a9fbb5e5e64a576c673f792d9c6b4ca46
// contractVersion: 0.9.0
// origin:          spfn-primitives-ci-export
//
// Bundle origin: spfn-primitives-ci-export.
//
// Regenerate with: ./gradlew :contract-codegen:spfnGenerateClients
// Verified by:     ./gradlew :contract-codegen:spfnCodegenVerify

package xyz.superfunction.spfn.generated

import xyz.superfunction.spfn.core.SpfnDecodingException

/**
 * Which surface answers with a given code.
 *
 * The contract carries both sets in one list and they are not interchangeable: a
 * proven call can be met by a clientProofV1 refusal and never by a rest one, and a
 * call to the /_auth surface is the reverse. Code that reasons about a refusal
 * reads this rather than the position a code happened to have.
 */
enum class SpfnGeneratedErrorSurface(val wireValue: String)
{
    CLIENT_PROOF_V1("clientProofV1"),
    REST("rest");
}

/**
 * Every error code the contract declares. A code outside this list is rejected
 * rather than mapped onto a neighbouring one.
 */
enum class SpfnGeneratedErrorCode(
    val wireCode: String,
    val httpStatus: Int,
    val isRetryable: Boolean,
    val surface: SpfnGeneratedErrorSurface
)
{
    PROOF_INVALID("PROOF_INVALID", 401, false, SpfnGeneratedErrorSurface.CLIENT_PROOF_V1),
    PROOF_REPLAYED("PROOF_REPLAYED", 401, false, SpfnGeneratedErrorSurface.CLIENT_PROOF_V1),
    PROOF_EXPIRED("PROOF_EXPIRED", 401, false, SpfnGeneratedErrorSurface.CLIENT_PROOF_V1),
    SESSION_REVOKED("SESSION_REVOKED", 401, false, SpfnGeneratedErrorSurface.CLIENT_PROOF_V1),
    PROFILE_REJECTED("PROFILE_REJECTED", 400, false, SpfnGeneratedErrorSurface.CLIENT_PROOF_V1),
    CONTRACT_UNSUPPORTED("CONTRACT_UNSUPPORTED", 409, false, SpfnGeneratedErrorSurface.CLIENT_PROOF_V1),
    ValidationError("ValidationError", 400, false, SpfnGeneratedErrorSurface.REST),
    NativeSignInUnsupportedError("NativeSignInUnsupportedError", 400, false, SpfnGeneratedErrorSurface.REST),
    NonceKeyBindingError("NonceKeyBindingError", 400, false, SpfnGeneratedErrorSurface.REST),
    InvalidKeyFingerprintError("InvalidKeyFingerprintError", 400, false, SpfnGeneratedErrorSurface.REST),
    UnverifiedEmailLinkError("UnverifiedEmailLinkError", 400, false, SpfnGeneratedErrorSurface.REST),
    InvalidSocialTokenError("InvalidSocialTokenError", 401, false, SpfnGeneratedErrorSurface.REST),
    AccountDisabledError("AccountDisabledError", 403, false, SpfnGeneratedErrorSurface.REST),
    AccountPendingDeletionError("AccountPendingDeletionError", 403, false, SpfnGeneratedErrorSurface.REST),
    RegistrationRejectedError("RegistrationRejectedError", 403, false, SpfnGeneratedErrorSurface.REST),
    KeyIdAlreadyRegisteredError("KeyIdAlreadyRegisteredError", 409, false, SpfnGeneratedErrorSurface.REST),
    TooManyRequestsError("TooManyRequestsError", 429, true, SpfnGeneratedErrorSurface.REST),
    Error("Error", 500, false, SpfnGeneratedErrorSurface.REST);

    companion object
    {
        /** Resolves a wire code, or throws with the raw string preserved. */
        fun decode(raw: String): SpfnGeneratedErrorCode =
            entries.firstOrNull { it.wireCode == raw }
                ?: throw SpfnDecodingException("UNKNOWN_ERROR_CODE", "unknown error code '$raw'");
    }
}
