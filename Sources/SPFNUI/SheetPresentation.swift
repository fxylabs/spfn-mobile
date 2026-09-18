#if canImport(SwiftUI)
// SPFN Mobile — the sheet iOS presents, and the height it stands at.
//
// The nearest counterpart is
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/Sheet.kt, and the two files are
// not the same size because the two platforms are not doing the same amount of work. Android
// has no sheet outside Material and this repository depends on no Material artifact, so
// `Sheet.kt` draws one — scrim, handle, anchors, phases. Here the system draws it, and what
// is left is naming the three heights and measuring the one the system cannot resolve for
// itself. That is this whole file.
//
// It was inside `FlowHost.swift` until the Android split was matched here. `FlowHost` is the
// one place a flow is bound to a navigator, and how a sheet resolves a detent is not a
// question about navigation: it is the same subject `Sheet.kt` is a file for, and the sheet's
// own arithmetic is `SheetGeometry`, in a file of its own again.
//
// `ScreenContentHeightKey` travels with it, because this modifier is its only reader. A
// preference goes UP and neither end knows the other — a `Screen` knows how tall its content
// is and nothing about being inside a sheet, and the sheet knows it needs a height and
// nothing about which route drew one — so the key stands beside whichever end is fewer, and
// that is this one.
//
// Guarded whole, first line of code to last, the way every SwiftUI file in this repository is
// (docs/IMPLEMENTATION-PITFALLS.md P20).

import SwiftUI

/// How tall the content of the screen on show is, travelling UP to whatever presented it.
///
/// A preference and not a binding, because the direction is up and neither end knows the
/// other: a ``Screen`` knows how tall its content is and nothing about being inside a sheet,
/// and the sheet below knows it needs a height and nothing about which of its routes drew
/// one. Written by ``Screen``, read by `SheetPresentation`, and by nothing else.
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
struct SheetPresentation: ViewModifier
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
