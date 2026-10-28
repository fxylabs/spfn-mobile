// SPFN Mobile — Contracts/fixtures/state/cases.json, run through StateCoreRun.
//
// The case table is the README's, written by hand (Contracts/fixtures/state_cases.py says
// how). Each case is run once per arrival order; `after` is checked against what the arrival
// produced. Tests/SPFNStateTests/StateCases.swift reads the same file the same way.

package xyz.superfunction.spfn.state

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import xyz.superfunction.spfn.client.SpfnCloseReason
import xyz.superfunction.spfn.client.SpfnEventStreamState
import xyz.superfunction.spfn.client.SpfnIdleReason
import xyz.superfunction.spfn.client.SpfnRetryReason
import xyz.superfunction.spfn.client.SpfnSubscriptionStatus
import xyz.superfunction.spfn.client.SpfnWireSignal
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnDecoding
import xyz.superfunction.spfn.core.SpfnDecodingException
import java.io.File

internal object StateCases
{
    /** Injected by the Gradle test task, so the suite reads the checkout the build runs against. */
    private val repoRoot = File(requireNotNull(System.getProperty("spfn.repoRoot")) { "spfn.repoRoot is not set; the module build script must pass it to the test task" });

    val table: Map<String, SpfnCanonicalValue> by lazy()
    {
        SpfnDecoding.obj(SpfnCanonicalJson.parse(File(repoRoot, "Contracts/fixtures/state/cases.json").readBytes()), "$")
    };

    private val cases: List<Map<String, SpfnCanonicalValue>>
        get() = SpfnDecoding.array(table["cases"], "$.cases").map { SpfnDecoding.obj(it, "$.cases[]") };

    fun run(id: String)
    {
        val one = StateCase(checkNotNull(cases.firstOrNull { it["id"] == text(id) }) { "no case $id in cases.json" });
        one.arrivals.forEachIndexed { index, arrival ->
            val run = one.start();
            one.steps(one.before, run);
            run.forget();
            one.steps(arrival, run);
            one.check(run, index);
        };
    }
}

internal class StateCase(private val members: Map<String, SpfnCanonicalValue>)
{
    val id: String
        get() = members["id"]?.string ?: "?";

    private val described: List<String>
        get() = members["described"]?.strings ?: StateCoreRun.DESCRIBED;

    val before: List<SpfnCanonicalValue>
        get() = members["before"]?.array.orEmpty();

    val arrivals: List<List<SpfnCanonicalValue>>
        get() = members["arrivals"]?.array?.map { it.array.orEmpty() } ?: listOf(members["arrival"]?.array.orEmpty());

    fun start(): StateCoreRun
    {
        val initial = members["initial"]?.let()
        {
            val fields = SpfnDecoding.obj(it, "$.initial");
            SpfnStateInitial(SpfnStatesSnapshot.decode(fields.getValue("snapshot")), fields["query"]?.key ?: emptyMap())
        };
        return StateCoreRun(described, initial, (members["maxRetries"]?.integer ?: 0L).toInt());
    }

    fun steps(steps: List<SpfnCanonicalValue>, run: StateCoreRun)
    {
        steps.forEach { step(SpfnDecoding.obj(it, "$.step"), run) };
    }

    // ---- steps -------------------------------------------------------------

    private fun step(step: Map<String, SpfnCanonicalValue>, run: StateCoreRun)
    {
        val state = step["state"]?.string ?: "";
        val key = step["key"]?.key ?: emptyMap();
        when (step["op"]?.string)
        {
            "subscribe" -> run.subscribe(step.text("sub"), state, key)
            "unsubscribe" -> run.unsubscribe(step.text("sub"))
            "stream" -> run.stream(streamState(step.getValue("state")))
            "opened" -> run.opened(
                epoch = step.getValue("epoch").integer!!.toInt(),
                query = step["query"]?.key ?: emptyMap(),
                initial = step["initial"]?.strings,
                denied = step["denied"]?.strings.orEmpty(),
                described = described,
                clientId = step["clientID"]?.string
            )
            "frame" -> run.frame(state, step.text("at"), step.getValue("value"), step["key"]?.key, step["data"])
            "granted" -> run.feed(SpfnStateInput.Wire(SpfnWireSignal.Subscription(SpfnStateAccess.eventName(state), SpfnSubscriptionStatus.GRANTED)))
            "revoked" -> run.feed(SpfnStateInput.Wire(SpfnWireSignal.Subscription(SpfnStateAccess.eventName(state), SpfnSubscriptionStatus.REVOKED)))
            "overflow" -> run.feed(SpfnStateInput.Wire(SpfnWireSignal.Overflow))
            "unavailable" -> run.feed(SpfnStateInput.Wire(SpfnWireSignal.Unavailable(step.getValue("names").strings!!)))
            "answer" -> run.answer(step.text("request"), answer(step))
            "get" -> run.get(step.text("call"), state, key)
            "write" -> run.write(step.text("call"), state, key)
            "snapshotCall" -> run.snapshot(step.text("call"), step["query"]?.key)
            "retry" -> run.feed(SpfnStateInput.Retry)
            "close" -> run.feed(SpfnStateInput.Close)
            "timer" -> run.fireTimer()
            "subscribeConnection" -> run.watchConnection(step.text("watch"))
            "subject" -> run.feed(SpfnStateInput.Subject(step.getValue("clientID").string))
            else -> fail("$id: unknown step $step")
        };
    }

