// SPFN Mobile — what a flow navigates over, and how it is entered.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/FlowRoute.kt.
// A route is the app's own type: this module holds the stack, the app holds what is on it.
// The protocol exists so `Flow` can name a bound rather than accept `AnyHashable`, which is
// what would otherwise let two flows' routes end up on one stack.
//
// Nothing in this file imports SwiftUI, so the whole entry vocabulary — and therefore the
// close rules written against it — compiles and is tested on Linux.

/// A destination inside one flow.
///
/// `Hashable` because both platform navigators identify a stack entry by value —
/// SwiftUI's `NavigationStack(path:)` and `navigationDestination(for:)` are written
/// against it — and `Sendable` because a route is data a flow carries, never a reference
/// to a screen.
public protocol FlowRoute: Hashable, Sendable {}

/// How a flow's host presents its stack.
///
/// The difference is what a back on the flow's LAST route LOOKS like, and each of the three
/// answers is a different act. A `modal` flow closes — it was presented over something, and
/// dismissing it returns to what it covered. A `sheet` closes for the same reason and can
/// also be dragged away. A `push` flow closes too, and closing it is what returns the person
/// to the host's own screen: its stack was appended to the host's inside a
/// ``NavigationHost``, so the route under its root is the host's own.
///
/// What differs between them is therefore the CONTROL rather than the outcome: a pushed
/// flow's root offers a back, because what is under it is the host's screen, and a presented
/// flow's root offers a close, because what is under it is the screen it covered.
///
/// The whole table is in ``Flow/back(entry:)`` and ``Flow/wayOut(entry:)`` rather than in
/// either host, which is what keeps the two platforms saying the same thing about the same
/// gesture.
public enum FlowEntry: Sendable, Equatable
{
    /// Presented over the screen that opened it; back on the last route closes the flow.
    case modal

    /// Pushed onto the surrounding navigation; back on the last route is the host app's.
    case push

    /// Presented as a sheet standing at `detent`, over the screen that opened it.
    ///
    /// A sheet is a modal that can also be dismissed by dragging it down, and the height it
    /// stands at is fixed by `detent` for the whole flow: pushing a route inside a sheet
    /// navigates within that height rather than resizing it.
    case sheet(detent: SheetDetent)
}

/// How tall a sheet stands.
///
/// Three heights and no fourth, because a height a caller can name is a height both
/// platforms can honour: iOS resolves these onto `presentationDetents` and Android
/// resolves them with ``SheetGeometry`` against the space the host gave the flow.
public enum SheetDetent: Sendable, Equatable
{
    /// As tall as its content needs, and never taller than ``full``.
    case fit

    /// Half the available height.
    case half

    /// As tall as a sheet goes, which is short of the whole screen by design.
    case full
}

/// What a screen's way out is, decided by how the flow was entered and how deep it stands.
/// See ``Flow/wayOut(entry:)``.
///
/// Named for what it MEANS rather than for where it is drawn. It was `ScreenLeading` while
/// both controls lived in the header's left slot; the close is now an X in the RIGHT one
/// (decision N3), and a value called "leading" that decides what the trailing slot draws is
/// a name that has to be unlearned at every call site.
public enum WayOut: Sendable, Equatable
{
    /// No way out of this screen's own: a closed flow, and nothing else.
    case none

    /// A back control in the header's leading slot, which pops one route — or, on the root
    /// of a pushed flow, hands the person back to the host by closing it.
    case back

    /// A close control in the header's trailing slot, which closes the whole flow.
    case close
}
