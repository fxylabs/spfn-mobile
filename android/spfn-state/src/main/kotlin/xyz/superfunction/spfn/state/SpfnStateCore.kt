// SPFN Mobile — the state client's decisions.
//
// The web client's (`@spfn/core/state/client`, `client.ts`) rules, on the native stream:
// the first snapshot before anything else (Man), one connection query that is the union of
// the subscribed keys (Str, §2-7), every arrival through the store's ordering rule (Ord),
// revoked marks per connection (Rev, §2-8), the connection status (Con, §2-9) and the
// initial snapshot (Cfg, §2-10). The signed-in boundary (§2-14) is the native client's own:
// one client lives through every account, so a change of account empties the store. Where
// the native stream differs — it is the app's, opened
// by the SDK's lifecycle, and its `Opened` signal arrives in the frames' queue — §2-2 and
// §2-6 say what stands in for the web client's own `SSEClient`.
//
// One input at a time, effects back; nothing here waits or locks. SpfnStateClient's engine
// is the only caller.
//
// Sources/SPFNState/SPFNStateCore.swift is the same core in Swift.

package xyz.superfunction.spfn.state

import xyz.superfunction.spfn.client.SpfnEventStreamState
import xyz.superfunction.spfn.client.SpfnIdleReason
import xyz.superfunction.spfn.client.SpfnStreamConnected
import xyz.superfunction.spfn.client.SpfnSubscriptionStatus
import xyz.superfunction.spfn.client.SpfnWireSignal
import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnDecoding

internal class SpfnStateCore(private val configuration: Configuration, initial: SpfnStateInitial?)
{
    class Configuration(
        val states: List<SpfnStateAccess>,
        val snapshot: (SpfnStateKey) -> SpfnCall<Unit, SpfnCanonicalValue>,
        val retryDelayMillis: Long,
        val maxRetries: Int
    )

    /** Which connection an automatic read was started for, and when (§2-8). */
    private class Tag(val lifetime: Int, val generation: Int, val tick: Int)

    private sealed interface Pending
    {
        /** The app call this request answers; null for an automatic read. */
        val call: Int?

        class Snapshot(val query: SpfnStateKey, val tag: Tag, override val call: Int?, val first: Boolean) : Pending

        class Shadow(val slot: SpfnStateSlot, val tag: Tag) : Pending
        {
            override val call: Int?
                get() = null;
        }

        class Get(val slot: SpfnStateSlot, override val call: Int?) : Pending

        class Write(val slot: SpfnStateSlot, override val call: Int, val decode: (SpfnCanonicalValue) -> Any?) : Pending
    }

    /** A signed-in client id the key lifecycle reported, or none. */
    private data class Subject(val clientId: String?)

    /** An app call waiting for the first snapshot. */
    private sealed interface Waiter
    {
        val call: Int

        class Get(override val call: Int, val slot: SpfnStateSlot) : Waiter

        class Write(override val call: Int, val slot: SpfnStateSlot, val request: SpfnStateRequest, val decode: (SpfnCanonicalValue) -> Any?) : Waiter
    }

    private val states: Map<String, SpfnStateAccess> = configuration.states.reversed().associateBy { it.id };
    private var effects = mutableListOf<SpfnStateEffect>();

    private val store = SpfnStateStore();
    private var manifest: Map<String, SpfnStatesSnapshot.State>? = null;
    private val subscriptions = mutableMapOf<Int, SpfnStateSlot>();
    /** Subscribed slots in the order they were first subscribed — the latest key wins (§2-7). */
    private val watched = mutableListOf<SpfnStateSlot>();
    private val watchCounts = mutableMapOf<SpfnStateSlot, Int>();
    private var flushQueued = false;

    private val pending = mutableMapOf<Int, Pending>();
    private var nextRequest = 0;
    private var firstRead: Int? = null;
    private var waiters = mutableListOf<Waiter>();
    /** Subscriptions wait on the first snapshot: its read in flight, or its retry timer set. */
    private var waiting = false;
    private var retries = 0;
    private var timerGeneration: Int? = null;
    private var timers = 0;

    private var lifetime = 0;
    private var closed = false;
    private var generation = 0;
    private var tick = 0;
    private val grants = mutableMapOf<SpfnStateSlot, Int>();
    private var connectionQuery: SpfnStateKey? = null;
    private var query: SpfnStateKey = emptyMap();
    private var streamBound = false;
    private var streamFailed = false;
    private var streamState: SpfnEventStreamState = SpfnEventStreamState.Idle(SpfnIdleReason.SIGNED_OUT);
    private var status: SpfnStateConnection = SpfnStateConnection.Reconnecting(0);
    private val reportedUnavailable = mutableSetOf<String>();

