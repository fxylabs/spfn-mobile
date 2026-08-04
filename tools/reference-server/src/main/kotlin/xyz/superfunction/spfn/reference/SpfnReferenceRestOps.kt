// SPFN Mobile — the REST enrollment surface, as a test fixture implements it.
//
// Contract 0.3.0 adds four operations under /_auth. Two of them are what the SDK flows
// use — the native social enrollment and the key rotation — and those are implemented
// here in the contract's shapes. The other two (register, login) are routed and refused
// explicitly, so a client reaching for them gets an honest "this fixture does not
// implement that" instead of a 404 that reads as a broken URL.
//
// The refusals here are NOT the six contract codes. The bundle's `restOperations`
// section states that the six-code envelope belongs to proven calls only, and an
// unproven call's errors are "the SPFN error shape with HTTP status semantics". So this
// file carries its own small refusal type over the same envelope shape — which is also
// why it does not touch SpfnReferenceRefusal, whose header forbids a seventh code.
//
// The idToken accepted here is a fixed TEST rule, not a verifier:
//
//     spfn-test-idtoken.<provider>.<userId>.<nonce>
//
// The provider segment must equal the path's provider, the nonce segment must equal the
// body's nonce, and the userId becomes the enrolled key's owner. A real server verifies
// a real token; this fixture verifies that the CLIENT sent everything in the right
// place, which is the only thing an SDK test can prove. The same rule is written into
// Contracts/fixtures/enrollment/enrollment.json, so the unit suites and the
// integration matrix drive one grammar.

package xyz.superfunction.spfn.reference

import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnDigest
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import java.util.Base64

/**
 * A refusal on the unproven REST surface: the SPFN error envelope shape carrying a
 * non-contract code, exactly as the bundle's `restOperations.errorEnvelope` clause
 * describes. Every message is a fixed string; nothing a request carried rides in one.
 */
class SpfnReferenceRestRefusal(val code: String, val httpStatus: Int, val message: String)
{
    fun envelopeBytes(requestId: String): ByteArray =
        SpfnCanonicalJson.encode(SpfnErrorEnvelope(code, message, requestId).canonicalValue())

    override fun toString(): String = "SpfnReferenceRestRefusal($code)"

    companion object
    {
        fun badRequest(message: String): SpfnReferenceRestRefusal =
            SpfnReferenceRestRefusal("BAD_REQUEST", 400, message)

        fun authFailed(): SpfnReferenceRestRefusal = SpfnReferenceRestRefusal(
            "AUTH_FAILED",
            401,
            "the id token was not accepted"
        )

        fun notImplemented(): SpfnReferenceRestRefusal = SpfnReferenceRestRefusal(
            "NOT_IMPLEMENTED",
            501,
            "this reference fixture does not implement that operation"
        )
    }
}

/** The fixed test idToken grammar, parsed or refused. */
class SpfnReferenceTestIdToken(val provider: String, val userId: String, val nonce: String)
{
    override fun toString(): String = "SpfnReferenceTestIdToken(redacted)"

    companion object
    {
        private const val PREFIX = "spfn-test-idtoken"

        /**
         * Parses the fixed rule, or null. Null covers every malformation at once: the
         * caller answers AUTH_FAILED without saying which segment failed, the same
         * non-disclosure a real verifier owes a forged token.
         */
        fun parse(idToken: String): SpfnReferenceTestIdToken?
        {
            val segments = idToken.split('.');
            if (segments.size != 4 || segments[0] != PREFIX || segments.any { it.isEmpty() })
            {
                return null;
            }
            return SpfnReferenceTestIdToken(provider = segments[1], userId = segments[2], nonce = segments[3]);
        }
    }
}

object SpfnReferenceRestOps
{
    const val ALGORITHM: String = "ES256"

    sealed interface Result
    {
        class Enrolled(val userId: String, val keyId: String, val isNewUser: Boolean) : Result

        class Refused(val refusal: SpfnReferenceRestRefusal) : Result
    }

