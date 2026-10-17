// SPFN Mobile — the snapshot every state is read in at once (`getStates`).
//
// `{ at, states: { [id]?: { value, data? } }, manifest: { [id]: { key, write } } }` — the
// shape the state router derives (README "Contract"). The state client reads it itself:
// the generated decoder checks the document's shape first, and what stays here is the
// canonical value, so each state's entry is decoded by that state's own read decoder.
//
// Sources/SPFNState/SPFNStatesSnapshot.swift is the same value in Swift.

package xyz.superfunction.spfn.state

import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnDecoding

/**
 * @property states each state present, as `{ value, data? }`. A state the subject may not
 *   read is absent.
 */
data class SpfnStatesSnapshot(
    val at: String,
    val manifest: Map<String, State>,
    val states: Map<String, SpfnCanonicalValue>
)
{
    /** What the manifest says of one state: its key fields and whether it has a write. */
    data class State(val key: List<String>, val write: Boolean)

    companion object
    {
        /**
         * Reads a snapshot from its canonical value — the server's answer, or a fixture.
         *
         * @throws xyz.superfunction.spfn.core.SpfnDecodingException the value is not a snapshot.
         */
        fun decode(canonical: SpfnCanonicalValue): SpfnStatesSnapshot
        {
            val members = SpfnDecoding.obj(canonical, "$");
            return SpfnStatesSnapshot(
                at = SpfnDecoding.string(members["at"], "$.at"),
                manifest = SpfnDecoding.obj(members["manifest"], "$.manifest").mapValues { state(it.value, "$.manifest." + it.key) },
                states = SpfnDecoding.obj(members["states"], "$.states")
            );
        }

        private fun state(value: SpfnCanonicalValue, path: String): State
        {
            val members = SpfnDecoding.obj(value, path);
            val key = SpfnDecoding.array(members["key"], "$path.key").mapIndexed { index, field -> SpfnDecoding.string(field, "$path.key[$index]") };
            return State(key, SpfnDecoding.boolean(members["write"], "$path.write"));
        }
    }
}