    /**
     * Whose values the store holds: null until the key lifecycle's first value, the baseline;
     * then the signed-in client id, or none (§2-14).
     */
    private var subject: Subject? = null;

    /** Counts the changes of [subject]. The engine hands nothing on that the core produced for an older one (D5). */
    var epoch = 0
        private set;

    /**
     * Wire frames, grants, revokes and overflows are taken only while this holds: always
     * while the subject is unknown, else from an `Opened` of a connection made for it (W-6,
     * W-7, W-16).
     */
    private var wireTrusted = true;

    /** From a change of subject until its connection opens: the status stays `Reconnecting(0)` (D6). */
    private var switching = false;

    /** The entries as the core holds them; the suites read them. */
    val cells: Map<SpfnStateSlot, SpfnStateCell>
        get() = store.cells;

    init
    {
        if (initial != null)
        {
            hydrate(initial);
        }
    }

    /** The effects of construction: the entries `initial` brought. */
    fun start(): List<SpfnStateEffect> = drain();

    fun process(input: SpfnStateInput): List<SpfnStateEffect>
    {
        effects = mutableListOf();
        when (input)
        {
            is SpfnStateInput.Subscribe -> subscribe(input.subscription, input.slot)
            is SpfnStateInput.Unsubscribe -> unsubscribe(input.subscription)
            SpfnStateInput.Flush -> flush()
            is SpfnStateInput.Wire -> wire(input.signal)
            is SpfnStateInput.Stream -> stream(input.state)
            is SpfnStateInput.Answered -> answered(input.request, input.result)
            is SpfnStateInput.Get -> requireManifest(Waiter.Get(input.call, input.slot))
            is SpfnStateInput.Write -> requireManifest(Waiter.Write(input.call, input.slot, input.request, input.decode))
            is SpfnStateInput.Snapshot -> readSnapshot(input.query ?: streamQuery(), tag(), input.call, first = false)
            SpfnStateInput.Retry -> retry()
            SpfnStateInput.Close -> close()
            is SpfnStateInput.RetryElapsed -> retryElapsed(input.generation)
            is SpfnStateInput.Subject -> follow(input.clientId)
        };
        return drain();
    }

    private fun drain(): List<SpfnStateEffect>
    {
        val produced = effects;
        effects = mutableListOf();
        return produced;
    }

    // ---- subscriptions and the query ---------------------------------------

    private fun subscribe(subscription: Int, slot: SpfnStateSlot)
    {
        if (closed)
        {
            closed = false;
            effects.add(SpfnStateEffect.AttachWire);
        }
        val refusal = refusal(slot, beforeManifest = manifest == null);
        if (refusal != null)
        {
            effects.add(SpfnStateEffect.Report(refusal));
            effects.add(SpfnStateEffect.Finish(subscription));
            return;
        }
        subscriptions[subscription] = slot;
        effects.add(SpfnStateEffect.Deliver(subscription, store.cells[slot]));
        watch(slot, 1);
    }

    private fun unsubscribe(subscription: Int)
    {
        val slot = subscriptions.remove(subscription) ?: return;
        watch(slot, -1);
    }

    /** An unsubscribe that leaves others while `Failed` changes nothing (C-17). */
    private fun watch(slot: SpfnStateSlot, delta: Int)
    {
        val count = (watchCounts[slot] ?: 0) + delta;
        if (count > 0)
        {
            if (watchCounts[slot] == null)
            {
                watched.add(slot);
            }
            watchCounts[slot] = count;
        }
        else
        {
            watchCounts.remove(slot);
            watched.remove(slot);
        }
        if (delta == 1 || watched.isEmpty() || status != SpfnStateConnection.Failed)
        {
            queueFlush();
        }
    }

    private fun queueFlush()
    {
        if (flushQueued)
        {
            return;
        }
        flushQueued = true;
        effects.add(SpfnStateEffect.ScheduleFlush);
    }

    /**
     * Nothing watched: no key in the query, the start status, the retry count over (C-18).
     * No manifest: wait for the first snapshot. Else the query, and the stream if it failed.
     */
    private fun flush()
    {
        flushQueued = false;
        if (watched.isEmpty())
        {
            retries = 0;
            waiting = false;
            cancelTimer();
            unbindStream();
            setConnection(SpfnStateConnection.Reconnecting(0));
            return;
        }
        if (manifest == null)
        {
            awaitManifest();
            return;
        }
        val union = streamQuery();
        if (!streamBound || streamFailed || union != query)
        {
            bindStream(union);
        }
    }

