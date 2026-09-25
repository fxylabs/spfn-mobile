#if canImport(SwiftUI)
// SPFN Mobile — the frame every screen in a flow is drawn in.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/Screen.kt. A body,
// the items a screen puts in the system navigation bar, and the things a screen used to have
// to remember for itself: what the keyboard is covering, and which way out this screen has.
//
// Guarded whole, first line of code to last, the way every SwiftUI file in this repository
// is (docs/IMPLEMENTATION-PITFALLS.md P20): SwiftUI is Apple's, `SPFNUI` builds on Linux,
// and validate.sh section 8 holds the guard to the file rather than to the import.
//
// ---------------------------------------------------------------------------
// The header is the platform's, and that is the whole of the iOS half
// ---------------------------------------------------------------------------
//
// This file used to hide the system navigation bar and draw a header of its own. Hiding the
// bar took UIKit's two back gestures with it, and every way of getting them back was a
// reach past UIKit — a private recognizer class found by name, a delegate swapped on a
// recognizer the SDK does not own (docs/IMPLEMENTATION-PITFALLS.md P29, P32). Emptying the
// bar instead of hiding it does not survive iOS 26, which draws the back button as a glass
// circle whatever image it is given (docs/architecture/screen-header-design.md §5 R1–R3).
//
// So the bar stays, and everything the drawn header did is said to it instead: the title is
// `navigationTitle`, the app's three items are `ToolbarItem`s, the flow's close on a
// presented root is a trailing toolbar item, and the back is the system back button with
// both of its gestures, UIKit's own. Nothing here turns a gesture on or off.
//
// The toolbar is attached HERE, to the view a route draws, and that is not a detail. A
// `.toolbar` reaches the bar of the navigation destination it stands inside; one attached to
// a wrapper outside the stack — the host's root, a `FlowHost` anchor — reaches no bar at all.
// Every route of every flow is drawn by a `Screen`, so every destination carries its own.
//
// ---------------------------------------------------------------------------
// The theme reaches the bar through SwiftUI, per screen
// ---------------------------------------------------------------------------
//
// The title is drawn by `SpfnText` in the `.principal` placement, and the bar's background
// is `.toolbarBackground` with the palette's background colour. `UINavigationBarAppearance`
// was the other door and it is refused here for two reasons. The theme's type is a SwiftUI
// `Font`, and there is no conversion from one to the `UIFont` `titleTextAttributes` needs;
// carrying a `UIFont` would be a change to the theme's keys. And an appearance is either
// global — every bar in the host app, and only bars created after it was set — or it is set
// on one bar by reaching into UIKit, which is exactly the kind of reach this file stopped
// making. The two modifiers read the environment, so an app that injects another theme, or
// a scheme that changes while the screen is up, changes the bar with the body.
//
// `navigationTitle` is still set whenever there is a title. The principal item is what is
// SEEN; the navigation title is what the NEXT screen's back button is labelled with and what
// VoiceOver announces for the screen, and neither of those is drawn.
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

/// A screen inside a flow: a body, under the system navigation bar.
///
/// - Parameters:
///   - title: what the bar says. Left out, the bar says nothing.
///   - leading: an item at the bar's left, BESIDE the system back button where there is one
///     rather than in place of it: the back button is the platform's and so are its gestures.
///     On a screen that stands on something the space beside the back is narrow, so a logo
///     belongs in `principal`.
///   - principal: an item in the bar's centre, drawn instead of the title.
///   - trailing: an item at the bar's right. Left out, the flow decides — an X on the root of
///     a modal or a sheet, and nothing anywhere else. A host app that passes one overrides
///     that entirely, which is also how a screen suppresses the flow's own close.
///   - scroll: whether the body scrolls. A body that scrolls also gets out of the keyboard's
///     way; a body that does not is the caller saying its content always fits.
///
/// The items are `AnyView?` rather than generic parameters, and that is a considered trade:
/// three more generic parameters would have to be spelled out at every call that omits one,
/// because Swift has no default for a generic parameter. What it costs is one layer of
/// erasure on an item that is drawn once per screen.
@MainActor
public struct Screen<Content: View>: View
{
    private let title: String?
    private let leading: AnyView?
    private let principal: AnyView?
    private let trailing: AnyView?
    private let scroll: Bool
    private let content: () -> Content

    @Environment(\.screenChrome) private var chrome
    @Environment(\.spfnTheme) private var theme
    @Environment(\.colorScheme) private var scheme

    private var palette: SPFNPalette
    {
        theme.palette(for: scheme)
    }

    public init(
        title: String? = nil,
        leading: AnyView? = nil,
        principal: AnyView? = nil,
        trailing: AnyView? = nil,
        scroll: Bool = true,
        @ViewBuilder content: @escaping () -> Content
    )
    {
        self.title = title
        self.leading = leading
        self.principal = principal
        self.trailing = trailing
        self.scroll = scroll
        self.content = content
    }

