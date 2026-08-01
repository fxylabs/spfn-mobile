// SPFN Mobile — the OkHttp transport adapter.
//
// OkHttp is the one external dependency the Android SDK takes. The adapter's job is to
// make it behave the way the transport contract says, which means switching off three
// conveniences that would otherwise corrupt an authenticated exchange — redirect
// following, cookies and caching — and turning a callback API into a cancellable
// suspending one without losing the reason a call failed.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [SpfnTransport] over OkHttp.
 *
 * Redirects are not followed. A proof is bound to a method and a path, so a 3xx that the
 * stack quietly re-issued would arrive at the new location carrying a proof for the old
 * one. The 3xx is returned as the response instead, and the layers above decide.
 */
class SpfnOkHttpTransport(client: OkHttpClient = OkHttpClient()) : SpfnTransport
{
    private val hardenedClient: OkHttpClient = hardened(client)

    /**
     * Sends the request once.
     *
     * Cancelling the calling coroutine cancels the OkHttp call and rethrows
     * `CancellationException` unwrapped. Wrapping it in a [SpfnTransportError] would make
     * a cancelled scope look like a network failure to every enclosing coroutine, and the
     * layers above would retry something the caller asked to stop.
     */
    override suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse
    {
        val call = hardenedClient.newCall(toOkHttpRequest(request))
        call.timeout().timeout(request.timeoutMillis, TimeUnit.MILLISECONDS)

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback
            {
                override fun onResponse(call: Call, response: Response)
                {
                    try
                    {
                        continuation.resume(response.use { toTransportResponse(it) })
                    }
                    catch (e: IOException)
                    {
                        // Reading the body can still fail after the headers arrived. It is
                        // the same call failing, so it is classified the same way.
                        continuation.resumeWithException(transportErrorFor(call, e))
                    }
                }

                override fun onFailure(call: Call, e: IOException)
                {
                    continuation.resumeWithException(transportErrorFor(call, e))
                }
            })
        }
    }
}

/**
 * The caller's client with the transport contract imposed on it.
 *
 * Hardening a supplied client rather than only a default one means the contract holds for
 * every caller: sharing a connection pool with the rest of an app must not import that
 * app's cookie jar, cache, redirect policy or retry policy into an authenticated exchange.
 * Only those four and the timeouts are replaced — the dispatcher, the connection pool,
 * interceptors and every other setting are the caller's and are shared as they were.
 *
 * `retryOnConnectionFailure` is off. OkHttp's retry is not confined to establishing a
 * connection: when a request has already been written to a pooled socket the server has
 * since closed, OkHttp can write that same request again on a new connection. For a
 * request carrying a proof over a nonce, that is a second delivery of the same nonce, and
 * the exactly-one-exchange contract the layers above count on would be silently false.
 *
 * The individual connect/read/write limits are removed because the request's
 * `timeoutMillis` is meant to be the only deadline; a leftover 10-second read limit would
 * silently override a 30-second request.
 */
internal fun hardened(client: OkHttpClient): OkHttpClient =
    client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .connectTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .build()

/**
 * Methods for which HTTP requires a body, restated because OkHttp's own list is internal.
 *
 * A request with no body on one of these gets a zero-length body instead of being
 * refused, which is what URLSession puts on the wire for the same input. The request
 * object keeps `body == null`, so the absent-body digest above this layer is unaffected.
 */
private val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

/**
 * Refuses a request that names the same header field twice, comparing names the way HTTP
 * does — without regard to case.
 *
 * The two stacks cannot agree on what to do with a repeated name. OkHttp writes two header
 * lines; URLRequest has no representation for that at all and folds them into one
 * comma-joined value. Rather than let the same request produce different bytes on the two
 * platforms, neither sends it. The layers above assemble each header once.
 *
 * The reason carries the field name and never the value: a repeated `Authorization` is
 * exactly the case where a value must not reach an error string.
 */
internal fun rejectDuplicateHeaderNames(headers: List<Pair<String, String>>)
{
    val seen = mutableSetOf<String>()
    for ((name, _) in headers)
    {
        val key = name.lowercase()
        if (!seen.add(key))
        {
            throw SpfnTransportError.Connectivity("duplicate request header name: $key")
        }
    }
}

/** Maps the transport request onto an OkHttp request without adding anything of its own. */
internal fun toOkHttpRequest(request: SpfnTransportRequest): Request
{
    if (request.timeoutMillis <= 0)
    {
        // The two stacks disagree about what a non-positive deadline means — OkHttp reads
        // zero as "no timeout", URLSession replaces it with its own default — so the
        // request is refused on both rather than behaving differently on each.
        throw SpfnTransportError.Connectivity("timeoutMillis must be positive")
    }

    val url = request.url.toHttpUrlOrNull()
        ?: throw SpfnTransportError.Connectivity("request URL is not an absolute URL")

    rejectDuplicateHeaderNames(request.headers)

    val builder = Request.Builder().url(url)

    // `addHeader` appends rather than replaces, so a header the caller assembled reaches
    // the wire exactly once, as itself.
    for ((name, value) in request.headers)
    {
        builder.addHeader(name, value)
    }

    val body = when
    {
        request.body != null -> request.body.toRequestBody()
        request.method.uppercase() in METHODS_REQUIRING_BODY -> ByteArray(0).toRequestBody()
        else -> null
    }

    try
    {
        builder.method(request.method, body)
    }
    catch (e: IllegalArgumentException)
    {
        // Nothing was sent and nothing can be: the method and the body presence are
        // incompatible. Reported the same way as a malformed URL, and without the
        // exception's message, which quotes the request.
        throw SpfnTransportError.Connectivity(
            "method ${request.method} does not permit the given request body"
        )
    }

    return builder.build()
}

/** Maps an OkHttp response onto the transport response. Reads the body eagerly. */
internal fun toTransportResponse(response: Response): SpfnTransportResponse =
    SpfnTransportResponse(
        statusCode = response.code,
        headers = response.headers.toList(),
        body = response.body.bytes()
    )

/**
 * Maps an OkHttp failure onto the transport's four cases.
 *
 * Order matters. A call timeout makes OkHttp cancel the call as its way of stopping it,
 * so `isCanceled()` is true for a timeout as well; asking about the timeout first is what
 * keeps the two distinguishable. Cancellation itself arrives as a plain
 * `IOException("Canceled")`, which would otherwise read as a connectivity failure.
 *
 * The reason string is the exception class name and nothing else. Messages from the
 * network stack can embed the failing URL, and a URL can carry a nonce.
 */
internal fun transportErrorFor(call: Call, e: IOException): SpfnTransportError = when
{
    e is SpfnTransportError -> e
    e is InterruptedIOException -> SpfnTransportError.TimedOut()
    call.isCanceled() -> SpfnTransportError.Cancelled()
    else -> SpfnTransportError.Connectivity(e.javaClass.name)
}