    /** The union of the subscribed keys' fields; on a conflict the latest subscribed key. */
    private fun streamQuery(): SpfnStateKey
    {
        val union = mutableMapOf<String, String>();
        watched.forEach { union.putAll(it.key) };
        return union;
    }

    /**
     * The web client's `openStream`: here the stream is the app's, so a new query is
     * `setQuery` and a failed stream is `reconnectNow`. Unless connected the status is the
     * start value until it opens; a reopen while connected stays connected (C-15).
     */
    private fun bindStream(union: SpfnStateKey)
    {
        val first = !streamBound;
        streamBound = true;
        var moved = false;
        if (union != query)
        {
            query = union;
            effects.add(SpfnStateEffect.SetQuery(union));
            moved = true;
        }
        if (streamFailed)
        {
            streamFailed = false;
            effects.add(SpfnStateEffect.ReconnectNow);
            moved = true;
        }
        if (moved && status != SpfnStateConnection.Connected)
        {
            setConnection(SpfnStateConnection.Reconnecting(0));
        }
        else if (first && !moved)
        {
            followStream(streamState);
        }
    }

    private fun unbindStream()
    {
        streamBound = false;
        streamFailed = false;
        if (query.isNotEmpty())
        {
            query = emptyMap();
            effects.add(SpfnStateEffect.SetQuery(emptyMap()));
        }
    }

    // ---- the manifest and the first snapshot -------------------------------

    /**
     * Why `slot` cannot be subscribed or read: a state outside the configuration, an id the
     * manifest does not name, key fields not the state's (K-8, K-9). Before the manifest only
     * the configuration is known, and a subscription waits for the rest (K-10).
     */
    private fun refusal(slot: SpfnStateSlot, beforeManifest: Boolean): SpfnStateClientError?
    {
        val state = states[slot.id] ?: return SpfnStateClientError.InvalidConfiguration("state '${slot.id}' is not among the configured states");
        if (beforeManifest)
        {
            return null;
        }
        val described = manifest?.get(slot.id) ?: return SpfnStateClientError.UnknownState(slot.id);
        val given = SpfnStateOrder.sortedFields(slot.key.keys);
        for (expected in listOf(SpfnStateOrder.sortedFields(described.key), state.keyFields))
        {
            if (expected != given)
            {
                return SpfnStateClientError.KeyFields(slot.id, expected, given);
            }
        }
        return null;
    }

    /** One waiter per lifetime. A subscribe while `Failed` starts the count over. */
    private fun awaitManifest()
    {
        if (waiting)
        {
            return;
        }
        if (status == SpfnStateConnection.Failed)
        {
            retries = 0;
            setConnection(SpfnStateConnection.Reconnecting(0));
        }
        waiting = true;
        ensureManifest();
    }

    /** The first snapshot, read once however many wait for it (I-9, I-10). */
    private fun ensureManifest()
    {
        if (manifest != null || firstRead != null)
        {
            return;
        }
        firstRead = readSnapshot(streamQuery(), tag(), call = null, first = true);
    }

    private fun requireManifest(waiter: Waiter)
    {
        if (manifest != null)
        {
            run(waiter);
            return;
        }
        waiters.add(waiter);
        ensureManifest();
    }

    private fun run(waiter: Waiter)
    {
        when (waiter)
        {
            is Waiter.Get ->
            {
                val refusal = keyRefusal(waiter.slot);
                if (refusal != null)
                {
                    effects.add(SpfnStateEffect.Resolve(waiter.call, Result.failure(refusal)));
                    return;
                }
                send(readRequest(waiter.slot), Pending.Get(waiter.slot, waiter.call));
            }
            is Waiter.Write ->
            {
                val refusal = keyRefusal(waiter.slot) ?: writeRefusal(waiter.slot);
                if (refusal != null)
                {
                    effects.add(SpfnStateEffect.Resolve(waiter.call, Result.failure(refusal)));
                    return;
                }
                send(waiter.request, Pending.Write(waiter.slot, waiter.call, waiter.decode));
            }
        };
    }

    /** A read or write is never built with key fields other than the descriptor's (§2-13-2). */
    private fun keyRefusal(slot: SpfnStateSlot): SpfnStateClientError?
    {
        val state = states[slot.id] ?: return SpfnStateClientError.InvalidConfiguration("state '${slot.id}' is not among the configured states");
        val given = SpfnStateOrder.sortedFields(slot.key.keys);
        return if (given == state.keyFields) null else SpfnStateClientError.KeyFields(slot.id, state.keyFields, given);
    }

