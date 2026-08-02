// SPFN Mobile — the clientProofV1 session.
//
// One responsibility: hold whatever session the server last opened, open one when there
// is none, and issue the proof headers a request carries. It does not decide what a
// server answer means beyond "did the handshake open a session", it does not retry, and
// it does not classify transport failures. Those belong to the single execute path
// above it, which is a separate change set — so nothing here has vocabulary for them.
//
// Sources/SPFNClient/SPFNSession.swift is the same object in Swift.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.superfunction.spfn.auth.SpfnClientProof
import xyz.superfunction.spfn.auth.SpfnProofInput
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.core.SpfnOperation
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnHandshakeRequest
import xyz.superfunction.spfn.generated.SpfnHandshakeResponse

/**
 * The session a handshake opened.
 *
 * Deliberately not a data class: the generated `toString` would print [sessionId], which
 * authenticates requests and is redacted for the same reason a key is.
 */
class SpfnSessionState(
    /** The opaque session identifier the server issued. A bearer credential. */
    val sessionId: String,
    /** When the server says the session stops being usable, in epoch milliseconds. */
    val expiresAtMillis: Long
)
{
    override fun equals(other: Any?): Boolean =
        other is SpfnSessionState && other.sessionId == sessionId && other.expiresAtMillis == expiresAtMillis

    override fun hashCode(): Int = 31 * sessionId.hashCode() + expiresAtMillis.hashCode()

    override fun toString(): String =
        "SpfnSessionState(sessionId=redacted, expiresAtMillis=$expiresAtMillis)"
}

/**
 * The two ways opening a session can fail on this layer.
 *
 * Everything else passes through unchanged: a [SpfnTransportError] stays a transport
 * error and a `SpfnAuthException` stays an auth failure. Wrapping them here would erase
 * the distinction the execute path above is going to classify on.
 */
sealed class SpfnSessionError(message: String) : IllegalStateException(message)
{
    /** The server refused the handshake and answered with a well-formed error envelope. */
    class HandshakeRejected(val envelope: SpfnErrorEnvelope) :
        SpfnSessionError("handshake rejected with ${envelope.code}")

    /**
     * The response body was not what its status said it would be. [reason] names the
     * shape that was expected and never carries any part of the body.
     */
    class MalformedResponse(val reason: String) :
        SpfnSessionError("malformed handshake response: $reason")
}

/**
 * Holds the session and issues proof headers.
 *
 * [ensureSession] opens at most one session no matter how many callers ask at once: the
 * first caller runs the handshake and the rest await its result rather than starting a
 * second one.
 */
