#if canImport(SwiftUI)
// SPFN Mobile — the container a host app gives a pushed flow to stand in.
//
// Counterpart of
// android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/NavigationHost.kt: same name,
// same one job, same `HostStack` underneath. Guarded whole, first line of code to last, the
// way every SwiftUI file in this module is (docs/IMPLEMENTATION-PITFALLS.md P20).
//
// ---------------------------------------------------------------------------
// What this is for
// ---------------------------------------------------------------------------
//
// A `FlowHost(.push)` used to build a `NavigationStack` of its own and draw it over
// whatever the host app was showing. Nothing said it was wrong until a person opened one on
// a phone: the flow's first screen APPEARED rather than sliding in, and its header had no
// way back to the menu that opened it, because there was no route under it to go back TO
// (docs/IMPLEMENTATION-PITFALLS.md P31). "The push root's back is the host's" was a rule
// with nothing to hand the gesture to.
//
// Decision N1 is this type. The host app wraps whatever it draws in one
// `NavigationHost { ... }`; a `FlowHost(.push)` inside it APPENDS its routes to this stack
// instead of building a second one, so the person gets the platform's own right-to-left push
// into the flow and the platform's own way back out of it.
//
// ---------------------------------------------------------------------------
// One destination, and why it is not one per flow
// ---------------------------------------------------------------------------
//
// `navigationDestination(for:)` is resolved by the TYPE of the path's elements, and this
// path carries `HostEntry` — one flow's routes and another's on one list, which is the whole
// point of the list. So there is exactly one destination, it is declared here in the host's
// own root subtree, and it asks the owner registered for that entry to draw it.
//
// The alternative — every `FlowHost` declaring `navigationDestination(for: Route.self)` at
// its own place in the tree — was considered and refused. It needs the path to carry each
// flow's concrete route type, which means `NavigationPath` and appending values whose static
// type has been erased to `AnyHashable`; whether SwiftUI then matches the destination
// registered for the route's own type is undocumented, and the failure if it does not is a
// runtime warning and a blank screen. A registration that cannot be misplaced is worth more
// than a destination declared closer to its flow: a `navigationDestination` inside a lazy
// container or outside the stack is ignored, and this one is neither by construction.
//
// What a host app wraps its own root in does NOT reach a pushed flow here either. The root
// is the stack's root and a pushed flow's routes are destinations above it, so a modifier or
// an environment value applied inside the root closure is not above them. It matters far
// less on this platform than on Compose — a SwiftUI accessibility identifier is per-view and
// survives any presentation, where Android's `testTagsAsResourceId` is inherited and does
// not (docs/IMPLEMENTATION-PITFALLS.md P33) — and the rule is the same on both: what is
// meant for the whole of an app's navigation goes outside this view.
//
// The registry is also what carries the CHROME. A `Screen` reads its way out of the
// environment, and the value it has to read is the owning flow's — not the host's, and not
// whichever flow happened to register last — so the closure the owner registers wraps its
// own content in its own chrome, read at draw time so that a stack that moved is a header
// that moved with it.

import Observation
import SwiftUI

/// The host app's navigation, and the stack a pushed flow appends to.
///
/// One per app — or one per independently navigating region — wrapped around whatever the
/// app draws:
///
/// ```swift
/// NavigationHost
/// {
///     ZStack
///     {
///         menu
///         SomePushFlowHost(container: container)
///     }
/// }
/// ```
///
/// A `FlowHost` for a modal or a sheet needs nothing from this and behaves the same inside
/// it or outside it: both are presentations OVER the navigation rather than entries in it.
@MainActor
public struct NavigationHost<Root: View>: View
{
    private let root: () -> Root

    /// The host's own stack and the flows registered against it. `@State` so it outlives
    /// every recomposition of the root, and `@Observable` so a flow appending to it is a
    /// reason for this view to redraw.
    @State private var host = HostStackStore()

