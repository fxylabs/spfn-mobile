#if canImport(SwiftUI)
// SPFN Mobile — the four sizes that are not design decisions.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/Metrics.kt. This is
// what is LEFT of the old `ScreenStyle` after the tokens took the colours, the spacing, the
// radii and the fonts: `touchTarget`, `headerHeight`, `borderWidth` and `iconSize`, four
// numbers that a design flow does not get to move.
//
// 44pt is Apple's minimum touch target and 48dp is Android's, and they are the sizes
// docs/IMPLEMENTATION-PITFALLS.md P21 is about — a control smaller than one reports a
// rectangle its neighbour has already eaten, and a device runner then taps the neighbour.
// `iconSize` is the glyph drawn INSIDE that target and follows from it rather than from a
// palette; `borderWidth` is one device pixel's worth of hairline, which is what a border is
// on both platforms before anybody designs one. None of them is a token, because a token is
// a value the design flow replaces (decision S10) and these are the platforms'.
//
// The header height is here rather than in the tokens for a smaller reason: it is a layout
// constant of one component, not a value any other component reads.
//
// 56 has no source to cite and this comment will not invent one. It arrived whole with the
// module (`cad422b`, PR #51) and nothing in the commit, the decisions or the pitfalls
// argues it: arbitrary choice, on the grounds that it is a header tall enough to stand a
// 44pt control in with room above and below, and that both platforms' own navigation bars
// are within a few points of it. It is the one number in this file a design flow would be
// entitled to argue with, and moving it moves nothing but the header.

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

    /// How big a header's own mark is drawn, inside a ``touchTarget``-sized frame.
    static let iconSize: CGFloat = 20
}
#endif
