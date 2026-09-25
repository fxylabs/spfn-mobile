#if canImport(SwiftUI)
// SPFN Mobile — the way out of the screen being drawn, for a view that draws its own.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/ScreenWayOut.kt.
// `ScreenChrome` is what a `FlowHost` tells the screens inside it and it stays internal: a
// host app never writes one, and a public one would be a second way to answer a question
// ``Flow/wayOut(entry:)`` already answers. What is public is the READING of it — which way
// out this screen has, and the two acts — so a view that is not a `Screen`, or an item an app
// puts in the bar, can offer the way out the flow means without knowing which flow it is in.
//
// Read-only by construction and not by convention: `screenWayOut` is a getter on
// `EnvironmentValues` with no setter, so `@Environment(\.screenWayOut)` reads it and
// `.environment(\.screenWayOut, …)` does not compile.
//
// Guarded whole, first line of code to last, the way every SwiftUI file in this repository is
// (docs/IMPLEMENTATION-PITFALLS.md P20): `EnvironmentValues` is SwiftUI's and `SPFNUI` builds
// on Linux.

import SwiftUI

/// The way out of the screen being drawn, and the two acts that take it.
///
/// ``none`` outside any ``FlowHost``: nothing there knows what going back would mean. Inside
/// one, ``wayOut`` is the flow's answer for this screen's depth and entry, and both acts go
/// through the flow — ``back()`` is `Flow.back(entry:)`, which is what the system back button
/// and its gestures do too, and ``close()`` is `Flow.close()`.
///
/// Both acts are carried even though a screen offers one of them: which one changes with the
/// depth of the stack.
public struct ScreenWayOut: Sendable
{
    /// The way out this screen has: a back, a close, or none.
    public let wayOut: WayOut

    private let chrome: ScreenChrome

    init(chrome: ScreenChrome)
    {
        self.wayOut = chrome.wayOut
        self.chrome = chrome
    }

    /// Goes back one step, the way the system back button does.
    @MainActor
    public func back()
    {
        chrome.onBack()
    }

    /// Closes the flow, the way the close in the bar does.
    @MainActor
    public func close()
    {
        chrome.onClose()
    }

    /// The way out of a screen drawn outside any flow: none, and two acts that do nothing.
    public static let none = ScreenWayOut(chrome: ScreenChrome())
}

extension EnvironmentValues
{
    /// The way out of the screen being drawn. Read-only: a ``FlowHost`` writes it.
    public var screenWayOut: ScreenWayOut
    {
        ScreenWayOut(chrome: screenChrome)
    }
}
#endif
