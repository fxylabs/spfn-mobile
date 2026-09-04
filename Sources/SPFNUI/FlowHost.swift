#if canImport(SwiftUI)
// SPFN Mobile — the one place a Flow is bound to the platform navigator.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/FlowHost.kt, and
// the only file in this module that imports SwiftUI. It is guarded whole, first line of
// code to last, so `SPFNUI` reduces to its four non-UI types where SwiftUI is absent and
// the Flow transition suite still runs there.
//
// `NavigationStack(path:)` writes back: a system back gesture pops the binding rather than
// asking anyone. That is the whole design problem this file solves, and it is solved by
// refusing to own a second stack. The binding's getter derives the path from `flow.stack`
// every time it is read, and its setter never assigns — it counts how many entries SwiftUI
// dropped and calls `flow.pop()` that many times, so the flow stays the single source of
// truth and every observer sees one transition per pop rather than a stack that was
// replaced behind them.
//
// There is deliberately no `@Environment(\.dismiss)` anywhere in this module. `dismiss`
// closes whatever presented the current view without telling the flow, which is exactly how
// a host ends up presented over a flow that still believes it is open.
//
// Every back this file sees goes to `Flow.back(entry:)`, and so does the Android host's.
// The close table lives in `Flow`, where a test can drive all six of its rows without a
// toolkit; nothing here decides what a back means. A sheet is the presentation that makes
// that matter most: the system can dismiss one BEFORE anybody asks, so the `isPresented`
// binding is written to follow — the platform closing the sheet closes the flow, and the
// flow closing takes the sheet down with it, out of the same one piece of state.
//
// `interactiveDismissDisabled` is deliberately absent. A sheet a user cannot drag away is a
// sheet that has to justify itself, and no flow in this repository has that to say yet.

import SwiftUI

/// Renders `flow`'s stack, and follows it as it changes.
///
/// Renders nothing at all while the flow is closed, which is not a special case bolted on:
/// with no root route there is no `NavigationStack` to build.
@MainActor
public struct FlowHost<Route: FlowRoute, Content: View>: View
{
    private let flow: Flow<Route>
    private let entry: FlowEntry
    private let content: (Route) -> Content

    public init(
        flow: Flow<Route>,
        entry: FlowEntry,
        @ViewBuilder content: @escaping (Route) -> Content
    )
    {
        self.flow = flow
        self.entry = entry
        self.content = content
    }

    public var body: some View
    {
        switch entry
        {
        case .push:
            navigation
        case .modal:
            presenter
        case .sheet(let detent):
            sheetPresenter(detent)
        }
    }

    /// The stack itself. `.push` shows this directly, inside whatever navigation the host
    /// app already has; `.modal` shows it inside a cover and `.sheet` inside a sheet.
    ///
    /// The chrome goes on here rather than around the presentation, so it reaches the
    /// screens of all three entry styles by the same line: a `Screen` inside this stack
    /// reads it and draws the way out this flow has, and a `Screen` outside any host reads
    /// the default and draws none.
    @ViewBuilder
    private var navigation: some View
    {
        if let root = flow.stack.first
        {
            NavigationStack(path: path)
            {
                content(root)
                    .navigationDestination(for: Route.self)
                    { route in
                        content(route)
                    }
            }
            .environment(\.screenChrome, chrome)
        }
    }

    /// What the screens inside this flow are told about their way out.
    ///
    /// Both actions go through the flow rather than through the presentation: a close is
    /// `Flow.close()` whatever drew the control, which is the same rule that makes
    /// `dismiss` refused here.
    ///
    /// The back is `Flow.back(entry:)` and not `Flow.pop()`, which is what makes the header
    /// control and the system gesture one act rather than two that agree by coincidence. It
    /// is also what a pushed flow's ROOT needs: its back is a close (decision N2), and only
    /// the close table knows that.
    private var chrome: ScreenChrome
    {
        ScreenChrome(
            wayOut: flow.wayOut(entry: entry),
            onBack: { [flow, entry] in flow.back(entry: entry) },
            onClose: { [flow] in flow.close() }
        )
    }

    /// A zero-sized anchor carrying the sheet, so a sheet flow needs nothing from the host
    /// but a place in its view tree — the same shape `presenter` has, and for the same
    /// reason.
    ///
    /// The stack lives INSIDE the sheet, which is what makes a push inside a sheet navigate
    /// within the sheet rather than push a second sheet: the detent is the sheet's and the
    /// sheet is the presentation, so its height does not move when the stack does.
    private func sheetPresenter(_ detent: SheetDetent) -> some View
    {
        Color.clear
            .frame(width: 0, height: 0)
            .sheet(isPresented: presented)
            {
                navigation
                    .modifier(SheetPresentation(detent: detent))
            }
    }

