// SPFN Mobile — the state client in Compose: `rememberServerState` and `rememberConnectionStatus`.
//
// `@spfn/core/state/react` on the native SDK (state-client-design.md §2-12, Q-F). The root host
// takes the state client beside the stream and provides it in LocalSpfnStateClient; a
// composable reads the state it draws in one line:
//
//     val consent by rememberServerState(AppStates.consent)
//     val connection by rememberConnectionStatus()
//
// The first composition draws the client's current entry; a `LaunchedEffect` keyed by the
// client, the state and the key follows the subscription, and leaving the composition ends it
// (A-5) the way SpfnEventEffect's listening ends (event-stream-design §3-4).
//
// Sources/SPFNEvents/SPFNServerState.swift is the SwiftUI half.

package xyz.superfunction.spfn.events

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import xyz.superfunction.spfn.state.SpfnStateClient
import xyz.superfunction.spfn.state.SpfnStateConnection
import xyz.superfunction.spfn.state.SpfnStateDescriptor
import xyz.superfunction.spfn.state.SpfnStateEntry
import xyz.superfunction.spfn.state.SpfnStateKey

/**
 * The state client the nearest [SpfnEventStreamHost] provides. Reading it outside one that was
 * given a state client is a programmer error and throws (A-8).
 */
val LocalSpfnStateClient = staticCompositionLocalOf<SpfnStateClient> { missingStateClient() };

/** One state for this composable: its entry, current value first. */
@Composable
fun <V, D> rememberServerState(state: SpfnStateDescriptor<V, D>, key: SpfnStateKey = emptyMap()): State<SpfnStateEntry<V, D>>
{
    val client = LocalSpfnStateClient.current;
    val entry = remember { mutableStateOf<SpfnStateEntry<V, D>>(SpfnStateEntry.Loading) };
    val driver = remember { SpfnServerStateDriver<V, D> { entry.value = it } };
    driver.update(client, state, key);
    LaunchedEffect(client, state.id, key)
    {
        driver.collect();
    };
    DisposableEffect(driver)
    {
        onDispose { driver.end() };
    };
    return entry;
}

/** The state client's connection status for this composable, current value first (A-7). */
@Composable
fun rememberConnectionStatus(): State<SpfnStateConnection>
{
    val client = LocalSpfnStateClient.current;
    val connection = remember { mutableStateOf<SpfnStateConnection>(SpfnStateConnection.Reconnecting(0)) };
    val driver = remember { SpfnConnectionStatusDriver { connection.value = it } };
    driver.update(SpfnStateClientSource(client));
    LaunchedEffect(client)
    {
        driver.collect();
    };
    return connection;
}
