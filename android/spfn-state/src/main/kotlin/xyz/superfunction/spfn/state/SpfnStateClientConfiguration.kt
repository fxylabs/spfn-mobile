// SPFN Mobile — what an app tells the state client once, at its composition root.
//
// The app hands over the stream it already made, its key lifecycle, and the generated
// `AppStates.all` and `AppStates.snapshot`; it writes nothing per state (§2-13-1, §2-13-4).
// Every refusal is at construction, so a missing event name is found on the first run.
//
// Sources/SPFNState/SPFNStateClientConfiguration.swift is the same value in Swift.

package xyz.superfunction.spfn.state

import xyz.superfunction.spfn.client.SpfnEventStream
import xyz.superfunction.spfn.client.SpfnEventStreamConfiguration
import xyz.superfunction.spfn.client.SpfnKeyLifecycle
import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnCanonicalValue

/**
 * @property stream the app's one event stream. Its events must include every
 *   `state.<id>.changed` (`AppStates.events`); the state client owns its connection query (§2-2).
 * @property keyLifecycle signs every read and write: asked for the signed-in client at each call (§2-3).
 * @property states `AppStates.all`.
 * @property onError something the client ran on its own failed, or a `subscribe` was
 *   refused. Never a cancellation (P16).
 * @property retryDelayMillis the wait before the first snapshot is read again after it failed.
 * @property maxRetries how many times the first snapshot is read again before `Failed`; 0 is no limit.
 * @param snapshot `AppStates.snapshot`.
 */
class SpfnStateClientConfiguration(
    val stream: SpfnEventStream,
    val keyLifecycle: SpfnKeyLifecycle,
    val states: List<SpfnStateDescriptor<*, *>>,
    snapshot: (SpfnStateKey) -> SpfnCall<Unit, *>,
    val onError: (Throwable) -> Unit = SpfnStateClient::logError,
    val retryDelayMillis: Long = 3_000,
    val maxRetries: Int = 0,
    val initial: SpfnStateInitial? = null
)
{
    /** `AppStates.snapshot`, its answer kept canonical once the generated decoder checked it. */
    internal val snapshot: (SpfnStateKey) -> SpfnCall<Unit, SpfnCanonicalValue> = { query -> SpfnStateAccess.canonical(snapshot(query)) };

    /**
     * The descriptors as the core reads them.
     *
     * @throws IllegalArgumentException naming the field that is wrong: no state, a state
     *   twice, an event name the stream does not carry.
     */
    internal fun accesses(events: SpfnEventStreamConfiguration): List<SpfnStateAccess>
    {
        val accesses = states.map { it.access };
        require(accesses.isNotEmpty()) { "states: the list is empty" };
        require(accesses.map { it.id }.toSet().size == accesses.size) { "states: an id is described twice" };
        val missing = accesses.firstOrNull { !events.contains(it.eventName) };
        require(missing == null) { "stream: '${missing?.eventName}' is not among its events" };
        return accesses;
    }
}
