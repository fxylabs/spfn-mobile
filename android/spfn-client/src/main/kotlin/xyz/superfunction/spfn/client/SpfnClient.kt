// SPFN Mobile — the single execute path.
//
// Every operation goes through one function. That is the whole point of the file: there is
// no second way to send a request, so a rule stated here — the body is encoded once, the
// proof is fresh, an auth refusal buys exactly one re-handshake and nothing else buys
// anything — holds for every operation the contract will ever declare, including the ones
// that do not exist yet.
//
// Retry is off. The one exception is an auth refusal, which re-opens the session and
// re-sends once, because that is the only failure where the client knows what changed and
// knows the request was never applied. A transport failure is not retried: this layer
// cannot tell a request that never arrived from one that arrived and whose answer was
// lost, and re-sending the second kind is how a client applies an operation twice.
//
// Sources/SPFNClient/SPFNClient.swift is the same path in Swift.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.core.SpfnOperation
import xyz.superfunction.spfn.generated.SpfnGeneratedAuthClass
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import kotlin.coroutines.coroutineContext

/**
 * One operation with the two functions that turn its types into contract bytes.
 *
 * The client is generic over request and response rather than knowing any operation, so the
 * per-operation values that fill this in can be generated later without this file changing.
 * Keeping the operation inside the descriptor is what stops a caller pairing `echo.send`
 * with the codec for `items.list`.
 */
class SpfnCall<Req, Resp>(
    val operation: SpfnOperation,
    val encode: (Req) -> SpfnCanonicalValue,
    val decode: (SpfnCanonicalValue) -> Resp
)

/**
 * Sends contract operations over a transport, against one session.
 *
 * Holds nothing that changes. Every piece of shared mutable state a request touches — the
 * session, the handshake in flight — belongs to [SpfnSession], which owns the lock.
 *
 * @param timeoutMillis the deadline for one attempt, not for the call. A re-handshake and
 *   the re-sent request each get a fresh budget, because the transport's deadline is per
 *   call and splitting one budget across three calls would make the last one fail for
 *   reasons the caller never asked for. It applies to the operation requests only: a
 *   handshake is a call the session makes, under the deadline the session was given.
 */
