// SPFN Mobile — the state client's engine: performs the core's effects, one input at a time.
//
// Ownership: the client owns the inbox; the engine owns the core and the jobs it starts.
// Only the client's one inbox coroutine calls into the engine, so the core and the maps
// below are never touched concurrently. The jobs it starts — requests, the timer, the two
// listeners — only send inputs back into the inbox.
//
// The signed-in boundary (§2-14, D5) is crossed twice. The key lifecycle's observer moves
// `SpfnStateShared` to the next account inside the call that stored its key, on that call's
// thread — `current` answers `Loading` from then on — and queues `Subject` for the core. Until
// the core has processed it, what the core still produces is the last account's: the engine
// hands no value to a subscriber or to `current`, and no answer to a call, while the two
// epochs differ. A `loading` still goes through: it carries no account's value.
//
// Sources/SPFNState/SPFNStateEngine.swift is the same runner in Swift.

package xyz.superfunction.spfn.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.launch
import xyz.superfunction.spfn.client.SpfnSleeper
import kotlin.coroutines.cancellation.CancellationException

internal sealed interface SpfnStateEngineInput
{
    data class Core(val input: SpfnStateInput) : SpfnStateEngineInput

    class Subscribe(val subscription: Int, val slot: SpfnStateSlot, val sink: SpfnStateSink) : SpfnStateEngineInput

    class Call(val answer: CompletableDeferred<SpfnStateResolution>, val input: SpfnStateInput, val call: Int) : SpfnStateEngineInput
}

/** One subscription's way out: its flow's channel, typed by the caller. */
internal class SpfnStateSink(val deliver: (SpfnStateCell?) -> Unit, val finish: () -> Unit)

/** What the engine reaches beyond the core. */
internal class SpfnStateServices(
    val stream: SpfnStateStream,
    val names: List<String>,
    val executor: SpfnStateExecutor,
    val sleeper: SpfnSleeper,
    val schedule: (() -> Unit) -> Unit,
    val onError: (Throwable) -> Unit,
    val scope: CoroutineScope
)

internal class SpfnStateEngine(
    private val core: SpfnStateCore,
    private val services: SpfnStateServices,
    private val shared: SpfnStateShared,
    private val inbox: SendChannel<SpfnStateEngineInput>
)
{
    private val sinks = mutableMapOf<Int, SpfnStateSink>();
    private val calls = mutableMapOf<Int, CompletableDeferred<SpfnStateResolution>>();
    private val requests = mutableMapOf<Int, Job>();
    private var listening = listOf<Job>();
    private var timer: Job? = null;

    init
    {
        // `current` answers what `initial` brought from construction on, before the loop runs.
        core.start().filterIsInstance<SpfnStateEffect.Publish>().forEach { shared.publish(it.slot, it.cell) };
    }

    /** The wire listener and the stream's states, from construction until `close()`. */
    fun attach()
    {
        perform(SpfnStateEffect.AttachWire);
    }

    fun process(input: SpfnStateEngineInput)
    {
        when (input)
        {
            is SpfnStateEngineInput.Core ->
            {
                if (input.input is SpfnStateInput.Answered)
                {
                    requests.remove(input.input.request);
                }
                core.process(input.input).forEach { perform(it) };
            }
            is SpfnStateEngineInput.Subscribe ->
            {
                sinks[input.subscription] = input.sink;
                core.process(SpfnStateInput.Subscribe(input.subscription, input.slot)).forEach { perform(it) };
            }
            is SpfnStateEngineInput.Call ->
            {
                calls[input.call] = input.answer;
                core.process(input.input).forEach { perform(it) };
            }
        };
    }

    /** The scope ended: nothing keeps running, every subscription ends, every call is refused. */
    fun shutDown()
    {
        (listening + requests.values + listOfNotNull(timer)).forEach { it.cancel() };
        sinks.values.forEach { it.finish() };
        calls.values.forEach { it.completeExceptionally(SpfnStateClientError.Closed) };
        sinks.clear();
        calls.clear();
    }

    private fun perform(effect: SpfnStateEffect)
    {
        when (effect)
        {
            // A `Loading` carries no account's value, so it is handed on even while behind.
            is SpfnStateEffect.Deliver -> if (current || effect.cell == null) sinks[effect.subscription]?.deliver?.invoke(effect.cell)
            is SpfnStateEffect.Finish -> sinks.remove(effect.subscription)?.finish?.invoke()
            is SpfnStateEffect.Publish -> if (current) shared.publish(effect.slot, effect.cell)
            SpfnStateEffect.Clear -> shared.clear()
            is SpfnStateEffect.Execute -> execute(effect.request, effect.what)
            is SpfnStateEffect.SetQuery -> runCatching { services.stream.setQuery(effect.query) }
            SpfnStateEffect.ReconnectNow -> services.stream.reconnectNow()
            is SpfnStateEffect.Connection -> shared.connection(effect.connection)
            is SpfnStateEffect.StartRetryTimer -> startTimer(effect.generation, effect.millis)
            SpfnStateEffect.CancelRetryTimer -> timer?.cancel()
            is SpfnStateEffect.Report -> services.onError(effect.error)
            is SpfnStateEffect.Resolve ->
                calls.remove(effect.call)?.completeWith(if (current) effect.result else Result.failure(SpfnStateClientError.SignedInChanged))
            SpfnStateEffect.CountDropped -> shared.countDropped()
            SpfnStateEffect.ScheduleFlush -> services.schedule { inbox.trySend(SpfnStateEngineInput.Core(SpfnStateInput.Flush)) }
            SpfnStateEffect.AttachWire -> listen()
            SpfnStateEffect.DetachWire ->
            {
                listening.forEach { it.cancel() };
                listening = emptyList();
            }
        };
    }

    /** The core works for the account [shared] was last moved to (D5). */
    private val current: Boolean
        get() = core.epoch == shared.epoch;

    /** A cancelled request sends nothing back: cancellation is not a failure (P16). */
    private fun execute(request: Int, what: SpfnStateRequest)
    {
        requests[request] = services.scope.launch {
            val result = runCatching { what.perform(services.executor) };
            if (result.exceptionOrNull() is CancellationException)
            {
                return@launch;
            }
            inbox.trySend(SpfnStateEngineInput.Core(SpfnStateInput.Answered(request, result)));
        };
    }

    private fun startTimer(generation: Int, millis: Long)
    {
        timer?.cancel();
        timer = services.scope.launch {
            services.sleeper.sleep(millis);
            inbox.trySend(SpfnStateEngineInput.Core(SpfnStateInput.RetryElapsed(generation)));
        };
    }

    private fun listen()
    {
        val wire = services.stream.listenWire(services.names);
        val states = services.stream.states;
        listening = listOf(
            services.scope.launch { wire.collect { inbox.trySend(SpfnStateEngineInput.Core(SpfnStateInput.Wire(it))) } },
            services.scope.launch { states.collect { inbox.trySend(SpfnStateEngineInput.Core(SpfnStateInput.Stream(it))) } }
        );
    }
}