    private fun writeRefusal(slot: SpfnStateSlot): SpfnStateClientError? =
        if (manifest?.get(slot.id)?.write == true) null else SpfnStateClientError.ReadOnly(slot.id);

    /**
     * The manifest is static: the first one stays. Subscriptions made before it are checked
     * now, and the stream follows for those it accepts — whichever call brought it.
     */
    private fun installManifest(installed: Map<String, SpfnStatesSnapshot.State>)
    {
        if (manifest != null)
        {
            return;
        }
        manifest = installed;
        for (slot in watched.toList())
        {
            val refusal = refusal(slot, beforeManifest = false) ?: continue;
            watched.remove(slot);
            watchCounts.remove(slot);
            for ((subscription, subscribed) in subscriptions.toList())
            {
                if (subscribed == slot)
                {
                    subscriptions.remove(subscription);
                    effects.add(SpfnStateEffect.Finish(subscription));
                }
            }
            effects.add(SpfnStateEffect.Report(refusal));
        }
        if (watched.isNotEmpty())
        {
            queueFlush();
        }
    }

    private fun firstSnapshotAnswered(result: Result<Unit>, lifetime: Int)
    {
        firstRead = null;
        val ready = waiters;
        waiters = mutableListOf();
        for (waiter in ready)
        {
            val failure = result.exceptionOrNull();
            when
            {
                failure != null -> effects.add(SpfnStateEffect.Resolve(waiter.call, Result.failure(failure)))
                manifest == null -> effects.add(SpfnStateEffect.Resolve(waiter.call, Result.failure(SpfnStateClientError.Closed)))
                else -> run(waiter)
            };
        }
        if (!waiting || lifetime != this.lifetime)
        {
            return;
        }
        val failure = result.exceptionOrNull();
        if (failure != null)
        {
            effects.add(SpfnStateEffect.Report(failure));
            retryLater();
            return;
        }
        waiting = false;
        cancelTimer();
        flush();
    }

    /**
     * Read the first snapshot again after `retryDelay` — or, with `maxRetries` spent, give
     * up: `Failed`, no timer, no wait (C-9, C-10). The first read is not a retry.
     */
    private fun retryLater()
    {
        if (configuration.maxRetries > 0 && retries >= configuration.maxRetries)
        {
            waiting = false;
            setConnection(SpfnStateConnection.Failed);
            return;
        }
        retries += 1;
        timers += 1;
        timerGeneration = timers;
        effects.add(SpfnStateEffect.StartRetryTimer(timers, configuration.retryDelayMillis));
    }

    private fun retryElapsed(generation: Int)
    {
        if (generation != timerGeneration)
        {
            return;
        }
        timerGeneration = null;
        waiting = false;
        flush();
    }

    private fun cancelTimer()
    {
        if (timerGeneration == null)
        {
            return;
        }
        timerGeneration = null;
        effects.add(SpfnStateEffect.CancelRetryTimer);
    }

    // ---- restarting --------------------------------------------------------

    /**
     * Nothing to do when connected or with nothing watched. The first snapshot again before
     * the manifest (C-11, C-12); the stream again after it (C-13).
     */
    private fun retry()
    {
        if (status == SpfnStateConnection.Connected || watched.isEmpty())
        {
            return;
        }
        if (manifest != null)
        {
            streamFailed = true;
            bindStream(streamQuery());
            setConnection(SpfnStateConnection.Reconnecting(0));
        }
        else
        {
            rereadManifest();
        }
    }

    /** The count starts over and the first snapshot is read now. One in flight is not doubled. */
    private fun rereadManifest()
    {
        retries = 0;
        if (waiting && timerGeneration == null)
        {
            return;
        }
        cancelTimer();
        setConnection(SpfnStateConnection.Reconnecting(0));
        waiting = false;
        flush();
    }

    /**
     * The client's lifetime ends: answers in flight are discarded, calls waiting on the first
     * snapshot are refused, subscriptions end, the query is emptied. The stream is the app's
     * and stays. The entries and the manifest stay: a later `subscribe` starts again (C-19).
     */
    private fun close()
    {
        lifetime += 1;
        cancelTimer();
        retries = 0;
        waiting = false;
        firstRead = null;
        for (waiter in waiters)
        {
            effects.add(SpfnStateEffect.Resolve(waiter.call, Result.failure(SpfnStateClientError.Closed)));
        }
        waiters = mutableListOf();
        unbindStream();
        for (subscription in subscriptions.keys.sorted())
        {
            effects.add(SpfnStateEffect.Finish(subscription));
        }
        subscriptions.clear();
        watched.clear();
        watchCounts.clear();
        setConnection(SpfnStateConnection.Reconnecting(0));
        if (!closed)
        {
            closed = true;
            effects.add(SpfnStateEffect.DetachWire);
        }
    }

