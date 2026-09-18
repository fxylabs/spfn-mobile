// SPFN Mobile — auth profile policy. v1 is single-profile by decision.
//
// The allowlist is two constants and no resolver. There used to be a `resolve(profileName)`
// that turned a wire name into a profile or refused it, and nothing in the SDK ever called
// it: no operation names its class as free text — `SpfnGeneratedAuthClass.of`
// reads the pinned bundle and `execute` refuses a class it does not know — so the function
// was answered only by its own test. What holds the boundary is not a function anyway:
// tools/validate/validate.sh pins the allowlist literal and the profile enum's case count,
// and neither can be widened by editing one call site.
//
// Counterpart of Sources/SPFNAuth/SPFNAuthProfile.swift and SPFNAuthPolicy.swift.

package xyz.superfunction.spfn.auth

/** Mirror of the Swift `SPFNAuthProfile`. Adding an entry is a security-boundary change. */
enum class SpfnAuthProfile(val wireName: String)
{
    CLIENT_PROOF_V1("clientProofV1")
}

/**
 * Auth failures, each carrying the error code it surfaces as — which is not the same as
 * each carrying a code the contract declares. Mirror of the Swift `SPFNAuthError`.
 *
 * [controlCharacterInProofField] is the exception and stays one. Its `PROOF_INPUT_INVALID`
 * is a LOCAL code: the pinned bundle does not declare it, no server ever sends it, and
 * nothing reaches a server to be refused by it, because the refusal happens while the proof
 * input is being assembled. It is spelled in the contract's style so a log reads the same
 * either way; a conformance fixture will not find it, and should not look.
 *
 * Every other factory does name a code the bundle declares, and is the client-side spelling
 * of the refusal a server answers with.
 */
class SpfnAuthException(val code: String, message: String) : IllegalArgumentException(message)
{
    companion object
    {
        fun unknownProfileRejected(profileName: String): SpfnAuthException =
            SpfnAuthException("PROFILE_REJECTED", "auth profile '$profileName' is not allowlisted")

        fun controlCharacterInProofField(field: String): SpfnAuthException =
            SpfnAuthException(
                "PROOF_INPUT_INVALID",
                "proof field '$field' contains a control character, which would make the canonical form ambiguous"
            )

        fun proofInvalid(): SpfnAuthException =
            SpfnAuthException("PROOF_INVALID", "the presented proof did not verify")

        fun proofReplayed(): SpfnAuthException =
            SpfnAuthException("PROOF_REPLAYED", "the nonce was already spent inside the replay window")

        fun proofExpired(): SpfnAuthException =
            SpfnAuthException("PROOF_EXPIRED", "issuedAtMillis falls outside the replay window")

        fun sessionRevoked(): SpfnAuthException =
            SpfnAuthException("SESSION_REVOKED", "the key or session was revoked")
    }
}

/** Mirror of the Swift `SPFNAuthPolicy`. */
object SpfnAuthPolicy
{
    val ALLOWED_PROFILES: List<SpfnAuthProfile> = listOf(SpfnAuthProfile.CLIENT_PROOF_V1)

    val DEFAULT_PROFILE: SpfnAuthProfile = SpfnAuthProfile.CLIENT_PROOF_V1

}
