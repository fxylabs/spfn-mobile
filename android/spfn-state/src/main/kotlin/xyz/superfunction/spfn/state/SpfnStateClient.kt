// SPFN Mobile — the state client: the server's states, kept current over the app's stream.
//
// `@spfn/core/state/client` on the native SDK. A subscription is a flow that yields the
// current entry first and every change after; `current` and `connection` answer at once;
// `get`, `write` and `snapshot` go through the generated calls and `execute` (§2-13-3).
//
// This file is the API. The decisions are SpfnStateCore's; the engine (SpfnStateEngine.kt)
// performs its effects — requests, the stream's query, the retry timer, the subscribers —
// and feeds each result back. Every input is processed in order by one coroutine on the
// given scope, the way SpfnEventStream runs its machine. The synchronous reads (`current`,
// `connection`) come from values the engine publishes under one lock.
//
// The stream is the app's and stays the app's: this client attaches a wire listener and
// writes the connection query, and never opens or closes the connection itself (§2-2).
//
// One client lives through every account the app signs in (§2-14): it observes the key
// lifecycle's signed-in client id from construction until its scope ends, synchronously, so
// no value received for one account is answered once another account's key — or none — is
// stored.
//
// Kotlin has no deinit: the client runs until its scope is cancelled, which is when its
// engine stops, its signed-in observer is removed and its stream may take another state
// client. `close()` is what detaches it
// from the stream while the scope lives.
//
// Sources/SPFNState/SPFNStateClient.swift is the same client in Swift.

package xyz.superfunction.spfn.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import xyz.superfunction.spfn.client.SpfnDelaySleeper
import xyz.superfunction.spfn.client.SpfnEventStream
import xyz.superfunction.spfn.client.SpfnEventStreamConfiguration
import xyz.superfunction.spfn.client.SpfnEventStreamState
import xyz.superfunction.spfn.client.SpfnKeyLifecycle
import xyz.superfunction.spfn.client.SpfnSignedInObserverAccess
import xyz.superfunction.spfn.client.SpfnSleeper
import xyz.superfunction.spfn.client.SpfnWireSignal
import xyz.superfunction.spfn.core.SpfnCall
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The server's states for one app, over its one event stream.
 *
 * @constructor The seam the suites use: a scripted stream, a scripted `execute`, a held
 *   timer, and the end of a turn on the test scheduler.
 * @throws IllegalArgumentException naming what is wrong: no state, an id twice, a
 *   `state.<id>.changed` missing from the stream's events, or a stream that already has a
 *   state client — the connection query has one owner (§2-13-1).
 */
