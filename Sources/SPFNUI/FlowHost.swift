#if canImport(SwiftUI)
// SPFN Mobile — the one place a Flow is bound to the platform navigator.
//
// Counterpart of android/spfn-ui/src/main/kotlin/xyz/superfunction/spfn/ui/FlowHost.kt, and
// the only file in this module that imports SwiftUI. It is guarded whole, first line of
// code to last, so `SPFNUI` reduces to its four non-UI types where SwiftUI is absent and
// the Flow transition suite still runs there.
//
// `NavigationStack(path:)` writes back: a system back gesture pops the binding rather than
// asking anyone. That is the whole design problem this file solves, and it is solved by
// refusing to own a second stack. The binding's getter derives the path from `flow.stack`
// every time it is read, and its setter never assigns — it counts how many entries SwiftUI
// dropped and calls `flow.pop()` that many times, so the flow stays the single source of
// truth and every observer sees one transition per pop rather than a stack that was
// replaced behind them.
//
// There is deliberately no `@Environment(\.dismiss)` anywhere in this module. `dismiss`
// closes whatever presented the current view without telling the flow, which is exactly how
// a host ends up presented over a flow that still believes it is open.

import SwiftUI

/// Renders `flow`'s stack, and follows it as it changes.
///
/// Renders nothing at all while the flow is closed, which is not a special case bolted on:
/// with no root route there is no `NavigationStack` to build.
@MainActor
public struct FlowHost<Route: FlowRoute, Content: View>: View
{
    private let flow: Flow<Route>
    private let entry: FlowEntry
    private let content: (Route) -> Content

    public init(
        flow: Flow<Route>,
        entry: FlowEntry,
        @ViewBuilder content: @escaping (Route) -> Content
    )
    {
        self.flow = flow
        self.entry = entry
        self.content = content
    }

    public var body: some View
    {
        switch entry
        {
        case .push:
            navigation
        case .modal:
            presenter
        }
    }

    /// The stack itself. `.push` shows this directly, inside whatever navigation the host
    /// app already has; `.modal` shows it inside a cover.
    @ViewBuilder
    private var navigation: some View
    {
        if let root = flow.stack.first
        {
            NavigationStack(path: path)
            {
                content(root)
                    .navigationDestination(for: Route.self)
                    { route in
                        content(route)
                    }
            }
        }
    }

    /// A zero-sized anchor carrying the presentation, so a modal flow needs nothing from
    /// the host but a place in its view tree.
    ///
    /// macOS has no full-screen cover; `.sheet` is that platform's modal presentation, and
    /// naming it here rather than guarding the whole file is what keeps this type on both
    /// platforms (docs/IMPLEMENTATION-PITFALLS.md P20).
    private var presenter: some View
    {
    #if os(macOS)
        Color.clear
            .frame(width: 0, height: 0)
            .sheet(isPresented: presented)
            {
                navigation
            }
    #else
        Color.clear
            .frame(width: 0, height: 0)
            .fullScreenCover(isPresented: presented)
            {
                navigation
            }
    #endif
    }

    /// The routes above the root, which is what `NavigationStack` calls its path.
    ///
    /// The setter is the reconciliation. SwiftUI only ever shortens this array on its own —
    /// a system back or a swipe — so a shorter value is a pop it already performed
    /// visually, and the flow is told about it one `pop()` per dropped entry. Any other
    /// value is ignored: pushing is `flow.push(_:)`'s job, and accepting an arbitrary array
    /// here is precisely how a second copy of the stack starts.
    private var path: Binding<[Route]>
    {
        Binding(
            get:
            {
                Array(flow.stack.dropFirst())
            },
            set:
            { newPath in
                let depth = max(flow.stack.count - 1, 0)
                guard newPath.count < depth
                else
                {
                    return
                }
                for _ in 0 ..< (depth - newPath.count)
                {
                    flow.pop()
                }
            }
        )
    }

    /// Whether the cover is up. Dismissing it closes the flow, which is the modal half of
    /// the entry rule: on the last route there is nothing to pop back to, so back closes.
    private var presented: Binding<Bool>
    {
        Binding(
            get:
            {
                flow.isPresented
            },
            set:
            { isPresented in
                if !isPresented
                {
                    flow.close()
                }
            }
        )
    }
}
#endif
