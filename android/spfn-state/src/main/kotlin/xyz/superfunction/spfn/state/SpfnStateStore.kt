// SPFN Mobile — the entries, their ordering stamps and their revoked marks.
//
// One rule for every arrival — a snapshot entry, a frame, a read or a write answer — per
// `(id, key)`: the README's "Ordering" table, rows O-1 … O-16. The stamp is the newest `at`
// seen and is kept apart from the entry, so an arrival that changes nothing still orders the
// ones after it while the entry's `at` stays the `at` of the read that last changed the value.
// The revoked mark is kept apart too, with the connection (epoch) that set it: a mark holds
// before any value arrives, and only a newer connection's arrival lifts it (§2-8).
//
// Values are compared canonically — objects as maps, arrays in order — which is the web
// client's `sameJson` (§2-5). A generated type's `equals` is not used: its decoder drops
// fields it does not know, and a change in one would be news on the web and not here.
//
// Sources/SPFNState/SPFNStateStore.swift is the same store in Swift.

package xyz.superfunction.spfn.state

import xyz.superfunction.spfn.core.SpfnCanonicalValue

/** One `(id, key)`. */
internal data class SpfnStateSlot(val id: String, val key: SpfnStateKey)

/**
 * One arrival, canonical for comparing and typed for handing out.
 *
 * @property typed the `SpfnStateFrame<V, D>` the state's decoder made of it.
 */
internal class SpfnStateArrival(
    val at: String,
    val value: SpfnCanonicalValue,
    val data: SpfnCanonicalValue?,
    val typed: Any?
)
{
    companion object
    {
        /** `{ at, value, data? }` — a snapshot entry with the snapshot's `at`, as a frame. */
        fun canonical(at: String, entry: SpfnCanonicalValue): SpfnCanonicalValue
        {
            if (entry !is SpfnCanonicalValue.Obj)
            {
                return entry;
            }
            return SpfnCanonicalValue.Obj(entry.members + ("at" to SpfnCanonicalValue.Text(at)));
        }
    }
}

/** An entry as the store holds it. No cell is `LOADING`. */
internal class SpfnStateCell(val status: SpfnStateStatus, val frame: SpfnStateArrival?)

internal class SpfnStateStore
{
    private val entries = mutableMapOf<SpfnStateSlot, SpfnStateCell>();
    private val stamps = mutableMapOf<SpfnStateSlot, String>();
    private val marks = mutableMapOf<SpfnStateSlot, Int>();

    val cells: Map<SpfnStateSlot, SpfnStateCell>
        get() = entries;

    /**
     * An arrival under the ordering rule; the status comes from the mark. Answers the new
     * cell when the entry changed, null when it did not.
     */
    fun apply(slot: SpfnStateSlot, arrival: SpfnStateArrival): SpfnStateCell?
    {
        val current = entries[slot];
        val status = if (marks[slot] == null) SpfnStateStatus.READY else SpfnStateStatus.REVOKED;
        val ordered = stamps[slot]?.let { !SpfnStateOrder.isOlder(arrival.at, it) } ?: true;
        if (ordered)
        {
            stamps[slot] = arrival.at;
        }
        if (ordered && changes(current, arrival))
        {
            return replace(slot, SpfnStateCell(status, arrival));
        }
        if (current != null && current.status != status)
        {
            return replace(slot, SpfnStateCell(status, current.frame));
        }
        return null;
    }

    /**
     * An arrival of an automatic read or a frame, tagged with its connection: a connection
     * newer than the one that set the mark lifts it, whatever the `at` (R-11 … R-14).
     */
    fun serve(slot: SpfnStateSlot, arrival: SpfnStateArrival, generation: Int): SpfnStateCell?
    {
        val mark = marks[slot];
        if (mark != null && mark < generation)
        {
            marks.remove(slot);
        }
        return apply(slot, arrival);
    }

    /** Marks `slot` revoked by the connection of `generation`; a value it has is kept. */
    fun revoke(slot: SpfnStateSlot, generation: Int): SpfnStateCell?
    {
        marks[slot] = generation;
        if (entries[slot]?.status == SpfnStateStatus.REVOKED)
        {
            return null;
        }
        return replace(slot, SpfnStateCell(SpfnStateStatus.REVOKED, entries[slot]?.frame));
    }

    /** The newest `at` seen for `slot`. */
    fun stamp(slot: SpfnStateSlot): String? = stamps[slot];

    /** The connection that revoked `slot`, or null while it is served. */
    fun mark(slot: SpfnStateSlot): Int? = marks[slot];

    /** Clears the mark: the next arrival for `slot` is `READY` (R-10). */
    fun allow(slot: SpfnStateSlot)
    {
        marks.remove(slot);
    }

    private fun replace(slot: SpfnStateSlot, cell: SpfnStateCell): SpfnStateCell
    {
        entries[slot] = cell;
        return cell;
    }

    /** The web client's `changes`: no value yet, or another value or data (deep equality). */
    private fun changes(current: SpfnStateCell?, arrival: SpfnStateArrival): Boolean
    {
        val frame = current?.frame ?: return true;
        return frame.value != arrival.value || frame.data != arrival.data;
    }
}
