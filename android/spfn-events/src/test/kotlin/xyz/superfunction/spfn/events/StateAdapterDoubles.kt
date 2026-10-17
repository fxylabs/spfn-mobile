// SPFN Mobile — what SpfnStateAdapterTest runs against: a scripted state source, and the
// signed-out key lifecycle a real state client is built over for A-9 (it never connects).

package xyz.superfunction.spfn.events

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import xyz.superfunction.spfn.client.SpfnKeyLifecycle
import xyz.superfunction.spfn.client.SpfnKeyMetadataStore
import xyz.superfunction.spfn.client.SpfnKeystoreEngine
import xyz.superfunction.spfn.client.SpfnKeystoreGeneratedKey
import xyz.superfunction.spfn.client.SpfnStoredKeyMetadata
import xyz.superfunction.spfn.client.SpfnTransport
import xyz.superfunction.spfn.client.SpfnTransportError
import xyz.superfunction.spfn.client.SpfnTransportRequest
import xyz.superfunction.spfn.client.SpfnTransportResponse
import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnOperation
import xyz.superfunction.spfn.state.SpfnStateConnection
import xyz.superfunction.spfn.state.SpfnStateDescriptor
import xyz.superfunction.spfn.state.SpfnStateEntry
import xyz.superfunction.spfn.state.SpfnStateFrame
import xyz.superfunction.spfn.state.SpfnStateKey

const val T1 = "2026-10-17T09:00:00.001Z";

val consent: SpfnStateDescriptor<String, Unit> = SpfnStateDescriptor(id = "consent", read = { unsentCall<String>() }, frame = { SpfnStateFrame(it, it, Unit) });

val runtime: SpfnStateDescriptor<String, Unit> =
    SpfnStateDescriptor(id = "runtime", keyFields = listOf("workspaceId"), read = { unsentCall<String>() }, frame = { SpfnStateFrame(it, it, Unit) });

fun ready(value: String): SpfnStateEntry<String, Unit> = SpfnStateEntry.Ready(SpfnStateFrame(T1, value, Unit));

fun <R> unsentCall(): SpfnCall<Unit, R> = SpfnCall(
    operation = SpfnOperation(id = "test.unsent", method = "GET", path = "/unsent", authProfile = "none", requiresSession = false, declaresResponse = true),
    encode = { null },
    decode = { throw IllegalStateException("never sent") }
);

/** A state client stand-in: entries by "id key", subscriptions the test emits into. */
internal class ScriptedStateSource : SpfnStateSource, SpfnConnectionSource
{
    val entries = mutableMapOf<String, SpfnStateEntry<String, Unit>>();
    val connection = MutableStateFlow<SpfnStateConnection>(SpfnStateConnection.Reconnecting(0));
    private val emitted = MutableSharedFlow<Pair<String, SpfnStateEntry<String, Unit>>>(extraBufferCapacity = 64);
    var currentReads = 0
        private set
    var subscribed = 0
        private set
    var ended = 0
        private set

    override val identity: Any
        get() = this

    @Suppress("UNCHECKED_CAST")
    override fun <V, D> current(state: SpfnStateDescriptor<V, D>, key: SpfnStateKey): SpfnStateEntry<V, D>
    {
        currentReads += 1;
        val name = (listOf(state.id) + key.values.sorted()).joinToString(" ");
        return (entries[name] ?: SpfnStateEntry.Loading) as SpfnStateEntry<V, D>;
    }

    @Suppress("UNCHECKED_CAST")
    override fun <V, D> subscribe(state: SpfnStateDescriptor<V, D>, key: SpfnStateKey): Flow<SpfnStateEntry<V, D>> =
        object : Flow<SpfnStateEntry<V, D>>
        {
            override suspend fun collect(collector: kotlinx.coroutines.flow.FlowCollector<SpfnStateEntry<V, D>>)
            {
                emitted
                    .onStart { subscribed += 1 }
                    .onCompletion { ended += 1 }
                    .collect { if (it.first == state.id) collector.emit(it.second as SpfnStateEntry<V, D>) };
            }
        };

    suspend fun emit(id: String, entry: SpfnStateEntry<String, Unit>)
    {
        emitted.emit(id to entry);
    }

    fun emitBlocking(id: String, entry: SpfnStateEntry<String, Unit>)
    {
        runBlocking { emitted.emit(id to entry) };
    }

    override fun connection(): SpfnStateConnection = connection.value;

    override fun subscribeConnection(): Flow<SpfnStateConnection> = connection;
}

/** Nobody signed in, nothing stored, nothing sent. */
fun signedOutLifecycle(): SpfnKeyLifecycle = SpfnKeyLifecycle(
    transport = object : SpfnTransport
    {
        override suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse = throw SpfnTransportError.Connectivity("not in this suite");
    },
    store = object : SpfnKeyMetadataStore
    {
        override fun load(slot: String): SpfnStoredKeyMetadata? = null;

        override fun save(slot: String, metadata: SpfnStoredKeyMetadata) = Unit;

        override fun delete(slot: String) = Unit;
    },
    engine = object : SpfnKeystoreEngine
    {
        override fun generate(alias: String, preferStrongBox: Boolean): SpfnKeystoreGeneratedKey = throw UnsupportedOperationException("no keys in this suite");

        override fun publicKeySpkiDer(alias: String): ByteArray? = null;

        override fun signDer(alias: String, message: ByteArray): ByteArray = throw UnsupportedOperationException("no keys in this suite");

        override fun contains(alias: String): Boolean = false;

        override fun delete(alias: String) = Unit;
    },
    baseUrl = "https://example.test"
);