    public var body: some View
    {
        scrollableBody
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(palette.background)
            // A tap that lands on the frame rather than on a control puts the keyboard away.
            //
            // `onTapGesture` on the ANCESTOR, which is neither of the two spellings that fail.
            // `simultaneousGesture` fires alongside whatever the tap actually hit, so tapping
            // the field raised the keyboard and dismissed it in the same moment and the
            // typing that followed reached nothing (P27). A `Color.clear` layer BEHIND the
            // content is the opposite failure: a sibling underneath never receives the event
            // at all, because the scroll view in front answers the hit test first, and then
            // no tap in the body ever put the keyboard away.
            //
            // An ancestor is neither. SwiftUI resolves a tap at the deepest view that answers
            // and lets the gesture travel UP from there, innermost first — so a button or a
            // field takes its own tap and this never sees it, while a tap the scroll view
            // merely sat under arrives here. `contentShape` is what makes the empty parts of
            // the frame answer the hit test in the first place.
            .contentShape(Rectangle())
            .onTapGesture { SPFNKeyboard.dismiss() }
            .modifier(
                ScreenBar(
                    title: title,
                    background: palette.background,
                    leading: leading,
                    principal: principal ?? titleItem,
                    trailing: trailing ?? flowClose
                )
            )
    }

    /// The title as the bar draws it: the theme's title role, in the theme's text colour.
    private var titleItem: AnyView?
    {
        title.map { AnyView(SpfnText($0, role: .title)) }
    }

    /// The flow's close, on the root of a flow presented over something, and nothing
    /// anywhere else.
    ///
    /// The chrome arrives from ``FlowHost``, which is the only thing that knows both how the
    /// flow was entered and how deep it stands; a `Screen` outside a host reads the default
    /// and draws no close. There is no back counterpart, deliberately: a `.back` screen has
    /// the system back button, and that button is the one both gestures belong to.
    ///
    /// The X lives on the right, which is decision N3. The identifier and the label are the
    /// ones the drawn header used, so a runner finds the same control by the same id.
    private var flowClose: AnyView?
    {
        guard chrome.wayOut == .close
        else
        {
            return nil
        }
        return AnyView(
            Button(action: chrome.onClose)
            {
                CloseCross()
            }
            .accessibilityIdentifier("screen.close")
            .accessibilityLabel(SPFNStrings.controlClose)
        )
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
    /// The CONTENT and never the scroll view around it, and never the bar above it. A scroll
    /// view inside a sheet is as tall as the sheet, so a detent resolved from one feeds its
    /// own answer back in and never settles; the stack inside it has a natural height that
    /// does not move when the sheet does, which is why the measurement is taken here and why
    /// the view above is fixed vertically first. The bar's height is added by the sheet
    /// (`SheetPresentation`), as the drawn header's was.
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
}

/// What a `Screen` says to the system navigation bar: its title, its three items, and the
/// theme's background.
///
/// Private to this file, and a type only because two of the things it says do not exist on
/// macOS — there is no navigation bar there to set a background or a title mode on, and the
/// top-bar placements are iOS names — while `SPFNUI` is built for both.
///
/// An item that is `nil` is not placed at all rather than placed empty. An empty
/// `ToolbarItem` is still an item, and iOS 26 draws a glass capsule around an item whether or
/// not it holds anything.
private struct ScreenBar: ViewModifier
{
    let title: String?
    let background: Color
    let leading: AnyView?
    let principal: AnyView?
    let trailing: AnyView?

    func body(content: Content) -> some View
    {
    #if os(macOS)
        content
            .navigationTitle(title ?? "")
            .toolbar { items(leading: .navigation, trailing: .primaryAction) }
    #else
        titled(content)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(background, for: .navigationBar)
            .toolbar { items(leading: .topBarLeading, trailing: .topBarTrailing) }
    #endif
    }

    /// The title, where there is one. A screen with no title sets none, rather than an empty
    /// one, so the screen after it is backed out of with the platform's own "Back".
    @ViewBuilder
    private func titled(_ content: Content) -> some View
    {
        if let title = title
        {
            content.navigationTitle(title)
        }
        else
        {
            content
        }
    }

    @ToolbarContentBuilder
    private func items(leading leadingPlacement: ToolbarItemPlacement, trailing trailingPlacement: ToolbarItemPlacement) -> some ToolbarContent
    {
        if let leading = leading
        {
            ToolbarItem(placement: leadingPlacement) { leading }
        }
        if let principal = principal
        {
            ToolbarItem(placement: .principal) { principal }
        }
        if let trailing = trailing
        {
            ToolbarItem(placement: trailingPlacement) { trailing }
        }
    }
}
#endif
