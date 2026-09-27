// SPFN Mobile — what an app tells the event stream once, at its composition root.
//
// The stream path, the fixed list of event names and the server's ping interval. Every
// refusal happens here, at construction, so a wrong list is found on the first run rather
// than as a stream that never opens (docs/architecture/event-stream-design.md §3-1).
//
// Sources/SPFNClient/SPFNEventStreamConfiguration.swift is the same type in Swift.

package xyz.superfunction.spfn.client

/**
 * The reconnect schedule: `initialMillis`, doubled per consecutive failure up to
 * `maxMillis`, then multiplied by a jitter drawn from [0.5, 1.0].
 */
data class SpfnEventStreamBackoff(
    val initialMillis: Long,
    val multiplier: Long,
    val maxMillis: Long
)
{
    init
    {
        require(initialMillis > 0 && multiplier >= 1 && maxMillis >= initialMillis) { "backoff values are out of range" };
    }

    /**
     * The delay before the retry that follows `failure` consecutive failures (1-based).
     * `jitter` is clamped into [0.5, 1.0] so an injected source cannot produce zero.
     */
    fun delayMillis(failure: Int, jitter: Double): Long
    {
        var base = initialMillis;
        repeat(maxOf(failure, 1) - 1)
        {
            base = minOf(base * multiplier, maxMillis);
        };
        return (minOf(base, maxMillis) * jitter.coerceIn(0.5, 1.0)).toLong();
    }

    companion object
    {
        /** 1 s, 2 s, 4 s, 8 s, 16 s, then 30 s: the design's §4 numbers. */
        val Standard: SpfnEventStreamBackoff = SpfnEventStreamBackoff(1_000, 2, 30_000);
    }
}

/**
 * @param events the names this app receives on the stream: the server event router's
 *   KEYS. Sorted and comma-joined, they are the `events=` query. Duplicates count once.
 * @param streamPath where the server mounted the stream; begins with `/`, no query.
 * @param tokenPath where the one-use token is minted; derived from [streamPath] by the
 *   server's own rule when null (`/events/stream` becomes `/events/token`).
 * @param pingIntervalMillis the SERVER's ping interval. The silence watchdog is 2.5 times it.
 * @param deliveryBuffer one listener's queue length before it is replaced by one reread.
 * @throws IllegalArgumentException for an empty list, an empty name, a name holding a
 *   comma, a path that does not begin with `/` or that carries a query.
 */
class SpfnEventStreamConfiguration(
    events: List<String>,
    val streamPath: String = "/events/stream",
    tokenPath: String? = null,
    val pingIntervalMillis: Long = 10_000,
    val backoff: SpfnEventStreamBackoff = SpfnEventStreamBackoff.Standard,
    val deliveryBuffer: Int = 256
)
{
    /** The configured names, deduplicated and sorted: the order the query carries them in. */
    val events: List<String> = events.toSortedSet().toList();

    val tokenPath: String = tokenPath ?: derivedTokenPath(streamPath);

    /** 2.5 × the server's ping interval: two missed pings and half an interval more. */
    val silenceMillis: Long = pingIntervalMillis * 5 / 2;

    init
    {
        require(this.events.isNotEmpty()) { "events: the list is empty" };
        require(this.events.none { it.isEmpty() || it.contains(',') }) { "events: a name is empty or holds a comma" };
        requirePath(streamPath, "streamPath");
        requirePath(this.tokenPath, "tokenPath");
        require(pingIntervalMillis > 0) { "pingIntervalMillis: must be positive" };
        require(deliveryBuffer > 0) { "deliveryBuffer: must be positive" };
    }

    /** Whether a listener may ask for `name`. The one check behind L-3. */
    fun contains(name: String): Boolean = events.binarySearch(name) >= 0;

    companion object
    {
        /**
         * The server's rule: the last path segment of the stream path becomes `token`.
         * `/events/stream` → `/events/token`, `/sse` → `/token`.
         */
        fun derivedTokenPath(streamPath: String): String =
            streamPath.substring(0, streamPath.lastIndexOf('/') + 1) + "token";

        private fun requirePath(path: String, field: String)
        {
            require(path.startsWith("/") && !path.contains('?') && !path.contains('#')) { "$field: must begin with / and carry no query" };
        }
    }
}
