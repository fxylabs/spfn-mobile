#if canImport(SwiftUI)
// SPFN Mobile — what a flow tells the screens inside it.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/components/ScreenChrome.kt, which
// is the same three declarations spelled as a composition local. This file was `Screen.swift`
// until the Kotlin half's split was matched here: a `Screen` READS this and a `FlowHost`
// WRITES it, so it belongs to neither of them and standing inside the reader made it look
// like the reader's own.
//
// Guarded whole, first line of code to last, the way every SwiftUI file in this repository is
// (docs/IMPLEMENTATION-PITFALLS.md P20): `EnvironmentKey` is SwiftUI's and `SPFNUI` builds on
// Linux.
//
// It is deliberately not part of the module's public vocabulary. A host app never builds one
// — `FlowHost` provides it and `Screen` reads it — and a public one would be a second way to
// answer a question ``Flow/wayOut(entry:)`` already answers. What a host app may do is READ
// it, and `ScreenWayOut.swift` is that door: a value with the way out and the two acts, and
// no way to write it.

import SwiftUI

/// What a flow tells the screens inside it.
///
/// Counterpart of the `LocalScreenChrome` composition local on Android. A `Screen` has to
/// offer a way out without knowing which flow it is in or how deep, and a `FlowHost` knows
/// both and does not know which of its routes drew a `Screen`. The environment is the one
/// place those two meet without either of them holding the other.
///
/// On iOS a `Screen` spends only the close: a back is the system navigation bar's own button,
/// and what that button does reaches the flow through the stack's path binding rather than
/// through `onBack`. `onBack` is still carried, for ``ScreenWayOut/back()``.
///
/// Both actions are carried even though only one of them is ever drawn: which one that is
/// changes with the depth of the stack.
struct ScreenChrome: Sendable
{
    var wayOut: WayOut = .none
    var onBack: @MainActor @Sendable () -> Void = {}
    var onClose: @MainActor @Sendable () -> Void = {}
}

private struct ScreenChromeKey: EnvironmentKey
{
    /// A `Screen` outside any `FlowHost` — a preview, a host app's own screen — reads this
    /// and draws no way out at all, which is the honest answer: nothing there knows what
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
