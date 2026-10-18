// SPFN Mobile — the network observer: ConnectivityManager's default network as a flow of the
// network input.
//
// Public since the app root packages read it (superself-apps packages/spfn-app): the event
// stream's host and an app's root share one reading of the platform's network, so the two
// never disagree about whether there is one. The stream's rule is the host's —
// `setNetworkAvailable` on every element — and this object only observes.
//
// Sources/SPFNEvents/SPFNNetworkMonitor.swift is the same observer in Swift.

package xyz.superfunction.spfn.events

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object SpfnNetworkMonitor
{
    /**
     * Whether a default network exists: now, then on every change, until the collector stops.
     * The callback is unregistered when the collection ends.
     */
    fun availability(context: Context): Flow<Boolean> = callbackFlow {
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java);
        trySend(connectivity.activeNetwork != null);
        val callback = SpfnDefaultNetworkCallback { trySend(spfnNetworkAvailableOf(it)) };
        connectivity.registerDefaultNetworkCallback(callback);
        awaitClose { connectivity.unregisterNetworkCallback(callback) };
    };
}

internal class SpfnDefaultNetworkCallback(private val report: (SpfnNetworkSignal) -> Unit) : ConnectivityManager.NetworkCallback()
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
