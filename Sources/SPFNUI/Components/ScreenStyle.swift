#if canImport(SwiftUI)
// SPFN Mobile — every value `Screen` draws with, in one type.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/ScreenStyle.kt.
// There are no design tokens in this repository yet, so a screen is drawn in the system font
// on white with black text. That is a placeholder and it is deliberately a SHALLOW one: the
// whole of it is here, five values and two fonts, so the work that brings tokens in replaces
// this file and touches no layout.
//
// The touch target is not a placeholder. 44pt is Apple's minimum and 48dp is Android's, and
// they are the sizes docs/IMPLEMENTATION-PITFALLS.md P21 is about — a control smaller than
// one reports a rectangle its neighbour has already eaten, and a device runner then taps the
// neighbour.

import SwiftUI

/// What ``Screen`` draws with until the token work replaces it.
enum ScreenStyle
{
    /// The surface a screen stands on.
    static let background: Color = .white

    /// Text and controls.
    static let foreground: Color = .black

    /// Apple's minimum touch target (docs/IMPLEMENTATION-PITFALLS.md P21).
    static let touchTarget: CGFloat = 44

    /// The header's height before the safe area is added to it.
    static let headerHeight: CGFloat = 56

    /// The margin down both sides of a header.
    static let gutter: CGFloat = 16

    /// The title.
    static let title: Font = .system(size: 20, weight: .semibold)

    /// A header control.
    static let control: Font = .system(size: 16)
}
#endif
