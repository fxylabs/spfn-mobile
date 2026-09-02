// SPFN Mobile — operation and error shapes shared by hand-written and generated code.
//
// Counterpart of Sources/SPFNCore/SPFNOperation.swift. These types are hand-written and
// stable; the per-operation values that fill them in are generated into
// xyz.superfunction.spfn.generated from the pinned bundle.

package xyz.superfunction.spfn.core

/** One contract operation, as the pinned bundle describes it. */
data class SpfnOperation(
    val id: String,
    val method: String,
    val path: String,
    val authProfile: String,
    val requiresSession: Boolean,
    /**
     * Whether the contract declares a response body for this operation.
     *
     * `false` is the contract's own bodyless shape, stated by `restOperations.responseBody`
     * from 0.10.0 on: "An operation that declares no responseType answers 204 with an empty
     * body and there is nothing to decode". [SpfnClient] reads this rather than the
     * operation id, so a later bodyless operation needs no change to the execute path.
     *
     * No default value. Every descriptor states it, because a defaulted `true` would make
     * the bodyless case something a hand-built descriptor forgets rather than something it
     * declares — and a Kotlin default is no default at all to a Java caller, which has to
     * pass every argument anyway.
     */
    val declaresResponse: Boolean
)

/**
 * What an operation that declares no response body answers with.
 *
 * An object rather than [Unit]: `Unit` is what a Kotlin function returns when it returns
 * nothing, so a caller could not tell "the server accepted it and said nothing" — which is
 * a result — from a call that reports nothing at all. A Java caller sees
 * `SpfnNoResponse.INSTANCE`, a named type from this contract, rather than `kotlin.Unit`.
 *
 * Swift's counterpart is the `SPFNNoResponse` struct, for the same reason on that side.
 */
object SpfnNoResponse

/** Decoding failures shared by generated response types. */
class SpfnDecodingException(val code: String, message: String) : IllegalArgumentException(message)

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

/**
 * Field readers used by generated decoders. Kept here so generated code stays a thin,
 * obviously-correct listing of the contract rather than a place where logic hides.
 */
object SpfnDecoding
{
    fun obj(value: SpfnCanonicalValue?, path: String): Map<String, SpfnCanonicalValue>
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            throw SpfnDecodingException("MISSING_FIELD", "missing field at $path");
        }
        if (value !is SpfnCanonicalValue.Obj)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected object at $path");
        }
        return value.members;
    }

    fun string(value: SpfnCanonicalValue?, path: String): String
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            throw SpfnDecodingException("MISSING_FIELD", "missing field at $path");
        }
        if (value !is SpfnCanonicalValue.Text)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected string at $path");
        }
        return value.value;
    }

    fun optionalString(value: SpfnCanonicalValue?, path: String): String?
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            return null;
        }
        if (value !is SpfnCanonicalValue.Text)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected string at $path");
        }
        return value.value;
    }

    fun integer(value: SpfnCanonicalValue?, path: String): Long
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            throw SpfnDecodingException("MISSING_FIELD", "missing field at $path");
        }
        if (value !is SpfnCanonicalValue.Integer)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected integer at $path");
        }
        return value.value;
    }

    fun optionalInteger(value: SpfnCanonicalValue?, path: String): Long?
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            return null;
        }
        if (value !is SpfnCanonicalValue.Integer)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected integer at $path");
        }
        return value.value;
    }

    fun boolean(value: SpfnCanonicalValue?, path: String): Boolean
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            throw SpfnDecodingException("MISSING_FIELD", "missing field at $path");
        }
        if (value !is SpfnCanonicalValue.Bool)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected boolean at $path");
        }
        return value.value;
    }

    fun array(value: SpfnCanonicalValue?, path: String): List<SpfnCanonicalValue>
    {
        if (value == null || value is SpfnCanonicalValue.Null)
        {
            throw SpfnDecodingException("MISSING_FIELD", "missing field at $path");
        }
        if (value !is SpfnCanonicalValue.Arr)
        {
            throw SpfnDecodingException("TYPE_MISMATCH", "expected array at $path");
        }
        return value.elements;
    }
}
