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
    UNKNOWN_ERROR_CODE,

    /**
     * The operation declares no response body and the server sent one anyway. The
     * contract's rule is that such an operation "answers 204 with an empty body and there
     * is nothing to decode", so bytes here mean the two ends disagree about the operation
     * — reading them would be reading a shape nothing declared.
     */
    BODY_ON_NO_RESPONSE_OPERATION,

    /**
     * The operation declares no response body and the server answered 2xx with a status
     * other than 204. Accepted as success it would hide a server that answers a different
     * operation than the one that was called.
     */
    NOT_NO_CONTENT_ON_NO_RESPONSE_OPERATION
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

    /**
     * A response arrived that the contract cannot describe.
     *
     * [onSuccessStatus] is the status band the unreadable answer arrived on, and it is
     * carried because the two bands mean opposite things to a caller that changes server
     * state. A refusal this SDK could not read is still a refusal: nothing was applied. A
     * 2xx this SDK could not read is not a refusal at all — the server answered success
     * and may well have done the thing — so a caller that treats it as one undoes work the
     * server kept. It is a band rather than the status itself: the classification is the
     * only question anybody asks of it, and a number would invite a second rule decided on
     * the status, which is what the rest of this file refuses to do.
     *
     * The band is not derivable from [failure]: [SpfnDecodingFailure.NOT_CANONICAL_JSON] is
     * raised on both paths, and the two session-handshake failures are raised where no
     * operation response exists at all — those carry false, because the request the caller
     * made was never sent.
     */
    class Decoding(val failure: SpfnDecodingFailure, val onSuccessStatus: Boolean) :
        SpfnClientError("the response was not what the contract describes: $failure")

    /**
     * This client and the server that answered do not hold the same contract, so the
     * answer is not read at all. Raised before the response is classified: a server
     * refusing on contract grounds announces its version on that refusal, and reading it
     * as [Server] instead would keep the refusal and lose the reason.
     *
     * [SpfnContractMismatch.serverVersion] is present only when this SDK parsed the
     * announced value as a version, so the no-server-text rule above holds.
     */
    class Contract(val mismatch: SpfnContractMismatch) :
        SpfnClientError(
            "this client and the server do not hold the same contract: ${mismatch.reason}, "
                + "server ${mismatch.serverVersion ?: "<unread>"}, admits ${mismatch.admittedRange}"
        )

    /**
     * The operation does not go through `execute`. Only the handshake is in this position:
     * it is what opens the session every other operation presents, so running it here would
     * send it without the session bookkeeping that gives it its point. [operationId] is the
     * contract operation id, not anything a server sent.
     */
    class UnsupportedOperation(val operationId: String) :
        SpfnClientError("operation '$operationId' does not go through execute")

    /**
     * The operation names an auth class this build's contract does not declare, so
     * nothing was sent. Fail-closed on purpose: the contract's own rule is that an
     * operation is never downgraded to anonymous handling, and an unknown class sent
     * with guessed headers would be exactly that. [authProfile] is the operation's
     * class string from the pinned bundle, not anything a server sent.
     */
    class UndeclaredAuthClass(val authProfile: String) :
        SpfnClientError("auth class '$authProfile' is not declared by this contract")
}

/**
 * True for the codes a re-handshake could plausibly clear.
 *
 * Written as an exhaustive `when` rather than a set or a status comparison: a code added to
 * the contract stops this file compiling until someone decides which side of the line it
 * falls on. An `else` here would silently classify every future code as a server failure.
 *
 * Every `rest` code is false, and not because each was judged and found wanting. A
 * re-handshake re-establishes a clientProofV1 session, and the /_auth operations carry no
 * proof and open no session — there is nothing there to re-establish. A rate limit clears
 * by waiting and a rejected id_token clears by getting another one; neither is something
 * this classification can ask for. They stay listed one by one so that a code added to
 * that surface still stops the build.
 */
fun SpfnGeneratedErrorCode.isAuthFailure(): Boolean = when (this)
{
    SpfnGeneratedErrorCode.PROOF_INVALID,
    SpfnGeneratedErrorCode.PROOF_REPLAYED,
    SpfnGeneratedErrorCode.PROOF_EXPIRED,
    SpfnGeneratedErrorCode.SESSION_REVOKED -> true

    SpfnGeneratedErrorCode.PROFILE_REJECTED,
    SpfnGeneratedErrorCode.CONTRACT_UNSUPPORTED -> false

    SpfnGeneratedErrorCode.ValidationError,
    SpfnGeneratedErrorCode.NativeSignInUnsupportedError,
    SpfnGeneratedErrorCode.NonceKeyBindingError,
    SpfnGeneratedErrorCode.InvalidKeyFingerprintError,
    SpfnGeneratedErrorCode.KeyAlgorithmMismatchError,
    SpfnGeneratedErrorCode.UnverifiedEmailLinkError,
    SpfnGeneratedErrorCode.InvalidSocialTokenError,
    SpfnGeneratedErrorCode.AccountDisabledError,
    SpfnGeneratedErrorCode.AccountPendingDeletionError,
    SpfnGeneratedErrorCode.RegistrationRejectedError,
    SpfnGeneratedErrorCode.KeyIdAlreadyRegisteredError,
    SpfnGeneratedErrorCode.MfaVerificationFailedError,
    SpfnGeneratedErrorCode.TooManyRequestsError,
    SpfnGeneratedErrorCode.DeviceAuthExpiredError,
    SpfnGeneratedErrorCode.DeviceAuthDeniedError,
    SpfnGeneratedErrorCode.DeviceAuthNotFoundError,
    SpfnGeneratedErrorCode.DeviceAuthAlreadyHandledError,
    SpfnGeneratedErrorCode.DeviceLinkExpiredError,
    SpfnGeneratedErrorCode.DeviceLinkDeniedError,
    SpfnGeneratedErrorCode.DeviceLinkNotFoundError,
    SpfnGeneratedErrorCode.Error -> false
}
