// SPFN Mobile — the reference server's test hooks.
//
// `/control` is NOT part of the contract. Nothing under it appears in the bundle, no SDK
// knows it exists, and its answers are plain objects rather than contract envelopes — a
// control failure that looked like a contract error would teach a reader that the
// contract has codes it does not have.
//
// It exists because two of the cases the integration matrix has to cover — a revoked key
// and a dropped session — are things only a server can cause, and the Swift suite drives
// the server from another process where there is no object to call a method on.
//
// Two things keep the surface from being a hole. It is only reachable on the loopback
// interface, because that is the only interface the server binds; and every route except
// the readiness probe requires the token the launch generated, which is written to the
// launcher's port file and never printed or logged.

package xyz.superfunction.spfn.reference

import com.sun.net.httpserver.HttpExchange
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue

class SpfnReferenceControl(
    private val state: SpfnReferenceState,
    private val clock: SpfnReferenceClock,
    private val controlToken: String
)
{
    fun handle(exchange: HttpExchange): SpfnReferenceAnswer
    {
        val path = exchange.requestURI.path;
        if (path == HEALTH)
        {
            return ok(mapOf("status" to text("ok")));
        }
        if (exchange.requestHeaders.getFirst(TOKEN_HEADER) != controlToken)
        {
            return answer(HTTP_FORBIDDEN, mapOf("ok" to SpfnCanonicalValue.Bool(false), "reason" to text("control token")));
        }

        val body = exchange.requestBody.readNBytes(MAX_CONTROL_BODY_BYTES);
        return route(path, body);
    }

    private fun route(path: String, body: ByteArray): SpfnReferenceAnswer = when (path)
    {
        STATS -> stats()
        RESET ->
        {
            state.reset();
            ok(emptyMap());
        }
        EXPIRE_SESSIONS ->
        {
            state.expireSessions();
            ok(emptyMap());
        }
        REGISTER_KEY -> registerKey(body)
        REVOKE_KEY -> revokeKey(body)
        SESSION_TTL -> sessionTtl(body)
        HOLD -> hold(body)
        ADVANCE_CLOCK -> advanceClock(body)
        else -> answer(HTTP_NOT_FOUND, mapOf("ok" to SpfnCanonicalValue.Bool(false), "reason" to text("unknown control route")))
    }

    // ---- routes ------------------------------------------------------------

    private fun stats(): SpfnReferenceAnswer
    {
        val counters = state.stats();
        return ok(
            mapOf(
                "echoCount" to number(counters.echoCount),
                "handshakeCount" to number(counters.handshakeCount),
                "itemsListCount" to number(counters.itemsListCount),
                "liveSessionCount" to number(counters.liveSessionCount),
                "refusalCount" to number(counters.refusalCount),
                "requestCount" to number(counters.requestCount),
                "spentNonceCount" to number(counters.spentNonceCount)
            )
        );
    }

    /**
     * Registers the public key a test client generated — the asymmetric counterpart of
     * the shared-key provisioning the HMAC profile injected at construction. The body
     * carries only the public half (SPKI DER base64); no secret ever crosses this
     * route. The field names mirror the primitives dev server's `/control/register-key`
     * exactly, so the integration suites can drive either server with a URL change.
     */
    private fun registerKey(body: ByteArray): SpfnReferenceAnswer
    {
        val keyId = string(body, "keyId") ?: return badRequest("keyId");
        val publicKey = string(body, "publicKey") ?: return badRequest("publicKey");
        val decoded = try
        {
            java.util.Base64.getDecoder().decode(publicKey)
        }
        catch (_: IllegalArgumentException)
        {
            return badRequest("publicKey");
        };
        try
        {
            state.registerPublicKey(keyId, decoded);
        }
        catch (_: IllegalArgumentException)
        {
            return badRequest("publicKey");
        }
        return ok(emptyMap());
    }

    private fun revokeKey(body: ByteArray): SpfnReferenceAnswer
    {
        val keyId = string(body, "keyId") ?: return badRequest("keyId");
        state.revokeKey(keyId);
        return ok(emptyMap());
    }

    private fun sessionTtl(body: ByteArray): SpfnReferenceAnswer
    {
        val ttlMillis = integer(body, "ttlMillis") ?: return badRequest("ttlMillis");
        state.sessionTtlMillis(ttlMillis);
        return ok(emptyMap());
    }

    private fun hold(body: ByteArray): SpfnReferenceAnswer
    {
        val path = string(body, "path") ?: return badRequest("path");
        val millis = integer(body, "millis") ?: return badRequest("millis");
        val count = integer(body, "count") ?: return badRequest("count");
        state.holdPath(path, millis, count.toInt());
        return ok(emptyMap());
    }

    /**
     * Moves a movable clock forward — the frozen one the unit suites run on and the
     * ticking one a launch builds alike. Refused when the server is running on the wall
     * clock, because silently doing nothing is how a test passes for the wrong reason.
     */
    private fun advanceClock(body: ByteArray): SpfnReferenceAnswer
    {
        val movableClock = clock as? SpfnReferenceMovableClock
            ?: return answer(
                HTTP_CONFLICT,
                mapOf("ok" to SpfnCanonicalValue.Bool(false), "reason" to text("server is running on the system clock"))
            );
        val millis = integer(body, "millis") ?: return badRequest("millis");
        movableClock.advance(millis);
        return ok(emptyMap());
    }

    // ---- plumbing ----------------------------------------------------------

    private fun members(body: ByteArray): Map<String, SpfnCanonicalValue>?
    {
        if (body.isEmpty())
        {
            return emptyMap();
        }
        val parsed = try
        {
            SpfnCanonicalJson.parse(body)
        }
        catch (_: IllegalArgumentException)
        {
            return null;
        };
        return (parsed as? SpfnCanonicalValue.Obj)?.members;
    }

    private fun string(body: ByteArray, field: String): String? =
        (members(body)?.get(field) as? SpfnCanonicalValue.Text)?.value

    private fun integer(body: ByteArray, field: String): Long? =
        (members(body)?.get(field) as? SpfnCanonicalValue.Integer)?.value

    private fun badRequest(field: String): SpfnReferenceAnswer = answer(
        HTTP_BAD_REQUEST,
        mapOf("ok" to SpfnCanonicalValue.Bool(false), "reason" to text("missing or malformed field: $field"))
    )

    private fun ok(extra: Map<String, SpfnCanonicalValue>): SpfnReferenceAnswer =
        answer(HTTP_OK, extra + ("ok" to SpfnCanonicalValue.Bool(true)))

    private fun answer(statusCode: Int, members: Map<String, SpfnCanonicalValue>): SpfnReferenceAnswer =
        SpfnReferenceAnswer(statusCode, SpfnCanonicalJson.encode(SpfnCanonicalValue.Obj(members)))

    private fun text(value: String): SpfnCanonicalValue = SpfnCanonicalValue.Text(value)

    private fun number(value: Long): SpfnCanonicalValue = SpfnCanonicalValue.Integer(value)

    companion object
    {
        const val TOKEN_HEADER: String = "x-spfn-reference-control"

        const val HEALTH: String = "/control/health"
        const val STATS: String = "/control/stats"
        const val RESET: String = "/control/reset"
        const val EXPIRE_SESSIONS: String = "/control/expire-sessions"
        const val REGISTER_KEY: String = "/control/register-key"
        const val REVOKE_KEY: String = "/control/revoke-key"
        const val SESSION_TTL: String = "/control/session-ttl"
        const val HOLD: String = "/control/hold"
        const val ADVANCE_CLOCK: String = "/control/advance-clock"

        private const val HTTP_OK = 200
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_CONFLICT = 409

        private const val MAX_CONTROL_BODY_BYTES = 4096
    }
}