class SpfnSession(
    private val transport: SpfnTransport,
    private val keyProvider: SpfnKeyProvider,
    baseUrl: String,
    private val clock: SpfnClock = SpfnSystemClock(),
    private val nonceGenerator: SpfnNonceGenerator = SpfnRandomNonceGenerator(),
    private val timeoutMillis: Long = 15_000
)
{
    private val baseUrl: String = baseUrl.trimEnd('/')

    private val mutex = Mutex()
    private var state: SpfnSessionState? = null

    /**
     * Bumped by [invalidate]. A handshake that started before the bump does not store
     * its result, so a session discarded mid-flight cannot come back. The network call
     * itself is left to finish; its answer is simply not installed.
     */
    private var generation: Long = 0
    private var inFlight: InFlight? = null

    private class InFlight(val generation: Long, val result: CompletableDeferred<SpfnSessionState>)

    /**
     * The session currently held, expired or not. Exposed so a caller can observe the
     * session without provoking a handshake.
     */
    suspend fun currentState(): SpfnSessionState? = mutex.withLock { state }

    /** Opens a session unconditionally, replacing whatever is held. */
    suspend fun handshake(): SpfnSessionState
    {
        val operation = SpfnGeneratedOperations.authClientProofHandshake;
        val nonce = nonceGenerator.nextNonce();
        val issuedAtMillis = clock.nowMillis();

        val body = SpfnHandshakeRequest(
            clientId = keyProvider.clientId,
            keyId = keyProvider.keyId,
            nonce = nonce,
            issuedAtMillis = issuedAtMillis
        );

        // Encoded exactly once. The bytes the proof is taken over and the bytes that go
        // on the wire are the same array, so they cannot drift apart the way two
        // separate encodings of the same value can.
        val canonicalBody = SpfnCanonicalJson.encode(body.canonicalValue());

        val headers = proofHeaders(operation, canonicalBody, nonce, issuedAtMillis, sessionId = null);
        val started = mutex.withLock { generation };

        val response = transport.execute(
            SpfnTransportRequest(
                method = operation.method,
                url = baseUrl + operation.path,
                headers = headers,
                body = canonicalBody,
                timeoutMillis = timeoutMillis
            )
        );

        val opened = readSession(response);
        mutex.withLock { if (started == generation) state = opened };
        return opened;
    }

    /**
     * Returns a usable session, opening one only when there is none or it has expired.
     *
     * Concurrent callers share one handshake, its failure included.
     */
    suspend fun ensureSession(): SpfnSessionState
    {
        when (val claim = claim())
        {
            is Claim.Cached -> return claim.state
            is Claim.Join -> return claim.result.await()
            is Claim.Own ->
            {
                val opened = try
                {
                    handshake()
                }
                catch (failure: Throwable)
                {
                    claim.result.completeExceptionally(failure);
                    throw failure;
                }
                claim.result.complete(opened);
                return opened;
            }
        }
    }

    /**
     * Issues the headers one request carries, with a fresh nonce and a fresh timestamp.
     *
     * The returned list is the complete set of headers the request needs: `content-type`
     * when it has a body, then the proof fields in contract order, then the session for
     * an operation that requires one. A caller adds nothing.
     *
     * [canonicalBody] must be the exact bytes the request will send. Passing `null` means
     * the request carries no body at all, which the proof digests differently from a body
     * of zero length.
     */
    suspend fun proofHeaders(
        operation: SpfnOperation,
        canonicalBody: ByteArray?
    ): List<Pair<String, String>>
    {
        val sessionId = if (operation.requiresSession) ensureSession().sessionId else null;

        // Read after the handshake, not before: a nonce and a timestamp minted before a
        // network round trip would already be stale by the time the request is sent.
        return proofHeaders(
            operation = operation,
            canonicalBody = canonicalBody,
            nonce = nonceGenerator.nextNonce(),
            issuedAtMillis = clock.nowMillis(),
            sessionId = sessionId
        );
    }

    /** Discards the held session and abandons any handshake still in flight. */
    suspend fun invalidate()
    {
        mutex.withLock {
            state = null;
            generation += 1;
            inFlight = null;
        }
    }

    // ---- assembly ----------------------------------------------------------

    private fun proofHeaders(
        operation: SpfnOperation,
        canonicalBody: ByteArray?,
        nonce: String,
        issuedAtMillis: Long,
        sessionId: String?
    ): List<Pair<String, String>>
    {
        val input = SpfnProofInput.forRequest(
            method = operation.method,
            path = operation.path,
            clientId = keyProvider.clientId,
            keyId = keyProvider.keyId,
            nonce = nonce,
            issuedAtMillis = issuedAtMillis,
            canonicalBody = canonicalBody
        );

        val proof = keyProvider.withKey { key -> SpfnClientProof.proof(input, key) };

        val headers = mutableListOf<Pair<String, String>>();
        if (canonicalBody != null)
        {
            headers.add(SpfnWireHeaders.CONTENT_TYPE to SpfnWireHeaders.REQUEST_CONTENT_TYPE);
        }
        headers.add(SpfnWireHeaders.PROFILE to SpfnClientProof.PROFILE_NAME);
        headers.add(SpfnWireHeaders.CLIENT_ID to keyProvider.clientId);
        headers.add(SpfnWireHeaders.KEY_ID to keyProvider.keyId);
        headers.add(SpfnWireHeaders.NONCE to nonce);
        headers.add(SpfnWireHeaders.ISSUED_AT_MILLIS to issuedAtMillis.toString());
        headers.add(SpfnWireHeaders.PROOF to proof);

        // Guarded by the operation rather than by the caller: the handshake opens the
        // session it would otherwise present, so it can never carry one.
        if (operation.requiresSession && sessionId != null)
        {
            headers.add(SpfnWireHeaders.SESSION to sessionId);
        }
        return headers;
    }

    private sealed interface Claim
    {
        class Cached(val state: SpfnSessionState) : Claim

        class Join(val result: CompletableDeferred<SpfnSessionState>) : Claim

        class Own(val result: CompletableDeferred<SpfnSessionState>) : Claim
    }

    /**
     * Decides, under the lock, whether this caller returns the held session, waits for a
     * handshake someone else started, or runs one itself.
     *
     * A finished in-flight entry is treated as absent rather than cleared by whoever
     * finished it. Clearing it would mean suspending on the lock inside a `finally`,
     * which a cancelled coroutine cannot do — and a slot left behind by a cancelled
     * owner would strand every later caller on a result nobody completes.
     */
    private suspend fun claim(): Claim = mutex.withLock {
        val current = state;
        if (current != null && clock.nowMillis() < current.expiresAtMillis)
        {
            return@withLock Claim.Cached(current);
        }

        val existing = inFlight;
        if (existing != null && existing.generation == generation && existing.result.isActive)
        {
            return@withLock Claim.Join(existing.result);
        }

        val owned = CompletableDeferred<SpfnSessionState>();
        inFlight = InFlight(generation, owned);
        Claim.Own(owned);
    }

    // ---- reading the answer ------------------------------------------------

    /**
     * Reads a handshake answer, or throws the reason it was not one.
     *
     * Both reasons are fixed strings. A reason built from the body would put whatever the
     * server sent into an error, and from there into a log.
     */
    private fun readSession(response: SpfnTransportResponse): SpfnSessionState
    {
        val opened = response.statusCode in 200..299;
        val reason = if (opened) NOT_A_HANDSHAKE_RESPONSE else NOT_AN_ERROR_ENVELOPE;

        val parsed = try
        {
            SpfnCanonicalJson.parse(response.body)
        }
        catch (_: IllegalArgumentException)
        {
            throw SpfnSessionError.MalformedResponse(reason);
        }

        if (!opened)
        {
            val envelope = try
            {
                SpfnErrorEnvelope.decode(parsed)
            }
            catch (_: IllegalArgumentException)
            {
                throw SpfnSessionError.MalformedResponse(reason);
            }
            throw SpfnSessionError.HandshakeRejected(envelope);
        }

        val decoded = try
        {
            SpfnHandshakeResponse.decode(parsed)
        }
        catch (_: IllegalArgumentException)
        {
            throw SpfnSessionError.MalformedResponse(reason);
        }
        return SpfnSessionState(sessionId = decoded.sessionId, expiresAtMillis = decoded.expiresAtMillis);
    }

    private companion object
    {
        const val NOT_A_HANDSHAKE_RESPONSE = "response body is not a HandshakeResponse"
        const val NOT_AN_ERROR_ENVELOPE = "response body is not an SPFN error envelope"
    }
}
