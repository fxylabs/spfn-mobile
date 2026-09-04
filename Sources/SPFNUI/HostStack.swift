// SPFN Mobile — the one ordered list a host's navigation stands on.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/HostStack.kt.
// Deliberately free of SwiftUI — free of every toolkit, and free of the `canImport` guard
// every other file in this module carries — because every rule here is a rule about an
// array. That is what lets the whole reconciliation run as an ordinary unit suite on the
// Linux host this repository's Swift gate runs on, where `NavigationStack` does not exist.
//
// ---------------------------------------------------------------------------
// Why a host needs a list at all
// ---------------------------------------------------------------------------
//
// A pushed flow used to draw its OWN navigation stack over the host's screen (work unit
// w-evwna 3d and before). That is what decision N1 undid: a stack drawn over the host is a
// stack with no transition into it and no route under its root, so the flow's first screen
// appeared without moving and had no way back to the menu that opened it
// (docs/IMPLEMENTATION-PITFALLS.md P31). A pushed flow now APPENDS to the host's own stack,
// and this is the list it appends to.
//
// One list and not one per flow, because the platform navigators take one: SwiftUI's
// `NavigationStack(path:)` is one array and Compose's `NavDisplay` is one back stack. Two
// flows may stand on it at once, so every entry says whose it is, and the three operations
// below are the whole of what a host does with them:
//
//   `sync`        one flow's routes are replaced by its current stack, leaving every other
//                 flow's entries where they were. This is how a flow's own state reaches
//                 the host: the flow stays the single source of truth and the host follows.
//   `shortened`   the platform cut the tail off — a system back, a swipe — and each flow
//                 has to be told how many of ITS routes went, because one gesture on one
//                 stack can only belong to whoever was on top.
//   `topOwner`    whose `back` a system gesture is, asked before the gesture is claimed.
//
// Nothing here mutates: every operation answers with a value, and the host is what stores
// the answer. A second mutable copy of a stack is the exact defect `FlowHost` was built to
// avoid, and it does not become safe by being one level up.

/// One route on a host's stack, and whose it is.
///
/// The route is `AnyHashable` because the entries of two flows with two different route
/// types stand on one list. `Hashable` is what both platform navigators identify a stack
/// entry by, so the pair has to be hashable as a pair — which is also why the owner is an
/// `ObjectIdentifier` rather than the flow itself: a flow is not `Hashable` and does not
/// become so for a navigator's benefit.
public struct HostEntry: Hashable
{
    /// The flow this route belongs to, as its identity.
    public let owner: ObjectIdentifier

    /// The route itself, as the flow's own type erased to what a navigator needs.
    public let route: AnyHashable

    public init(owner: ObjectIdentifier, route: AnyHashable)
    {
        self.owner = owner
        self.route = route
    }
}

/// The host's stack: every open flow's routes, in the order they are drawn.
public struct HostStack: Equatable
{
    /// The entries, oldest first. The host's own root is NOT one of them — it is what this
    /// list stands on, and a list that carried it could be emptied past it.
    public let entries: [HostEntry]

    public init(entries: [HostEntry] = [])
    {
        self.entries = entries
    }

    /// Replaces `owner`'s entries with `routes`, leaving every other owner's alone.
    ///
    /// The replacement goes back where the owner's FIRST entry was, so a flow that pushes a
    /// route does not jump over a flow that was standing under it; an owner with nothing on
    /// the stack yet is appended at the end, because it is arriving now. A flow that closed
    /// syncs an empty list, which is how its entries leave.
    public func sync(owner: ObjectIdentifier, routes: [AnyHashable]) -> HostStack
    {
        var kept = entries.filter { $0.owner != owner }
        // Every entry before this owner's first is somebody else's, so that index counts
        // the kept entries standing in front of it exactly.
        let position = entries.firstIndex { $0.owner == owner } ?? kept.count
        kept.insert(contentsOf: routes.map { HostEntry(owner: owner, route: $0) }, at: position)
        return HostStack(entries: kept)
    }

    /// How many entries each owner loses when this stack is cut to `count`.
    ///
    /// The platform shortens its own path first and tells us afterwards, and what it hands
    /// over is a LENGTH. Turning that back into "this flow lost two and that one lost one"
    /// is the whole of the reconciliation, and it is arithmetic rather than a guess: the
    /// entries beyond `count` are exactly what went, and each of them says whose it was.
    ///
    /// A `count` at or past the current length drops nothing, which is every value the
    /// platform sends that is not a shortening.
    public func shortened(to count: Int) -> [ObjectIdentifier: Int]
    {
        var dropped: [ObjectIdentifier: Int] = [:]
        for entry in entries.dropFirst(max(count, 0))
        {
            dropped[entry.owner, default: 0] += 1
        }
        return dropped
    }

    /// Whose entry is on top, or `nil` on a stack standing on the host's own root.
    ///
    /// What a system back is asked about: only the flow on top can have consumed it, and a
    /// stack with nothing on it has handed the gesture back to the platform already.
    public func topOwner() -> ObjectIdentifier?
    {
        entries.last?.owner
    }
}
