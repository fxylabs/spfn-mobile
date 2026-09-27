// SPFN Mobile — the root attachment: one host, three observers.
//
// An app wraps its root once. The host puts the stream where every screen below finds it
// (LocalSpfnEventStream) and carries three platform facts into it: the process's
// foreground (ProcessLifecycleOwner), who is signed in (the key lifecycle's read-only
// value) and whether a default network exists (ConnectivityManager). The stream decides
// everything else. Leaving the composition unregisters all three and hands the stream
// `setForeground(false)`; the stream itself is the app's and outlives the host
// (docs/architecture/event-stream-design.md §3-3).
//
// Sources/SPFNEvents/SPFNEventStreamAttachment.swift is the same attachment in SwiftUI.

package xyz.superfunction.spfn.events

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import xyz.superfunction.spfn.client.SpfnEventStream
import xyz.superfunction.spfn.client.SpfnEventStreamState
import xyz.superfunction.spfn.client.SpfnKeyLifecycle

/**
 * The stream the nearest [SpfnEventStreamHost] provides. Reading it outside one is a
 * programmer error and throws (L-8): a screen that listens without a host would wait for
 * signals that can never come.
 */
val LocalSpfnEventStream = staticCompositionLocalOf<SpfnEventStream> { missingEventStream() };

internal fun missingEventStream(): Nothing =
    throw IllegalStateException("no SpfnEventStreamHost above this composable: wrap the app's root in one");

/**
 * Attach [stream] at the app's root, once. Idempotent per stream; a second host with a
 * different stream shadows the first for everything below it.
 */
@Composable
fun SpfnEventStreamHost(stream: SpfnEventStream, keyLifecycle: SpfnKeyLifecycle, content: @Composable () -> Unit)
{
    val context = LocalContext.current.applicationContext;
    ObserveForeground(stream);
    ObserveSignedIn(stream, keyLifecycle);
    ObserveNetwork(stream, context);
    WarnAboutUnavailableEvents(stream, context);
    CompositionLocalProvider(LocalSpfnEventStream provides stream, content = content);
}

@Composable
private fun ObserveForeground(stream: SpfnEventStream)
{
    DisposableEffect(stream)
    {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle;
        val observer = LifecycleEventObserver { _, event -> spfnForegroundOf(event)?.let { stream.setForeground(it) } };
        // Adding replays the events up to the current state, so a host composed while the
        // process is already started reports the foreground at once.
        lifecycle.addObserver(observer);
        onDispose()
        {
            lifecycle.removeObserver(observer);
            stream.setForeground(false);
        }
    };
}

@Composable
private fun ObserveSignedIn(stream: SpfnEventStream, keyLifecycle: SpfnKeyLifecycle)
{
    LaunchedEffect(stream, keyLifecycle)
    {
        keyLifecycle.signedInClientId.collect { stream.setSignedIn(it) };
    };
}

@Composable
private fun ObserveNetwork(stream: SpfnEventStream, context: Context)
{
    DisposableEffect(stream, context)
    {
        val connectivity = context.getSystemService(ConnectivityManager::class.java);
        val callback = SpfnDefaultNetworkCallback { stream.setNetworkAvailable(spfnNetworkAvailableOf(it)) };
        connectivity.registerDefaultNetworkCallback(callback);
        onDispose { connectivity.unregisterNetworkCallback(callback) };
    };
}

/**
 * Q-F: the server does not serve some configured names and the stream runs without them.
 * Said once per change, in debuggable builds only; event names are safe to log (§6).
 */
@Composable
private fun WarnAboutUnavailableEvents(stream: SpfnEventStream, context: Context)
{
    if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0)
    {
        return;
    }
    LaunchedEffect(stream)
    {
        stream.state
            .map { (it as? SpfnEventStreamState.Open)?.unavailableEvents.orEmpty() }
            .distinctUntilChanged()
            .filter { it.isNotEmpty() }
            .collect { Log.w(LOG_TAG, "the server does not serve these configured events: ${it.joinToString()}") };
    };
}

private class SpfnDefaultNetworkCallback(private val report: (SpfnNetworkSignal) -> Unit) : ConnectivityManager.NetworkCallback()
{
    override fun onAvailable(network: Network)
    {
        report(SpfnNetworkSignal.AVAILABLE);
    }

    override fun onLost(network: Network)
    {
        report(SpfnNetworkSignal.LOST);
    }

    override fun onUnavailable()
    {
        report(SpfnNetworkSignal.UNAVAILABLE);
    }
}

private const val LOG_TAG = "SpfnEvents";
