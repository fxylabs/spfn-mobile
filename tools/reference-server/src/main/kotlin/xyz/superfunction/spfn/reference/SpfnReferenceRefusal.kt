// SPFN Mobile — every way the reference server refuses a request.
//
// The contract declares six error codes and says an unknown one is never mapped onto a
// neighbouring code. That binds a server as much as a client: this file may not invent a
// seventh code, so every refusal below is one of the six, and the ones that are not an
// obvious fit carry the reasoning for the fit that was chosen.
//
// Two rules decide which code a refusal gets.
//
//   1. A refusal a new session could clear is an auth-family code. The client
//      re-handshakes exactly once on those, so putting a malformed request in that family
//      would buy a second handshake that cannot possibly help.
//   2. Everything else — the request is not the shape the contract describes — is
//      CONTRACT_UNSUPPORTED. That code is declared as "the server contract version is
//      outside the range this SDK declares support for", and a request that is not
//      canonical, not routable or missing a contract header is the same disagreement seen
//      from the other side: the two ends do not agree on what the contract is. The
//      alternative candidates were both worse. PROOF_INVALID is an auth-family code and
//      would provoke the pointless re-handshake rule 1 rules out; PROFILE_REJECTED is the
//      only other non-auth code and it names one specific thing — an auth profile outside
//      the allowlist — which this server does use it for, exactly and only.
//
// Every message here is a fixed string. A message assembled from the request would put
// a nonce, a session identifier or a body fragment into an error the client is free to
// log, and the client's own redaction cannot undo that.

package xyz.superfunction.spfn.reference

import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode

/**
 * One refusal, ready to be written as the contract's error envelope.
 *
 * [SpfnGeneratedErrorCode.httpStatus] is the status this server answers with, always.
 * A server free to pick a different status is a server that teaches clients to classify
 * on the status, and the SDKs deliberately classify on the code instead.
 */
class SpfnReferenceRefusal(val code: SpfnGeneratedErrorCode, val message: String)
{
    val httpStatus: Int
        get() = code.httpStatus

    /** The canonical bytes of `{"error":{"code":…,"message":…,"requestId":…}}`. */
    fun envelopeBytes(requestId: String): ByteArray =
        SpfnCanonicalJson.encode(SpfnErrorEnvelope(code.wireCode, message, requestId).canonicalValue())

    /** Nothing server-chosen and nothing request-derived reaches a log through this. */
    override fun toString(): String = "SpfnReferenceRefusal(${code.wireCode})"

    companion object
    {
        // ---- shape: what arrived is not the contract (rule 2) -------------------

        fun unroutable(): SpfnReferenceRefusal = contractViolation(
            "no operation in this contract answers that method and path"
        )

        fun malformedHeaders(): SpfnReferenceRefusal = contractViolation(
            "the request does not carry the contract header fields exactly once each"
        )

        fun missingContentType(): SpfnReferenceRefusal = contractViolation(
            "a request that carries a body must declare the contract content type"
        )

        fun bodyTooLarge(): SpfnReferenceRefusal = contractViolation(
            "the request body exceeds the size this server accepts"
        )

        /**
         * The body parsed but its bytes are not the canonical form of what it parsed to.
         *
         * Not PROOF_INVALID even though it is discovered next to the proof: the proof over
         * these bytes verifies perfectly well, and calling it an auth failure would tell
         * the client to re-handshake and send the same non-canonical bytes again.
         */
        fun bodyNotCanonical(): SpfnReferenceRefusal = contractViolation(
            "the request body is not the canonical JSON form of the value it encodes"
        )

        fun bodyNotTheDeclaredType(): SpfnReferenceRefusal = contractViolation(
            "the request body is not the request type this operation declares"
        )

        fun sessionHeaderMisplaced(): SpfnReferenceRefusal = contractViolation(
            "the session header is present exactly on the operations that require one"
        )

        fun unprocessable(): SpfnReferenceRefusal = contractViolation(
            "the request could not be processed"
        )

        // ---- the profile allowlist ----------------------------------------------

        /** The one thing PROFILE_REJECTED is declared for, used for exactly that. */
        fun profileRejected(): SpfnReferenceRefusal = SpfnReferenceRefusal(
            SpfnGeneratedErrorCode.PROFILE_REJECTED,
            "the named auth profile is not on this contract's allowlist"
        )

        // ---- auth: a new session might clear it (rule 1) --------------------------

        fun sessionRevoked(): SpfnReferenceRefusal = SpfnReferenceRefusal(
            SpfnGeneratedErrorCode.SESSION_REVOKED,
            "the key or session was revoked"
        )

        fun proofExpired(): SpfnReferenceRefusal = SpfnReferenceRefusal(
            SpfnGeneratedErrorCode.PROOF_EXPIRED,
            "issuedAtMillis falls outside the replay window"
        )

        fun proofReplayed(): SpfnReferenceRefusal = SpfnReferenceRefusal(
            SpfnGeneratedErrorCode.PROOF_REPLAYED,
            "the nonce was already used inside the replay window"
        )

        fun proofInvalid(): SpfnReferenceRefusal = SpfnReferenceRefusal(
            SpfnGeneratedErrorCode.PROOF_INVALID,
            "the client proof did not verify"
        )

        private fun contractViolation(message: String): SpfnReferenceRefusal =
            SpfnReferenceRefusal(SpfnGeneratedErrorCode.CONTRACT_UNSUPPORTED, message)
    }
}
