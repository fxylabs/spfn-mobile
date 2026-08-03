// SPFN Mobile — clientProofV1, algorithm SPFN-PROOF-INPUT-1.
//
// Counterpart of Sources/SPFNAuth/SPFNClientProof.swift. The proof input is a
// newline-joined list of eight fields in a fixed order, so a C0 control character in
// any field is an error rather than something to escape: an escaping scheme is one more
// thing two platforms can disagree about.
//
// The proof itself is an ECDSA P-256 signature over those bytes (contract 0.2.0): raw
// r ‖ s, 64 bytes, as base16-lower. Verification is against a registered public key in
// SPKI DER, never against a shared secret. The JCA speaks DER, so both directions go
// through SpfnEcdsa; CryptoKit on the Swift side is already raw and never converts.

package xyz.superfunction.spfn.auth

import xyz.superfunction.spfn.core.SpfnDigest
import java.security.PublicKey
import java.security.Signature

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

    /** The canonical proof input string, before any signature is applied. */
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

    /** The canonical proof input bytes. This is what the signature covers. */
    fun canonicalBytes(input: SpfnProofInput): ByteArray = canonicalString(input).toByteArray(Charsets.UTF_8)

    /**
     * SHA-256 of the canonical proof input, as lowercase base16.
     *
     * Carries no secret, so it is the value the conformance fixtures use to prove Swift
     * and Kotlin agree byte for byte before any key is involved.
     */
    fun canonicalDigest(input: SpfnProofInput): String = SpfnDigest.sha256Hex(canonicalBytes(input))

    /**
     * The proof itself: the signer's raw r ‖ s signature over the canonical input,
     * as lowercase base16 (128 hex characters).
     *
     * [sign] is the provider's one operation, taken as a function so this module never
     * holds a key type. A signer that returns anything but 64 bytes emitted DER — or
     * nothing — and is refused here rather than put on the wire.
     */
    fun proof(input: SpfnProofInput, sign: (ByteArray) -> ByteArray): String
    {
        val signature = sign(canonicalBytes(input));
        if (signature.size != SpfnEcdsa.RAW_SIGNATURE_BYTES)
        {
            throw SpfnAuthException.proofInvalid();
        }
        return hexEncode(signature);
    }

    /**
     * Verifies a presented proof against a registered public key (SPKI DER).
     *
     * Every failure is the same `proofInvalid`: a wire proof that is not exactly 128
     * lowercase hex characters (DER, truncation, uppercase), a key that does not parse,
     * and a signature that does not verify are one answer on purpose, so the refusal
     * discloses nothing about which stage refused.
     */
    fun verify(presented: String, input: SpfnProofInput, publicKeySpkiDer: ByteArray)
    {
        val publicKey = try
        {
            SpfnEcdsa.publicKeyFromSpki(publicKeySpkiDer)
        }
        catch (_: Exception)
        {
            throw SpfnAuthException.proofInvalid();
        };
        verify(presented, input, publicKey);
    }

    /** [verify], for a caller that parsed the key once and verifies per request. */
    fun verify(presented: String, input: SpfnProofInput, publicKey: PublicKey)
    {
        val raw = decodeWireSignature(presented) ?: throw SpfnAuthException.proofInvalid();
        val verified = try
        {
            val verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(canonicalBytes(input));
            verifier.verify(SpfnEcdsa.rawToDer(raw));
        }
        catch (_: Exception)
        {
            false
        };
        if (!verified)
        {
            throw SpfnAuthException.proofInvalid();
        }
    }

    /**
     * The raw signature bytes a wire proof carries, or null when it is not one.
     *
     * Strict on purpose, and byte-range-explicit so this cannot drift from Swift's
     * ASCII-explicit scalar ranges: exactly 128 characters, each one of `0-9a-f`.
     * Uppercase is refused because the contract says base16-lower, and a value only
     * one platform would accept is a disagreement waiting for a server.
     */
    fun decodeWireSignature(presented: String): ByteArray?
    {
        if (presented.length != SpfnEcdsa.RAW_SIGNATURE_BYTES * 2)
        {
            return null;
        }

        val bytes = ByteArray(SpfnEcdsa.RAW_SIGNATURE_BYTES);
        for (index in bytes.indices)
        {
            val high = hexNibble(presented[index * 2]) ?: return null;
            val low = hexNibble(presented[index * 2 + 1]) ?: return null;
            bytes[index] = (high shl 4 or low).toByte();
        }
        return bytes;
    }

    private fun hexNibble(character: Char): Int? = when (character.code)
    {
        in 0x30..0x39 -> character.code - 0x30          // '0'..'9'
        in 0x61..0x66 -> character.code - 0x61 + 10     // 'a'..'f', lowercase only
        else -> null
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    private fun hexEncode(bytes: ByteArray): String
    {
        val out = StringBuilder(bytes.size * 2);
        for (byte in bytes)
        {
            val value = byte.toInt() and 0xFF;
            out.append(HEX_DIGITS[value shr 4]);
            out.append(HEX_DIGITS[value and 0x0F]);
        }
        return out.toString();
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
    fun admit(presented: String, input: SpfnProofInput, publicKeySpkiDer: ByteArray, nowMillis: Long)
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

        SpfnClientProof.verify(presented, input, publicKeySpkiDer);
        seen.add(replayKey);
    }
}