    /**
     * The fake native social enrollment: checks everything the client is responsible
     * for placing — token grammar, provider and nonce binding, fingerprint over the
     * exact SPKI bytes, the algorithm name — then registers the key under the token's
     * userId.
     */
    fun oauthNative(
        state: SpfnReferenceState,
        provider: String,
        idToken: String,
        nonce: String,
        publicKeyBase64: String,
        keyId: String,
        fingerprint: String,
        algorithm: String
    ): Result
    {
        val token = SpfnReferenceTestIdToken.parse(idToken)
            ?: return Result.Refused(SpfnReferenceRestRefusal.authFailed());
        if (token.provider != provider || token.nonce != nonce)
        {
            return Result.Refused(SpfnReferenceRestRefusal.authFailed());
        }

        val spkiDer = try
        {
            Base64.getDecoder().decode(publicKeyBase64)
        }
        catch (_: IllegalArgumentException)
        {
            return Result.Refused(SpfnReferenceRestRefusal.badRequest("publicKey is not base64"));
        };
        if (algorithm != ALGORITHM)
        {
            return Result.Refused(SpfnReferenceRestRefusal.badRequest("algorithm must be $ALGORITHM"));
        }
        // The fingerprint binds the base64 the server stores to the bytes the client
        // hashed; a mismatch means the two halves of the request disagree about the key.
        if (fingerprint != SpfnDigest.sha256Hex(spkiDer))
        {
            return Result.Refused(SpfnReferenceRestRefusal.badRequest("fingerprint is not the SHA-256 of publicKey"));
        }
        // The contract's `nativeEnrollment.nonceRule`, and the check the real server does
        // in `assertNonceBindsPublicKey`. Both halves are needed: the fingerprint check
        // above alone would let any nonce through, and this one alone would accept a
        // fingerprint that is not the key's hash. An id_token travels, so without this a
        // stolen one could be paired with the thief's key.
        if (nonce != fingerprint)
        {
            return Result.Refused(SpfnReferenceRestRefusal.badRequest("nonce must equal fingerprint"));
        }

        val isNewUser = try
        {
            state.enrollKey(keyId, spkiDer, ownerId = token.userId)
        }
        catch (_: IllegalArgumentException)
        {
            return Result.Refused(SpfnReferenceRestRefusal.badRequest("publicKey is not a P-256 key, or keyId exists"));
        };
        return Result.Enrolled(userId = token.userId, keyId = keyId, isNewUser = isNewUser);
    }

    /**
     * The proven rotation body checks. Admission — the old key's proof, the ownership
     * rule — has already run; what is judged here is the replacement key itself.
     * Returns null when the rotation applied, or the refusal.
     */
    fun rotate(
        state: SpfnReferenceState,
        oldKeyId: String,
        publicKeyBase64: String,
        newKeyId: String,
        fingerprint: String,
        algorithm: String
    ): SpfnReferenceRestRefusal?
    {
        val spkiDer = try
        {
            Base64.getDecoder().decode(publicKeyBase64)
        }
        catch (_: IllegalArgumentException)
        {
            return SpfnReferenceRestRefusal.badRequest("publicKey is not base64");
        };
        if (algorithm != ALGORITHM)
        {
            return SpfnReferenceRestRefusal.badRequest("algorithm must be $ALGORITHM");
        }
        if (fingerprint != SpfnDigest.sha256Hex(spkiDer))
        {
            return SpfnReferenceRestRefusal.badRequest("fingerprint is not the SHA-256 of publicKey");
        }

        val applied = try
        {
            state.rotateKey(oldKeyId = oldKeyId, newKeyId = newKeyId, publicKeySpkiDer = spkiDer)
        }
        catch (_: IllegalArgumentException)
        {
            return SpfnReferenceRestRefusal.badRequest("publicKey is not a P-256 key");
        };
        return if (applied)
        {
            null
        }
        else
        {
            SpfnReferenceRestRefusal.badRequest("the replacement keyId is not usable");
        };
    }
}
