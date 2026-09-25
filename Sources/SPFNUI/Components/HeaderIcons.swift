#if canImport(SwiftUI)
// SPFN Mobile — the two marks the SDK still draws in the navigation bar.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/HeaderIcons.kt. The
// back on iOS is the system navigation bar's own button (`Screen.swift`), so neither mark is
// part of an ordinary screen any more. Each has one caller left:
//
//   - the X is the flow's close on the root of a presented flow, the SDK's item in the bar's
//     trailing place — an X on the right is what a person on an iPhone looking for the way
//     out of a sheet looks for (decision N3);
//   - the chevron is the back on the root of a pushed flow drawn WITHOUT a `NavigationHost`,
//     the compatibility path `FlowHost.swift` describes. That root is its own navigator's
//     root, so the system draws no back button there, and without this mark a person would
//     be left on a screen with no way off it (docs/IMPLEMENTATION-PITFALLS.md P31).
//
// Not part of the public component set, and deliberately outside the names section 15 of the
// validator compares. These are marks the SDK draws for itself; a host app that wants its own
// control passes an item to `Screen`, which is the door that already exists.
//
// The marks are 20pt. The system bar sizes the button around them, and the bar's own buttons
// meet the platform's minimum touch target, `Metrics.touchTarget`, which is P21's number.
//
// iOS draws both out of SF Symbols, which is the system's own vocabulary for exactly these
// two marks; the Compose half has no such vocabulary that is not Material (decision C2), so it
// draws the same two shapes with `Canvas`. What the two halves share is the geometry — 20
// across, and the primary text colour.

import SwiftUI

/// The mark a back control draws.
struct BackChevron: View
{
    @Environment(\.spfnTheme) private var theme
    @Environment(\.colorScheme) private var scheme

    var body: some View
    {
        Image(systemName: "chevron.left")
            .font(.system(size: Metrics.iconSize, weight: .medium))
            .foregroundStyle(theme.palette(for: scheme).text)
    }
}

/// The mark a close control draws.
struct CloseCross: View
{
    @Environment(\.spfnTheme) private var theme
    @Environment(\.colorScheme) private var scheme

    var body: some View
    {
        Image(systemName: "xmark")
            .font(.system(size: Metrics.iconSize, weight: .medium))
            .foregroundStyle(theme.palette(for: scheme).text)
    }
}
#endif
