// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    a41a3c06c9d995d4865613daa698c207ba66b53ee5c25a71015c730e7253538d
// contractVersion: 0.3.0
// origin:          spfn-primitives-ci-export
//
// Bundle origin: spfn-primitives-ci-export.
//
// Regenerate with: ./gradlew :contract-codegen:spfnGenerateClients
// Verified by:     ./gradlew :contract-codegen:spfnCodegenVerify

package xyz.superfunction.spfn.generated

import xyz.superfunction.spfn.core.SpfnDecodingException

/**
 * Every error code the contract declares. A code outside this list is rejected
 * rather than mapped onto a neighbouring one.
 */
enum class SpfnGeneratedErrorCode(
    val wireCode: String,
    val httpStatus: Int,
    val isRetryable: Boolean
)
{
    PROOF_INVALID("PROOF_INVALID", 401, false),
    PROOF_REPLAYED("PROOF_REPLAYED", 401, false),
    PROOF_EXPIRED("PROOF_EXPIRED", 401, false),
    SESSION_REVOKED("SESSION_REVOKED", 401, false),
    PROFILE_REJECTED("PROFILE_REJECTED", 400, false),
    CONTRACT_UNSUPPORTED("CONTRACT_UNSUPPORTED", 409, false);

    companion object
    {
        /** Resolves a wire code, or throws with the raw string preserved. */
        fun decode(raw: String): SpfnGeneratedErrorCode =
            entries.firstOrNull { it.wireCode == raw }
                ?: throw SpfnDecodingException("UNKNOWN_ERROR_CODE", "unknown error code '$raw'");
    }
}
