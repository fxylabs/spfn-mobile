// SPFN Mobile — which server the Android integration suite is talking to.
//
// By default it starts one in-process: the suite is self-contained and `./gradlew
// :reference-server:spfnIntegrationTest` needs nothing else running. Given a target URL it
// starts nothing and drives whatever is on the other end instead, which is how the same
// five cases are run against the canonical implementation rather than against the local
// reference server.
//
// The two modes differ in exactly one thing: how the case reaches the server's state. In
// process there is an object to call a method on; against an external server there is only
// `/control` over HTTP. `SpfnReferenceControlSurface` is that one difference, so every case
// body below is the same code in both modes and both write the same receipt.
//
// A target that is named but unusable is an error, never a fall back to the in-process
// server. A run that was asked to check the canonical implementation and quietly checked
// the local one instead would report the strongest evidence this repository can produce
// while producing none of it.

package xyz.superfunction.spfn.reference

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * An external server the suite was pointed at, and the token its `/control` needs.
 *
 * The token is checked here rather than at each place that uses one, because there is more
 * than one way in — a property, a launch file, and a launch file the runner wrote from an
 * environment variable — and a rule enforced on some of them is a rule that holds until
 * somebody uses the other door.
 */
class SpfnIntegrationTarget(val baseUrl: String, val controlToken: String)
{
    init
    {
        if (!TOKEN_CHARSET.matches(controlToken))
        {
            refuse(
                "the control token holds characters that cannot be carried in an HTTP header " +
                    "field value; allowed: $TOKEN_CHARSET_DESCRIPTION"
            );
        }
    }

    companion object
    {
        /** Set either of these and the suite stops starting a server of its own. */
        const val URL_PROPERTY: String = "spfn.integrationTargetUrl"

        const val LAUNCH_FILE_PROPERTY: String = "spfn.integrationLaunchFile"

        const val TOKEN_PROPERTY: String = "spfn.integrationControlToken"

        /**
         * What a control token is allowed to be made of.
         *
         * Every token this repository generates is hex, so the set is wider than anything
         * that has to pass it. It is narrow on purpose: the token is written into a header
         * field value, and a colon, a space or a line break there is not a bad token but a
         * second header field, which is a request nobody wrote. Non-ASCII is refused for
         * the same reason — a header field value has no encoding to declare it in.
         *
         * `tools/reference-server/run-integration.sh` enforces the same set before it runs
         * anything, and `Tests/SPFNIntegrationTests/SPFNIntegrationEnvironment.swift` does
         * for the Swift suite. Three enforcers, one set: change it in all three or in none.
         */
        val TOKEN_CHARSET: Regex = Regex("[A-Za-z0-9._-]+")

        const val TOKEN_CHARSET_DESCRIPTION: String = "A-Z a-z 0-9 . _ -"

        /** The target the run named, or null when it named none and one is started locally. */
        fun resolve(): SpfnIntegrationTarget?
        {
            val url = property(URL_PROPERTY);
            val launchFilePath = property(LAUNCH_FILE_PROPERTY);
            if (url == null && launchFilePath == null)
            {
                return null;
            }

            val launched = launchFilePath?.let { fromLaunchFile(File(it)) };
            val baseUrl = url ?: launched?.baseUrl
                ?: refuse("$launchFilePath has no baseUrl, and $URL_PROPERTY was not set either");
            val token = property(TOKEN_PROPERTY) ?: launched?.controlToken
                ?: refuse("a target was named but no control token was: set $TOKEN_PROPERTY or $LAUNCH_FILE_PROPERTY");

            return SpfnIntegrationTarget(normalise(baseUrl), token);
        }

        /** The `{"baseUrl","controlToken"}` object a launched server writes. */
        fun fromLaunchFile(file: File): SpfnIntegrationTarget
        {
            if (!file.isFile)
            {
                refuse("$LAUNCH_FILE_PROPERTY names ${file.path}, which is not a file");
            }
            val parsed = try
            {
                SpfnCanonicalJson.parse(file.readBytes())
            }
            catch (failure: IllegalArgumentException)
            {
                refuse("${file.path} is not readable as canonical JSON: ${failure.message}");
            };
            val members = (parsed as? SpfnCanonicalValue.Obj)?.members
                ?: refuse("${file.path} is not a launch file: expected an object with baseUrl and controlToken");
            return SpfnIntegrationTarget(
                baseUrl = text(members, "baseUrl") ?: refuse("${file.path} has no baseUrl"),
                controlToken = text(members, "controlToken") ?: refuse("${file.path} has no controlToken")
            );
        }

        /**
         * Trims a trailing slash, because every path in the suite starts with one.
         *
         * `https://host/` and `https://host` name the same server, and only one of them
         * produces `https://host//control/health`, which is a different path and a 404 that
         * reads as an unreachable server.
         */
        private fun normalise(baseUrl: String): String
        {
            val trimmed = baseUrl.trim().trimEnd('/');
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://"))
            {
                refuse("$URL_PROPERTY must be an absolute http(s) URL, got '$baseUrl'");
            }
            return trimmed;
        }

        private fun property(name: String): String? = System.getProperty(name)?.trim()?.ifEmpty { null }

        private fun text(members: Map<String, SpfnCanonicalValue>, field: String): String? =
            (members[field] as? SpfnCanonicalValue.Text)?.value?.ifEmpty { null }

        private fun refuse(reason: String): Nothing = throw IllegalStateException(
            "the integration suite was pointed at an external server and cannot run: $reason. " +
                "It will not fall back to an in-process server, because a run that checked the local " +
                "server while reporting the external one proves nothing."
        )
    }
}