    // ---- the signed-in subject ---------------------------------------------

    /**
     * The key lifecycle's client id (§2-14). The first is the baseline and clears nothing; the
     * same id again — a rotation — changes nothing (W-1, W-17). Another id, or none, ends the
     * last subject's values, and the wire is not trusted until the new subject's connection
     * opens; the baseline too waits for one, so a connection replayed for another account
     * when the client attaches is not taken.
     */
    private fun follow(clientId: String?)
    {
        val known = subject;
        if (known == Subject(clientId))
        {
            return;
        }
        subject = Subject(clientId);
        wireTrusted = false;
        if (known == null)
        {
            return;
        }
        epoch += 1;
        switching = true;
        forgetSubject();
    }

    /**
     * D2: the entries, stamps, marks and grants go; the manifest, the names already reported
     * unavailable and the subscriptions stay, each told `Loading` — once: one already at
     * `Loading` hears nothing (W-2 … W-5). Even a closed client forgets (W-18).
     */
    private fun forgetSubject()
    {
        val held = store.cells.keys.toSet();
        store.clear();
        grants.clear();
        effects.add(SpfnStateEffect.Clear);
        for ((subscription, slot) in subscriptions.entries.sortedBy { it.key })
        {
            if (slot in held)
            {
                effects.add(SpfnStateEffect.Deliver(subscription, null));
            }
        }
        refuseCalls();
        restartFirstSnapshot();
        setConnection(SpfnStateConnection.Reconnecting(0));
    }

    /**
     * Every request in flight was sent for the last subject: it is forgotten, so its answer
     * is applied to nothing (W-9, W-10), and an app call on one — or waiting for the
     * manifest — is refused now, once (W-11 … W-14).
     */
    private fun refuseCalls()
    {
        val calls = pending.values.mapNotNull { it.call } + waiters.map { it.call };
        for (call in calls.sorted())
        {
            effects.add(SpfnStateEffect.Resolve(call, Result.failure(SpfnStateClientError.SignedInChanged)));
        }
        pending.clear();
        waiters = mutableListOf();
    }

    /**
     * A first snapshot in flight was the last subject's and is forgotten with it; the
     * subscriptions that waited on it wait on one read for the next subject.
     */
    private fun restartFirstSnapshot()
    {
        if (firstRead == null)
        {
            return;
        }
        firstRead = null;
        if (waiting)
        {
            waiting = false;
            queueFlush();
        }
    }

    /**
     * While the subject is known, only a connection opened for it is trusted; any other —
     * the last account's, replayed or late — is dropped whole, its snapshot unread (W-7). The
     * subject's own ends the switch, and the status follows the stream again (W-8).
     */
    private fun trust(connected: SpfnStreamConnected): Boolean
    {
        val known = subject;
        if (known != null && connected.clientId != known.clientId)
        {
            return false;
        }
        wireTrusted = true;
        if (switching)
        {
            switching = false;
            if (streamBound)
            {
                followStream(streamState);
            }
        }
        return true;
    }

    // ---- the connection status ---------------------------------------------

    /**
     * The native stream's state, read as the web client reads its `SSEClient` callbacks —
     * only while the client has a stream to speak of: something watched, the manifest known.
     */
    private fun stream(state: SpfnEventStreamState)
    {
        val wasOffline = streamState is SpfnEventStreamState.Offline;
        streamState = state;
        if (wasOffline && state !is SpfnEventStreamState.Offline)
        {
            online();
        }
        if (streamBound)
        {
            followStream(state);
        }
    }

    private fun followStream(state: SpfnEventStreamState)
    {
        when (state)
        {
            is SpfnEventStreamState.Open ->
            {
                streamFailed = false;
                if (!switching)
                {
                    setConnection(SpfnStateConnection.Connected);
                }
            }
            is SpfnEventStreamState.Connecting ->
            {
                if (status == SpfnStateConnection.Failed)
                {
                    setConnection(SpfnStateConnection.Reconnecting(0));
                }
            }
            is SpfnEventStreamState.Retrying -> setConnection(SpfnStateConnection.Reconnecting(state.attempt))
            is SpfnEventStreamState.Offline -> setConnection(SpfnStateConnection.Reconnecting(state.attempt))
            is SpfnEventStreamState.Idle -> setConnection(SpfnStateConnection.Reconnecting(0))
            is SpfnEventStreamState.Closed ->
            {
                streamFailed = true;
                setConnection(SpfnStateConnection.Failed);
            }
        };
    }

