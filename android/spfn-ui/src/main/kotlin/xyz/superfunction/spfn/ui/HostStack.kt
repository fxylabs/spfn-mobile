// SPFN Mobile — the one ordered list a host's navigation stands on.
//
// Counterpart of Sources/SPFNUI/HostStack.swift. Deliberately free of Compose — free of
// every toolkit — because every rule here is a rule about a list, so a JVM unit test drives
// the whole reconciliation without a device, an emulator or a composition.
//
// ---------------------------------------------------------------------------
// Why a host needs a list at all
// ---------------------------------------------------------------------------
//
// A pushed flow used to draw its OWN NavDisplay over the host's screen (work unit w-evwna 3d
// and before). That is what decision N1 undid: a stack drawn over the host is a stack with
// no transition into it and no route under its root, so the flow's first screen appeared
// without moving and had no way back to the menu that opened it
// (docs/IMPLEMENTATION-PITFALLS.md P31). A pushed flow now APPENDS to the host's own stack,
// and this is the list it appends to.
//
// One list and not one per flow, because the platform navigators take one: Compose's
// `NavDisplay` is one back stack and SwiftUI's `NavigationStack(path:)` is one array. Two
// flows may stand on it at once, so every entry says whose it is, and the three operations
// below are the whole of what a host does with them:
//
//   `sync`        one flow's routes are replaced by its current stack, leaving every other
//                 flow's entries where they were. This is how a flow's own state reaches
//                 the host: the flow stays the single source of truth and the host follows.
//
// The list is CHRONOLOGICAL: an entry stands where it was pushed, and the two flows on it
// interleave if that is the order a person put them in. A list grouped by owner instead —
// each flow's routes gathered where its first one went — reads tidier and is wrong on a
// device: with `[a1, b1]` on the stack, a push from the covered flow A would land a2 UNDER
// b1, so A's own top and the host's top would be two different screens and the system back
// would go to the flow the person was not looking at.
//   `shortened`   the platform cut the tail off — a system back, a predictive back — and
//                 each flow has to be told how many of ITS routes went.
//   `topOwner`    whose `back` a system gesture is, asked before the gesture is claimed.
//
// Nothing here mutates: every operation answers with a value, and the host is what stores
// the answer. A second mutable copy of a stack is the exact defect `FlowHost` was built to
// avoid, and it does not become safe by being one level up.

package xyz.superfunction.spfn.ui

/**
 * One route on a host's stack, and whose it is.
 *
 * A `data class` and not a pair of fields on something bigger, because Navigation 3
 * identifies a back stack entry BY VALUE: two entries that are equal are one entry to it,
 * and a key with no `equals`/`hashCode` of its own would make every route on the stack
 * distinct by identity and its saved state unreachable. The route is `Any` because the
 * entries of two flows with two different route types stand on one list.
 */
public data class HostEntry(
    /** The flow this route belongs to, as its identity. */
    public val owner: Any,
    /** The route itself, as the flow's own type erased to what a navigator needs. */
    public val route: Any
)

/** The host's stack: every open flow's routes, in the order they are drawn. */
public data class HostStack(
    /**
     * The entries, oldest first. The host's own root is NOT one of them — it is what this
     * list stands on, and a list that carried it could be emptied past it.
     */
    public val entries: List<HostEntry> = emptyList()
)
{
    /**
     * Replaces [owner]'s entries with [routes], leaving every other owner's alone and every
     * entry that stays exactly where it stood.
     *
     * Three rules, and they are the chronological order the header states:
     *
     * - what this owner already had and still has, from the front — the shared prefix —
     *   does not move, because none of it was pushed or popped;
     * - what it had past that prefix is gone, wherever on the list it was standing;
     * - what is new past that prefix is APPENDED, in order, because a route pushed now goes
     *   on top of everything pushed before it, including another flow's.
     *
     * A flow that closed syncs an empty list: nothing is shared, so every entry of its goes.
     */
    public fun sync(owner: Any, routes: List<Any>): HostStack
    {
        val shared = sharedPrefix(entries.filter { it.owner == owner }.map { it.route }, routes);

        // `seen` counts this owner's entries only, so the nth of them survives exactly while
        // n is still inside the shared prefix — which is what "does not move" comes to.
        var seen = 0;
        val kept = entries.filter { entry ->
            if (entry.owner != owner) return@filter true;
            seen++;
            seen <= shared;
        }.toMutableList();
        kept.addAll(routes.drop(shared).map { HostEntry(owner, it) });
        return HostStack(kept.toList());
    }

    /**
     * How many entries each owner loses when this stack is cut to [to].
     *
     * The platform shortens its own back stack first and tells us afterwards, and what it
     * hands over is a LENGTH. Turning that back into "this flow lost two and that one lost
     * one" is the whole of the reconciliation, and it is arithmetic rather than a guess: the
     * entries beyond [to] are exactly what went, and each of them says whose it was.
     *
     * A [to] at or past the current length drops nothing, which is every value the platform
     * sends that is not a shortening.
     */
    public fun shortened(to: Int): Map<Any, Int>
    {
        val dropped = mutableMapOf<Any, Int>();
        entries.drop(maxOf(to, 0)).forEach { entry ->
            dropped[entry.owner] = (dropped[entry.owner] ?: 0) + 1;
        };
        return dropped.toMap();
    }

    /**
     * Whose entry is on top, or null on a stack standing on the host's own root.
     *
     * What a system back is asked about: only the flow on top can have consumed it, and a
     * stack with nothing on it has handed the gesture back to the platform already.
     */
    public fun topOwner(): Any? = entries.lastOrNull()?.owner
}

/**
 * How many routes [old] and [new] share from the front.
 *
 * The dividing line [HostStack.sync] is written around: everything before it is a route the
 * flow has not touched, and everything at or after it on either side is what this sync pops
 * or pushes.
 */
private fun sharedPrefix(old: List<Any>, new: List<Any>): Int
{
    var shared = 0;
    while (shared < minOf(old.size, new.size) && old[shared] == new[shared])
    {
        shared++;
    }
    return shared;
}
