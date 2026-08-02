// SPFN Mobile — what one executed request can fail with.
//
// Four classes, and the class is decided by the error code the contract declares, never
// by the HTTP status. A proxy can answer 401 with a body no SPFN server wrote; reading
// that as an auth failure would make the client re-handshake against something that
// never asked it to. So the status is carried for diagnosis and the code decides.
//
// Every value here is safe to print. Server-chosen text lives only inside
// [SpfnErrorEnvelope], which redacts itself, and the failure types below carry nothing
// else the server wrote — which matters more here than in Swift, because a `Throwable`'s
// message is printed by `toString` and by every stack trace.
//
// Sources/SPFNClient/SPFNClientError.swift is the same taxonomy in Swift.

package xyz.superfunction.spfn.client

import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode

/**
 * Why a response could not be read as the contract describes it.
 *
 * Every entry is a constant. A reason assembled from the body — a field path the server
 * chose, an unrecognised code spelled out — would put server text into a stack trace, so
 * no entry carries one.
 */
enum class SpfnDecodingFailure
{
    /** The body was not canonical JSON, whatever the status said. */
    NOT_CANONICAL_JSON,

    /** A 2xx body parsed, but was not the response type the operation declares. */
    NOT_THE_DECLARED_RESPONSE,

    /** A non-2xx body parsed, but was not an SPFN error envelope. */
    NOT_AN_ERROR_ENVELOPE,

    /** An envelope arrived carrying a code this contract does not declare. */
    UNKNOWN_ERROR_CODE
}

/**
 * A refusal the server authenticated the request on.
 *
 * One of these is the only thing that makes `execute` re-handshake, and it does so at most
 * once per call.
 *
 * Deliberately not a `data class`: the generated `toString` would print [envelope]'s
 * fields through it.
 */
class SpfnAuthFailure(
    /** The declared code, resolved from the envelope. Always one of the auth family. */
    val code: SpfnGeneratedErrorCode,
    /**
     * The status the server actually answered with, which may differ from
     * [SpfnGeneratedErrorCode.httpStatus] — the contract states what a code is supposed to
     * arrive as, and a disagreement is worth seeing rather than smoothing over.
     */
    val httpStatus: Int,
    /** The envelope as it arrived. Server-chosen text; it never prints itself. */
    val envelope: SpfnErrorEnvelope
)
{
    override fun equals(other: Any?): Boolean =
        other is SpfnAuthFailure &&
            other.code == code &&
            other.httpStatus == httpStatus &&
            other.envelope == envelope

    override fun hashCode(): Int = (31 * (31 * code.hashCode() + httpStatus)) + envelope.hashCode()

    override fun toString(): String =
        "SpfnAuthFailure(code=${code.wireCode}, httpStatus=$httpStatus, envelope=redacted)"
}

/** A refusal on any ground other than authentication. */
class SpfnServerFailure(
    /** The declared code, resolved from the envelope. */
    val code: SpfnGeneratedErrorCode,
    /** The status the server actually answered with. See [SpfnAuthFailure.httpStatus]. */
    val httpStatus: Int,
    /** The envelope as it arrived. Server-chosen text; it never prints itself. */
    val envelope: SpfnErrorEnvelope
)
{
    override fun equals(other: Any?): Boolean =
        other is SpfnServerFailure &&
            other.code == code &&
            other.httpStatus == httpStatus &&
            other.envelope == envelope

    override fun hashCode(): Int = (31 * (31 * code.hashCode() + httpStatus)) + envelope.hashCode()

    override fun toString(): String =
        "SpfnServerFailure(code=${code.wireCode}, httpStatus=$httpStatus, envelope=redacted)"
}

/**
 * Everything [SpfnClient.execute] classifies.
 *
 * It is not everything `execute` can throw. `CancellationException` is never one of these
 * — wrapping it would break every enclosing coroutine's idea of what cancelled means — and
 * an error the path did not produce, such as a `SpfnAuthException` raised while assembling
 * a proof, passes through unchanged rather than being flattened into one of these: a
 * client-side assembly bug dressed as a server failure is read as a server failure.
 */
sealed class SpfnClientError(message: String, cause: Throwable? = null) : Exception(message, cause)
{
    /**
     * No response existed. Cancellation of the underlying call arrives here as
     * [SpfnTransportError.Cancelled], the same way it does when the transport is the one
     * that observes it; cancellation of the calling coroutine does not.
     */
    class Transport(val error: SpfnTransportError) :
        SpfnClientError("the request never reached a server", error)

    /** The server refused the request's authentication. */
    class Auth(val failure: SpfnAuthFailure) :
        SpfnClientError("the server refused this request's authentication: ${failure.code.wireCode}")

    /** The server refused the request on contract grounds. */
    class Server(val failure: SpfnServerFailure) :
        SpfnClientError("the server refused this request: ${failure.code.wireCode}")

    /** A response arrived that the contract cannot describe. */
    class Decoding(val failure: SpfnDecodingFailure) :
        SpfnClientError("the response was not what the contract describes: $failure")

    /**
     * The operation does not go through `execute`. Only the handshake is in this position:
     * it is what opens the session every other operation presents, so running it here would
     * send it without the session bookkeeping that gives it its point. [operationId] is the
     * contract operation id, not anything a server sent.
     */
    class UnsupportedOperation(val operationId: String) :
        SpfnClientError("operation '$operationId' does not go through execute")
}

/**
 * True for the codes a re-handshake could plausibly clear.
 *
 * Written as an exhaustive `when` rather than a set or a status comparison: a code added to
 * the contract stops this file compiling until someone decides which side of the line it
 * falls on. An `else` here would silently classify every future code as a server failure.
 */
fun SpfnGeneratedErrorCode.isAuthFailure(): Boolean = when (this)
{
    SpfnGeneratedErrorCode.PROOF_INVALID,
    SpfnGeneratedErrorCode.PROOF_REPLAYED,
    SpfnGeneratedErrorCode.PROOF_EXPIRED,
    SpfnGeneratedErrorCode.SESSION_REVOKED -> true

    SpfnGeneratedErrorCode.PROFILE_REJECTED,
    SpfnGeneratedErrorCode.CONTRACT_UNSUPPORTED -> false
}
