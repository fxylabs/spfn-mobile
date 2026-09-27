// SPFN Mobile — platform signals, read as the event stream's three inputs.
//
// The rules the host applies are small and named, so a JVM test can hold them without a
// device: the PROCESS's start and stop are the foreground (an activity's are not — a
// rotation recreates the activity, §8 H-7), and the default network's availability is the
// network. Everything else a lifecycle or a network callback reports is not an input.
//
// Sources/SPFNEvents/SPFNPlatformSignals.swift is the same rule set in Swift.

package xyz.superfunction.spfn.events

import androidx.lifecycle.Lifecycle

/**
 * `ON_START` is the foreground and `ON_STOP` the background; every other event is not a
 * foreground change and answers null. Read from `ProcessLifecycleOwner`, whose `ON_STOP`
 * waits out a configuration change.
 */
fun spfnForegroundOf(event: Lifecycle.Event): Boolean? = when (event)
{
    Lifecycle.Event.ON_START -> true
    Lifecycle.Event.ON_STOP -> false
    else -> null
};

/** What the default-network callback reported. */
enum class SpfnNetworkSignal
{
    AVAILABLE,
    LOST,
    UNAVAILABLE
}

/** The network input: a default network exists. */
fun spfnNetworkAvailableOf(signal: SpfnNetworkSignal): Boolean = signal == SpfnNetworkSignal.AVAILABLE;