    /** A snapshot answer without a manifest carries the table's. */
    private fun answer(step: Map<String, SpfnCanonicalValue>): Result<SpfnCanonicalValue>
    {
        val value = step["with"] ?: return Result.failure(StateTestNetworkError());
        if (step["request"] != text("snapshot") || value !is SpfnCanonicalValue.Obj || value.members.containsKey("manifest"))
        {
            return Result.success(value);
        }
        return Result.success(SpfnCanonicalValue.Obj(value.members + ("manifest" to StateCases.table.getValue("manifest"))));
    }

    // ---- expectations ------------------------------------------------------

    fun check(run: StateCoreRun, order: Int)
    {
        val after = members["after"]?.obj.orEmpty();
        val label = "$id (order ${order + 1})";
        val expectedDeliveries = after["deliveries"]?.obj.orEmpty().mapValues { deliveries -> deliveries.value.array!!.map { entry(it) } };
        for (name in (run.deliveries.keys + expectedDeliveries.keys).sorted())
        {
            assertEquals("$label: deliveries to $name", expectedDeliveries[name].orEmpty(), run.deliveries[name].orEmpty().map { describe(it) });
        }
        assertEquals("$label: queries", after["queries"]?.array.orEmpty().map { it.key!! }, run.queries);
        assertEquals("$label: requests", after["requests"]?.array.orEmpty().map { SpfnCanonicalJson.encodeToString(it) }, run.requests.map { request(it) });
        assertEquals("$label: errors", after["errors"]?.array.orEmpty().map { SpfnCanonicalJson.encodeToString(it) }, run.errors.map { error(it) });
        assertEquals("$label: finished", after["finished"]?.strings.orEmpty(), run.finished);
        assertEquals("$label: reconnects", (after["reconnects"]?.integer ?: 0L).toInt(), run.reconnects);
        checkOptional(run, after, label);
    }

    private fun checkOptional(run: StateCoreRun, after: Map<String, SpfnCanonicalValue>, label: String)
    {
        for (expected in after["entries"]?.array.orEmpty())
        {
            val fields = expected.obj!!;
            val slot = SpfnStateSlot(fields.text("state"), fields["key"]?.key ?: emptyMap());
            assertEquals("$label: entry $slot", entry(fields.getValue("entry")), describe(run.cells[slot]));
        }
        after["connection"]?.let { assertEquals("$label: connection", SpfnCanonicalJson.encodeToString(it), connection(run.connection)) };
        after["connections"]?.array?.let { expected ->
            assertEquals("$label: connections", expected.map { SpfnCanonicalJson.encodeToString(it) }, run.connections.map { connection(it) });
        };
        for ((name, expected) in after["connectionWatch"]?.obj.orEmpty())
        {
            assertEquals("$label: watch $name", expected.array!!.map { SpfnCanonicalJson.encodeToString(it) }, run.watches[name]?.map { connection(it) });
        }
        for ((name, expected) in after["calls"]?.obj.orEmpty())
        {
            assertEquals("$label: call $name", SpfnCanonicalJson.encodeToString(expected), run.result(name)?.let { call(it) });
        }
        after["timers"]?.integer?.let { assertEquals("$label: timers", it.toInt(), run.timersStarted) };
        after["dropped"]?.integer?.let { assertEquals("$label: dropped", it.toInt(), run.dropped) };
    }

