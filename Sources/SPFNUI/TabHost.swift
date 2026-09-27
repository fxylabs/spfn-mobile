#if canImport(SwiftUI)
// SPFN Mobile — the bottom tab container: one NavigationHost per tab, and a bar the SDK draws.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/TabHost.kt: same
// names, same bar, same rules. Guarded whole, first line of code to last, the way every
// SwiftUI file in this module is (docs/IMPLEMENTATION-PITFALLS.md P20). The design is
// docs/architecture/tab-host-design.md; the short form is here so the code can be read
// against it.
//
// ---------------------------------------------------------------------------
// Not the system `TabView`, and why the bar is in each tab's ROOT
// ---------------------------------------------------------------------------
//
// A `TabView` needs every pushed destination to hide its bar with
// `.toolbar(.hidden, for: .tabBar)` and the pop to bring it back, and both of those, and a
// path-bound `NavigationStack` inside a tab, carry reported defects inside this package's
// iOS 17–26 range; on the 18.2 and 26.3 simulators the system bar came back about 0.23 s
// after a pop had finished (§2-1, §5 U-1). So the SDK draws the bar (decision Q-A), and puts
// it where nothing has to hide it: in the root screen of each tab, as a bottom safe-area
// inset. A pushed route is a navigation destination ABOVE that root, so it slides in over
// the root and its bar together, and the edge swipe back previews the root with its bar.
// No code here hides a bar, and P29's grep for `toolbar(.hidden` stays empty.
//
// The inset is attached ONCE, here, to whatever the app's root closure returns. A root that
// re-renders re-renders inside it and does not add a second bar, and the bottom safe area is
// the bar's to spend while it is shown: a `Screen` inside the root scrolls to end above it.
//
// ---------------------------------------------------------------------------
// A tab once opened stays alive
// ---------------------------------------------------------------------------
//
// Every tab opened at least once stays in a `ZStack`, hidden when not selected — invisible,
// not hit-testable and out of the accessibility tree — so its `NavigationHost`'s store and
// the scroll position UIKit holds are where they were left (decision Q-D). A tab never
// opened is not built at all: its root would otherwise make its first read before anybody
// looked at it (R6).
//
// A modal or a sheet opened inside a tab needs nothing from this: `fullScreenCover` and
// `sheet` are presented by the window and cover the bar wherever they are opened from.

import SwiftUI

/// One tab, declared as data: its id, what the bar shows for it, and its root content.
///
/// The root is erased to `AnyView` so that tabs whose roots have different types stand in
/// one array; it is built once per render of its tab, the cost `Screen` pays for its items.
public struct TabItem: Identifiable
{
    /// The tab's identity: ``TabState``'s id and the bar item's accessibility identifier
    /// `tab.<id>`. lowerCamelCase, as a spec name is.
    public let id: String

    /// The bar item's label.
    public let title: String

    /// The bar item's mark, drawn as a template in the theme's colours.
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

/// The bottom tab container: `tabs` in a bar the SDK draws, one navigation stack per tab.
///
/// The app's top level. There is no ``NavigationHost`` above it — each tab IS one, and a
/// `NavigationStack` inside another is a nesting SwiftUI does not support (§2-3). Theme it
/// from outside, as a host: `TabHost(state: tabs, tabs: items).spfnTheme(brand)`.
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
/// build draws the items `tabs` has.
@MainActor
public struct TabHost: View
{
    private let state: TabState
    private let tabs: [TabItem]

    /// One store per tab, made the first time a tab is drawn and kept for as long as this
    /// view is: it is what a pop to a tab's root goes through.
    @State private var stores = TabStores()

    /// The tabs opened at least once, which are the ones kept alive.
    @State private var opened: Set<String> = []

    public init(state: TabState, tabs: [TabItem])
    {
        assert(tabs.map(\.id) == state.tabs, "TabHost was given tabs \(tabs.map(\.id)) for a TabState of \(state.tabs)")
        self.state = state
        self.tabs = tabs
    }

