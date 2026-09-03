#if canImport(SwiftUI)
// SPFN Mobile — the frame every screen in a flow is drawn in.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/Screen.kt. One
// header, one body, and the three things a screen used to have to remember for itself:
// where the status bar is, what the keyboard is covering, and which way out this screen has.
//
// Guarded whole, first line of code to last, the way every SwiftUI file in this repository
// is (docs/IMPLEMENTATION-PITFALLS.md P20): SwiftUI is Apple's, `SPFNUI` builds on Linux,
// and validate.sh section 8 holds the guard to the file rather than to the import.
//
// ---------------------------------------------------------------------------
// The two halves are not written the same way, because the platforms are not
// ---------------------------------------------------------------------------
//
// Android has to ASK for its insets: an app targeting API 35 is drawn edge to edge whether
// it asks or not, so the Compose half spends `WindowInsets.statusBars` on the header and the
// union of the ime and the navigation bars on the body. SwiftUI insets a view by its safe
// area unless it is told not to, so the header being the first thing in the stack IS the
// header consuming the status bar inset, and the body reaching the bottom edge IS the body
// carrying the home indicator's. What the two halves share is the outcome the rule names:
// the header owns the top inset, the body owns the bottom one, and a screen owns neither.
//
// The system navigation bar is hidden because this header replaces it. A `NavigationStack`
// that drew its own bar as well would put two back controls on one screen, only one of which
// the flow knows about.
//
// ---------------------------------------------------------------------------
// Screen owns two of the seven keyboard clauses, and only two
// ---------------------------------------------------------------------------
//
// The body gets out of the keyboard's way, and a tap outside a field puts the keyboard away.
// Both are about the FRAME rather than about any field in it, which is why they are here and
// the other five are on ``SpfnTextField``. `scrollDismissesKeyboard(.interactively)` is the
// third affordance and the one a person reaches for without being told: dragging the content
// they came to read.

import SwiftUI

/// A screen inside a flow: a header, and a body under it.
///
/// - Parameters:
///   - title: what the header says.
///   - leading: the header's left slot. Left out, the flow decides — a back on a stack of
///     two or more, a close on the root of a modal or a sheet, and nothing on the root of a
///     pushed flow (``Flow/leading(entry:)``). A host app that passes one overrides that
///     entirely.
///   - trailing: the header's right slot. There is no default: nothing but the app knows
///     what a screen's action is.
///   - scroll: whether the body scrolls. A body that scrolls also gets out of the keyboard's
///     way; a body that does not is the caller saying its content always fits.
///
/// The two slots are `AnyView?` rather than generic parameters, and that is a considered
/// trade: two more generic parameters would have to be spelled out at every call that omits
/// one, because Swift has no default for a generic parameter. What it costs is one layer of
/// erasure on a control that is drawn once per screen.
@MainActor
public struct Screen<Content: View>: View
{
    private let title: String
    private let leading: AnyView?
    private let trailing: AnyView?
    private let scroll: Bool
    private let content: () -> Content

    @Environment(\.screenChrome) private var chrome
    @Environment(\.colorScheme) private var scheme

    private var palette: SPFNPalette
    {
        spfnPalette(for: scheme)
    }

    public init(
        title: String,
        leading: AnyView? = nil,
        trailing: AnyView? = nil,
        scroll: Bool = true,
        @ViewBuilder content: @escaping () -> Content
    )
    {
        self.title = title
        self.leading = leading
        self.trailing = trailing
        self.scroll = scroll
        self.content = content
    }

    public var body: some View
    {
        VStack(spacing: 0)
        {
            header
            scrollableBody
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(palette.background)
        .modifier(HiddenNavigationBar())
        // A tap that lands on the frame rather than on a control puts the keyboard away.
        // `simultaneousGesture` rather than `onTapGesture`: the latter would claim the tap
        // and a button underneath it would stop responding.
        .contentShape(Rectangle())
        .simultaneousGesture(TapGesture().onEnded { SPFNKeyboard.dismiss() })
    }

    /// The header. Both slots are laid out at the minimum touch target whether or not they
    /// hold anything, so the title sits in the same place on every screen of a flow and a
    /// control that appears does not move it.
    private var header: some View
    {
        HStack(spacing: 0)
        {
            leadingControl
                .frame(minWidth: Metrics.touchTarget, alignment: .leading)
            SpfnText(title, role: .title)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, SPFNTokens.space4)
            (trailing ?? AnyView(EmptyView()))
                .frame(minWidth: Metrics.touchTarget, alignment: .trailing)
        }
        .padding(.horizontal, SPFNTokens.space4)
        .frame(minHeight: Metrics.headerHeight)
    }