class SpfnStateClient internal constructor(
    val configuration: SpfnStateClientConfiguration,
    scope: CoroutineScope,
    stream: SpfnStateStream,
    executor: SpfnStateExecutor,
    sleeper: SpfnSleeper,
    schedule: (() -> Unit) -> Unit
)
{
    /**
     * @param scope where every input is processed and every request runs. The app's scope,
     *   not a screen's: the client outlives every screen, like the stream.
     * @throws IllegalArgumentException see the class.
     */
    constructor(configuration: SpfnStateClientConfiguration, scope: CoroutineScope) : this(
        configuration = configuration,
        scope = scope,
        stream = SpfnEventStreamStateStream(configuration.stream),
        executor = SpfnKeyLifecycleExecutor(configuration.keyLifecycle),
        sleeper = SpfnDelaySleeper(),
        schedule = { work -> scope.launch { work() } }
    );

    internal val shared = SpfnStateShared();
    private val inbox = Channel<SpfnStateEngineInput>(Channel.UNLIMITED);
    private val ids = AtomicInteger();

    init
    {
        val accesses = configuration.accesses(stream.configuration);
        require(SpfnStateClaims.claim(stream.owner, this)) { "stream: it already has a state client" };
        val engine = SpfnStateEngine(
            core = SpfnStateCore(
                SpfnStateCore.Configuration(accesses, configuration.snapshot, configuration.retryDelayMillis, configuration.maxRetries),
                configuration.initial
            ),
            services = SpfnStateServices(stream, accesses.map { it.eventName }, executor, sleeper, schedule, configuration.onError, scope),
            shared = shared,
            inbox = inbox
        );
        val stopObserving = observeSignedIn(configuration.keyLifecycle);
        val loop = scope.launch {
            try
            {
                engine.attach();
                for (input in inbox)
                {
                    engine.process(input);
                }
            }
            finally
            {
                inbox.close();
                engine.shutDown();
            }
        };
        loop.invokeOnCompletion {
            stopObserving();
            SpfnStateClaims.release(stream.owner, this);
        };
    }

    /**
     * D5: called with the id at once — the baseline, queued before the loop runs — then
     * inside every call that changes it. The lambda holds the cache and the inbox, never the
     * client. Answers what removes it.
     */
    @OptIn(SpfnSignedInObserverAccess::class)
    private fun observeSignedIn(keyLifecycle: SpfnKeyLifecycle): () -> Unit
    {
        val cache = shared;
        val queue = inbox;
        val observation = keyLifecycle.observeSignedIn { clientId ->
            cache.signedIn(clientId);
            queue.trySend(SpfnStateEngineInput.Core(SpfnStateInput.Subject(clientId)));
        };
        return observation::cancel;
    }

    // ---- states ------------------------------------------------------------

    /**
     * The entry of `state` under `key` now, then every change of its status, value or data.
     * Attaches when collected; ending the collection unsubscribes. A refused subscription
     * (unknown id, other key fields) emits nothing and completes, its reason sent to
     * `onError` (K-8, K-9). Collecting twice is two subscriptions.
     */
    fun <V, D> subscribe(state: SpfnStateDescriptor<V, D>, key: SpfnStateKey = emptyMap()): Flow<SpfnStateEntry<V, D>>
    {
        val slot = SpfnStateSlot(state.id, key.toMap());
        return callbackFlow {
            val subscription = ids.incrementAndGet();
            val sink = SpfnStateSink(
                deliver = { trySend(SpfnStateShared.entry<V, D>(it)) },
                finish = { channel.close() }
            );
            if (inbox.trySend(SpfnStateEngineInput.Subscribe(subscription, slot, sink)).isFailure)
            {
                channel.close();
            }
            awaitClose { inbox.trySend(SpfnStateEngineInput.Core(SpfnStateInput.Unsubscribe(subscription))) };
        }.buffer(Channel.UNLIMITED);
    }

    /** The entry now; `Loading` when nothing has arrived for this key. */
    fun <V, D> current(state: SpfnStateDescriptor<V, D>, key: SpfnStateKey = emptyMap()): SpfnStateEntry<V, D> =
        SpfnStateShared.entry(shared.cell(SpfnStateSlot(state.id, key)));

    /**
     * Reads `state` now — after the first snapshot when the manifest is not known yet — and
     * applies the answer under the ordering rule.
     */
    suspend fun <V, D> get(state: SpfnStateDescriptor<V, D>, key: SpfnStateKey = emptyMap()): SpfnStateFrame<V, D>
    {
        val slot = SpfnStateSlot(state.id, key.toMap());
        return frame(call { SpfnStateInput.Get(it, slot) });
    }

    /**
     * Writes `state` and applies the answer at once, before the write's own frame. Refused
     * before any request when the manifest says the state has no write (I-11).
     */
    suspend fun <V, D, I> write(state: SpfnWritableStateDescriptor<V, D, I>, input: I, key: SpfnStateKey = emptyMap()): SpfnStateFrame<V, D>
    {
        val slot = SpfnStateSlot(state.id, key.toMap());
        val put = state.write(slot.key);
        val request = SpfnStateRequest(SpfnStateRequest.Kind.Write(slot)) { it.execute(put, input) };
        val access = state.access;
        return frame(call { SpfnStateInput.Write(it, slot, request) { value -> access.decode(slot.key, value) } });
    }

    /**
     * Reads every state at once — by default with the stream's query — and applies it: each
     * state present, and `Revoked` for a subscribed one it omits. A refusal changes nothing.
     */
    suspend fun snapshot(query: SpfnStateKey? = null): SpfnStatesSnapshot
    {
        val frozen = query?.toMap();
        val resolution = call { SpfnStateInput.Snapshot(it, frozen) };
        return (resolution as? SpfnStateResolution.Snapshot)?.snapshot ?: throw SpfnStateClientError.InvalidConfiguration("snapshot");
    }

    // ---- connection --------------------------------------------------------

    fun connection(): SpfnStateConnection = shared.connection.value;

    /** The status now, then every change; the same status is not repeated (C-22, C-23). */
    fun subscribeConnection(): StateFlow<SpfnStateConnection> = shared.connection;

    /**
     * Restart what gave up or is still retrying, now: the first snapshot before the
     * manifest, the stream after it. Nothing when connected or with nothing subscribed.
     */
    fun retry()
    {
        inbox.trySend(SpfnStateEngineInput.Core(SpfnStateInput.Retry));
    }

    /**
     * Ends every subscription, detaches from the stream and empties its query. The stream
     * stays open: it is the app's. A later `subscribe` starts again. Idempotent.
     */
    fun close()
    {
        inbox.trySend(SpfnStateEngineInput.Core(SpfnStateInput.Close));
    }

    /** Frames whose payload the state's decoder could not read (§2-4). */
    val droppedFrames: Int
        get() = shared.droppedFrames;

    // ---- calls -------------------------------------------------------------

    private suspend fun call(make: (Int) -> SpfnStateInput): SpfnStateResolution
    {
        val id = ids.incrementAndGet();
        val answer = CompletableDeferred<SpfnStateResolution>();
        if (inbox.trySend(SpfnStateEngineInput.Call(answer, make(id), id)).isFailure)
        {
            answer.completeExceptionally(SpfnStateClientError.Closed);
        }
        return answer.await();
    }

    companion object
    {
        /** The default `onError`: the error's name only. A key and a value are the app's data. */
        fun logError(error: Throwable)
        {
            println("SpfnState: ${SpfnStateShared.name(error)}");
        }

        private fun <V, D> frame(resolution: SpfnStateResolution): SpfnStateFrame<V, D>
        {
            @Suppress("UNCHECKED_CAST")
            val frame = (resolution as? SpfnStateResolution.Frame)?.typed as? SpfnStateFrame<V, D>;
            return frame ?: throw SpfnStateClientError.InvalidConfiguration("descriptor");
        }
    }
}

