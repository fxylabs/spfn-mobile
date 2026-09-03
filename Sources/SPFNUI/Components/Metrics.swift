#if canImport(SwiftUI)
// SPFN Mobile — the two sizes that are not design decisions.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/Metrics.kt. This is
// what is LEFT of the old `ScreenStyle` after the tokens took the colours, the spacing, the
// radii and the fonts: two numbers that a design flow does not get to move.
//
// 44pt is Apple's minimum touch target and 48dp is Android's, and they are the sizes
// docs/IMPLEMENTATION-PITFALLS.md P21 is about — a control smaller than one reports a
// rectangle its neighbour has already eaten, and a device runner then taps the neighbour.
// They are not tokens because a token is a value the design flow replaces (decision S10) and
// these two are the platforms'.
//
// The header height is here rather than in the tokens for a smaller reason: it is a layout
// constant of one component, not a value any other component reads.

import SwiftUI

/// Sizes the platform fixes, not the palette.
enum Metrics
{
    /// Apple's minimum touch target (docs/IMPLEMENTATION-PITFALLS.md P21).
    static let touchTarget: CGFloat = 44

    /// The header's height before the safe area is added to it.
    static let headerHeight: CGFloat = 56

    /// How thick a field's or an outlined control's border is drawn.
    static let borderWidth: CGFloat = 1
}
#endif
