#if canImport(SwiftUI)
// SPFN Mobile — the bottom tab container: the system `TabView`, one NavigationHost per tab.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/TabHost.kt: same
// names, same `TabState`, same rules — but not the same bar. Guarded whole, first line of code
// to last, the way every SwiftUI file in this module is (docs/IMPLEMENTATION-PITFALLS.md P20).
// The design is docs/architecture/tab-host-design.md; the short form is here so the code can
// be read against it.
//
// ---------------------------------------------------------------------------
// The system `TabView`, and what that costs
// ---------------------------------------------------------------------------
//
// The bar is the platform's: on iOS 26 the floating Liquid Glass capsule with its selected
// pill, before that the classic bar, and on both the system's own accessibility, large content
// viewer and insets. An SDK-drawn bar looked like no other iOS app on 26, so the decision Q-A
// was reopened and turned around (§2-1): iOS uses the system `TabView` as it is, and Android
// keeps the bar the SDK draws. What was measured against this choice is accepted, not solved:
// the system hides its bar for a pushed destination and brings it back about 0.23 s AFTER a
// pop has finished (§5 U-1, iOS 18.2 and 26.3).
//
// A pushed destination hides the bar with `.toolbar(.hidden, for: .tabBar)`, and the SDK
// applies it — `NavigationHost` does, on the one destination it declares, when it stands in a
// tab. The app writes nothing. A tab's root is not a destination, so depth 0 shows the bar.
//
// Selection runs through a binding whose getter is `TabState.selected` and whose setter is
// `TabState.select(_:depth:)`. The system calls the setter for a press on the tab that is
// already selected too (§5 U-3), which is how a reselect still pops to the root or bumps
// `TabScrollToTop` as the case table says.
//
// ---------------------------------------------------------------------------
// A tab once opened stays alive
// ---------------------------------------------------------------------------
//
// `TabView` keeps the view of every tab that has been shown, and builds a tab's content the
// first time it is selected. The stores are not in those views: `TabStores` sits in this
// view's `@State` and hands each tab's `NavigationHost` the same store on every render, so a
// tab left at a depth is at that depth when it is selected again (decision Q-D).
//
// A modal or a sheet opened inside a tab needs nothing from this: `fullScreenCover` and
// `sheet` are presented by the window and cover the system bar wherever they are opened from.

import SwiftUI

/// One tab, declared as data: its id, what the system bar shows for it, and its root content.
///
/// The root is erased to `AnyView` so that tabs whose roots have different types stand in
/// one array; it is built once per render of its tab, the cost `Screen` pays for its items.
public struct TabItem: Identifiable
{
    /// The tab's identity: ``TabState``'s id, and the Android bar item's test tag `tab.<id>`.
    /// lowerCamelCase, as a spec name is. The iOS bar is the system's, whose buttons carry no
    /// identifier of the SDK's: a runner finds them by their label.
    public let id: String

    /// The bar item's label.
    public let title: String

    /// The bar item's mark. Handed to the system bar as a template, so the bar tints it: an SF
    /// Symbol as it is, and an app's own image by its alpha alone, whatever rendering its asset
    /// catalog sets. The system bar does not scale an app's image, so that image is a vector
    /// asset drawn at the bar's glyph size (about 25 pt in the Human Interface Guidelines).
    public let icon: Image

    /// The mark while the tab is selected. `nil` draws ``icon`` in both states.
    public let selectedIcon: Image?

    /// What VoiceOver reads for the item. `nil` reads ``title``.
    public let accessibilityLabel: String?

    let root: @MainActor () -> AnyView

    public init<Root: View>(
        id: String,
        title: String,
        icon: Image,
        selectedIcon: Image? = nil,
        accessibilityLabel: String? = nil,
        @ViewBuilder root: @escaping @MainActor () -> Root
    )
    {
        self.id = id
        self.title = title
        self.icon = icon
        self.selectedIcon = selectedIcon
        self.accessibilityLabel = accessibilityLabel
        self.root = { AnyView(root()) }
    }
}

/// How many times this tab's root has been asked to scroll to its top — a press on the tab
/// that is already selected, standing on its root.
///
/// The SDK does not scroll: the root's list is the app's, and so is its scroll state
/// (decision Q-C). A list follows the count instead:
///
/// ```swift
/// @Environment(\.tabScrollToTop) private var scrollToTop
/// ...
/// .onChange(of: scrollToTop) { proxy.scrollTo(top) }
/// ```
public struct TabScrollToTop: Equatable, Sendable
{
    public let count: Int

    public init(count: Int)
    {
        self.count = count
    }

    /// Outside a ``TabHost``, and on a tab that has not been asked yet.
    public static let none = TabScrollToTop(count: 0)
}

