// SPFN Mobile — what a flow navigates over, and how it is entered.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/FlowRoute.kt.
// A route is the app's own type: this module holds the stack, the app holds what is on it.
// The protocol exists so `Flow` can name a bound rather than accept `AnyHashable`, which is
// what would otherwise let two flows' routes end up on one stack.

/// A destination inside one flow.
///
/// `Hashable` because both platform navigators identify a stack entry by value —
/// SwiftUI's `NavigationStack(path:)` and `navigationDestination(for:)` are written
/// against it — and `Sendable` because a route is data a flow carries, never a reference
/// to a screen.
public protocol FlowRoute: Hashable, Sendable {}

/// How a flow's host presents its stack.
///
/// The difference is what happens to the system back gesture on the flow's LAST route. A
/// `modal` flow closes — it was presented over something, and dismissing it returns to what
/// it covered. A `push` flow does not handle it at all, because it was pushed onto the host
/// app's own stack and the host app's back is what should apply.
public enum FlowEntry: Sendable, Equatable
{
    /// Presented over the screen that opened it; back on the last route closes the flow.
    case modal

    /// Pushed onto the surrounding navigation; back on the last route is the host app's.
    case push
}