/**
 * The server state a case needs to arrange, however this run can reach it.
 *
 * Everything here is a test hook rather than contract surface: dropping a session, revoking
 * a key and holding a request are the conditions cases (b), (c) and (e) exist to observe,
 * and none of them is something a client can cause.
 */
interface SpfnReferenceControlSurface
{
    /** Back to the state the server started in, counters included. */
    fun reset()

    /** Drops held sessions without touching the expiry each client was told. */
    fun expireSessions()

    fun revokeKey(keyId: String)

    /** Makes the next [count] requests to [path] wait, so a timeout has something to time out on. */
    fun hold(path: String, millis: Long, count: Int)

    /**
     * Moves the target's clock forward, or answers false when it runs on the wall clock
     * and cannot be moved.
     *
     * False is a fact about the target, not a failure: a launched server runs on the
     * system clock on purpose, and a case that needs an expiry it cannot arrange says so
     * loudly and skips rather than quietly asserting something else.
     */
    fun advanceClock(millis: Long): Boolean

    fun stats(): SpfnReferenceStats
}

/** The in-process surface: the same state object the server's own `/control` routes call. */
class SpfnInProcessControl(
    private val state: SpfnReferenceState,
    private val clock: SpfnReferenceClock
) : SpfnReferenceControlSurface
{
    override fun reset() = state.reset()

    override fun expireSessions() = state.expireSessions()

    override fun revokeKey(keyId: String) = state.revokeKey(keyId)

    override fun hold(path: String, millis: Long, count: Int) = state.holdPath(path, millis, count)

    override fun advanceClock(millis: Long): Boolean
    {
        val testClock = clock as? SpfnReferenceTestClock ?: return false;
        testClock.advance(millis);
        return true;
    }

    override fun stats(): SpfnReferenceStats = state.stats()
}

/**
 * The same surface over `/control`, for a server in another process.
 *
 * Plain OkHttp rather than the SDK's transport: `/control` is not in the contract, so an
 * SDK that knew how to reach it would know something no application is allowed to know.
 * Canonical JSON both ways, which also checks that the two ends agree about the encoding
 * outside anything the contract fixtures cover.
 */
