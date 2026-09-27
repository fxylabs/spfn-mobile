// SPFN Mobile — which tab is selected, and what a press on the bar or a system back means.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/TabState.kt.
// Deliberately free of SwiftUI, for the reason `Flow` is: every rule here is a rule about a
// list of ids and one selected id, so the whole case table of
// docs/architecture/tab-host-design.md §4 that is pure state runs as an ordinary unit suite
// on the Linux host this repository's Swift gate runs on. `TabHost.swift` is what binds it
// to a view.
//
// ---------------------------------------------------------------------------
// Why the depth is an argument
// ---------------------------------------------------------------------------
//
// A press on the current tab pops to its root when the tab stands on something, and asks
// for a scroll to the top when it does not. A system back on a tab's root goes to the start
// tab. Both answers depend on how deep the SELECTED tab's stack stands, and that stack is
// its `NavigationHost`'s — which this type does not hold and must not: one tab per
// `NavigationHost` is the design (§2-3), and a second copy of a host's depth kept here would
// be a second writer of a stack that has one on purpose. So the host passes the depth in and
// this type answers, the way `Flow.back(entry:)` is told the presentation and answers. The
// decision is a pure function; the host performs it.
//
// The attribute is behind the same `canImport(Observation) && canImport(SwiftUI)` as
// `Flow`'s, for the link failure `Flow.swift` records.

#if canImport(Observation) && canImport(SwiftUI)
import Observation
#endif

/// What a press on a tab bar item asked for, which the host then performs.
public enum TabSelection: Equatable, Sendable
{
    /// Another tab was pressed and is now selected. Nothing moved in either tab's stack.
    case switched

    /// The selected tab was pressed while it stood above its root: the host pops it there.
    case popToRoot

    /// The selected tab was pressed on its root: its scroll-to-top count went up by one.
    case scrollToTop

    /// The id is not one of this state's tabs. Nothing changed.
    case ignored
}

/// The tabs a `TabHost` shows, which one is selected, and what a press or a back means.
///
/// The ORDER of `tabs` is the bar's order, and the first is the start tab: the one a launch
/// selects and the one a system back on another tab's root returns to. There is no argument
/// that names a start tab of its own, because a second way to say it is a second answer.
///
/// ```swift
/// let tabs = try TabState(tabs: ["home", "account"])
/// tabs.show("account")                    // a deep link, or the app switching tabs itself
/// ```
#if canImport(Observation) && canImport(SwiftUI)
@Observable
#endif
@MainActor
public final class TabState
{
    /// The tab ids, in the bar's order.
    public let tabs: [String]

    /// The first tab, which a launch selects and a back on another tab's root returns to.
    public let start: String

    /// The selected tab's id.
    public private(set) var selected: String

    /// How many times each tab's root was asked to scroll to its top. Absent means zero.
    private var scrollCounts: [String: Int] = [:]

    /// - Throws: ``SPFNUIError/noTabs`` for an empty list, ``SPFNUIError/duplicateTab(_:)``
    ///   for an id written twice, ``SPFNUIError/unknownTab(_:)`` for a `selected` that is not
    ///   in the list. The same refusal `Flow.open(at:)` makes: a state this type cannot be in
    ///   is refused where it is asked for rather than drawn as a bar with nothing selected.
    public init(tabs: [String], selected: String? = nil) throws
    {
        guard let first = tabs.first
        else
        {
            throw SPFNUIError.noTabs
        }
        if let twice = firstDuplicate(tabs)
        {
            throw SPFNUIError.duplicateTab(twice)
        }
        if let selected = selected, !tabs.contains(selected)
        {
            throw SPFNUIError.unknownTab(selected)
        }
        self.tabs = tabs
        self.start = first
        self.selected = selected ?? first
    }

    /// A press on the bar item `id`, while the selected tab's stack stands `depth` above its
    /// root. What the bar calls, and only the bar.
    ///
    /// - Returns: what the host has to do about it. ``TabSelection/popToRoot`` is the host's
    ///   to perform — this type does not hold the stack.
    @discardableResult
    public func select(_ id: String, depth: Int) -> TabSelection
    {
        guard tabs.contains(id)
        else
        {
            return .ignored
        }
        if id != selected
        {
            selected = id
            return .switched
        }
        if depth > 0
        {
            return .popToRoot
        }
        scrollCounts[id, default: 0] += 1
        return .scrollToTop
    }

    /// Selects `id` the way an app does — a deep link, a switch in code. Never pops and never
    /// asks for a scroll, and does nothing for an id that is already selected or not a tab.
    ///
    /// Not ``select(_:depth:)`` with a depth of zero, and the difference is the point: a
    /// press on the selected tab MEANS something, and an app that shows the tab it is already
    /// on means nothing by it. A deep link that popped the stack it arrived in would undo
    /// itself.
    public func show(_ id: String)
    {
        guard tabs.contains(id), id != selected
        else
        {
            return
        }
        selected = id
    }

    /// A system back, while the selected tab's stack stands `depth` above its root.
    ///
    /// On the root of a tab that is not the start tab, the start tab is selected and the back
    /// is consumed. Everywhere else it is not: above a root the tab's own navigator pops, and
    /// on the start tab's root the platform leaves the app (decision Q-B).
    ///
    /// - Returns: whether this state consumed the back.
    @discardableResult
    public func back(depth: Int) -> Bool
    {
        guard handlesBack(depth: depth)
        else
        {
            return false
        }
        selected = start
        return true
    }

    /// Whether ``back(depth:)`` would consume a back, asked BEFORE the gesture is claimed —
    /// Android's back handler takes its `enabled` flag ahead of the event, as `Flow`'s does.
    public func handlesBack(depth: Int) -> Bool
    {
        depth == 0 && selected != start
    }

    /// How many times the root of tab `id` has been asked to scroll to its top. An app's list
    /// scrolls when this changes; the SDK does not hold the list's scroll state and does not
    /// scroll it (decision Q-C).
    public func scrollToTop(for id: String) -> Int
    {
        scrollCounts[id] ?? 0
    }
}

/// Which tab each pushed flow belongs to, so one flow is never on two tabs' stacks.
///
/// A flow's `FlowHost(.push)` registers with the nearest `NavigationHost`, and inside a
/// `TabHost` that is its own tab's: another tab's host is not in its environment, so it
/// cannot register there by mistake. What CAN happen is an app putting the same flow's host
/// in two tab roots, and then two hosts would follow one stack and the detail would stand on
/// both tabs at once. The first tab to claim a flow keeps it; the second claim is refused,
/// which a debug build turns into a stop (docs/architecture/tab-host-design.md §2-3).
@MainActor
final class TabOwners
{
    private var tabs: [ObjectIdentifier: String] = [:]

    /// Whether `tab` may register `owner`: it may if nobody has, or if it already did.
    func claim(_ owner: ObjectIdentifier, for tab: String) -> Bool
    {
        if let holder = tabs[owner]
        {
            return holder == tab
        }
        tabs[owner] = tab
        return true
    }
}

/// The first id that appears twice in `ids`, or nil.
private func firstDuplicate(_ ids: [String]) -> String?
{
    var seen = Set<String>()
    for id in ids where !seen.insert(id).inserted
    {
        return id
    }
    return nil
}