/// The bottom tab container: the system `TabView` with `tabs` in it, one navigation stack per
/// tab.
///
/// The app's top level. There is no ``NavigationHost`` above it — each tab IS one, and a
/// `NavigationStack` inside another is a nesting SwiftUI does not support (§2-3). Theme it
/// from outside, as a host: `TabHost(state: tabs, tabs: items).spfnTheme(brand)`; the bar's
/// selected item takes the theme's accent.
///
/// ```swift
/// TabHost(
///     state: container.tabs,
///     tabs: [
///         TabItem(id: "home", title: "Home", icon: Image(systemName: "house"))
///         {
///             ZStack { HomeScreen(); ItemFlowHost(container: container) }
///         },
///         TabItem(id: "account", title: "Account", icon: Image(systemName: "person"))
///         {
///             AccountScreen()
///         }
///     ]
/// )
/// ```
///
/// `tabs`' ids must be `state`'s, in its order. A debug build stops on a mismatch; a release
/// build shows the tabs `tabs` has.
@MainActor
public struct TabHost: View
{
    private let state: TabState
    private let tabs: [TabItem]

    /// One store per tab, made the first time a tab is drawn and kept for as long as this
    /// view is: it is what a pop to a tab's root goes through, and what a tab selected again
    /// finds its stack in.
    @State private var stores = TabStores()

    public init(state: TabState, tabs: [TabItem])
    {
        assert(tabs.map(\.id) == state.tabs, "TabHost was given tabs \(tabs.map(\.id)) for a TabState of \(state.tabs)")
        self.state = state
        self.tabs = tabs
    }

    /// iOS 18's `Tab(value:)` where it exists, and `.tabItem` with `.tag` on iOS 17, this
    /// package's floor. Each branch is erased on its own so that neither type has to exist on
    /// the other branch's systems.
    public var body: some View
    {
        if #available(iOS 18, macOS 15, *)
        {
            AnyView(
                TabView(selection: selection)
                {
                    ForEach(tabs)
                    { item in
                        Tab(value: item.id)
                        {
                            tab(item)
                        }
                        label:
                        {
                            label(item)
                        }
                    }
                }
                .modifier(ThemeTint())
            )
        }
        else
        {
            AnyView(
                TabView(selection: selection)
                {
                    ForEach(tabs)
                    { item in
                        tab(item)
                            .tabItem
                            {
                                label(item)
                            }
                            .tag(item.id)
                    }
                }
                .modifier(ThemeTint())
            )
        }
    }

    /// `TabState` as the selection the system bar reads and writes. Every press on the bar is
    /// the setter, the selected tab's included, and it is `TabState` that says what it means.
    private var selection: Binding<String>
    {
        Binding(
            get:
            {
                state.selected
            },
            set:
            { id in
                press(id)
            }
        )
    }

    /// A press on the bar item `id`, asked about with the depth of the tab standing selected
    /// when it came — the one whose stack a reselect pops.
    private func press(_ id: String)
    {
        let store = stores.of(state.selected)
        if state.select(id, depth: store.depth) == .popToRoot
        {
            store.shorten(to: 0)
        }
    }

    /// One tab: its own host around the app's root, which hides the bar on what it pushes.
    private func tab(_ item: TabItem) -> some View
    {
        NavigationHost(tabStore: stores.of(item.id))
        {
            item.root()
        }
        .environment(\.tabScrollToTop, TabScrollToTop(count: state.scrollToTop(for: item.id)))
    }

    /// What the bar shows for `item`: its title over its mark, the selected mark while the tab
    /// is selected, both as templates the bar tints.
    private func label(_ item: TabItem) -> some View
    {
        Label
        {
            Text(item.title)
        }
        icon:
        {
            (item.id == state.selected ? item.selectedIcon ?? item.icon : item.icon)
                .renderingMode(.template)
        }
        .accessibilityLabel(item.accessibilityLabel ?? item.title)
    }
}

/// Every tab's store, and which tab each flow belongs to.
///
/// A plain class held in `@State`: nothing draws from it, so nothing has to observe it, and
/// making a store the first time a tab is drawn is a write no view has to be told about.
@MainActor
final class TabStores
{
    private let owners = TabOwners()

    private var stores: [String: HostStackStore] = [:]

    /// The store of tab `id`, made on first use. It refuses a flow another tab already holds;
    /// a debug build stops there, and a release build keeps the first tab's (§2-3).
    func of(_ id: String) -> HostStackStore
    {
        if let store = stores[id]
        {
            return store
        }
        let store = HostStackStore
        { [owners] owner in
            let claimed = owners.claim(owner, for: id)
            assert(claimed, "a flow registered with tab '\(id)' is already on another tab's stack; one flow belongs to one tab")
            return claimed
        }
        stores[id] = store
        return store
    }
}

private struct TabScrollToTopKey: EnvironmentKey
{
    static let defaultValue = TabScrollToTop.none
}

extension EnvironmentValues
{
    /// The scroll-to-top count of the tab this view stands in; ``TabScrollToTop/none`` outside
    /// a ``TabHost``.
    public var tabScrollToTop: TabScrollToTop
    {
        get { self[TabScrollToTopKey.self] }
        set { self[TabScrollToTopKey.self] = newValue }
    }
}
#endif
