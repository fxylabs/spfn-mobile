// SPFN Mobile — one state of the state client, kept for a composable.
//
// The toolkit-free body of `rememberServerState` (SpfnServerState.kt): `@spfn/core/state/react`'s
// `useServerState` on the native SDK. `update` reads the client's current entry at once — a
// cached entry or `initial` draws `Ready` on the first frame, never `Loading` (A-1) — and
// `collect` follows the subscription in the calling coroutine, taking only what differs from
// what it holds, so the subscription's first element (the same current entry) changes nothing.
// The key is compared by value: the same fields again does nothing, other fields end the old
// binding and read the new key's current entry first (A-3, A-4). `end` is the composable
// leaving (A-5).
//
// The state client's engine runs in its own scope; `collect` runs where it is called — in
// `rememberServerState`, a `LaunchedEffect` on the composition's main dispatcher (A-6).
//
// Plain Kotlin, so the A-cells run on the JVM. Sources/SPFNEvents/SPFNServerStateDriver.swift
// is the same driver in Swift.

package xyz.superfunction.spfn.events

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import xyz.superfunction.spfn.client.SpfnEventStream
import xyz.superfunction.spfn.state.SpfnStateClient
import xyz.superfunction.spfn.state.SpfnStateConnection
import xyz.superfunction.spfn.state.SpfnStateDescriptor
import xyz.superfunction.spfn.state.SpfnStateEntry
import xyz.superfunction.spfn.state.SpfnStateKey

class SpfnServerStateDriver<V, D>(private val onChange: (SpfnStateEntry<V, D>) -> Unit = {})
{
    /** The entry the composable draws. */
    var entry: SpfnStateEntry<V, D> = SpfnStateEntry.Loading
        private set

    private var binding: Binding<V, D>? = null
    private var collecting: Job? = null

    private class Binding<V, D>(val source: SpfnStateSource, val state: SpfnStateDescriptor<V, D>, val key: SpfnStateKey)
    {
        fun isFor(source: SpfnStateSource, state: SpfnStateDescriptor<V, D>, key: SpfnStateKey): Boolean =
            this.source.identity === source.identity && this.state.id == state.id && this.key == key;
    }

    /** Binds the driver to [state] under [key] on [client]. The same binding again does nothing. */
    fun update(client: SpfnStateClient, state: SpfnStateDescriptor<V, D>, key: SpfnStateKey)
    {
        update(SpfnStateClientSource(client), state, key);
    }

    /** Follows the current binding's subscription until the caller is cancelled or [end]. */
    suspend fun collect()
    {
        val bound = binding ?: return;
        collecting = currentCoroutineContext()[Job];
        bound.source.subscribe(bound.state, bound.key).collect { take(it) };
    }

    /** The composable left: the subscription ends. */
    fun end()
    {
        collecting?.cancel();
        collecting = null;
        binding = null;
    }

    internal fun update(source: SpfnStateSource, state: SpfnStateDescriptor<V, D>, key: SpfnStateKey)
    {
        if (binding?.isFor(source, state, key) == true)
        {
            return;
        }
        end();
        binding = Binding(source, state, key);
        take(source.current(state, key));
    }

    private fun take(next: SpfnStateEntry<V, D>)
    {
        if (next == entry)
        {
            return;
        }
        entry = next;
        onChange(next);
    }
}

/** The connection status for a composable: `connection()` first, then each change (A-7). */
internal class SpfnConnectionStatusDriver(private val onChange: (SpfnStateConnection) -> Unit = {})
{
    var connection: SpfnStateConnection = SpfnStateConnection.Reconnecting(0)
        private set

    private var source: SpfnConnectionSource? = null

    fun update(source: SpfnConnectionSource)
    {
        if (this.source?.identity === source.identity)
        {
            return;
        }
        this.source = source;
        take(source.connection());
    }

    suspend fun collect()
    {
        source?.subscribeConnection()?.collect { take(it) };
    }

    private fun take(next: SpfnStateConnection)
    {
        if (next == connection)
        {
            return;
        }
        connection = next;
        onChange(next);
    }
}

/** What the drivers read of a state client; the suite stands a scripted one in. */
internal interface SpfnStateSource
{
    /** The object compared when a binding is checked: the client, not a wrapper around it. */
    val identity: Any

    fun <V, D> current(state: SpfnStateDescriptor<V, D>, key: SpfnStateKey): SpfnStateEntry<V, D>

    fun <V, D> subscribe(state: SpfnStateDescriptor<V, D>, key: SpfnStateKey): Flow<SpfnStateEntry<V, D>>
}

internal interface SpfnConnectionSource
{
    val identity: Any

    fun connection(): SpfnStateConnection

    fun subscribeConnection(): Flow<SpfnStateConnection>
}

internal class SpfnStateClientSource(private val client: SpfnStateClient) : SpfnStateSource, SpfnConnectionSource
{
    override val identity: Any
        get() = client

    override fun <V, D> current(state: SpfnStateDescriptor<V, D>, key: SpfnStateKey): SpfnStateEntry<V, D> = client.current(state, key);

    override fun <V, D> subscribe(state: SpfnStateDescriptor<V, D>, key: SpfnStateKey): Flow<SpfnStateEntry<V, D>> = client.subscribe(state, key);

    override fun connection(): SpfnStateConnection = client.connection();

    override fun subscribeConnection(): Flow<SpfnStateConnection> = client.subscribeConnection();
}

/** A-8: reading a state with no host above is a programmer error, as listening is (L-8). */
internal fun missingStateClient(): Nothing =
    throw IllegalStateException("no SpfnEventStreamHost with a stateClient above this composable: pass the state client to the host at the app's root");

/** A-9: the query of a stream has one owner, its state client. */
internal fun spfnRequireStateClientOf(stream: SpfnEventStream, stateClient: SpfnStateClient)
{
    require(stateClient.configuration.stream === stream) { "the state client given to SpfnEventStreamHost was made for another stream" };
}
