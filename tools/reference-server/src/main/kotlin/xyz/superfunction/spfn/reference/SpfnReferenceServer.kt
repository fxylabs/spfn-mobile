// SPFN Mobile — the local reference server.
//
// It implements the pinned contract bundle and nothing else: three operations,
// clientProofV1 verification in the contract's check order, the contract's error
// envelope, and a `/control` surface that exists only so a test can revoke a key or drop
// a session without waiting for a wall clock.
//
// It is a test fixture. It binds the loopback interface, it has no persistence, its keys
// are the synthetic conformance vectors, and none of its paths is a deployed route.
//
// Nothing a request carried is logged. The one line per request names the method, the
// path and the status, because a nonce, a session identifier, a proof and a body are all
// things a developer's terminal scrollback should never end up holding.

package xyz.superfunction.spfn.reference

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import xyz.superfunction.spfn.auth.SpfnProofInput
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnDigest
import xyz.superfunction.spfn.core.SpfnOperation
import xyz.superfunction.spfn.generated.SpfnEchoRequest
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnHandshakeRequest
import xyz.superfunction.spfn.generated.SpfnHandshakeResponse
import xyz.superfunction.spfn.generated.SpfnListItemsRequest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** The contract header fields one request presented, already known to appear once each. */
class SpfnReferenceCredentials(
    val profile: String,
    val clientId: String,
    val keyId: String,
    val nonce: String,
    val issuedAtMillis: Long,
    val proof: String,
    val sessionId: String?
)
{
    /** Nothing a request carried reaches a log through this. */
    override fun toString(): String = "SpfnReferenceCredentials(redacted)"
}

/** One HTTP answer, already serialized. */
class SpfnReferenceAnswer(val statusCode: Int, val body: ByteArray)

/**
 * A reference server bound to the loopback interface.
 *
 * Construct, [start], read [baseUrl], and [close] when done. Port 0 asks the operating
 * system for a free port, which is what keeps two suites running at once from colliding.
 */
