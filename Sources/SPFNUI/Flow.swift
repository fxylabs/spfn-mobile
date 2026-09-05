// SPFN Mobile — a stack of routes, and the five things that can happen to it.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/Flow.kt.
// Deliberately free of SwiftUI: every rule this class holds is a rule about an array, so it
// compiles and its whole transition table runs wherever Swift does, including the Linux
// host this repository's Swift gate runs on. `FlowHost.swift` is the only file in this
// module that imports SwiftUI.
//
// One state, not two. `isPresented` is exactly `!stack.isEmpty` — an open flow always
// stands on at least one route, which is why `open(at: [])` is refused rather than accepted
// as a way of being open with nothing to show.
//
// `@Observable` rather than `ObservableObject`: it is the iOS 17 mechanism, the baseline
// this package now compiles against (D5 revision, 2026-09-02), and it is what lets a
// SwiftUI view depend on `stack` without the flow publishing a change to every view that
// merely holds a reference.
//
// The attribute is behind `canImport(Observation) && canImport(SwiftUI)`, and the second
// half of that is not redundant. Observation IS importable on Linux — so `canImport` alone
// admits it there — but linking it is what fails: Swift 6.2.1's `libswiftObservation.so`
// carries an undefined reference to `swift::threading::fatal`, and a test binary that
// pulls the module in does not link at all. Measured, not assumed: with the attribute on
// `canImport(Observation)` alone, `swift test` on this Linux host failed at
// `Linking SPFNMobilePackageTests.xctest` while `swift build` still succeeded, because a
// library build never links. `@Observable` is only ever observed by SwiftUI, so naming
// SwiftUI here costs nothing on the platforms that have it and gives Linux the plain class
// with the same stored properties and the same five methods.

#if canImport(Observation) && canImport(SwiftUI)
import Observation
#endif

/// The navigation state of one flow.
///
/// A flow is closed when its stack is empty and open when it is not. `push` on a closed
/// flow opens it; `close()` is the only thing that closes one, and `pop()` on the last
/// route is a no-op rather than a close — a screen's back gesture and a flow's dismissal
/// are different acts, and collapsing them would make every last-route back tear the flow
/// down whether the host asked for that or not. `back(entry:)` is where the two are joined
/// again for the one caller that means the flow's own way out.
///
/// `@MainActor` is the isolation, and it is the one place this type is stronger than its
/// Kotlin counterpart: the compiler refuses an off-main mutation here, while Kotlin has no
/// equivalent to declare and its documentation has to ask instead.
#if canImport(Observation) && canImport(SwiftUI)
@Observable
#endif
@MainActor
public final class Flow<Route: FlowRoute>
{
    /// The routes, oldest first. Empty exactly when the flow is closed.
    public private(set) var stack: [Route]

    /// Whether the flow is open. True exactly when ``stack`` is not empty.
    public private(set) var isPresented: Bool

    public init(initial: [Route] = [])
    {
        self.stack = initial
        self.isPresented = !initial.isEmpty
    }

    /// Puts `route` on top. On a closed flow this opens it on that one route.
    public func push(_ route: Route)
    {
        moveTo(stack + [route])
    }

    /// Drops the top route.
    ///
    /// A no-op on a stack of one: a flow standing on its first route has nothing to go back
    /// to, and closing it is ``close()``'s job.
    public func pop()
    {
        guard stack.count > 1
        else
        {
            return
        }
        moveTo(Array(stack.dropLast()))
    }

    /// Swaps the top route for `route`, leaving everything under it. A no-op when closed.
    public func replace(_ route: Route)
    {
        guard !stack.isEmpty
        else
        {
            return
        }
        moveTo(Array(stack.dropLast()) + [route])
    }

    /// Opens the flow on a whole stack at once — a deep link, or a restored session.
    ///
    /// - Throws: ``SPFNUIError/emptyStack`` when `stack` is empty. An open flow with nothing
    ///   on it is not a state this type has: it would present a host with no route to
    ///   render, and the platform navigators underneath refuse an empty back stack outright.
    public func open(at stack: [Route]) throws
    {
        guard !stack.isEmpty
        else
        {
            throw SPFNUIError.emptyStack
        }
        moveTo(stack)
    }

    /// Closes the flow and forgets its routes. A no-op on a flow that is already closed.
    public func close()
    {
        moveTo([])
    }

    /// What a back gesture does here, and whether this flow did it.
    ///
    /// The one place the close table lives, so that the two hosts spend it rather than each
    /// restate it: a stack of two or more pops, a stack of one closes whatever it was
    /// entered as, and a closed flow is refused outright.
    ///
    /// `entry` no longer changes the answer, and that is decision N2 rather than an
    /// oversight. A pushed flow used to refuse the back on its root and let the host app's
    /// own apply; it now stands ON the host's navigation stack (``NavigationHost``), so the
    /// screen under its root is the host's and closing the flow is exactly what uncovers it.
    /// The parameter stays because the hosts ask this question per entry and because
    /// ``wayOut(entry:)`` still answers differently for each — a pushed root draws a back
    /// and a presented root draws a close, for the same act.
    ///
    /// - Returns: whether this flow consumed the back.
    @discardableResult
    public func back(entry: FlowEntry) -> Bool
    {
        guard handlesBack(entry: entry)
        else
        {
            return false
        }
        if stack.count > 1
        {
            pop()
            return true
        }
        close()
        return true
    }

    /// Whether ``back(entry:)`` would consume a back gesture, asked before the gesture is
    /// claimed.
    ///
    /// A back handler has to be enabled or disabled ahead of the event on both platforms —
    /// Android's `BackHandler` takes an `enabled` flag and a handler that consumed a back
    /// cannot hand it on — so "would you handle this" is a separate question from "handle
    /// this", and both answer out of the same rule. An OPEN flow always claims it now, for
    /// the reason ``back(entry:)`` states; a closed one never does, and that is the only
    /// gesture this type hands on.
    public func handlesBack(entry: FlowEntry) -> Bool
    {
        !stack.isEmpty
    }

    /// The way out the screen at the top of this flow should draw.
    ///
    /// Depth decides first: anything standing on a route above the root goes back, whatever
    /// it was entered as. On the root the ENTRY decides, and it decides the control rather
    /// than the act — a pushed flow's root draws a back, because the screen under it is the
    /// host's own, and a flow presented over something draws a close, because what is under
    /// it is the screen it covered. Both end in ``back(entry:)`` and both close the flow.
    ///
    /// A host app that passes its own slot to `Screen` overrides this; this is what a screen
    /// shows when nobody said otherwise.
    public func wayOut(entry: FlowEntry) -> WayOut
    {
        if stack.isEmpty
        {
            return .none
        }
        if stack.count > 1 || entry == .push
        {
            return .back
        }
        return .close
    }

    /// The one writer, so the two published properties cannot disagree about whether this
    /// flow is open. Assigning the state a flow is already in still assigns, which is what
    /// an `@Observable` property does anyway; nothing downstream distinguishes the two.
    private func moveTo(_ next: [Route])
    {
        stack = next
        isPresented = !next.isEmpty
    }
}

/// What this module refuses.
///
/// One case, because there is one refusal: `Flow.open(at:)` will not open a flow on nothing.
/// The Kotlin counterpart raises `IllegalArgumentException` for the same call, which is that
/// platform's shape for the same event.
public enum SPFNUIError: Error, Equatable, Sendable
{
    /// `Flow.open(at:)` was given an empty stack.
    case emptyStack
}
