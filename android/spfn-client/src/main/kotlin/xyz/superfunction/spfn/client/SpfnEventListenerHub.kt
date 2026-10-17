// SPFN Mobile — the listener hub: every screen's listening, over one connection.
//
// One frame's path to one listener is decode → condition → queue (design §3-4). A decode
// failure is counted as dropped and the condition never sees it; a false condition is
// counted as filtered and costs nothing; a true one enqueues. Rereads skip both, because
// they carry no value to decide on (L-13). Each listener has its own queue of
// `deliveryBuffer` frames, and a full queue is replaced by one overflow reread — for that
// listener only.
//
// Nothing here feeds the state machine. The hub receives the machine's `deliver`,
// `reread` and `markUnavailable` effects and the listeners' attach and detach, and
// produces nothing the machine reads (§4-2, `listeners_neverReachMachine`).
//
// A wire listener (revision 4, S-2) is the state client's kind: several names in one queue,
// no decoder and no condition, and the connection's own signals — `Opened` with what the
// `connected` frame said, `Subscription` grants and revokes — in order with the frames.
// The hub keeps the open connection's `connected` so a wire listener attached while it is
// open hears it once more right after `Attached`; the connection ending forgets it.
//
// Sources/SPFNClient/SPFNEventListenerHub.swift is the same hub in Swift.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue

internal class SpfnEventListenerHub(private val deliveryBuffer: Int)
{
    /** One listener: its name, decoder and condition, fixed when it attaches, and its queue. */
    internal class Listener<E>(
        val name: String,
        private val decode: (SpfnCanonicalValue) -> E,
        private val condition: (E) -> Boolean
    )
    {
        val queue = ArrayDeque<SpfnEventSignal<E>>();
        var pendingFrames = 0;
        var unavailableSent = false;
        var failure: Exception? = null;
        val wake = Channel<Unit>(Channel.CONFLATED);

        /** Decode, then the condition. A throwing decoder is data; a throwing condition is a bug. */
        fun evaluate(payload: SpfnCanonicalValue): Evaluation<E>
        {
            val value = try
            {
                decode(payload)
            }
            catch (_: Exception)
            {
                return Evaluation.Dropped;
            };
            return try
            {
                if (condition(value)) Evaluation.Enqueue(value) else Evaluation.Filtered
            }
            catch (thrown: Exception)
            {
                Evaluation.Failed(thrown)
            };
        }
    }

    /** One wire listener: its names, fixed when it attaches, and its one queue (S-2). */
    internal class WireListener(val names: Set<String>)
    {
        val queue = ArrayDeque<SpfnWireSignal>();
        var pendingFrames = 0;
        val reportedUnavailable = mutableSetOf<String>();
        val wake = Channel<Unit>(Channel.CONFLATED);
    }

    internal sealed interface Evaluation<out E>
    {
        data class Enqueue<E>(val value: E) : Evaluation<E>

        data object Dropped : Evaluation<Nothing>

        data object Filtered : Evaluation<Nothing>

        data class Failed(val thrown: Exception) : Evaluation<Nothing>
    }

    private val lock = Any();
    private val listeners = mutableListOf<Listener<*>>();
    private val wireListeners = mutableListOf<WireListener>();
    private var unavailable: Set<String> = emptySet();
    private var connection: SpfnStreamConnected? = null;
    private val counts = MutableStateFlow(SpfnEventDiagnostics());

    val diagnostics: StateFlow<SpfnEventDiagnostics> = counts.asStateFlow();

    /** L-1: added, and its first signal is `attached` whatever the connection is doing. */
    fun <E> attach(name: String, decode: (SpfnCanonicalValue) -> E, condition: (E) -> Boolean): Listener<E>
    {
        val listener = Listener(name, decode, condition);
        synchronized(lock) {
            listeners.add(listener);
            push(listener, SpfnEventSignal.Reread(SpfnRereadCause.Attached));
            if (name in unavailable)
            {
                sendUnavailable(listener);
            }
        };
        return listener;
    }

    /**
     * L-19, L-20: `Attached`, any of its names already unavailable, and — while a connection
     * is open — that connection's `Opened` once more, all under the hub's lock.
     */
    fun attachWire(names: Set<String>): WireListener
    {
        val listener = WireListener(names);
        synchronized(lock) {
            wireListeners.add(listener);
            pushWire(listener, SpfnWireSignal.Attached);
            sendUnavailable(listener, unavailable);
            connection?.let { pushWire(listener, SpfnWireSignal.Opened(it), dropping = ::belongsToConnection) };
        };
        return listener;
    }