    /**
     * The browser's `online`: the event module already takes the stream out of `Offline`, so
     * only the first snapshot is left to restart (C-20, C-21).
     */
    private fun online()
    {
        if (manifest != null || status == SpfnStateConnection.Connected || watched.isEmpty())
        {
            return;
        }
        rereadManifest();
    }

    private fun setConnection(next: SpfnStateConnection)
    {
        if (next == status)
        {
            return;
        }
        status = next;
        effects.add(SpfnStateEffect.Connection(next));
    }

    // ---- the wire ----------------------------------------------------------

    private fun wire(signal: SpfnWireSignal)
    {
        when (signal)
        {
            SpfnWireSignal.Attached -> Unit
            is SpfnWireSignal.Opened -> opened(signal.connected)
            is SpfnWireSignal.Frame -> if (wireTrusted) frame(signal.name, signal.value)
            is SpfnWireSignal.Subscription -> if (wireTrusted) subscription(signal.name, signal.status)
            SpfnWireSignal.Overflow ->
            {
                val carried = connectionQuery;
                if (wireTrusted && carried != null && manifest != null && watched.isNotEmpty())
                {
                    refresh(carried);
                }
            }
            is SpfnWireSignal.Unavailable -> unavailable(signal.names)
        };
    }

    /**
     * Every connection must cover what was sent while none was open. The snapshot is read
     * only when a described state is neither served in `initial` nor denied, or when the
     * server predates `initial` (C-26 … C-28); a denied watched state is revoked (R-3, R-4).
     */
    private fun opened(connected: SpfnStreamConnected)
    {
        if (!trust(connected))
        {
            return;
        }
        generation = connected.epoch;
        connectionQuery = connected.query;
        if (manifest == null || watched.isEmpty())
        {
            return;
        }
        val denied = connected.deniedEvents.toSet();
        val served = connected.initial.orEmpty().toSet();
        revokeDenied(denied, connected.query);
        val unserved = configuration.states.filter { !denied.contains(it.eventName) && !served.contains(it.eventName) };
        if (connected.initial != null && unserved.isEmpty())
        {
            readShadowedKeys(connected.query);
        }
        else
        {
            refresh(connected.query);
        }
    }

    private fun revokeDenied(denied: Set<String>, query: SpfnStateKey)
    {
        for (state in configuration.states)
        {
            if (!denied.contains(state.eventName))
            {
                continue;
            }
            val key = keyIn(state.id, query) ?: continue;
            val slot = SpfnStateSlot(state.id, key);
            if (watchCounts[slot] != null)
            {
                changed(slot, store.revoke(slot, generation));
            }
        }
    }

    /**
     * A frame for a key nobody here subscribed is dropped (O-11); a subscribed shadowed key's
     * frame is applied under the ordering rule (K-4). A frame that does not decode is counted.
     */
    private fun frame(name: String, value: SpfnCanonicalValue)
    {
        val state = configuration.states.firstOrNull { it.eventName == name } ?: return;
        val key = frameKey(value) ?: return;
        val slot = SpfnStateSlot(state.id, key);
        if (watchCounts[slot] == null)
        {
            return;
        }
        val arrival = decode(state, key, value);
        if (arrival == null)
        {
            effects.add(SpfnStateEffect.CountDropped);
            return;
        }
        changed(slot, store.serve(slot, arrival, generation));
    }

    /**
     * A revoke or grant concerns the key the connection carries. Granted: the mark goes and
     * the key is read once; the server's frame may come before or after (R-10).
     */
    private fun subscription(name: String, change: SpfnSubscriptionStatus)
    {
        val state = configuration.states.firstOrNull { it.eventName == name } ?: return;
        val key = keyIn(state.id, connectionQuery ?: emptyMap()) ?: return;
        val slot = SpfnStateSlot(state.id, key);
        when (change)
        {
            SpfnSubscriptionStatus.REVOKED -> changed(slot, store.revoke(slot, generation))
            SpfnSubscriptionStatus.GRANTED ->
            {
                tick += 1;
                grants[slot] = tick;
                store.allow(slot);
                if (watchCounts[slot] != null)
                {
                    send(readRequest(slot), Pending.Get(slot, call = null));
                }
            }
        };
    }

    /** C-24: said once per name; the state stays unserved, so every open reads the snapshot. */
    private fun unavailable(names: List<String>)
    {
        for (name in names)
        {
            if (reportedUnavailable.add(name))
            {
                effects.add(SpfnStateEffect.Report(SpfnStateClientError.Unavailable(name)));
            }
        }
    }

    // ---- reads -------------------------------------------------------------

