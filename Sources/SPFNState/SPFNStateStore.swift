// SPFN Mobile — the entries, their ordering stamps and their revoked marks.
//
// One rule for every arrival — a snapshot entry, a frame, a read or a write answer — per
// `(id, key)`: the README's "Ordering" table, rows O-1 … O-16. The stamp is the newest `at`
// seen and is kept apart from the entry, so an arrival that changes nothing still orders the
// ones after it while the entry's `at` stays the `at` of the read that last changed the value.
// The revoked mark is kept apart too, with the connection (epoch) that set it: a mark holds
// before any value arrives, and only a newer connection's arrival lifts it (§2-8).
//
// Values are compared canonically — objects as dictionaries, arrays in order — which is
// the web client's `sameJson` (§2-5). A generated type's `==` is not used: its decoder drops
// fields it does not know, and a change in one would be news on the web and not here.
//
// android/spfn-state/.../SpfnStateStore.kt is the same store in Kotlin.

import SPFNCore

/// One `(id, key)`.
struct SPFNStateSlot: Hashable, Sendable
{
    let id: String
    let key: SPFNStateKey
}

/// One arrival, canonical for comparing and typed for handing out.
struct SPFNStateArrival: Sendable
{
    let at: String
    let value: SPFNCanonicalValue
    let data: SPFNCanonicalValue?
    /// The `SPFNStateFrame<Value, Data>` the state's decoder made of it.
    let typed: any Sendable

    /// `{ at, value, data? }` — a snapshot entry with the snapshot's `at`, as a frame.
    static func canonical(at: String, entry: SPFNCanonicalValue) -> SPFNCanonicalValue
    {
        guard case .object(var members) = entry
        else
        {
            return entry
        }
        members["at"] = .string(at)
        return .object(members)
    }
}

/// An entry as the store holds it. No cell is `loading`.
struct SPFNStateCell: Sendable
{
    let status: SPFNStateStatus
    let frame: SPFNStateArrival?
}

struct SPFNStateStore
{
    private(set) var cells: [SPFNStateSlot: SPFNStateCell] = [:]
    private var stamps: [SPFNStateSlot: String] = [:]
    private var marks: [SPFNStateSlot: Int] = [:]

    /// An arrival under the ordering rule; the status comes from the mark. Answers the new
    /// cell when the entry changed, nil when it did not.
    mutating func apply(_ slot: SPFNStateSlot, _ arrival: SPFNStateArrival) -> SPFNStateCell?
    {
        let current = cells[slot]
        let status: SPFNStateStatus = marks[slot] == nil ? .ready : .revoked
        let ordered = stamps[slot].map { !SPFNStateOrder.isOlder(arrival.at, than: $0) } ?? true
        if ordered
        {
            stamps[slot] = arrival.at
        }
        if ordered && Self.changes(current, arrival)
        {
            return replace(slot, SPFNStateCell(status: status, frame: arrival))
        }
        if let current, current.status != status
        {
            return replace(slot, SPFNStateCell(status: status, frame: current.frame))
        }
        return nil
    }

    /// An arrival of an automatic read or a frame, tagged with its connection: a connection
    /// newer than the one that set the mark lifts it, whatever the `at` (R-11 … R-14).
    mutating func serve(_ slot: SPFNStateSlot, _ arrival: SPFNStateArrival, generation: Int) -> SPFNStateCell?
    {
        if let mark = marks[slot], mark < generation
        {
            marks[slot] = nil
        }
        return apply(slot, arrival)
    }

    /// Marks `slot` revoked by the connection of `generation`; a value it has is kept.
    mutating func revoke(_ slot: SPFNStateSlot, generation: Int) -> SPFNStateCell?
    {
        marks[slot] = generation
        guard cells[slot]?.status != .revoked
        else
        {
            return nil
        }
        return replace(slot, SPFNStateCell(status: .revoked, frame: cells[slot]?.frame))
    }

    /// The newest `at` seen for `slot`.
    func stamp(_ slot: SPFNStateSlot) -> String?
    {
        stamps[slot]
    }

    /// The connection that revoked `slot`, or nil while it is served.
    func mark(_ slot: SPFNStateSlot) -> Int?
    {
        marks[slot]
    }

    /// Clears the mark: the next arrival for `slot` is `ready` (R-10).
    mutating func allow(_ slot: SPFNStateSlot)
    {
        marks[slot] = nil
    }

    private mutating func replace(_ slot: SPFNStateSlot, _ cell: SPFNStateCell) -> SPFNStateCell
    {
        cells[slot] = cell
        return cell
    }

    /// The web client's `changes`: no value yet, or another value or data (deep equality).
    private static func changes(_ current: SPFNStateCell?, _ arrival: SPFNStateArrival) -> Bool
    {
        guard let frame = current?.frame
        else
        {
            return true
        }
        return frame.value != arrival.value || frame.data != arrival.data
    }
}
