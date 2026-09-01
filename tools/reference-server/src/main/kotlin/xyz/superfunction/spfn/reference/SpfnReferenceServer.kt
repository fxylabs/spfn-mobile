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
import xyz.superfunction.spfn.generated.SpfnOauthNativeRequest
import xyz.superfunction.spfn.generated.SpfnOauthNativeResponse
import xyz.superfunction.spfn.generated.SpfnRotateKeyRequest
import xyz.superfunction.spfn.generated.SpfnRotateKeyResponse
import xyz.superfunction.spfn.generated.SpfnServerTimeResponse
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
class SpfnReferenceAnswer(
    val statusCode: Int,
    val body: ByteArray,
    val headers: List<Pair<String, String>> = emptyList()
)

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
        val routed = route(exchange) ?: return refusal(SpfnReferenceRefusal.unroutable());
        if (routed.operation.id == SpfnGeneratedOperations.coreTime.id)
        {
            return coreTimeAnswer(exchange);
        }
        val body = readBody(exchange) ?: return refusal(SpfnReferenceRefusal.bodyTooLarge());

        // Before verification, so a request a test is holding open has not spent its nonce
        // by the time the client gives up waiting for it.
        waitOutHold(exchange.requestURI.path);

        // The two auth classes part ways here. The unproven class runs no admission at
        // all; the proven class runs the full contract order, exactly as before.
        if (routed.operation.authProfile == UNPROVEN_AUTH_CLASS)
        {
            return unprovenAnswer(exchange, routed, body);
        }

        return when (val admitted = authenticate(exchange, routed.operation, body))
        {
            is Admission.Refused -> refusal(admitted.refusal)
            is Admission.Accepted -> apply(routed.operation, admitted)
        };
    }

    /** The contract's bodyless, unproven clock anchor. */
    private fun coreTimeAnswer(exchange: HttpExchange): SpfnReferenceAnswer
    {
        for (header in PROOF_ONLY_HEADERS)
        {
            if (exchange.requestHeaders.containsKey(header))
            {
                return restRefusal(SpfnReferenceRestRefusal.badRequest(
                    "the clock operation carries neither proof headers nor a session header"
                ));
            }
        }
        val body = readBody(exchange) ?: return refusal(SpfnReferenceRefusal.bodyTooLarge());
        if (body.isNotEmpty())
        {
            return restRefusal(SpfnReferenceRestRefusal.badRequest("the clock operation carries no request body"));
        }
        state.recordOperation(SpfnGeneratedOperations.coreTime.id);
        return SpfnReferenceAnswer(
            statusCode = HTTP_OK,
            body = SpfnCanonicalJson.encode(SpfnServerTimeResponse(clock.nowMillis()).canonicalValue()),
            headers = listOf("cache-control" to "no-store")
        );
    }

    /** One routed request: the operation and the path parameter it carried, if any. */
    private class Routed(val operation: SpfnOperation, val provider: String?)

    /**
     * The one operation this method and path name, or null.
     *
     * A query string is refused by omission: no contract path carries one, and a proof is
     * taken over the path alone, so a server that ignored a query would accept a request
     * whose proof does not cover all of what was sent.
     *
     * A `{provider}` segment matches one non-empty path segment and captures it; the
     * contract's `pathTemplate` clause says the client substitutes before signing or
     * sending, so what arrives here is always concrete.
     */
    private fun route(exchange: HttpExchange): Routed?
    {
        if (exchange.requestURI.rawQuery != null)
        {
            return null;
        }
        val path = exchange.requestURI.path;
        for (operation in SpfnGeneratedOperations.all)
        {
            if (operation.method != exchange.requestMethod)
            {
                continue;
            }
            if (operation.path == path)
            {
                return Routed(operation, provider = null);
            }
            val provider = matchProviderTemplate(operation.path, path);
            if (provider != null)
            {
                return Routed(operation, provider = provider);
            }
        }
        return null;
    }

    /** The captured `{provider}` segment, or null when [path] is not [template]. */
    private fun matchProviderTemplate(template: String, path: String): String?
    {
        if (!template.contains(PROVIDER_SEGMENT))
        {
            return null;
        }
        val templateSegments = template.split('/');
        val pathSegments = path.split('/');
        if (templateSegments.size != pathSegments.size)
        {
            return null;
        }
        var provider: String? = null;
        for ((expected, actual) in templateSegments.zip(pathSegments))
        {
            if (expected == PROVIDER_SEGMENT)
            {
                if (actual.isEmpty())
                {
                    return null;
                }
                provider = actual;
            }
            else if (expected != actual)
            {
                return null;
            }
        }
        return provider;
    }

    // ---- the unproven surface ----------------------------------------------

    /**
     * Answers an operation of the contract's unproven class.
     *
     * The contract's `operationAuthClasses.none` clause: accepted with neither proof
     * headers nor a session header. This server holds the "neither" strictly — a proof
     * or session header on an unproven request is a request assembled by something
     * confused about which class it is calling, and waving it through would let a
     * half-proven request pass as either class. The body is plain JSON here, not
     * canonical-checked: the `restOperations.requestBody` clause requires canonical
     * bytes only where a proof binds them, and no proof exists to bind these.
     */
    private fun unprovenAnswer(exchange: HttpExchange, routed: Routed, body: ByteArray): SpfnReferenceAnswer
    {
        for (header in PROOF_ONLY_HEADERS)
        {
            if (exchange.requestHeaders.containsKey(header))
            {
                return restRefusal(SpfnReferenceRestRefusal.badRequest(
                    "an unproven operation carries neither proof headers nor a session header"
                ));
            }
        }
        if (!SpfnReferenceWire.isRequestContentType(single(exchange, SpfnReferenceWire.CONTENT_TYPE)))
        {
            return restRefusal(SpfnReferenceRestRefusal.badRequest(
                "a request that carries a body must declare the contract content type"
            ));
        }
        val value = try
        {
            SpfnCanonicalJson.parse(body)
        }
        catch (_: IllegalArgumentException)
        {
            return restRefusal(SpfnReferenceRestRefusal.badRequest("the request body is not JSON"));
        };

        val answered = try
        {
            when (routed.operation.id)
            {
                SpfnGeneratedOperations.authEnrollOauthNative.id -> oauthNative(routed, value)
                else -> RestAnswer.Refused(SpfnReferenceRestRefusal.notImplemented())
            }
        }
        catch (_: IllegalArgumentException)
        {
            // JSON, but not the request type this operation declares.
            RestAnswer.Refused(SpfnReferenceRestRefusal.badRequest(
                "the request body is not the request type this operation declares"
            ));
        };

        return when (answered)
        {
            is RestAnswer.Refused -> restRefusal(answered.refusal)
            is RestAnswer.Body ->
            {
                state.recordOperation(routed.operation.id);
                SpfnReferenceAnswer(HTTP_OK, SpfnCanonicalJson.encode(answered.value));
            }
        };
    }

    private sealed interface RestAnswer
    {
        class Refused(val refusal: SpfnReferenceRestRefusal) : RestAnswer

        class Body(val value: SpfnCanonicalValue) : RestAnswer
    }

    private fun oauthNative(routed: Routed, value: SpfnCanonicalValue): RestAnswer
    {
        val provider = routed.provider
            ?: return RestAnswer.Refused(SpfnReferenceRestRefusal.badRequest("no provider segment"));
        val request = SpfnOauthNativeRequest.decode(value);

        return when (val result = SpfnReferenceRestOps.oauthNative(
            state = state,
            provider = provider,
            idToken = request.idToken,
            nonce = request.nonce,
            publicKeyBase64 = request.publicKey,
            keyId = request.keyId,
            fingerprint = request.fingerprint,
            algorithm = request.algorithm
        ))
        {
            is SpfnReferenceRestOps.Result.Refused -> RestAnswer.Refused(result.refusal)
            is SpfnReferenceRestOps.Result.Enrolled -> RestAnswer.Body(
                SpfnOauthNativeResponse(
                    userId = result.userId,
                    keyId = result.keyId,
                    isNewUser = result.isNewUser
                ).canonicalValue()
            )
        };
    }

    private fun restRefusal(refusal: SpfnReferenceRestRefusal): SpfnReferenceAnswer
    {
        state.recordRefusal();
        return SpfnReferenceAnswer(refusal.httpStatus, refusal.envelopeBytes(SpfnReferenceState.newHexId()));
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
                SpfnGeneratedOperations.authKeysRotate.id -> rotateKey(admitted)
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
            is Answer.RestRefused -> restRefusal(answered.refusal)
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

        /** A proven REST operation's body-level refusal, in the REST vocabulary. */
        class RestRefused(val refusal: SpfnReferenceRestRefusal) : Answer

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

    /**
     * The proven rotation. The admission that already ran proves the OLD key — the one
     * the request's `x-spfn-key-id` names — and its ownership; what the body registers
     * is the replacement. A body-level failure is a shape refusal in the REST surface's
     * own vocabulary, never an auth code: the proof verified, and telling the client to
     * re-handshake over a malformed replacement key would buy nothing.
     */
    private fun rotateKey(admitted: Admission.Accepted): Answer
    {
        val request = SpfnRotateKeyRequest.decode(admitted.value);
        val refused = SpfnReferenceRestOps.rotate(
            state = state,
            oldKeyId = admitted.credentials.keyId,
            publicKeyBase64 = request.publicKey,
            newKeyId = request.keyId,
            fingerprint = request.fingerprint,
            algorithm = request.algorithm
        );
        if (refused != null)
        {
            return Answer.RestRefused(refused);
        }
        return Answer.Body(SpfnRotateKeyResponse(success = true, keyId = request.keyId).canonicalValue());
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
        for ((name, value) in answer.headers)
        {
            exchange.responseHeaders.set(name, value);
        }
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
        private const val PROVIDER_SEGMENT = "{provider}"

        /** The contract's unproven auth class, `operationAuthClasses.none`. */
        private const val UNPROVEN_AUTH_CLASS = "none"

        /** What an unproven request may not carry: any proof field, or a session. */
        private val PROOF_ONLY_HEADERS = listOf(
            SpfnReferenceWire.PROFILE,
            SpfnReferenceWire.CLIENT_ID,
            SpfnReferenceWire.KEY_ID,
            SpfnReferenceWire.NONCE,
            SpfnReferenceWire.ISSUED_AT_MILLIS,
            SpfnReferenceWire.PROOF,
            SpfnReferenceWire.SESSION
        )

        /** Far above any contract request and far below anything worth buffering. */
        private const val MAX_BODY_BYTES = 1 shl 20
    }
}
