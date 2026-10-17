// SPFN Mobile — one state, described: its id, its key fields, how to read it and write it.
//
// The app does not write these. The app contract generator emits one per state of the
// server's state router — `AppStates.<id>` — from the contract document (§2-13-2, Q-G).
// The factory stays public so fixtures and tests can build one over a fake call.
//
// A descriptor carries the generated calls as values; the client never knows a route's
// name or path (§2-3). A frame on the stream is the read's answer plus `key`/`subject`, so
// the read's decoder reads frames too and nobody writes a frame decoder by hand (§2-4).
//
// Only a state with a write has a `SpfnWritableStateDescriptor`, and `write` takes nothing
// else, so writing a state that has no write does not compile (§2-11 item 1). A Java caller
// sees erased generics, which is why the manifest's `write: false` is still refused at run
// time (I-11, P15).
//
// Sources/SPFNState/SPFNStateDescriptor.swift is the same pair in Swift.

package xyz.superfunction.spfn.state

import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnCanonicalValue

/**
 * A state the client can read and subscribe to. `V` is the read answer's `value`, `D` its
 * `data` (`Unit` for a state without `.data()`). Made by the top-level [SpfnStateDescriptor]
 * factory: a constructor cannot introduce the read answer's type.
 *
 * @property keyFields the read operation's query fields, in name order; empty for a keyless state.
 */
open class SpfnStateDescriptor<V, D> internal constructor(
    val id: String,
    val keyFields: List<String>,
    internal val access: SpfnStateAccess
)
{
    /**
     * The same state with its write (`put<Id>State`). The write's answer is checked by the
     * write call's own decoder, then read by this state's read decoder: the put answers the
     * read's shape — the generator refuses one that does not — but as a type of its own.
     */
    fun <I, R> writable(write: (SpfnStateKey) -> SpfnCall<I, R>): SpfnWritableStateDescriptor<V, D, I> =
        SpfnWritableStateDescriptor(this) { key -> SpfnStateAccess.canonical(write(key)) };
}

/**
 * A state the client can read and subscribe to.
 *
 * @param read the generated read call for a key (`get<Id>State`).
 * @param frame the three fields of the read's answer.
 */
fun <V, D, R> SpfnStateDescriptor(
    id: String,
    keyFields: List<String> = emptyList(),
    read: (SpfnStateKey) -> SpfnCall<Unit, R>,
    frame: (R) -> SpfnStateFrame<V, D>
): SpfnStateDescriptor<V, D>
{
    val sorted = SpfnStateOrder.sortedFields(keyFields);
    val access = SpfnStateAccess(
        id = id,
        keyFields = sorted,
        read = { key -> SpfnStateAccess.canonical(read(key)) },
        decode = { key, value -> frame(read(key).decode(value)) }
    );
    return SpfnStateDescriptor(id, sorted, access);
}

/** A state with a write: the only descriptor `SpfnStateClient.write` takes. */
class SpfnWritableStateDescriptor<V, D, I> internal constructor(
    base: SpfnStateDescriptor<V, D>,
    internal val write: (SpfnStateKey) -> SpfnCall<I, SpfnCanonicalValue>
) : SpfnStateDescriptor<V, D>(base.id, base.keyFields, base.access)

/** What the client needs of a descriptor, its types erased. */
internal class SpfnStateAccess(
    val id: String,
    val keyFields: List<String>,
    /** The read call for a key, answering the canonical value its generated decoder accepted. */
    val read: (SpfnStateKey) -> SpfnCall<Unit, SpfnCanonicalValue>,
    /** A read answer or a frame, decoded to the typed `SpfnStateFrame<V, D>`. */
    val decode: (SpfnStateKey, SpfnCanonicalValue) -> Any?
)
{
    val eventName: String
        get() = eventName(id);

    companion object
    {
        fun eventName(id: String): String = "state.$id.changed";

        /**
         * The same call, its answer kept canonical once the generated decoder has checked it:
         * "same value" is decided on what the server sent, not on what a type kept (§2-5).
         */
        fun <Req, Resp> canonical(call: SpfnCall<Req, Resp>): SpfnCall<Req, SpfnCanonicalValue> =
            SpfnCall(
                operation = call.operation,
                encode = call.encode,
                decode = { value ->
                    call.decode(value);
                    value
                },
                query = call.query
            );
    }
}