class SpfnClient(
    private val transport: SpfnTransport,
    private val session: SpfnSession,
    private val timeoutMillis: Long = 15_000
)
{
    /**
     * Sends one operation and returns its response.
     *
     * Throws [SpfnClientError] for everything this path classifies, and rethrows
     * `CancellationException` unwrapped. An error it did not produce passes through as
     * itself; see that type.
     */
    suspend fun <Req, Resp> execute(call: SpfnCall<Req, Resp>, request: Req): Resp
    {
        // The auth class is resolved before anything is sent, and an unknown one is
        // refused rather than guessed at. The contract's own rule: nothing is ever
        // downgraded to anonymous handling, so a class this build does not know is a
        // contract this build does not implement.
        val authClass = SpfnGeneratedAuthClass.of(call.operation)
            ?: throw SpfnClientError.UndeclaredAuthClass(call.operation.authProfile);

        // The handshake is the session's own operation whichever class it declares:
        // it opens the session every proven operation presents, so running it here
        // would send it without the bookkeeping that gives it its point.
        if (call.operation.id == SpfnGeneratedOperations.authClientProofHandshake.id)
        {
            throw SpfnClientError.UnsupportedOperation(call.operation.id);
        }

        // Encoded once, for both attempts. The proof is not: the re-sent request carries
        // the same bytes under a new nonce, a new timestamp and a new proof over them.
        val canonicalBody = SpfnCanonicalJson.encode(call.encode(request));

        if (authClass == SpfnGeneratedAuthClass.NONE)
        {
            return executeUnproven(call, canonicalBody);
        }

        val first = attempt(call.operation, canonicalBody);

        return try
        {
            read(first.response, call.decode)
        }
        catch (refusal: SpfnClientError.Auth)
        {
            retryOnce(call, canonicalBody, first.sessionId, refusal)
        };
    }

    // ---- the unproven class ------------------------------------------------

    /**
     * Sends an operation of the contract's unproven class: no proof, no identity, no
     * nonce and no session header, because it is called before any key exists to sign
     * with. Nothing here touches the session, so it can never provoke a handshake.
     *
     * There is also no retry. The one retry `execute` owns exists to replace a stale
     * session, and this request presented none; an auth refusal here is the server's
     * final answer and passes through as itself.
     */
    private suspend fun <Req, Resp> executeUnproven(call: SpfnCall<Req, Resp>, canonicalBody: ByteArray): Resp
    {
        val response = try
        {
            transport.execute(
                SpfnTransportRequest(
                    method = call.operation.method,
                    url = session.baseUrl + call.operation.path,
                    // The identity headers ride here too. The contract applies them to
                    // every operation "proven or not — enrolment and login are where a
                    // stale client is met first, and they carry no proof", and this is
                    // that path.
                    headers = listOf(SpfnWireHeaders.CONTENT_TYPE to SpfnWireHeaders.REQUEST_CONTENT_TYPE)
                        + SpfnClientIdentity.headers,
                    body = canonicalBody,
                    timeoutMillis = timeoutMillis
                )
            )
        }
        catch (cancellation: CancellationException)
        {
            throw cancellation;
        }
        catch (failure: Exception)
        {
            throw classify(failure);
        };
        return read(response, call.decode);
    }

    // ---- the two attempts --------------------------------------------------

    /** One request: fresh headers, one transport call, no interpretation of the answer. */
    private suspend fun attempt(operation: SpfnOperation, canonicalBody: ByteArray): Attempt
    {
        try
        {
            val headers = session.proofHeaders(operation, canonicalBody);
            val response = transport.execute(
                SpfnTransportRequest(
                    method = operation.method,
                    url = session.baseUrl + operation.path,
                    // Appended rather than assembled inside `proofHeaders`, because these
                    // are not proof fields: none of them enters the proof input, and the
                    // wire fixture pins what that function returns.
                    headers = headers + SpfnClientIdentity.headers,
                    body = canonicalBody,
                    timeoutMillis = timeoutMillis
                )
            );
            return Attempt(response, sessionIdIn(headers));
        }
        catch (cancellation: CancellationException)
        {
            throw cancellation;
        }
        catch (failure: Exception)
        {
            throw classify(failure);
        }
    }

    /**
     * Re-opens the session and sends the request once more.
     *
     * Straight-line on purpose. There is no loop and no counter: the second attempt has no
     * path back into this function, so "at most one re-handshake per call" is a property of
     * the shape rather than of a variable somebody could forget to reset.
     */
    private suspend fun <Req, Resp> retryOnce(
        call: SpfnCall<Req, Resp>,
        canonicalBody: ByteArray,
        presentedSessionId: String?,
        refusal: SpfnClientError.Auth
    ): Resp
    {
        // No session was presented, so there is nothing a re-handshake would replace.
        if (presentedSessionId == null)
        {
            throw refusal;
        }

        // Cancellation that lands between the two attempts costs no further request, and
        // stays a CancellationException so the enclosing scope still unwinds as one.
        coroutineContext.ensureActive();

        session.invalidate(staleSessionId = presentedSessionId);
        val second = attempt(call.operation, canonicalBody);
        return read(second.response, call.decode);
    }

    private class Attempt(
        val response: SpfnTransportResponse,
        /**
         * The session the request actually presented, so a refusal discards that session
         * and not whichever one is held by the time the refusal is read.
         */
        val sessionId: String?
    )

    // ---- reading the answer ------------------------------------------------

    /** Turns one response into a value, or into the failure it describes. */
    private fun <Resp> read(response: SpfnTransportResponse, decode: (SpfnCanonicalValue) -> Resp): Resp
    {
        // Before the body is looked at. A server refusing on contract grounds announces
        // its version on that refusal, so classifying first would return the refusal and
        // throw away the two numbers that say which end is stale.
        SpfnClientIdentity.mismatchIn(response)?.let { throw SpfnClientError.Contract(it) };

        val parsed = try
        {
            SpfnCanonicalJson.parse(response.body)
        }
        catch (_: IllegalArgumentException)
        {
            throw SpfnClientError.Decoding(SpfnDecodingFailure.NOT_CANONICAL_JSON);
        };

        if (response.statusCode !in 200..299)
        {
            val envelope = try
            {
                SpfnErrorEnvelope.decode(parsed)
            }
            catch (_: IllegalArgumentException)
            {
                throw SpfnClientError.Decoding(SpfnDecodingFailure.NOT_AN_ERROR_ENVELOPE);
            };
            throw refusal(envelope, response.statusCode);
        }

        return try
        {
            decode(parsed)
        }
        catch (_: IllegalArgumentException)
        {
            throw SpfnClientError.Decoding(SpfnDecodingFailure.NOT_THE_DECLARED_RESPONSE);
        };
    }

    /**
     * Classifies a refusal on the code the envelope declares.
     *
     * The status is carried, never consulted. A 401 an intermediary wrote carries no
     * envelope and never reaches here at all, so it cannot make the client re-handshake
     * against something that never refused a proof.
     */
    private fun refusal(envelope: SpfnErrorEnvelope, httpStatus: Int): SpfnClientError
    {
        val code = try
        {
            SpfnGeneratedErrorCode.decode(envelope.code)
        }
        catch (_: IllegalArgumentException)
        {
            return SpfnClientError.Decoding(SpfnDecodingFailure.UNKNOWN_ERROR_CODE);
        };

        return if (code.isAuthFailure())
        {
            SpfnClientError.Auth(SpfnAuthFailure(code, httpStatus, envelope))
        }
        else
        {
            SpfnClientError.Server(SpfnServerFailure(code, httpStatus, envelope))
        };
    }

    /**
     * Maps what the layers below raise onto the taxonomy, and leaves alone what it does not
     * recognise. Flattening an unknown error into one of the cases would make a client-side
     * bug read as something the server did.
     */
    private fun classify(failure: Exception): Exception = when (failure)
    {
        is SpfnTransportError -> SpfnClientError.Transport(failure)

        is SpfnSessionError.HandshakeRejected -> refusal(failure.envelope, failure.httpStatus)

        is SpfnSessionError.MalformedResponse ->
            if (failure.reason == SpfnSessionError.NOT_AN_ERROR_ENVELOPE)
            {
                SpfnClientError.Decoding(SpfnDecodingFailure.NOT_AN_ERROR_ENVELOPE)
            }
            else
            {
                SpfnClientError.Decoding(SpfnDecodingFailure.NOT_THE_DECLARED_RESPONSE)
            }

        else -> failure
    }

    private fun sessionIdIn(headers: List<Pair<String, String>>): String? =
        headers.firstOrNull { it.first == SpfnWireHeaders.SESSION }?.second
}