    companion object
    {
        fun streamState(value: SpfnCanonicalValue): SpfnEventStreamState
        {
            val fields = SpfnDecoding.obj(value, "$.state");
            fields["open"]?.integer?.let { return SpfnEventStreamState.Open(it.toInt()) };
            fields["connecting"]?.integer?.let { return SpfnEventStreamState.Connecting(it.toInt()) };
            fields["retrying"]?.integer?.let { return SpfnEventStreamState.Retrying(it.toInt(), fields["delayMillis"]?.integer ?: 1_000L, SpfnRetryReason.Network) };
            fields["offline"]?.integer?.let { return SpfnEventStreamState.Offline(it.toInt()) };
            fields["idle"]?.string?.let { return SpfnEventStreamState.Idle(if (it == "background") SpfnIdleReason.BACKGROUND else SpfnIdleReason.SIGNED_OUT) };
            return when (fields["closed"]?.string)
            {
                "unauthorized" -> SpfnEventStreamState.Closed(SpfnCloseReason.Unauthorized)
                "forbidden" -> SpfnEventStreamState.Closed(SpfnCloseReason.Forbidden)
                else -> throw SpfnDecodingException("TYPE_MISMATCH", "expected a stream state at $.state")
            };
        }

        /** An expected entry in the form `describe` gives a cell. */
        fun entry(value: SpfnCanonicalValue): String
        {
            val fields = value.obj ?: return value.string ?: "?";
            val at = fields["at"]?.string;
            val state = fields["value"];
            if (at == null || state == null)
            {
                return fields.text("status");
            }
            val data = fields["data"]?.let { ", " + SpfnCanonicalJson.encodeToString(it) } ?: "";
            return "${fields.text("status")}($at, ${SpfnCanonicalJson.encodeToString(state)}$data)";
        }

        fun request(kind: SpfnStateRequest.Kind): String
        {
            val value = when (kind)
            {
                is SpfnStateRequest.Kind.Snapshot -> obj("snapshot" to keyValue(kind.query))
                is SpfnStateRequest.Kind.Read -> obj("read" to obj("state" to text(kind.slot.id), "key" to keyValue(kind.slot.key)))
                is SpfnStateRequest.Kind.Write -> obj("write" to obj("state" to text(kind.slot.id), "key" to keyValue(kind.slot.key)))
            };
            return SpfnCanonicalJson.encodeToString(value);
        }

        fun error(error: Throwable): String
        {
            val value = when (error)
            {
                is SpfnStateClientError.KeyFields -> obj("keyFields" to obj("state" to text(error.state), "expected" to texts(error.expected), "given" to texts(error.given)))
                is SpfnStateClientError.UnknownState -> obj("unknownState" to text(error.state))
                is SpfnStateClientError.Unavailable -> obj("unavailable" to text(error.name))
                is SpfnStateClientError.ReadOnly -> obj("readOnly" to text(error.state))
                is StateTestNetworkError -> text("network")
                SpfnStateClientError.SignedInChanged -> text("signedInChanged")
                else -> text(error.toString())
            };
            return SpfnCanonicalJson.encodeToString(value);
        }

        fun connection(connection: SpfnStateConnection): String = when (connection)
        {
            SpfnStateConnection.Connected -> "\"connected\""
            SpfnStateConnection.Failed -> "\"failed\""
            is SpfnStateConnection.Reconnecting -> "{\"reconnecting\":${connection.attempt}}"
        };

        fun call(result: Result<SpfnStateResolution>): String
        {
            val resolution = result.getOrNull();
            val failure = result.exceptionOrNull();
            return when
            {
                failure != null -> "{\"rejected\":" + error(failure) + "}"
                resolution is SpfnStateResolution.Snapshot -> "\"snapshot\""
                else ->
                {
                    @Suppress("UNCHECKED_CAST")
                    val frame = (resolution as SpfnStateResolution.Frame).typed as CanonicalFrame;
                    val fields = mutableMapOf("at" to text(frame.at), "value" to frame.value);
                    if (frame.data != SpfnCanonicalValue.Null)
                    {
                        fields["data"] = frame.data;
                    }
                    SpfnCanonicalJson.encodeToString(obj("frame" to SpfnCanonicalValue.Obj(fields)))
                }
            };
        }

        private fun obj(vararg members: Pair<String, SpfnCanonicalValue>): SpfnCanonicalValue = SpfnCanonicalValue.Obj(members.toMap());
    }
}

// ---- reading canonical values ----------------------------------------------

internal val SpfnCanonicalValue.string: String?
    get() = (this as? SpfnCanonicalValue.Text)?.value;

internal val SpfnCanonicalValue.integer: Long?
    get() = (this as? SpfnCanonicalValue.Integer)?.value;

internal val SpfnCanonicalValue.array: List<SpfnCanonicalValue>?
    get() = (this as? SpfnCanonicalValue.Arr)?.elements;

internal val SpfnCanonicalValue.obj: Map<String, SpfnCanonicalValue>?
    get() = (this as? SpfnCanonicalValue.Obj)?.members;

internal val SpfnCanonicalValue.strings: List<String>?
    get() = array?.mapNotNull { it.string };

internal val SpfnCanonicalValue.key: SpfnStateKey?
    get() = obj?.mapNotNull { field -> field.value.string?.let { field.key to it } }?.toMap();

internal fun Map<String, SpfnCanonicalValue>.text(name: String): String = getValue(name).string ?: error("'$name' is not a string");
