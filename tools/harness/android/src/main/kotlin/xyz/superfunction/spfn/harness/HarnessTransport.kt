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

    val isBlocked: Boolean
        get() = blocked.get();

    fun setBlocked(value: Boolean)
    {
        blocked.set(value);
    }

    override suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse
    {
        if (blocked.get())
        {
            throw SpfnTransportError.Connectivity("harness: network blocked");
        }
        return inner.execute(request);
    }
}