    /// The body, scrolling or not.
    ///
    /// `scrollDismissesKeyboard(.interactively)` is the half of keyboard avoidance a screen
    /// cannot do for itself: SwiftUI already lifts a focused field above the keyboard, and
    /// this is what lets a person put the keyboard away by dragging the content they came
    /// to read.
    @ViewBuilder
    private var scrollableBody: some View
    {
        if scroll
        {
            ScrollView
            {
                content()
                    // Fixed vertically, so what is measured below is the content's OWN
                    // height rather than whatever the scroll view proposed to it.
                    .fixedSize(horizontal: false, vertical: true)
                    .background { contentMeasurement }
            }
            .scrollDismissesKeyboard(.interactively)
        }
        else
        {
            content()
        }
    }

    /// Reports how tall this screen's content is, for a `fit` sheet above it to stand on.
    ///
    /// The CONTENT and never the scroll view around it. A scroll view inside a sheet is as
    /// tall as the sheet, so a detent resolved from one feeds its own answer back in and
    /// never settles; the stack inside it has a natural height that does not move when the
    /// sheet does, which is why the measurement is taken here and why the view above is
    /// fixed vertically first.
    ///
    /// A body that does not scroll reports nothing, and that is honest rather than lazy: it
    /// is as tall as the space it was given, so its height says what the sheet already is.
    /// A `fit` sheet over one stands at ``SheetGeometry/fitFallbackFraction``.
    private var contentMeasurement: some View
    {
        GeometryReader
        { proxy in
            Color.clear
                .preference(key: ScreenContentHeightKey.self, value: proxy.size.height)
        }
    }

    /// The leading control the flow asked for, or the one the host app passed, or nothing.
    ///
    /// The chrome arrives from ``FlowHost``, which is the only thing that knows both how the
    /// flow was entered and how deep it stands. A `Screen` composed outside a host reads the
    /// default — no control at all — rather than inventing one.
    @ViewBuilder
    private var leadingControl: some View
    {
        if let leading = leading
        {
            leading
        }
        else
        {
            switch chrome.leading
            {
            case .none:
                EmptyView()
            case .back:
                control(label: SPFNStrings.controlBack, identifier: "screen.back", action: chrome.onBack)
            case .close:
                control(label: SPFNStrings.controlClose, identifier: "screen.close", action: chrome.onClose)
            }
        }
    }

    /// One header control, sized to Apple's minimum touch target in both directions
    /// (docs/IMPLEMENTATION-PITFALLS.md P21).
    private func control(
        label: String,
        identifier: String,
        action: @escaping @MainActor @Sendable () -> Void
    ) -> some View
    {
        Button(action: action)
        {
            SpfnText(label)
                .frame(minWidth: Metrics.touchTarget, minHeight: Metrics.touchTarget)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
    }
}

/// How tall the content of the screen on show is, travelling UP to whatever presented it.
///
/// A preference and not a binding, because the direction is up and neither end knows the
/// other: a ``Screen`` knows how tall its content is and nothing about being inside a sheet,
/// and ``FlowHost``'s sheet knows it needs a height and nothing about which of its routes
/// drew one. Read by `SheetPresentation`, and by nothing else.
///
/// The reduction is the TALLEST reporter rather than the last. A navigation stack has both
/// screens in the tree during a push, and a sheet that took the smaller of the two would
/// shrink under a transition and settle back afterwards.
struct ScreenContentHeightKey: PreferenceKey
{
    static let defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat)
    {
        value = max(value, nextValue())
    }
}

/// Hides the system navigation bar where there is one to hide.
///
/// A modifier rather than an `#if` inside the body's chain: `ToolbarPlacement.navigationBar`
/// does not exist on macOS, and a platform that has no navigation bar has nothing to hide.
private struct HiddenNavigationBar: ViewModifier
{
    func body(content: Content) -> some View
    {
    #if os(macOS)
        content
    #else
        content
            .toolbar(.hidden, for: .navigationBar)
    #endif
    }
}

/// What a flow tells the screens inside it.
///
/// Counterpart of the `LocalScreenChrome` composition local on Android. A `Screen` has to
/// draw a way out without knowing which flow it is in or how deep, and a `FlowHost` knows
/// both and does not know which of its routes drew a header. The environment is the one
/// place those two meet without either of them holding the other.
///
/// Both actions are carried even though only one of them is ever drawn: which one that is
/// changes with the depth of the stack.
struct ScreenChrome: Sendable
{
    var leading: ScreenLeading = .none
    var onBack: @MainActor @Sendable () -> Void = {}
    var onClose: @MainActor @Sendable () -> Void = {}
}

private struct ScreenChromeKey: EnvironmentKey
{
    /// A `Screen` outside any `FlowHost` — a preview, a host app's own screen — reads this
    /// and draws no leading control, which is the honest answer: nothing there knows what
    /// going back would mean.
    static let defaultValue = ScreenChrome()
}

extension EnvironmentValues
{
    var screenChrome: ScreenChrome
    {
        get { self[ScreenChromeKey.self] }
        set { self[ScreenChromeKey.self] = newValue }
    }
}
#endif