class SpfnHttpControl(private val target: SpfnIntegrationTarget) : SpfnReferenceControlSurface
{
    private val http = OkHttpClient.Builder()
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override fun reset() = post(SpfnReferenceControl.RESET, emptyMap())

    override fun expireSessions() = post(SpfnReferenceControl.EXPIRE_SESSIONS, emptyMap())

    override fun revokeKey(keyId: String) =
        post(SpfnReferenceControl.REVOKE_KEY, mapOf("keyId" to SpfnCanonicalValue.Text(keyId)))

    override fun hold(path: String, millis: Long, count: Int) = post(
        SpfnReferenceControl.HOLD,
        mapOf(
            "count" to SpfnCanonicalValue.Integer(count.toLong()),
            "millis" to SpfnCanonicalValue.Integer(millis),
            "path" to SpfnCanonicalValue.Text(path)
        )
    )

    /**
     * The one route allowed to answer a status this surface reads rather than refuses:
     * `/control/advance-clock` answers 409 when the server is on the system clock, which
     * is the "cannot be arranged" this returns false for.
     */
    override fun advanceClock(millis: Long): Boolean
    {
        val builder = Request.Builder()
            .url(target.baseUrl + SpfnReferenceControl.ADVANCE_CLOCK)
            .addHeader(SpfnReferenceControl.TOKEN_HEADER, target.controlToken)
            .post(
                SpfnCanonicalJson.encode(
                    SpfnCanonicalValue.Obj(mapOf("millis" to SpfnCanonicalValue.Integer(millis)))
                ).toRequestBody(JSON)
            );
        http.newCall(builder.build()).execute().use { response ->
            response.body.bytes();
            if (response.code == HTTP_CONFLICT)
            {
                return false;
            }
            check(response.isSuccessful) { "${SpfnReferenceControl.ADVANCE_CLOCK} answered ${response.code}" };
            return true;
        }
    }

    override fun stats(): SpfnReferenceStats
    {
        val members = (send("GET", SpfnReferenceControl.STATS, null) as? SpfnCanonicalValue.Obj)?.members
            ?: error("${SpfnReferenceControl.STATS} did not answer an object");
        return SpfnReferenceStats(
            requestCount = number(members, "requestCount"),
            handshakeCount = number(members, "handshakeCount"),
            echoCount = number(members, "echoCount"),
            itemsListCount = number(members, "itemsListCount"),
            refusalCount = number(members, "refusalCount"),
            liveSessionCount = number(members, "liveSessionCount"),
            spentNonceCount = number(members, "spentNonceCount")
        );
    }

    private fun post(path: String, members: Map<String, SpfnCanonicalValue>)
    {
        send("POST", path, SpfnCanonicalValue.Obj(members));
    }

    private fun send(method: String, path: String, body: SpfnCanonicalValue?): SpfnCanonicalValue
    {
        val builder = Request.Builder()
            .url(target.baseUrl + path)
            // Read out of the launch file and never logged: this token revokes keys and
            // drops sessions, and one printed here would be in every terminal scrollback.
            .addHeader(SpfnReferenceControl.TOKEN_HEADER, target.controlToken);
        builder.method(method, body?.let { SpfnCanonicalJson.encode(it).toRequestBody(JSON) });

        http.newCall(builder.build()).execute().use { response ->
            val bytes = response.body.bytes();
            check(response.isSuccessful) { "$path answered ${response.code}" };
            return SpfnCanonicalJson.parse(bytes);
        }
    }

    private fun number(members: Map<String, SpfnCanonicalValue>, field: String): Long =
        (members[field] as? SpfnCanonicalValue.Integer)?.value
            ?: error("${SpfnReferenceControl.STATS} did not report $field")

    private companion object
    {
        const val CALL_TIMEOUT_SECONDS = 10L

        /** What `/control/advance-clock` answers when the server runs on the wall clock. */
        const val HTTP_CONFLICT = 409

        val JSON = "application/json".toMediaType()
    }
}