    /** The snapshot for the connection's query, and a read of each key it shadows. */
    private fun refresh(query: SpfnStateKey)
    {
        readSnapshot(query, tag(), call = null, first = false);
        readShadowedKeys(query);
    }

    private fun readShadowedKeys(query: SpfnStateKey)
    {
        for (slot in watched.toList())
        {
            if (slot.key.isNotEmpty() && !SpfnStateOrder.carries(query, slot.key))
            {
                send(readRequest(slot), Pending.Shadow(slot, tag()));
            }
        }
    }

    private fun readSnapshot(query: SpfnStateKey, tag: Tag, call: Int?, first: Boolean): Int
    {
        val snapshot = configuration.snapshot(query);
        val request = SpfnStateRequest(SpfnStateRequest.Kind.Snapshot(query)) { it.execute(snapshot, Unit) };
        return send(request, Pending.Snapshot(query, tag, call, first));
    }

    private fun readRequest(slot: SpfnStateSlot): SpfnStateRequest
    {
        val call = states.getValue(slot.id).read(slot.key);
        return SpfnStateRequest(SpfnStateRequest.Kind.Read(slot)) { it.execute(call, Unit) };
    }

    private fun send(request: SpfnStateRequest, purpose: Pending): Int
    {
        nextRequest += 1;
        pending[nextRequest] = purpose;
        effects.add(SpfnStateEffect.Execute(nextRequest, request));
        return nextRequest;
    }

    private fun tag(): Tag
    {
        tick += 1;
        return Tag(lifetime, generation, tick);
    }

    // ---- answers -----------------------------------------------------------

    private fun answered(request: Int, result: Result<SpfnCanonicalValue>)
    {
        when (val purpose = pending.remove(request) ?: return)
        {
            is Pending.Snapshot -> snapshotAnswered(result, purpose.query, purpose.tag, purpose.call, purpose.first && firstRead == request)
            is Pending.Shadow -> readAnswered(result, purpose.slot, purpose.tag.generation, call = null)
            is Pending.Get -> readAnswered(result, purpose.slot, generation = null, call = purpose.call)
            is Pending.Write -> writeAnswered(result, purpose.slot, purpose.call, purpose.decode)
        };
    }

    /** A snapshot answered after `close()` is discarded; the call that asked still gets it. */
    private fun snapshotAnswered(result: Result<SpfnCanonicalValue>, query: SpfnStateKey, tag: Tag, call: Int?, first: Boolean)
    {
        val parsed = result.mapCatching { SpfnStatesSnapshot.decode(it) };
        val snapshot = parsed.getOrNull();
        if (snapshot != null && tag.lifetime == lifetime)
        {
            installManifest(snapshot.manifest);
            applyPresent(snapshot, query, tag.generation);
            applyOmitted(snapshot, query, tag);
        }
        val failure = parsed.exceptionOrNull();
        if (call != null)
        {
            effects.add(SpfnStateEffect.Resolve(call, parsed.map { SpfnStateResolution.Snapshot(it) }));
        }
        else if (!first && failure != null && tag.lifetime == lifetime)
        {
            effects.add(SpfnStateEffect.Report(failure));
        }
        if (first)
        {
            firstSnapshotAnswered(parsed.map { }, tag.lifetime);
        }
    }

    /**
     * Each state present, served under its connection. A state the manifest does not
     * describe, or a keyed one whose key the query lacks, is skipped (K-13, I-3, I-6).
     */
    private fun applyPresent(snapshot: SpfnStatesSnapshot, query: SpfnStateKey, generation: Int)
    {
        for (id in SpfnStateOrder.sortedFields(snapshot.states.keys))
        {
            val state = states[id] ?: continue;
            val key = keyIn(id, query) ?: continue;
            val entry = snapshot.states[id] ?: continue;
            val slot = SpfnStateSlot(id, key);
            val arrival = decode(state, key, SpfnStateArrival.canonical(snapshot.at, entry));
            if (arrival == null)
            {
                effects.add(SpfnStateEffect.CountDropped);
                continue;
            }
            changed(slot, store.serve(slot, arrival, generation));
        }
    }

    /**
     * A watched state the query asked for and the snapshot omits is not served to this
     * subject — unless a newer connection exists or a grant came after the read started
     * (R-5, R-6, R-15, R-16).
     */
    private fun applyOmitted(snapshot: SpfnStatesSnapshot, query: SpfnStateKey, tag: Tag)
    {
        if (tag.generation != generation)
        {
            return;
        }
        for (slot in watched.toList())
        {
            if (snapshot.states[slot.id] != null)
            {
                continue;
            }
            val asked = slot.key.isEmpty() || SpfnStateOrder.carries(query, slot.key);
            val grantedSince = (grants[slot] ?: 0) > tag.tick;
            if (asked && !grantedSince)
            {
                changed(slot, store.revoke(slot, tag.generation));
            }
        }
    }