    public var body: some View
    {
        ZStack
        {
            ForEach(tabs)
            { item in
                if item.id == state.selected || opened.contains(item.id)
                {
                    let shown = item.id == state.selected
                    tab(item)
                        .opacity(shown ? 1 : 0)
                        .allowsHitTesting(shown)
                        .accessibilityHidden(!shown)
                }
            }
        }
        .onChange(of: state.selected, initial: true)
        {
            opened.insert(state.selected)
        }
    }

    /// One tab: its own host, and the app's root with the bar under it.
    private func tab(_ item: TabItem) -> some View
    {
        let store = stores.of(item.id)
        return NavigationHost(store: store)
        {
            item.root()
                .safeAreaInset(edge: .bottom, spacing: 0)
                {
                    TabBar(state: state, tabs: tabs, store: store)
                }
        }
        .environment(\.tabScrollToTop, TabScrollToTop(count: state.scrollToTop(for: item.id)))
    }
}

/// The bar: one item per tab, each an equal share of the width.
///
/// A container VoiceOver reads as a tab bar, whose items are buttons with the selected one
/// marked selected (§6). It stays under the keyboard rather than riding up on it: the root's
/// body gets out of the keyboard's way (K1) and the bar does not move for it (C-23, U-6).
@MainActor
private struct TabBar: View
{
    let state: TabState
    let tabs: [TabItem]
    let store: HostStackStore

    @Environment(\.spfnTheme) private var theme
    @Environment(\.colorScheme) private var scheme

    var body: some View
    {
        let palette = theme.palette(for: scheme)
        return HStack(spacing: 0)
        {
            ForEach(tabs)
            { item in
                TabBarItem(item: item, selected: item.id == state.selected)
                {
                    if state.select(item.id, depth: store.depth) == .popToRoot
                    {
                        store.shorten(to: 0)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity)
        .background(palette.surface)
        .overlay(alignment: .top)
        {
            Rectangle()
                .fill(palette.handle)
                .frame(height: Metrics.borderWidth)
        }
        .accessibilityElement(children: .contain)
        .modifier(TabBarTraits())
        .ignoresSafeArea(.keyboard, edges: .bottom)
    }
}

/// One item: the mark over the label, in the accent when selected and the secondary text
/// colour when not.
///
/// A plain `Button` whose label fills the item and carries a `contentShape`, so the whole
/// share answers a finger and not only the letters (P39); at least the minimum touch target
/// tall in its own frame (P21). The label is one line; past the first accessibility size the
/// item stops growing and a long press shows the large content viewer, as the system bar does.
@MainActor
private struct TabBarItem: View
{
    let item: TabItem
    let selected: Bool
    let onTap: @MainActor () -> Void

    @Environment(\.spfnTheme) private var theme
    @Environment(\.colorScheme) private var scheme

    var body: some View
    {
        let palette = theme.palette(for: scheme)
        let tint = selected ? palette.accent : palette.textSecondary
        return Button(action: onTap)
        {
            VStack(spacing: theme.spacing.space1)
            {
                (selected ? item.selectedIcon ?? item.icon : item.icon)
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: Metrics.iconSize, height: Metrics.iconSize)
                    .accessibilityHidden(true)
                Text(item.title)
                    .font(theme.typography.caption)
                    .lineLimit(1)
            }
            .foregroundStyle(tint)
            .padding(.vertical, theme.spacing.space1)
            .frame(maxWidth: .infinity, minHeight: Metrics.touchTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        .accessibilityLabel(item.accessibilityLabel ?? item.title)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("tab.\(item.id)")
        .modifier(LargeContentViewer(title: item.title))
    }
}

/// The tab bar trait, where the platform has one. iOS 17 — this package's floor — is where
/// `.isTabBar` arrived; macOS draws no tab bar of this kind and gets the container alone.
private struct TabBarTraits: ViewModifier
{
    func body(content: Content) -> some View
    {
    #if os(iOS)
        content.accessibilityAddTraits(.isTabBar)
    #else
        content
    #endif
    }
}

/// The large content viewer a system tab bar item shows on a long press at the largest
/// sizes, which is what stands in for a label that stopped growing. iOS only.
private struct LargeContentViewer: ViewModifier
{
    let title: String

    func body(content: Content) -> some View
    {
    #if os(iOS)
        content
            .accessibilityShowsLargeContentViewer
            {
                Text(title)
            }
    #else
        content
    #endif
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
