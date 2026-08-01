// SPFN Mobile — auth profile policy. v1 is single-profile by decision.
//
// Counterpart of Sources/SPFNAuth/SPFNAuthProfile.swift and SPFNAuthPolicy.swift.

package xyz.superfunction.spfn.auth

/** Mirror of the Swift `SPFNAuthProfile`. Adding an entry is a security-boundary change. */
enum class SpfnAuthProfile(val wireName: String)
{
    CLIENT_PROOF_V1("clientProofV1")
}

/**
 * Auth failures. Each carries the contract error code it surfaces as, so a client and
 * the conformance fixtures name the same outcome. Mirror of the Swift `SPFNAuthError`.
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

    /**
     * An unrecognised profile name is rejected. There is no fallback profile and no
     * mixing of profiles within a session (topology artifact §6).
     */
    fun resolve(profileName: String): SpfnAuthProfile
    {
        return ALLOWED_PROFILES.firstOrNull { it.wireName == profileName }
            ?: throw SpfnAuthException.unknownProfileRejected(profileName);
    }
}
