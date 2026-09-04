#if canImport(SwiftUI)
// SPFN Mobile — the two marks a header draws for the two ways out.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/HeaderIcons.kt.
// A chevron on the left for a back, an X on the right for a close (decision N3): the words
// `Back` and `Close` in body type read as prose rather than as controls, and a person on an
// iPhone looking for the way out of a sheet looks at the top right corner.
//
// Not part of the public component set, and deliberately outside the nine names section 15
// of the validator compares. These are two marks this component draws for itself; a host app
// that wants its own control passes a slot to `Screen`, which is the door that already
// exists.
//
// The size split is P21's, stated once here and once on the Compose half: the MARK is 20pt
// and the frame around it is `Metrics.touchTarget`. A control drawn at the mark's own size
// reports a rectangle its neighbour has already eaten, and a device runner then taps the
// neighbour.
//
// iOS draws both out of SF Symbols, which is the system's own vocabulary for exactly these
// two marks; the Compose half has no such vocabulary that is not Material (decision C2), so
// it draws the same two shapes with `Canvas`. What the two halves share is the geometry —
// 20 across, the platform's minimum around it, and the primary text colour.

import SwiftUI

/// The mark a back control draws.
struct BackChevron: View
{
    @Environment(\.colorScheme) private var scheme

    var body: some View
    {
        Image(systemName: "chevron.left")
            .font(.system(size: Metrics.iconSize, weight: .medium))
            .foregroundStyle(spfnPalette(for: scheme).text)
    }
}

/// The mark a close control draws.
struct CloseCross: View
{
    @Environment(\.colorScheme) private var scheme

    var body: some View
    {
        Image(systemName: "xmark")
            .font(.system(size: Metrics.iconSize, weight: .medium))
            .foregroundStyle(spfnPalette(for: scheme).text)
    }
}
#endif