/** What the synchronous reads answer from. */
internal class SpfnStateShared
{
    private val lock = Any();
    private val cells = mutableMapOf<SpfnStateSlot, SpfnStateCell>();
    private val status = MutableStateFlow<SpfnStateConnection>(SpfnStateConnection.Reconnecting(0));
    private var dropped = 0;
    private var subject: Subject? = null;
    private var changes = 0;

    private data class Subject(val clientId: String?)

    val connection: StateFlow<SpfnStateConnection> = status.asStateFlow();

    /** Counts the changes of the signed-in account, as the core's `epoch` does. */
    val epoch: Int
        get() = synchronized(lock) { changes };

    val droppedFrames: Int
        get() = synchronized(lock) { dropped };

    fun cell(slot: SpfnStateSlot): SpfnStateCell? = synchronized(lock) { cells[slot] };

    fun publish(slot: SpfnStateSlot, cell: SpfnStateCell)
    {
        synchronized(lock) { cells[slot] = cell };
    }

    fun clear()
    {
        synchronized(lock) { cells.clear() };
    }

    /**
     * The key lifecycle's observer, inside the call that stored the key: the first id is the
     * baseline; another one empties what `current` answers before that call returns.
     */
    fun signedIn(clientId: String?)
    {
        synchronized(lock) {
            val known = subject;
            subject = Subject(clientId);
            if (known != null && known != subject)
            {
                changes += 1;
                cells.clear();
            }
        };
    }

    fun countDropped()
    {
        synchronized(lock) { dropped += 1 };
    }

    fun connection(next: SpfnStateConnection)
    {
        status.value = next;
    }

    companion object
    {
        /** A cell as the entry of the descriptor's types; `Loading` for none. */
        fun <V, D> entry(cell: SpfnStateCell?): SpfnStateEntry<V, D>
        {
            if (cell == null)
            {
                return SpfnStateEntry.Loading;
            }
            @Suppress("UNCHECKED_CAST")
            val frame = cell.frame?.typed as? SpfnStateFrame<V, D>;
            if (cell.status == SpfnStateStatus.REVOKED)
            {
                return SpfnStateEntry.Revoked(frame);
            }
            return if (frame != null) SpfnStateEntry.Ready(frame) else SpfnStateEntry.Loading;
        }

        /** An error's name: a state client error as it prints, or the type of anything else. */
        fun name(error: Throwable): String = if (error is SpfnStateClientError) error.toString() else error.javaClass.name;
    }
}
