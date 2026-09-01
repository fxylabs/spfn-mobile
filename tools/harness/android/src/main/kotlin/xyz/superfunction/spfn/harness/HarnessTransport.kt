package xyz.superfunction.spfn.harness

import java.util.concurrent.atomic.AtomicBoolean
import xyz.superfunction.spfn.client.SpfnOkHttpTransport
import xyz.superfunction.spfn.client.SpfnTransport
import xyz.superfunction.spfn.client.SpfnTransportError
import xyz.superfunction.spfn.client.SpfnTransportRequest
import xyz.superfunction.spfn.client.SpfnTransportResponse

/**
 * The harness's one transport trick.
 *
 * Three of the ten cases the flows cover need the lifecycle in `ROTATION_PENDING`, and
 * there is exactly one way to get there: `rotate()` persists the candidate BEFORE it
 * sends, and a transport failure — no response at all — leaves that candidate in place
 * because the server may or may not have applied the request.
 *
 * So the harness needs to be able to drop the network on command. This wrapper is that
 * command. It is not a fake server and it answers nothing: it refuses to send, with the
 * same `Connectivity` failure a real network drop produces, so the state the app lands in
 * is the state a real network drop lands in.
 *
 * Nothing in the SDK changed to make this possible. The transport is injected, which is
 * what the boundary exists for.
 */
class HarnessTransport(private val inner: SpfnTransport = SpfnOkHttpTransport()) : SpfnTransport
{
    private val blocked = AtomicBoolean(false);

    /**
     * What the last response carried, for the two receipt fields the SDK does not report.
     *
     * `SpfnKeyLifecycle.enroll` answers with an enrolment, not with an HTTP response, and
     * that is the right boundary — an app has no business branching on a status. A receipt
     * is not an app, though: it records what the wire actually said, so the harness reads
     * it here, at the one place the response exists, instead of asking the SDK to widen its
     * surface for a test tool.
     *
     * `@Volatile` because the write happens on whichever thread the transport ran on and
     * the read happens after the call returns, on another.
     */
    @Volatile
    private var observedStatusCode: Int? = null;

    @Volatile
    private var observedServerCommit: String? = null;

    val isBlocked: Boolean
        get() = blocked.get();

    /** The status of the last response, or null when this attempt received none. */
    val lastStatusCode: Int?
        get() = observedStatusCode;

    /** The commit the last response named, or null when it named none. */
    val lastServerCommit: String?
        get() = observedServerCommit;

    fun setBlocked(value: Boolean)
    {
        blocked.set(value);
    }

    /** Forgets the previous attempt, so a receipt cannot inherit an earlier run's status. */
    fun resetObservation()
    {
        observedStatusCode = null;
        observedServerCommit = null;
    }

    override suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse
    {
        if (blocked.get())
        {
            throw SpfnTransportError.Connectivity("harness: network blocked");
        }
        val response = inner.execute(request);
        observedStatusCode = response.statusCode;
        observedServerCommit = serverCommit(response.headers);
        return response;
    }

    companion object
    {
        /**
         * The header names a server may name its build with, most specific first.
         *
         * The contract declares none, so the harness looks for what a server plausibly
         * sends and records `null` when it finds nothing — an absent commit is a fact
         * about the server, not a failure of the run.
         *
         * The comparison is `lowercase()`, which is `Locale.ROOT` in Kotlin and therefore
         * not the Turkish dotless-i mapping a default locale would apply to `I` (P9).
         */
        private val COMMIT_HEADERS: List<String> = listOf(
            "x-spfn-server-commit",
            "x-server-commit",
            "x-git-commit",
            "x-commit"
        );

        /** How much of a server-chosen header value a receipt will carry. */
        private const val COMMIT_MAX_LENGTH: Int = 128;

        fun serverCommit(headers: List<Pair<String, String>>): String?
        {
            for (candidate in COMMIT_HEADERS)
            {
                val value = headers.firstOrNull { it.first.lowercase() == candidate }?.second;
                if (value != null && value.isNotEmpty())
                {
                    return value.take(COMMIT_MAX_LENGTH);
                }
            }
            return null;
        }
    }
}