    /// A zero-sized anchor carrying the presentation, so a modal flow needs nothing from
    /// the host but a place in its view tree.
    ///
    /// macOS has no full-screen cover; `.sheet` is that platform's modal presentation, and
    /// naming it here rather than guarding the whole file is what keeps this type on both
    /// platforms (docs/IMPLEMENTATION-PITFALLS.md P20).
    private var presenter: some View
    {
    #if os(macOS)
        Color.clear
            .frame(width: 0, height: 0)
            .sheet(isPresented: presented)
            {
                navigation
            }
    #else
        Color.clear
            .frame(width: 0, height: 0)
            .fullScreenCover(isPresented: presented)
            {
                navigation
            }
    #endif
    }

    /// The routes above the root, which is what `NavigationStack` calls its path.
    ///
    /// The setter is the reconciliation. SwiftUI only ever shortens this array on its own —
    /// a system back or a swipe — so a shorter value is a pop it already performed
    /// visually, and the flow is told about it one `back(entry:)` per dropped entry. Any
    /// other value is ignored: pushing is `flow.push(_:)`'s job, and accepting an arbitrary
    /// array here is precisely how a second copy of the stack starts.
    ///
    /// `back(entry:)` rather than `pop()` even though the two cannot differ here — this
    /// path shortens a stack of two or more and never the root — because the rule belongs
    /// in one place, and a host that reaches past it is how the two platforms start
    /// disagreeing about what a swipe means.
    private var path: Binding<[Route]>
    {
        Binding(
            get:
            {
                Array(flow.stack.dropFirst())
            },
            set:
            { newPath in
                let depth = max(flow.stack.count - 1, 0)
                guard newPath.count < depth
                else
                {
                    return
                }
                for _ in 0 ..< (depth - newPath.count)
                {
                    flow.back(entry: entry)
                }
            }
        )
    }

    /// Whether the presentation is up, for both the cover and the sheet.
    ///
    /// Dismissing it closes the flow WHATEVER the depth, which is the table's rule for both:
    /// a modal's cover and a sheet's drag are dismissals of the presentation rather than of
    /// one route. The setter is what keeps the two halves in step when the platform moves
    /// first — a sheet dragged away sets this false before anybody asks, and the flow
    /// follows — and the getter is what keeps them in step when the flow moves first.
    private var presented: Binding<Bool>
    {
        Binding(
            get:
            {
                flow.isPresented
            },
            set:
            { isPresented in
                if !isPresented
                {
                    flow.close()
                }
            }
        )
    }
}

/// The three heights, resolved onto the system's own sheet vocabulary.
///
/// A separate modifier rather than an `#if` in the middle of a chain, because the platform
/// split is real: `presentationDetents` is iOS's and macOS has no sheet detents at all, so
/// on macOS this is a plain sheet and the detent is information the platform has no use for.
///
/// `half` and `full` are the system's own `.medium` and `.large` rather than fractions of
/// our own, because a sheet the user recognises is worth more than a sheet that matches
/// Android to the pixel.
///
/// `fit` has no system detent, so it is measured. There IS a non-circular measurement and it
/// took a Mac to find it: the thing to measure is the scroll CONTENT — the stack a `Screen`
/// fixes vertically before it lays it out — and never the scroll view, which inside a sheet
/// is as tall as the sheet and would feed the detent its own answer back. `Screen` reports
/// the content's height through `ScreenContentHeightKey` and this modifier stands the sheet
/// at that plus the header the content does not include. The measurement arrives once and
/// does not oscillate, because the number reported does not move when the sheet does.
///
/// Until it arrives — the first pass, and the permanent state of a screen whose body does
/// not scroll — the sheet takes `SheetGeometry`'s unmeasured fallback, which is the same
/// number Android falls back to when it has not measured either.
///
/// No ceiling is named on this side. `SheetGeometry.fitHeight` takes one and Android passes
/// its container's `full`; SwiftUI clamps a `.height` detent to the sheet's own maximum
/// itself, so a second, smaller ceiling invented here would only make the sheet shorter than
/// the platform's own answer.
private struct SheetPresentation: ViewModifier
{
    let detent: SheetDetent

    @State private var measured: CGFloat = 0

    func body(content: Content) -> some View
    {
    #if os(macOS)
        content
    #else
        content
            .onPreferenceChange(ScreenContentHeightKey.self)
            { height in
                measured = height
            }
            .presentationDetents([Self.presentationDetent(for: detent, content: measured)])
            .presentationDragIndicator(.visible)
    #endif
    }

#if !os(macOS)
    private static func presentationDetent(for detent: SheetDetent, content: CGFloat) -> PresentationDetent
    {
        switch detent
        {
        case .fit:
            let height = SheetGeometry.fitHeight(
                content: Double(content),
                header: Double(Metrics.headerHeight),
                max: .infinity
            )
            guard height > 0
            else
            {
                return .fraction(SheetGeometry.fitFallbackFraction)
            }
            return .height(CGFloat(height))
        case .half:
            return .medium
        case .full:
            return .large
        }
    }
#endif
}
#endif