class SpfnReferenceServer(
    requestedPort: Int = 0,
    private val clock: SpfnReferenceClock = SpfnReferenceClock.system(),
    sessionTtlMillis: Long = SpfnReferenceState.DEFAULT_SESSION_TTL_MILLIS,
    /** Presented by `/control` callers. Generated per launch; never logged, never printed. */
    val controlToken: String = SpfnReferenceState.newHexId(),
    private val log: (String) -> Unit = {}
) : AutoCloseable
{
    val state: SpfnReferenceState = SpfnReferenceState(clock, sessionTtlMillis)

    private val control = SpfnReferenceControl(state, clock, controlToken)

    private val http: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), BACKLOG)

    // A pool, not the default same-thread executor: `/control/hold` makes a handler wait,
    // and on the default executor one waiting handler would stall every other request —
    // including the `/control` call a test uses to end the wait.
    private val workers: ExecutorService = Executors.newFixedThreadPool(WORKER_COUNT) { runnable ->
        Thread(runnable, "spfn-reference-server").apply { isDaemon = true }
    }

    val port: Int
        get() = http.address.port

    val baseUrl: String
        get() = "http://127.0.0.1:$port"

    fun start(): SpfnReferenceServer
    {
        http.executor = workers;
        http.createContext("/", ::dispatch);
        http.start();
        return this;
    }

    override fun close()
    {
        http.stop(0);
        workers.shutdownNow();
    }

    // ---- dispatch ----------------------------------------------------------

    private fun dispatch(exchange: HttpExchange)
    {
        try
        {
            state.recordRequest();
            val answer = if (exchange.requestURI.path.startsWith(CONTROL_PREFIX))
            {
                control.handle(exchange)
            }
            else
            {
                operationAnswer(exchange)
            };
            respond(exchange, answer);
        }
        catch (_: Exception)
        {
            // The client gets a contract answer rather than a stack trace, and the reason
            // is a constant: an exception message can quote the request that produced it.
            respondQuietly(exchange, SpfnReferenceRefusal.unprocessable());
        }
        finally
        {
            exchange.close();
        }
    }

    private fun operationAnswer(exchange: HttpExchange): SpfnReferenceAnswer
    {
        val operation = route(exchange) ?: return refusal(SpfnReferenceRefusal.unroutable());
        val body = readBody(exchange) ?: return refusal(SpfnReferenceRefusal.bodyTooLarge());

        // Before verification, so a request a test is holding open has not spent its nonce
        // by the time the client gives up waiting for it.
        waitOutHold(exchange.requestURI.path);

        return when (val admitted = authenticate(exchange, operation, body))
        {
            is Admission.Refused -> refusal(admitted.refusal)
            is Admission.Accepted -> apply(operation, admitted)
        };
    }

    /**
     * The one operation this method and path name, or null.
     *
     * A query string is refused by omission: no contract path carries one, and a proof is
     * taken over the path alone, so a server that ignored a query would accept a request
     * whose proof does not cover all of what was sent.
     */
    private fun route(exchange: HttpExchange): SpfnOperation?
    {
        if (exchange.requestURI.rawQuery != null)
        {
            return null;
        }
        return SpfnGeneratedOperations.all.firstOrNull {
            it.path == exchange.requestURI.path && it.method == exchange.requestMethod
        };
    }

    // ---- verification ------------------------------------------------------

    private sealed interface Admission
    {
        class Refused(val refusal: SpfnReferenceRefusal) : Admission

        class Accepted(val value: SpfnCanonicalValue, val credentials: SpfnReferenceCredentials) : Admission
    }

    /**
     * Runs every check the contract puts between a request arriving and being applied.
     *
     * Shape first, then the profile allowlist, then the proof. That order is not the
     * contract's — the contract fixes the order inside the proof checks, which
     * [SpfnReferenceState.admit] owns — but it is forced: none of the proof checks can run
     * until the fields they read are known to be present and the body is known to be the
     * bytes the digest is supposed to cover.
     */
    private fun authenticate(
        exchange: HttpExchange,
        operation: SpfnOperation,
        body: ByteArray
    ): Admission
    {
        val credentials = credentials(exchange)
            ?: return Admission.Refused(SpfnReferenceRefusal.malformedHeaders());

        if (credentials.profile != SpfnReferenceWire.PROFILE_NAME)
        {
            return Admission.Refused(SpfnReferenceRefusal.profileRejected());
        }
        if (!SpfnReferenceWire.isRequestContentType(single(exchange, SpfnReferenceWire.CONTENT_TYPE)))
        {
            return Admission.Refused(SpfnReferenceRefusal.missingContentType());
        }
        if (operation.requiresSession != (credentials.sessionId != null))
        {
            return Admission.Refused(SpfnReferenceRefusal.sessionHeaderMisplaced());
        }

        val value = canonicalValueOf(body)
            ?: return Admission.Refused(SpfnReferenceRefusal.bodyNotCanonical());

        val refused = state.admit(
            clientId = credentials.clientId,
            keyId = credentials.keyId,
            presentedSessionId = credentials.sessionId,
            requiresSession = operation.requiresSession,
            proofInput = proofInput(operation, credentials, body),
            presentedProof = credentials.proof
        );
        return refused?.let { Admission.Refused(it) } ?: Admission.Accepted(value, credentials);
    }

    /**
     * The received bytes as a value, or null when they are not the canonical form.
     *
     * The parser accepts arbitrary whitespace and key order, so parsing alone proves
     * nothing about the bytes. Re-encoding and comparing is what makes SPFN-CANON-JSON-1 a
     * rule a client can break: a body whose keys are out of order verifies under its own
     * proof perfectly well, and would go unnoticed until the day a server digested the
     * re-encoded form instead.
     */
    private fun canonicalValueOf(body: ByteArray): SpfnCanonicalValue?
    {
        val value = try
        {
            SpfnCanonicalJson.parse(body)
        }
        catch (_: IllegalArgumentException)
        {
            return null;
        };
        return if (SpfnCanonicalJson.encode(value).contentEquals(body)) value else null;
    }

    /**
     * The proof input, with `bodySha256` taken over the bytes that actually arrived.
     *
     * Over the received bytes and never over a re-encoding of them: digesting what the
     * server produced would make the digest agree with itself no matter what the client
     * sent, which is exactly the check being asked for.
     */
    private fun proofInput(
        operation: SpfnOperation,
        credentials: SpfnReferenceCredentials,
        body: ByteArray
    ): SpfnProofInput = SpfnProofInput(
        method = operation.method,
        path = operation.path,
        clientId = credentials.clientId,
        keyId = credentials.keyId,
        nonce = credentials.nonce,
        issuedAtMillis = credentials.issuedAtMillis,
        bodySha256 = SpfnDigest.sha256Hex(body)
    )

    /** The contract header fields, or null when any of them is absent or repeated. */
    private fun credentials(exchange: HttpExchange): SpfnReferenceCredentials?
    {
        val issuedAt = single(exchange, SpfnReferenceWire.ISSUED_AT_MILLIS)?.toLongOrNull() ?: return null;
        val session = exchange.requestHeaders[SpfnReferenceWire.SESSION];
        if (session != null && session.size != 1)
        {
            return null;
        }
        return SpfnReferenceCredentials(
            profile = single(exchange, SpfnReferenceWire.PROFILE) ?: return null,
            clientId = single(exchange, SpfnReferenceWire.CLIENT_ID) ?: return null,
            keyId = single(exchange, SpfnReferenceWire.KEY_ID) ?: return null,
            nonce = single(exchange, SpfnReferenceWire.NONCE) ?: return null,
            issuedAtMillis = issuedAt,
            proof = single(exchange, SpfnReferenceWire.PROOF) ?: return null,
            sessionId = session?.get(0)
        );
    }

    /** One header value, or null when the field is absent or was sent more than once. */
    private fun single(exchange: HttpExchange, name: String): String?
    {
        val values = exchange.requestHeaders[name] ?: return null;
        return if (values.size == 1) values[0] else null;
    }

    // ---- applying ----------------------------------------------------------

    private fun apply(operation: SpfnOperation, admitted: Admission.Accepted): SpfnReferenceAnswer
    {
        val answered = try
        {
            when (operation.id)
            {
                SpfnGeneratedOperations.authClientProofHandshake.id -> handshake(admitted)
                SpfnGeneratedOperations.echoSend.id -> echo(admitted)
                SpfnGeneratedOperations.itemsList.id -> listItems(admitted)
                else -> Answer.Refused(SpfnReferenceRefusal.unroutable())
            }
        }
        catch (_: IllegalArgumentException)
        {
            // Canonical JSON, but not the request type this operation declares.
            Answer.Refused(SpfnReferenceRefusal.bodyNotTheDeclaredType());
        };

        return when (answered)
        {
            is Answer.Refused -> refusal(answered.refusal)
            is Answer.Body ->
            {
                state.recordOperation(operation.id);
                SpfnReferenceAnswer(HTTP_OK, SpfnCanonicalJson.encode(answered.value));
            }
        };
    }

    private sealed interface Answer
    {
        class Refused(val refusal: SpfnReferenceRefusal) : Answer

        class Body(val value: SpfnCanonicalValue) : Answer
    }

    private fun handshake(admitted: Admission.Accepted): Answer
    {
        val request = SpfnHandshakeRequest.decode(admitted.value);

        // The proof already binds the header identity to the key that signed it, so a body
        // naming a different client is a request whose two halves disagree about who sent it.
        if (request.clientId != admitted.credentials.clientId || request.keyId != admitted.credentials.keyId)
        {
            return Answer.Refused(SpfnReferenceRefusal.bodyNotTheDeclaredType());
        }

        val opened = state.openSession(request.clientId, request.keyId);
        return Answer.Body(
            SpfnHandshakeResponse(sessionId = opened.first, expiresAtMillis = opened.second).canonicalValue()
        );
    }

    private fun echo(admitted: Admission.Accepted): Answer = Answer.Body(
        SpfnReferenceOperations.echo(
            SpfnEchoRequest.decode(admitted.value),
            serverTimeMillis = clock.nowMillis()
        ).canonicalValue()
    )

    private fun listItems(admitted: Admission.Accepted): Answer =
        when (val result = SpfnReferenceOperations.listItems(SpfnListItemsRequest.decode(admitted.value)))
        {
            is SpfnReferenceOperations.Result.Refused -> Answer.Refused(result.refusal)
            is SpfnReferenceOperations.Result.Page -> Answer.Body(result.response.canonicalValue())
        }

    // ---- plumbing ----------------------------------------------------------

    /** The body, or null when it is larger than this server will read. */
    private fun readBody(exchange: HttpExchange): ByteArray?
    {
        val read = exchange.requestBody.readNBytes(MAX_BODY_BYTES + 1);
        return if (read.size > MAX_BODY_BYTES) null else read;
    }

    private fun waitOutHold(path: String)
    {
        val millis = state.takeHoldMillis(path);
        if (millis > 0)
        {
            Thread.sleep(millis);
        }
    }

    private fun refusal(refusal: SpfnReferenceRefusal): SpfnReferenceAnswer
    {
        state.recordRefusal();
        return SpfnReferenceAnswer(refusal.httpStatus, refusal.envelopeBytes(SpfnReferenceState.newHexId()));
    }

    private fun respondQuietly(exchange: HttpExchange, refused: SpfnReferenceRefusal)
    {
        try
        {
            respond(exchange, refusal(refused));
        }
        catch (_: Exception)
        {
            // The response was already begun, so there is nothing left to say.
        }
    }

    private fun respond(exchange: HttpExchange, answer: SpfnReferenceAnswer)
    {
        exchange.responseHeaders.set(SpfnReferenceWire.CONTENT_TYPE, SpfnReferenceWire.REQUEST_CONTENT_TYPE);
        exchange.sendResponseHeaders(answer.statusCode, answer.body.size.toLong());
        exchange.responseBody.write(answer.body);
        exchange.responseBody.flush();
        log("${exchange.requestMethod} ${exchange.requestURI.path} -> ${answer.statusCode}");
    }

    companion object
    {
        private const val BACKLOG = 32
        private const val WORKER_COUNT = 8
        private const val HTTP_OK = 200
        private const val CONTROL_PREFIX = "/control/"

        /** Far above any contract request and far below anything worth buffering. */
        private const val MAX_BODY_BYTES = 1 shl 20
    }
}
