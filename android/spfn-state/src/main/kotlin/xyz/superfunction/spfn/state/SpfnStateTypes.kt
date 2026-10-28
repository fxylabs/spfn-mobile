// SPFN Mobile — the state client's vocabulary: keys, frames, entries, connection status.
//
// An entry is one of three things and only those three: nothing has arrived yet
// (`Loading`), a value is served (`Ready`), or the server stopped serving it (`Revoked`,
// with the last value if one arrived). The web client's `StateEntry` is a union of
// optional fields; here the combinations it never produces cannot be written
// (docs/architecture/state-client-design.md §2-11).
//
// Sources/SPFNState/SPFNStateTypes.swift is the same vocabulary in Swift.

package xyz.superfunction.spfn.state

/** A keyed state's key: its key fields and their values. A keyless state's key is empty. */
typealias SpfnStateKey = Map<String, String>

/**
 * One value of one state as the server stamped it: the read's `at`, its `value`, its `data`.
 * A state declared without `.data()` has `D = Unit`.
 *
 * @property at `new Date().toISOString()` on the server, compared as a string (§2-5).
 */
data class SpfnStateFrame<out V, out D>(val at: String, val value: V, val data: D)

enum class SpfnStateStatus
{
    LOADING,
    READY,
    REVOKED
}

sealed interface SpfnStateEntry<out V, out D>
{
    /** No value has arrived for this key yet. */
    data object Loading : SpfnStateEntry<Nothing, Nothing>

    /** The server serves this key; the frame is the newest value. */
    data class Ready<out V, out D>(val frame: SpfnStateFrame<V, D>) : SpfnStateEntry<V, D>

    /** The server stopped serving this key to this subject; the last value, if any arrived. */
    data class Revoked<out V, out D>(val frame: SpfnStateFrame<V, D>?) : SpfnStateEntry<V, D>
}

val SpfnStateEntry<*, *>.status: SpfnStateStatus
    get() = when (this)
    {
        SpfnStateEntry.Loading -> SpfnStateStatus.LOADING
        is SpfnStateEntry.Ready -> SpfnStateStatus.READY
        is SpfnStateEntry.Revoked -> SpfnStateStatus.REVOKED
    };

val <V, D> SpfnStateEntry<V, D>.frame: SpfnStateFrame<V, D>?
    get() = when (this)
    {
        SpfnStateEntry.Loading -> null
        is SpfnStateEntry.Ready -> frame
        is SpfnStateEntry.Revoked -> frame
    };

val <V> SpfnStateEntry<V, *>.value: V?
    get() = frame?.value;

/** Whether the server's stream is serving this client (§2-9). */
sealed interface SpfnStateConnection
{
    data object Connected : SpfnStateConnection

    /**
     * No stream is open yet, nothing is subscribed, or the stream is retrying a dropped one;
     * `attempt` is 0 until a failure since the last open or restart.
     */
    data class Reconnecting(val attempt: Int) : SpfnStateConnection

    /** The first snapshot spent `maxRetries`, or the server refused the stream. */
    data object Failed : SpfnStateConnection
}

/**
 * A snapshot already read — `snapshot()`'s answer, or a fixture — to start from (§2-10).
 *
 * @property query the query the snapshot was read with: keyed states apply under its key.
 */
data class SpfnStateInitial(val snapshot: SpfnStatesSnapshot, val query: SpfnStateKey = emptyMap())

/**
 * What the state client refuses once it runs. The state id and field names only: a key's
 * values and a state's value are the app's data and are never in an error. A configuration
 * refused at construction is an [IllegalArgumentException] instead (§2-13-1).
 */
sealed class SpfnStateClientError(message: String) : Exception(message)
{
    /** A state the client was not configured with; `field` names what is wrong. */
    data class InvalidConfiguration(val field: String) : SpfnStateClientError("invalid configuration: $field")

    /** The server's manifest names no state with this id (K-9). */
    data class UnknownState(val state: String) : SpfnStateClientError("unknown state '$state'")

    /** The key's fields are not the state's (K-8). */
    data class KeyFields(val state: String, val expected: List<String>, val given: List<String>) :
        SpfnStateClientError("state '$state' is keyed by $expected, not $given")

    /** The manifest marks this state `write: false` (I-11). */
    data class ReadOnly(val state: String) : SpfnStateClientError("state '$state' has no write")

    /** The server does not serve this configured state event (C-24). */
    data class Unavailable(val name: String) : SpfnStateClientError("the server does not serve '$name'")

    /** Nobody is signed in, so no request could be signed. */
    data object SignedOut : SpfnStateClientError("nobody is signed in")

    /** `close()` ended the call before it was answered (C-19). */
    data object Closed : SpfnStateClientError("the state client was closed")

    /**
     * The signed-in account changed — signed out, or another account signed in — while the
     * call was in flight or waiting for the manifest; its answer, if any, is not applied
     * (§2-14). A refused `write` may already have been applied on the server.
     */
    data object SignedInChanged : SpfnStateClientError("the signed-in account changed")
}
