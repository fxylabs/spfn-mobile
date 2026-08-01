// SPFN Mobile — clientProofV1, algorithm SPFN-PROOF-INPUT-1.
//
// Counterpart of Sources/SPFNAuth/SPFNClientProof.swift. The proof input is a
// newline-joined list of eight fields in a fixed order, so a C0 control character in
// any field is an error rather than something to escape: an escaping scheme is one more
// thing two platforms can disagree about.

package xyz.superfunction.spfn.auth

import xyz.superfunction.spfn.core.SpfnDigest

/** The fields the proof is taken over, in the order the contract fixes. */
data class SpfnProofInput(
    val method: String,
    val path: String,
    val clientId: String,
    val keyId: String,
    val nonce: String,
    val issuedAtMillis: Long,
    val bodySha256: String
)
{
    companion object
    {
        /**
         * Builds the input for a request whose body is already canonical. An operation
         * with no body carries the absent-body digest rather than the digest of `""`.
         */
        fun forRequest(
            method: String,
            path: String,
            clientId: String,
            keyId: String,
            nonce: String,
            issuedAtMillis: Long,
            canonicalBody: ByteArray?
        ): SpfnProofInput = SpfnProofInput(
            method = method,
            path = path,
            clientId = clientId,
            keyId = keyId,
            nonce = nonce,
            issuedAtMillis = issuedAtMillis,
            bodySha256 = if (canonicalBody == null) SpfnDigest.ABSENT_BODY_DIGEST else SpfnDigest.sha256Hex(canonicalBody)
        )
    }
}

object SpfnClientProof
{
    /** The only profile name that ever appears in a proof input. */
    val PROFILE_NAME: String = SpfnAuthProfile.CLIENT_PROOF_V1.wireName

    /** The field order the contract fixes, named so a test can assert on it directly. */
    val PROOF_INPUT_FIELDS: List<String> = listOf(
        "profile",
        "method",
        "path",
        "clientId",
        "keyId",
        "nonce",
        "issuedAtMillis",
        "bodySha256"
    )

    /** The canonical proof input string, before any MAC is applied. */
    fun canonicalString(input: SpfnProofInput): String
    {
        val values = listOf(
            "profile" to PROFILE_NAME,
            "method" to input.method,
            "path" to input.path,
            "clientId" to input.clientId,
            "keyId" to input.keyId,
            "nonce" to input.nonce,
            "issuedAtMillis" to input.issuedAtMillis.toString(),
            "bodySha256" to input.bodySha256
        );

        for ((field, value) in values)
        {
            if (value.any { it.code < 0x20 })
            {
                throw SpfnAuthException.controlCharacterInProofField(field);
            }
        }

        return values.joinToString("\n") { it.second };
    }

    /** The canonical proof input bytes. This is what the digest and the MAC cover. */
    fun canonicalBytes(input: SpfnProofInput): ByteArray = canonicalString(input).toByteArray(Charsets.UTF_8)

    /**
     * SHA-256 of the canonical proof input, as lowercase base16.
     *
     * Carries no secret, so it is the value the conformance fixtures use to prove Swift
     * and Kotlin agree byte for byte before any key is involved.
     */
    fun canonicalDigest(input: SpfnProofInput): String = SpfnDigest.sha256Hex(canonicalBytes(input))

    /** The proof itself: HMAC-SHA-256 over the canonical input, as lowercase base16. */
    fun proof(input: SpfnProofInput, key: ByteArray): String =
        SpfnDigest.hmacSha256Hex(key, canonicalBytes(input))

    /** Verifies a presented proof without leaking where it first differs. */
    fun verify(presented: String, input: SpfnProofInput, key: ByteArray)
    {
        val expected = proof(input, key);
        if (!SpfnDigest.constantTimeEquals(expected, presented))
        {
            throw SpfnAuthException.proofInvalid();
        }
    }
}

/**
 * Replay and revocation state for a verifier.
 *
 * Check order is part of the contract: a revoked key is rejected before the proof is
 * verified, which keeps revocation distinguishable from a bad proof instead of
 * collapsing both into one opaque failure.
 */
class SpfnProofAcceptance(
    val replayWindowMillis: Long,
    revokedKeyIds: Set<String> = emptySet()
)
{
    private val revoked: MutableSet<String> = revokedKeyIds.toMutableSet()
    private val seen: MutableSet<String> = mutableSetOf()

    fun revoke(keyId: String)
    {
        revoked.add(keyId);
    }

    /** Admits one proof presentation, or throws the reason it was refused. */
    fun admit(presented: String, input: SpfnProofInput, key: ByteArray, nowMillis: Long)
    {
        if (revoked.contains(input.keyId))
        {
            throw SpfnAuthException.sessionRevoked();
        }

        val age = nowMillis - input.issuedAtMillis;
        if (age < 0 || age > replayWindowMillis)
        {
            throw SpfnAuthException.proofExpired();
        }

        val replayKey = "${input.clientId}\u001F${input.nonce}";
        if (seen.contains(replayKey))
        {
            throw SpfnAuthException.proofReplayed();
        }

        SpfnClientProof.verify(presented, input, key);
        seen.add(replayKey);
    }
}
