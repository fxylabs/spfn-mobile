// SPFN Mobile — the stream transport boundary.
//
// SpfnTransport sends one request and returns one whole response; a stream never ends on
// its own schedule, so it gets a boundary of its own: the status and headers once, then
// the body as chunks for as long as the connection lasts (design §3-9). The error
// vocabulary is SpfnTransportError's four cases and nothing new.
//
// `timeoutMillis` is the deadline for the connection and the headers. After them the
// adapter sets no deadline at all: the state machine's silence watchdog is the one
// place a quiet stream is judged, so the two platforms cannot disagree about when a
// healthy stream is cut (docs/architecture/event-stream-design.md §8 H-5).
//
// Sources/SPFNClient/SPFNStreamTransport.swift is the same boundary in Swift.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.flow.Flow

interface SpfnStreamTransport
{
    /** Opens the stream and returns once the headers are in, or throws SpfnTransportError. */
    suspend fun open(request: SpfnTransportRequest): SpfnStreamResponse
}

/**
 * @property chunks the body, in the order it arrived. Ends when the server ends the
 *   stream; throws an IOException when the connection fails.
 */
class SpfnStreamResponse(
    val statusCode: Int,
    val headers: List<Pair<String, String>>,
    val chunks: Flow<ByteArray>,
    private val onCancel: () -> Unit
)
{
    /** Closes the connection. Idempotent; a collector still reading sees the stream end. */
    fun cancel()
    {
        onCancel();
    }

    override fun toString(): String = "SpfnStreamResponse(status=$statusCode, headers=${headers.size})";
}
