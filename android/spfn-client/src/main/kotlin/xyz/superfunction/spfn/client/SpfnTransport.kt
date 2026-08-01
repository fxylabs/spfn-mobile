// SPFN Mobile — the transport boundary.
//
// One responsibility: send one HTTP request and return one HTTP response. It performs
// no retry, no re-handshake and no error classification. A 401 and a 500 are ordinary
// responses here; deciding what they mean belongs to the session and execute layers
// above this one, which is why this file has no vocabulary for either.
//
// Sources/SPFNClient/SPFNTransport.swift is the same boundary in Swift. The two carry
// the same semantics under different idioms, and the two test suites use corresponding
// case names so the parity is checkable rather than asserted.

package xyz.superfunction.spfn.client

import java.io.IOException

/**
 * One outbound HTTP request, already fully assembled by the caller.
 *
 * Headers are an ordered list rather than a map on purpose: a proof is taken over an
 * exact request, and a map silently reorders and deduplicates.
 *
 * Deliberately not a `data class`. The generated `toString` would print every header
 * value and every body byte, and the generated `equals` would compare [body] by identity;
 * both are worse than having neither.
 */
class SpfnTransportRequest(
    /** Uppercase HTTP method. Passed through verbatim; the transport never rewrites it. */
    val method: String,
    /** Absolute request URL, including any query. */
    val url: String,
    /** Header fields in the order they were assembled. Duplicate names are allowed. */
    val headers: List<Pair<String, String>> = emptyList(),
    /**
     * The request body, or `null` for a request that carries no body at all.
     *
     * `null` and an empty array are different values and stay different: the proof layer
     * digests an absent body differently from an empty one.
     */
    val body: ByteArray? = null,
    /** Deadline for the whole call, in milliseconds. */
    val timeoutMillis: Long
)
{
    override fun toString(): String =
        "SpfnTransportRequest(method=$method, headers=${headers.size}, body=${describeBody(body)})"

    private fun describeBody(body: ByteArray?): String =
        if (body == null) "absent" else "${body.size} bytes"
}

/**
 * One inbound HTTP response, unclassified.
 *
 * A non-2xx status arrives here as a value, not as an exception. [body] is always present
 * because an HTTP response always has one, possibly of zero length.
 */
class SpfnTransportResponse(
    val statusCode: Int,
    val headers: List<Pair<String, String>>,
    val body: ByteArray
)
{
    override fun toString(): String =
        "SpfnTransportResponse(status=$statusCode, headers=${headers.size}, body=${body.size} bytes)"
}

/**
 * The only failures a transport reports. Everything the server actually answered is a
 * [SpfnTransportResponse], so this list covers exactly the cases where no response exists.
 *
 * These are `IOException`s because that is what a caller of an HTTP call already catches.
 * Coroutine cancellation is NOT one of them: it stays a `CancellationException` so that
 * structured concurrency keeps working — see [SpfnOkHttpTransport.execute].
 */
sealed class SpfnTransportError(message: String) : IOException(message)
{
    /**
     * The request never completed at the network level, or was never sendable at all.
     * [reason] names the failure class and never carries any part of the request.
     */
    class Connectivity(val reason: String) : SpfnTransportError("transport failed: $reason")

    /**
     * The call exceeded `timeoutMillis`. Kept distinct from [Connectivity] because the
     * layers above retry the two differently.
     */
    class TimedOut : SpfnTransportError("transport call exceeded its timeout")

    /**
     * The underlying call was cancelled without the calling coroutine being cancelled —
     * for example by a caller that cancels the shared `OkHttpClient`'s calls by tag.
     * Coroutine cancellation surfaces as `CancellationException` instead.
     */
    class Cancelled : SpfnTransportError("transport call was cancelled")

    /** A response arrived but was not a usable HTTP response. */
    class InvalidResponse(val reason: String) : SpfnTransportError("invalid response: $reason")
}

/**
 * Sends exactly one request. Implementations do not retry.
 *
 * The layers above own retry, re-handshake and classification, so an implementation that
 * retried internally would make their accounting wrong.
 */
interface SpfnTransport
{
    suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse
}