    /**
     * A shadow read is tagged and lifts an older mark (R-13, R-14); an app's `get` and the read
     * after a grant are not, and only apply (§2-8).
     */
    private fun readAnswered(result: Result<SpfnCanonicalValue>, slot: SpfnStateSlot, generation: Int?, call: Int?)
    {
        val arrival = result.mapCatching { decodeOrThrow(states.getValue(slot.id), slot.key, it) };
        val frame = arrival.getOrNull();
        val failure = arrival.exceptionOrNull();
        if (frame != null)
        {
            changed(slot, if (generation != null) store.serve(slot, frame, generation) else store.apply(slot, frame));
        }
        else if (failure != null && call == null)
        {
            effects.add(SpfnStateEffect.Report(failure));
        }
        if (call != null)
        {
            effects.add(SpfnStateEffect.Resolve(call, arrival.map { SpfnStateResolution.Frame(it.typed) }));
        }
    }

    /**
     * A write's answer is applied at once, like a frame; one that cannot be read applies
     * nothing and the call is refused — the write's own frame brings the value (O-10, O-15).
     */
    private fun writeAnswered(result: Result<SpfnCanonicalValue>, slot: SpfnStateSlot, call: Int, decode: (SpfnCanonicalValue) -> Any?)
    {
        val arrival = result.mapCatching { arrival(it, decode(it)) };
        val frame = arrival.getOrNull();
        if (frame != null)
        {
            changed(slot, store.apply(slot, frame));
        }
        effects.add(SpfnStateEffect.Resolve(call, arrival.map { SpfnStateResolution.Frame(it.typed) }));
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * The key of state `id` the query names, by the manifest's fields; empty for a keyless
     * state; null when the query lacks one of the fields.
     */
    private fun keyIn(id: String, query: SpfnStateKey): SpfnStateKey?
    {
        val fields = manifest?.get(id)?.key ?: return null;
        val key = mutableMapOf<String, String>();
        for (field in fields)
        {
            key[field] = query[field] ?: return null;
        }
        return key;
    }

    private fun decode(state: SpfnStateAccess, key: SpfnStateKey, value: SpfnCanonicalValue): SpfnStateArrival? =
        runCatching { decodeOrThrow(state, key, value) }.getOrNull();

    private fun decodeOrThrow(state: SpfnStateAccess, key: SpfnStateKey, value: SpfnCanonicalValue): SpfnStateArrival =
        arrival(value, state.decode(key, value));

    /** A changed entry: `current` answers it, and every subscription of the slot hears it. */
    private fun changed(slot: SpfnStateSlot, cell: SpfnStateCell?)
    {
        if (cell == null)
        {
            return;
        }
        effects.add(SpfnStateEffect.Publish(slot, cell));
        for (subscription in subscriptions.keys.sorted())
        {
            if (subscriptions[subscription] == slot)
            {
                effects.add(SpfnStateEffect.Deliver(subscription, cell));
            }
        }
    }

    /**
     * Start from a snapshot already read: its manifest, then each state present, under
     * generation 0. Nothing is marked revoked: an omission there is no denial (I-1 … I-3).
     */
    private fun hydrate(initial: SpfnStateInitial)
    {
        installManifest(initial.snapshot.manifest);
        applyPresent(initial.snapshot, initial.query, tag().generation);
    }

    private companion object
    {
        fun arrival(value: SpfnCanonicalValue, typed: Any?): SpfnStateArrival
        {
            val members = SpfnDecoding.obj(value, "$");
            return SpfnStateArrival(
                at = SpfnDecoding.string(members["at"], "$.at"),
                value = members["value"] ?: SpfnCanonicalValue.Null,
                data = members["data"],
                typed = typed
            );
        }

        /** A frame's `key`; absent is keyless. A key with a field that is not a string is no key. */
        fun frameKey(value: SpfnCanonicalValue): SpfnStateKey?
        {
            val key = (value as? SpfnCanonicalValue.Obj)?.members?.get("key") ?: return emptyMap();
            val fields = (key as? SpfnCanonicalValue.Obj)?.members ?: return null;
            val result = mutableMapOf<String, String>();
            for ((field, text) in fields)
            {
                result[field] = (text as? SpfnCanonicalValue.Text)?.value ?: return null;
            }
            return result;
        }
    }
}