    /** L-2: removed with its queue. The connection is not told. */
    fun detach(listener: Listener<*>)
    {
        synchronized(lock) { listeners.remove(listener) };
    }

    fun detachWire(listener: WireListener)
    {
        synchronized(lock) { wireListeners.remove(listener) };
    }

    /** Everything queued for a wire listener, suspending until there is something. */
    suspend fun takeWire(listener: WireListener): List<SpfnWireSignal>
    {
        while (true)
        {
            val drained = synchronized(lock) {
                val drained = listener.queue.toList();
                listener.queue.clear();
                listener.pendingFrames = 0;
                drained
            };
            if (drained.isNotEmpty())
            {
                return drained;
            }
            listener.wake.receive();
        }
    }

    /**
     * Everything queued for `listener`, suspending until there is something. Ends by
     * throwing the condition's exception once the queue before it is drained (L-12).
     */
    suspend fun <E> take(listener: Listener<E>): List<SpfnEventSignal<E>>
    {
        while (true)
        {
            val drained = synchronized(lock) { drain(listener) };
            if (drained.isNotEmpty())
            {
                return drained;
            }
            listener.failure?.let { throw it };
            listener.wake.receive();
        }
    }

    /** E-22: one frame of a configured name, to every listener of that name. */
    fun deliver(name: String, data: String)
    {
        val targets = synchronized(lock) { listeners.filter { it.name == name } };
        val wireTargets = synchronized(lock) { wireListeners.filter { name in it.names } };
        if (targets.isEmpty() && wireTargets.isEmpty())
        {
            return;
        }
        val payload = envelopePayload(data);
        if (payload == null)
        {
            count { it.copy(droppedFrames = it.droppedFrames + targets.size + wireTargets.size) };
            return;
        }
        synchronized(lock) { wireTargets.forEach { enqueueWireFrame(it, SpfnWireSignal.Frame(name, payload)) } };
        for (target in targets)
        {
            offer(target, payload);
        }
    }

    /** E-13: a new epoch. Every queue's old frames go; the reread covers them. */
    fun reread(epoch: Int)
    {
        synchronized(lock) {
            for (listener in listeners)
            {
                clear(listener);
                push(listener, SpfnEventSignal.Reread(SpfnRereadCause.Opened(epoch)));
            }
        };
    }

    /** S-2: the connection that just opened, to every wire listener, and kept for later ones. */
    fun connected(connected: SpfnStreamConnected)
    {
        synchronized(lock) {
            connection = connected;
            wireListeners.forEach { pushWire(it, SpfnWireSignal.Opened(connected), dropping = ::belongsToConnection) };
        };
    }

    /** The connection ended: a wire listener attaching now hears `Attached` only (L-19). */
    fun disconnected()
    {
        synchronized(lock) { connection = null };
    }

    /** S-3 (L-25): a grant or revoke, to the wire listeners of that name. `listen` never hears it. */
    fun subscription(name: String, status: SpfnSubscriptionStatus)
    {
        synchronized(lock) {
            wireListeners.filter { name in it.names }.forEach { pushWire(it, SpfnWireSignal.Subscription(name, status)) };
        };
    }

    /** Q-F: the names the server does not serve. Each listener of one hears it once. */
    fun markUnavailable(names: List<String>)
    {
        synchronized(lock) {
            unavailable = names.toSet();
            listeners.filter { it.name in unavailable }.forEach { sendUnavailable(it) };
            wireListeners.forEach { sendUnavailable(it, unavailable) };
        };
    }

    fun countUnexpected()
    {
        count { it.copy(unexpectedFrames = it.unexpectedFrames + 1) };
    }

    private fun <E> offer(listener: Listener<E>, payload: SpfnCanonicalValue)
    {
        when (val evaluation = listener.evaluate(payload))
        {
            is Evaluation.Enqueue -> synchronized(lock) { enqueueFrame(listener, evaluation.value) }
            Evaluation.Dropped -> count { it.copy(droppedFrames = it.droppedFrames + 1) }
            Evaluation.Filtered -> count { it.copy(filteredFrames = it.filteredFrames + 1) }
            is Evaluation.Failed -> fail(listener, evaluation.thrown)
        };
    }

