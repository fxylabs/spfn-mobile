// SPFN Mobile — the error envelope every SPFN endpoint answers with.
//
// Counterpart of Sources/SPFNCore/SPFNErrorEnvelope.swift. Hand-written and stable, like
// the operation descriptor beside it: the envelope is the one response shape that is not
// per-operation, so nothing generates it. It sits in core rather than in the client
// because generated decoders read it, and the hand-written `toString` stays in the same
// file as the type because every field is text a server chose — separating the two would
// leave a class one `data` keyword away from printing a payload into every stack trace.

package xyz.superfunction.spfn.core

/**
 * The canonical error envelope every SPFN endpoint answers with.
 *
 * Every field is text a server chose. A server can put anything in [message] or
 * [requestId] — including a session identifier it echoed back — so none of them may
 * reach a log by default.
 *
 * Deliberately not a `data class`: the generated `toString` would print all three, and
 * a `Throwable` carrying one prints its message into every stack trace. `equals` and
 * `hashCode` are written out by hand so nothing else changes, and the fields stay
 * ordinary public properties, so classifying an error is unaffected.
 */
class SpfnErrorEnvelope(
    val code: String,
    val message: String,
    val requestId: String
)
{
    override fun equals(other: Any?): Boolean =
        other is SpfnErrorEnvelope &&
            other.code == code &&
            other.message == message &&
            other.requestId == requestId

    override fun hashCode(): Int = (31 * (31 * code.hashCode() + message.hashCode())) + requestId.hashCode()

    override fun toString(): String = "SpfnErrorEnvelope(code=redacted, message=redacted, requestId=redacted)"

    /** The canonical form of this envelope, so a client can assert on exact bytes. */
    fun canonicalValue(): SpfnCanonicalValue = SpfnCanonicalValue.Obj(
        mapOf(
            "error" to SpfnCanonicalValue.Obj(
                mapOf(
                    "code" to SpfnCanonicalValue.Text(code),
                    "message" to SpfnCanonicalValue.Text(message),
                    "requestId" to SpfnCanonicalValue.Text(requestId)
                )
            )
        )
    )

    companion object
    {
        /**
         * Reads the envelope out of a parsed response body.
         *
         * An unrecognised code is not mapped onto a neighbouring one — that is the
         * generated `SpfnGeneratedErrorCode`'s job, and it rejects instead of guessing.
         */
        fun decode(value: SpfnCanonicalValue): SpfnErrorEnvelope
        {
            val root = SpfnDecoding.obj(value, "\$");
            val error = SpfnDecoding.obj(root["error"] ?: SpfnCanonicalValue.Null, "\$.error");
            return SpfnErrorEnvelope(
                code = SpfnDecoding.string(error["code"], "\$.error.code"),
                message = SpfnDecoding.string(error["message"], "\$.error.message"),
                requestId = SpfnDecoding.string(error["requestId"], "\$.error.requestId")
            );
        }
    }
}
