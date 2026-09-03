package xyz.superfunction.spfn.harness

import java.util.concurrent.atomic.AtomicBoolean
import xyz.superfunction.spfn.client.SpfnOkHttpTransport
import xyz.superfunction.spfn.client.SpfnTransport
import xyz.superfunction.spfn.client.SpfnTransportError
import xyz.superfunction.spfn.client.SpfnTransportRequest
import xyz.superfunction.spfn.client.SpfnTransportResponse

/**
 * The harness's two transport powers: dropping the network, and watching what came back.
 *
 * **Dropping it.** Three of the ten cases the flows cover need the lifecycle in
 * `ROTATION_PENDING`, and there is exactly one way to get there: `rotate()` persists the
 * candidate BEFORE it sends, and a transport failure — no response at all — leaves that
 * candidate in place because the server may or may not have applied the request. So the
 * harness needs to be able to drop the network on command, and this wrapper is that
 * command. It is not a fake server and it answers nothing: it refuses to send, with the
 * same `Connectivity` failure a real network drop produces, so the state the app lands in
 * is the state a real network drop lands in.
 *
 * **Watching it.** A device receipt records the status the wire answered with, which no
 * layer above this one has any business knowing. See [HarnessObservation] for why the
 * recording belongs to an attempt rather than to this object. [onResponse] is the other
 * half of the same fact and it belongs to no attempt: it is what the `http=` readout
 * shows, so a response no tap on this screen produced still reaches a reader.
 *
 * Nothing in the SDK changed to make either possible. The transport is injected, which is
 * what the boundary exists for.
 */
class HarnessTransport(private val inner: SpfnTransport = SpfnOkHttpTransport()) : SpfnTransport
{
    private val blocked = AtomicBoolean(false);

    /**
     * Called with the status of every response, on whichever thread it arrived on.
     *
     * Set once, by the model, so the `http=` readout follows the wire rather than being
     * re-read whenever something else happens to redraw the screen. The generated
     * approval screens send through this transport and report their own refusals on their
     * own readouts; this is the only place the harness sees what the wire said.
     *
     * `@Volatile` for the reason [observation] is: written on the main thread at
     * construction and read on whichever thread a request ran on.
     */
    @Volatile
    var onResponse: ((Int) -> Unit)? = null;

    /**
     * The attempt currently being watched, or null when nothing asked to be.
     *
     * Held as a reference rather than as fields on this object, so that a second attempt
     * starting does not blank what the first one saw: the newer attempt installs its own
     * [HarnessObservation] and the older one keeps reading the object it was handed. The
     * screen refuses a second tap while one is in flight, and this is the part that stays
     * correct even if it did not.
     */
    @Volatile
    private var observation: HarnessObservation? = null;

    val isBlocked: Boolean
        get() = blocked.get();

    fun setBlocked(value: Boolean)
    {
        blocked.set(value);
    }

    /** Starts watching for one attempt, and answers the object that attempt reads. */
    fun observe(): HarnessObservation
    {
        val fresh = HarnessObservation();
        observation = fresh;
        return fresh;
    }

    override suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse
    {
        if (blocked.get())
        {
            throw SpfnTransportError.Connectivity("harness: network blocked");
        }
        val response = inner.execute(request);
        observation?.record(response);
        onResponse?.invoke(response.statusCode);
        return response;
    }
}
