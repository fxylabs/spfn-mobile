// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.1.0-dev
// bundle:          Contracts/spfn-mobile-contract.v1.json
// bundleSha256:    07fd82683576e3343753b590e00b5bf9725b2e598e1e5e6282f251e73a433e45
// contractVersion: 1.0.0-dev.1
// origin:          spfn-mobile-step2-dev-bundle
//
// The bundle was hand-authored in this repository and was NOT exported by SPFN primitives CI.
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