    public init(@ViewBuilder root: @escaping () -> Root)
    {
        self.root = root
    }

    public var body: some View
    {
        NavigationStack(path: path)
        {
            root()
                .navigationDestination(for: HostEntry.self)
                { entry in
                    host.screen(for: entry)
                }
        }
        .environment(\.hostStack, host)
    }

    /// The host's stack, as the array `NavigationStack` calls its path.
    ///
    /// The setter is the reconciliation, and it is `FlowHost`'s own rule one level up:
    /// SwiftUI only ever shortens this array by itself — a system back, an edge swipe — so a
    /// shorter value is a pop it has already performed visually, and every flow that lost a
    /// route is told how many it lost. Any other value is ignored: pushing is `Flow.push`'s
    /// job, and accepting an arbitrary array here is precisely how a second copy of the
    /// stack starts.
    private var path: Binding<[HostEntry]>
    {
        Binding(
            get:
            {
                host.stack.entries
            },
            set:
            { newPath in
                guard newPath.count < host.stack.entries.count
                else
                {
                    return
                }
                host.shorten(to: newPath.count)
            }
        )
    }
}

/// What one flow told the host: how to draw its routes, and what its back does.
///
/// Both are closures over the flow rather than the flow itself, which is what lets one
/// registry hold flows whose route types have nothing in common.
struct HostRegistration
{
    /// Draws one of this flow's routes, wrapped in this flow's own chrome. Answers an empty
    /// view for a route that is not this flow's, which cannot happen while entries carry
    /// their owner and is still not worth a crash.
    let screen: (AnyHashable) -> AnyView

    /// One back, spent through `Flow.back(entry:)` like every other back in this module.
    let back: () -> Void
}

/// The host's stack and its registry, as one observable thing under the environment.
///
/// Not public: a host app builds a `NavigationHost` and a flow registers itself through
/// `FlowHost`, and a third door onto this would be a second way to write a stack that has
/// one writer on purpose.
@MainActor
@Observable
final class HostStackStore
{
    /// The one list, and the only thing `NavigationHost` draws from.
    private(set) var stack = HostStack()

    private var registrations: [ObjectIdentifier: HostRegistration] = [:]

    /// Says how a flow's routes are drawn and what its back does. Re-registering is how a
    /// flow keeps its chrome current, so this replaces rather than refuses.
    func register(owner: ObjectIdentifier, registration: HostRegistration)
    {
        registrations[owner] = registration
    }

    /// Takes one flow's stack as it now is. A closed flow syncs nothing, which is how its
    /// entries leave the host's stack.
    func sync(owner: ObjectIdentifier, routes: [AnyHashable])
    {
        stack = stack.sync(owner: owner, routes: routes)
    }

    /// Tells every flow that lost routes to a platform pop how many it lost.
    ///
    /// The flows are what shorten the stack: each `back` moves that flow's own state, the
    /// flow publishes it, and the entries follow. Nothing here writes `stack` directly, for
    /// the reason `FlowHost` never does either.
    func shorten(to count: Int)
    {
        for (owner, dropped) in stack.shortened(to: count)
        {
            guard let registration = registrations[owner]
            else
            {
                continue
            }
            for _ in 0 ..< dropped
            {
                registration.back()
            }
        }
    }

    /// Draws one entry, by asking whoever put it there.
    func screen(for entry: HostEntry) -> AnyView
    {
        registrations[entry.owner]?.screen(entry.route) ?? AnyView(EmptyView())
    }
}

private struct HostStackKey: EnvironmentKey
{
    /// No host. A `FlowHost(.push)` that reads this draws its own inline stack, which is
    /// the compatibility path its header describes.
    static let defaultValue: HostStackStore? = nil
}

extension EnvironmentValues
{
    var hostStack: HostStackStore?
    {
        get { self[HostStackKey.self] }
        set { self[HostStackKey.self] = newValue }
    }
}
#endif
