// SPFN Mobile — the state client's core, driven step by step (design §4).
//
// `StateCoreRun` feeds SpfnStateCore one input at a time and records what each produced:
// deliveries per subscription, queries handed to the stream, requests, errors, connection
// changes, app call results. After every step it runs the flush the step asked for, so a
// step is a turn; `settle = false` keeps two steps in one turn (K-6, K-7).
//
// `StateCases` reads Contracts/fixtures/state/cases.json and runs one case through the same
// run. Tests/SPFNStateTests/StateCoreRun.swift is the counterpart.

package xyz.superfunction.spfn.state

import org.junit.Assert.fail
import xyz.superfunction.spfn.client.SpfnEventStreamState
import xyz.superfunction.spfn.client.SpfnStreamConnected
import xyz.superfunction.spfn.client.SpfnWireSignal
import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnDecoding
import xyz.superfunction.spfn.core.SpfnDecodingException
import xyz.superfunction.spfn.core.SpfnOperation

/** A failure the scripted `execute` answers with: "network" in the cases. */
class StateTestNetworkError : Exception("network")

typealias CanonicalFrame = SpfnStateFrame<SpfnCanonicalValue, SpfnCanonicalValue>

internal class StateCoreRun(
    described: List<String> = DESCRIBED,
    initial: SpfnStateInitial? = null,
    maxRetries: Int = 0
)
{
    val core = SpfnStateCore(
        SpfnStateCore.Configuration(
            states = described.map { descriptor(it).access },
            snapshot = { unsent { it } },
            retryDelayMillis = 3_000,
            maxRetries = maxRetries
        ),
        initial
    );
    val cells = mutableMapOf<SpfnStateSlot, SpfnStateCell>();
    var connection: SpfnStateConnection = SpfnStateConnection.Reconnecting(0)
        private set;
    private val subscriptions = mutableMapOf<String, Int>();
    private val calls = mutableMapOf<String, Int>();
    private var counter = 0;
    private var flushPending = false;
    var timer: Int? = null
        private set;
    private val pending = mutableListOf<Pair<Int, SpfnStateRequest.Kind>>();

    // Since the last `forget()`.
    val deliveries = mutableMapOf<String, MutableList<SpfnStateCell?>>();
    val finished = mutableListOf<String>();
    val queries = mutableListOf<SpfnStateKey>();
    var reconnects = 0
        private set;
    val requests = mutableListOf<SpfnStateRequest.Kind>();
    val errors = mutableListOf<Throwable>();
    val connections = mutableListOf<SpfnStateConnection>();
    val resolved = mutableMapOf<String, Result<SpfnStateResolution>>();
    var timersStarted = 0
        private set;
    var dropped = 0
        private set;
    val watches = mutableMapOf<String, MutableList<SpfnStateConnection>>();
    val wire = mutableListOf<String>();

    init
    {
        record(core.start());
        forget();
    }

    /** Clears what was recorded; the state itself stays. */
    fun forget()
    {
        deliveries.clear();
        finished.clear();
        queries.clear();
        reconnects = 0;
        requests.clear();
        errors.clear();
        connections.clear();
        resolved.clear();
        timersStarted = 0;
        dropped = 0;
        wire.clear();
        watches.values.forEach { it.clear() };
    }

    // ---- steps -------------------------------------------------------------

    fun feed(input: SpfnStateInput, settle: Boolean = true)
    {
        record(core.process(input));
        if (settle)
        {
            settle();
        }
    }

    /** The end of the turn: the flush a step asked for. */
    fun settle()
    {
        while (flushPending)
        {
            flushPending = false;
            record(core.process(SpfnStateInput.Flush));
        }
    }

    fun subscribe(name: String, state: String, key: SpfnStateKey = emptyMap(), settle: Boolean = true)
    {
        counter += 1;
        subscriptions[name] = counter;
        deliveries.getOrPut(name) { mutableListOf() };
        feed(SpfnStateInput.Subscribe(counter, SpfnStateSlot(state, key)), settle);
    }

    fun unsubscribe(name: String, settle: Boolean = true)
    {
        feed(SpfnStateInput.Unsubscribe(subscriptions.getValue(name)), settle);
    }

    fun stream(state: SpfnEventStreamState)
    {
        feed(SpfnStateInput.Stream(state));
    }

    fun opened(
        epoch: Int,
        query: SpfnStateKey = emptyMap(),
        initial: List<String>? = null,
        denied: List<String> = emptyList(),
        described: List<String> = DESCRIBED,
        clientId: String? = null
    )
    {
        val events = described.map { SpfnStateAccess.eventName(it) };
        val connected = SpfnStreamConnected(
            epoch = epoch,
            query = query,
            subscribedEvents = events.filter { !denied.contains(it) },
            deniedEvents = denied,
            initial = initial,
            clientId = clientId
        );
        feed(SpfnStateInput.Wire(SpfnWireSignal.Opened(connected)));
    }

    /** Opens connection `epoch`, its `initial` serving every described state. */
    fun open(epoch: Int, query: SpfnStateKey = emptyMap(), described: List<String> = DESCRIBED)
    {
        stream(SpfnEventStreamState.Open(epoch));
        opened(epoch, query, initial = described.map { SpfnStateAccess.eventName(it) }, described = described);
    }

    fun frame(state: String, at: String, value: SpfnCanonicalValue, key: SpfnStateKey? = null, data: SpfnCanonicalValue? = null)
    {
        val members = mutableMapOf("at" to text(at), "value" to value);
        data?.let { members["data"] = it };
        key?.let { members["key"] = keyValue(it) };
        feed(SpfnStateInput.Wire(SpfnWireSignal.Frame(SpfnStateAccess.eventName(state), SpfnCanonicalValue.Obj(members))));
    }

    fun get(call: String, state: String, key: SpfnStateKey = emptyMap())
    {
        feed(SpfnStateInput.Get(callId(call), SpfnStateSlot(state, key)));
    }

    fun write(call: String, state: String, key: SpfnStateKey = emptyMap())
    {
        val slot = SpfnStateSlot(state, key);
        val request = SpfnStateRequest(SpfnStateRequest.Kind.Write(slot)) { throw StateTestNetworkError() };
        feed(SpfnStateInput.Write(callId(call), slot, request) { decodeFrame(it) });
    }

    fun snapshot(call: String, query: SpfnStateKey?)
    {
        feed(SpfnStateInput.Snapshot(callId(call), query));
    }

    /** Answers the oldest unanswered request of that kind. */
    fun answer(kind: String, result: Result<SpfnCanonicalValue>)
    {
        val index = pending.indexOfFirst { name(it.second) == kind };
        if (index < 0)
        {
            fail("no $kind request is waiting");
        }
        feed(SpfnStateInput.Answered(pending.removeAt(index).first, result));
    }

    fun fireTimer()
    {
        val generation = checkNotNull(timer) { "no retry timer is set" };
        timer = null;
        feed(SpfnStateInput.RetryElapsed(generation));
    }

    fun watchConnection(name: String)
    {
        watches[name] = mutableListOf(connection);
    }

    fun result(call: String): Result<SpfnStateResolution>? = resolved[call];

    private fun callId(name: String): Int
    {
        counter += 1;
        calls[name] = counter;
        return counter;
    }

    // ---- effects -----------------------------------------------------------

    private fun record(effects: List<SpfnStateEffect>)
    {
        effects.forEach { record(it) };
    }

    private fun record(effect: SpfnStateEffect)
    {
        when (effect)
        {
            is SpfnStateEffect.Deliver -> deliveries.getOrPut(subscriptionName(effect.subscription)) { mutableListOf() }.add(effect.cell)
            is SpfnStateEffect.Finish -> finished.add(subscriptionName(effect.subscription))
            is SpfnStateEffect.Publish -> cells[effect.slot] = effect.cell
            SpfnStateEffect.Clear -> cells.clear()
            is SpfnStateEffect.Execute ->
            {
                pending.add(effect.request to effect.what.kind);
                requests.add(effect.what.kind);
            }
            is SpfnStateEffect.SetQuery -> queries.add(effect.query)
            SpfnStateEffect.ReconnectNow -> reconnects += 1
            is SpfnStateEffect.Connection ->
            {
                connection = effect.connection;
                connections.add(effect.connection);
                watches.values.forEach { it.add(effect.connection) };
            }
            is SpfnStateEffect.StartRetryTimer ->
            {
                timer = effect.generation;
                timersStarted += 1;
            }
            SpfnStateEffect.CancelRetryTimer -> timer = null
            is SpfnStateEffect.Report -> errors.add(effect.error)
            is SpfnStateEffect.Resolve -> resolved[calls.entries.first { it.value == effect.call }.key] = effect.result
            SpfnStateEffect.CountDropped -> dropped += 1
            SpfnStateEffect.ScheduleFlush -> flushPending = true
            SpfnStateEffect.AttachWire -> wire.add("attach")
            SpfnStateEffect.DetachWire -> wire.add("detach")
        };
    }

    private fun subscriptionName(id: Int): String = subscriptions.entries.first { it.value == id }.key;

    companion object
    {
        val DESCRIBED = listOf("aiAccounts", "consent", "runtime");
        val KEYED = setOf("aiAccounts", "runtime");

        fun descriptor(id: String): SpfnWritableStateDescriptor<SpfnCanonicalValue, SpfnCanonicalValue, SpfnCanonicalValue> =
            SpfnStateDescriptor(
                id = id,
                keyFields = if (KEYED.contains(id)) listOf("workspaceId") else emptyList(),
                read = { unsent<Unit, CanonicalFrame> { decodeFrame(it) } },
                frame = { it }
            ).writable(write = { unsent<SpfnCanonicalValue, CanonicalFrame> { decodeFrame(it) } });

        /** `{ at, value, data? }` as a frame of canonical values; `data` absent is `Null`. */
        fun decodeFrame(value: SpfnCanonicalValue): CanonicalFrame
        {
            val members = SpfnDecoding.obj(value, "$");
            val state = members["value"] ?: throw SpfnDecodingException("TYPE_MISMATCH", "expected a value at $.value");
            return SpfnStateFrame(SpfnDecoding.string(members["at"], "$.at"), state, members["data"] ?: SpfnCanonicalValue.Null);
        }

        private fun <Req, Resp> unsent(decode: (SpfnCanonicalValue) -> Resp): SpfnCall<Req, Resp> =
            SpfnCall(
                operation = SpfnOperation(id = "test.unsent", method = "GET", path = "/unsent", authProfile = "none", requiresSession = false, declaresResponse = true),
                encode = { null },
                decode = decode
            );

        fun name(kind: SpfnStateRequest.Kind): String = when (kind)
        {
            is SpfnStateRequest.Kind.Snapshot -> "snapshot"
            is SpfnStateRequest.Kind.Read -> "read"
            is SpfnStateRequest.Kind.Write -> "write"
        };
    }
}

// ---- values ----------------------------------------------------------------

internal fun text(value: String): SpfnCanonicalValue = SpfnCanonicalValue.Text(value);

internal fun keyValue(key: SpfnStateKey): SpfnCanonicalValue = SpfnCanonicalValue.Obj(key.mapValues { text(it.value) });

internal fun texts(texts: List<String>): SpfnCanonicalValue = SpfnCanonicalValue.Arr(texts.map { text(it) });

/** `ready(at, value)`, `revoked(at, value)` and `revoked` alone, for comparing. */
internal fun describe(cell: SpfnStateCell?): String
{
    if (cell == null)
    {
        return "loading";
    }
    val frame = cell.frame ?: return if (cell.status == SpfnStateStatus.REVOKED) "revoked" else "loading";
    val data = frame.data?.let { ", " + SpfnCanonicalJson.encodeToString(it) } ?: "";
    return "${cell.status.name.lowercase()}(${frame.at}, ${SpfnCanonicalJson.encodeToString(frame.value)}$data)";
}