/** The stream as the state client uses it. `SpfnEventStream` is the one the app has; the suites script one. */
internal interface SpfnStateStream
{
    /** What the one-client-per-stream claim is held on: the app's stream object. */
    val owner: Any

    val configuration: SpfnEventStreamConfiguration

    val states: Flow<SpfnEventStreamState>

    fun listenWire(names: List<String>): Flow<SpfnWireSignal>

    fun setQuery(fields: Map<String, String>)

    fun reconnectNow()
}

/** [SpfnEventStream] as a [SpfnStateStream]: Kotlin cannot add an interface to a foreign class. */
internal class SpfnEventStreamStateStream(private val stream: SpfnEventStream) : SpfnStateStream
{
    override val owner: Any
        get() = stream;

    override val configuration: SpfnEventStreamConfiguration
        get() = stream.configuration;

    override val states: Flow<SpfnEventStreamState>
        get() = stream.state;

    override fun listenWire(names: List<String>): Flow<SpfnWireSignal> = stream.listenWire(names);

    override fun setQuery(fields: Map<String, String>)
    {
        stream.setQuery(fields);
    }

    override fun reconnectNow()
    {
        stream.reconnectNow();
    }
}

/** `execute` by whoever is signed in at the moment of the call (§2-3). Nobody: nothing is sent. */
internal class SpfnKeyLifecycleExecutor(private val keyLifecycle: SpfnKeyLifecycle) : SpfnStateExecutor
{
    override suspend fun <Req, Resp> execute(call: SpfnCall<Req, Resp>, request: Req): Resp
    {
        val client = keyLifecycle.signedInClient() ?: throw SpfnStateClientError.SignedOut;
        return client.execute(call, request);
    }
}

/**
 * Which streams have a state client: the query has one owner (§2-2). Held weakly on both
 * sides, so a stream or a client nobody holds any more claims nothing.
 */
internal object SpfnStateClaims
{
    private val claimed = WeakHashMap<Any, WeakReference<SpfnStateClient>>();

    fun claim(stream: Any, client: SpfnStateClient): Boolean
    {
        synchronized(claimed)
        {
            if (claimed[stream]?.get() != null)
            {
                return false;
            }
            claimed[stream] = WeakReference(client);
            return true;
        };
    }

    fun release(stream: Any, client: SpfnStateClient)
    {
        synchronized(claimed)
        {
            if (claimed[stream]?.get() === client)
            {
                claimed.remove(stream);
            }
        };
    }
}
