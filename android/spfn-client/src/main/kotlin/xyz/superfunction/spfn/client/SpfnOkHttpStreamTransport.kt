// SPFN Mobile — the OkHttp stream adapter.
//
// The same hardening the request adapter applies (SpfnOkHttpTransport.kt `hardened`): no
// redirect is followed — the URL carries a one-use token and a redirect would carry it to
// another host — no cookie, no cache, and `retryOnConnectionFailure(false)`, because a
// library retry would present a token the server has already consumed (§8 H-1).
//
// No `okhttp-sse` artifact: the body is read from `BufferedSource` in chunks and the
// line grammar is SpfnSseLineParser's, so the module's dependency list does not grow.
//
// The call carries no call timeout. `timeoutMillis` bounds the wait for the headers,
// judged here; after them nothing bounds the call, and silence is the state machine's to
// judge (H-5).

package xyz.superfunction.spfn.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SpfnOkHttpStreamTransport(client: OkHttpClient = OkHttpClient()) : SpfnStreamTransport
{
    /** Visible to the adapter suite, which asserts the hardening rather than trusting it. */
    internal val streamClient: OkHttpClient = hardened(client).newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build();

    override suspend fun open(request: SpfnTransportRequest): SpfnStreamResponse
    {
        val call = streamClient.newCall(toOkHttpRequest(request));
        val response = withTimeoutOrNull(request.timeoutMillis) { headers(call) };
        if (response == null)
        {
            call.cancel();
            throw SpfnTransportError.TimedOut();
        }
        return SpfnStreamResponse(
            statusCode = response.code,
            headers = response.headers.toList(),
            chunks = chunks(response),
            onCancel = {
                call.cancel();
                response.close();
            }
        );
    }

    /** Resumes when the status line and headers are in; the body is still unread. */
    private suspend fun headers(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() };
        call.enqueue(object : Callback
        {
            override fun onResponse(call: Call, response: Response)
            {
                continuation.resume(response) { _, _, _ -> response.close() };
            }

            override fun onFailure(call: Call, e: IOException)
            {
                continuation.resumeWithException(transportErrorFor(call, e));
            }
        });
    }

    /**
     * The body, read on the IO dispatcher because `BufferedSource.read` blocks. A failed
     * read surfaces as OkHttp's own IOException; the event stream reads any failure of
     * an open stream as `network` (E-29), so there is nothing to translate.
     */
    private fun chunks(response: Response): Flow<ByteArray> = flow {
        val source = response.body.source();
        val buffer = okio.Buffer();
        while (source.read(buffer, CHUNK_BYTES) != -1L)
        {
            emit(buffer.readByteArray());
        }
    }.flowOn(Dispatchers.IO);

    private companion object
    {
        const val CHUNK_BYTES: Long = 8_192;
    }
}