    /**
     * L-4: a frame that finds the queue full replaces it with one overflow reread, which
     * covers that frame too. Only frames count toward the limit.
     */
    private fun <E> enqueueFrame(listener: Listener<E>, value: E)
    {
        if (listener.pendingFrames >= deliveryBuffer)
        {
            clear(listener);
            push(listener, SpfnEventSignal.Reread(SpfnRereadCause.Overflow));
            return;
        }
        listener.pendingFrames += 1;
        push(listener, SpfnEventSignal.Frame(value));
    }

    /** L-12: detached, and its flow ends with the condition's own exception. */
    private fun <E> fail(listener: Listener<E>, thrown: Exception)
    {
        synchronized(lock) {
            listeners.remove(listener);
            listener.failure = thrown;
        };
        listener.wake.trySend(Unit);
    }

    /** L-23: a frame that finds the queue full drops the queued frames for one `Overflow`. */
    private fun enqueueWireFrame(listener: WireListener, frame: SpfnWireSignal.Frame)
    {
        if (listener.pendingFrames >= deliveryBuffer)
        {
            pushWire(listener, SpfnWireSignal.Overflow, dropping = { it is SpfnWireSignal.Frame || it == SpfnWireSignal.Overflow });
            return;
        }
        pushWire(listener, frame);
        listener.pendingFrames += 1;
    }

    /** L-24: each of its names is reported unavailable once, however often it is marked. */
    private fun sendUnavailable(listener: WireListener, marked: Set<String>)
    {
        val fresh = (listener.names intersect marked) - listener.reportedUnavailable;
        if (fresh.isNotEmpty())
        {
            listener.reportedUnavailable += fresh;
            pushWire(listener, SpfnWireSignal.Unavailable(fresh.sorted()));
        }
    }

    /** Queues `signal` after dropping what `dropping` names; the caller holds the lock. */
    private fun pushWire(listener: WireListener, signal: SpfnWireSignal, dropping: ((SpfnWireSignal) -> Boolean)? = null)
    {
        if (dropping != null)
        {
            listener.queue.removeAll(dropping);
            listener.pendingFrames = 0;
        }
        listener.queue.addLast(signal);
        listener.wake.trySend(Unit);
    }

    private fun <E> sendUnavailable(listener: Listener<E>)
    {
        if (!listener.unavailableSent)
        {
            listener.unavailableSent = true;
            push(listener, SpfnEventSignal.Unavailable);
        }
    }

    /** Drops the queued frames and rereads. A queued `unavailable` stays: it is sent once. */
    private fun <E> clear(listener: Listener<E>)
    {
        val keep = listener.queue.contains(SpfnEventSignal.Unavailable);
        listener.queue.clear();
        listener.pendingFrames = 0;
        if (keep)
        {
            push(listener, SpfnEventSignal.Unavailable);
        }
    }

    private fun <E> push(listener: Listener<E>, signal: SpfnEventSignal<E>)
    {
        listener.queue.addLast(signal);
        listener.wake.trySend(Unit);
    }

    private fun <E> drain(listener: Listener<E>): List<SpfnEventSignal<E>>
    {
        val drained = listener.queue.toList();
        listener.queue.clear();
        listener.pendingFrames = 0;
        return drained;
    }

    private fun count(change: (SpfnEventDiagnostics) -> SpfnEventDiagnostics)
    {
        counts.update(change);
    }

    internal companion object
    {
        /** What a new connection's `Opened` supersedes: everything the old connection queued (L-26). */
        fun belongsToConnection(signal: SpfnWireSignal): Boolean =
            signal !is SpfnWireSignal.Attached && signal !is SpfnWireSignal.Unavailable;

        /**
         * The server's envelope `{"event": name, "data": payload}`, unwrapped. Null when it
         * is not one: nobody can decode what is not there.
         */
        fun envelopePayload(data: String): SpfnCanonicalValue? =
            (parse(data.toByteArray(Charsets.UTF_8)) as? SpfnCanonicalValue.Obj)?.members?.get("data");

        /** JSON the server wrote, or null when it is not JSON this SDK reads. */
        fun parse(bytes: ByteArray): SpfnCanonicalValue? = try
        {
            SpfnCanonicalJson.parse(bytes)
        }
        catch (_: IllegalArgumentException)
        {
            null
        };
    }
}
