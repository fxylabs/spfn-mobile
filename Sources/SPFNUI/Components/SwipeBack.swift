#if canImport(SwiftUI)
// SPFN Mobile — giving the edge swipe back to a screen that hid the bar it lived on.
//
// docs/IMPLEMENTATION-PITFALLS.md P29: `Screen` draws its own header and hides the system
// navigation bar with `.toolbar(.hidden, for: .navigationBar)` so the two do not both draw
// a back control. UIKit's `interactivePopGestureRecognizer` belongs to that bar rather than
// to the screen, so hiding it refuses the gesture too — the edge swipe stops working and
// nothing says why; cells u7b and u10b (docs/IMPLEMENTATION-PITFALLS.md P29) are what caught
// it, both "swipe back pops the pushed screen" and both green everywhere except this gesture.
//
// P29 names three ways out and this file is the first: keep the bar hidden, and reach past
// it to the interactive pop gesture directly rather than emptying the bar instead (which
// would push every screen's layout down by the bar's height) or moving the two cells' iOS
// half to `manual` (which would let this exact regression go undetected again).
//
// One gesture is not enough. `interactivePopGestureRecognizer` is the EDGE half of the
// pair UIKit installs on the navigation controller's container view — a full-width "content
// swipe" recognizer stands next to it with a `must-fail-for` relationship to it, and both
// carry their own delegate refusing to begin while the bar is hidden. Clearing only the
// public one leaves the edge gesture waiting on a sibling whose delegate never lets it
// resolve, so the edge gesture never resolves either: on device this reads as the swipe
// being accepted (the assertion after it is what fails, not the swipe command) while the
// pop itself lands seconds late or not at all. Confirmed on device — logging every gesture
// recognizer on the container view during u7b showed the edge gesture already re-enabled
// with its own delegate cleared and the pop still not landing, until the sibling's delegate
// was cleared too.
//
// So this walks every gesture recognizer sharing that container view instead of asking
// UIKit for one by name (a name is Apple's, in a private class, and free to change), and
// treats every one of them the way `interactivePopGestureRecognizer` is treated in every
// public-API guide for this trick: `isEnabled` toggled by depth, `delegate` cleared only
// while turning it on. `Parallax` is the one thread every recognizer in this pair's private
// class name has had across the iOS versions this was checked on, so it is the filter that
// widens the fewest other recognizers on that view.

import SwiftUI

#if canImport(UIKit)
import UIKit
#endif

/// Applies ``SwipeBackGesture`` to `content` where there is UIKit navigation to reach.
///
/// A no-op everywhere else (macOS, and any platform with no `UINavigationController`),
/// which is why this is a `ViewModifier` `Screen` can apply unconditionally rather than a
/// call site that has to know which platform it is on.
struct SwipeBackGesture: ViewModifier
{
    /// Whether the enclosing navigation controller's interactive pop gesture should work.
    /// `false` on the root of a stack, where there is nothing under it to pop to.
    let enabled: Bool

    func body(content: Content) -> some View
    {
    #if canImport(UIKit)
        content.background(SwipeBackGestureProbe(enabled: enabled))
    #else
        content
    #endif
    }
}

#if canImport(UIKit)
/// The zero-sized UIKit anchor ``SwipeBackGesture`` reads the navigation controller
/// through.
///
/// A `UIViewRepresentable` wrapping a plain `UIView` rather than a view controller: the
/// view's `next` responder chain reaches the enclosing `UIViewController` and, above it,
/// the `UINavigationController` the same way either shape would, and a view answers
/// `didMoveToWindow` — exactly the moment this needs, because that is when the responder
/// chain above it is actually the live one. Before a view is in a window, walking `next`
/// finds nothing reliable to walk to.
private struct SwipeBackGestureProbe: UIViewRepresentable
{
    let enabled: Bool

    func makeUIView(context: Context) -> ProbeView
    {
        let view = ProbeView()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        view.enabled = enabled
        return view
    }

    func updateUIView(_ uiView: ProbeView, context: Context)
    {
        uiView.enabled = enabled
    }

    /// Walks its own responder chain for the navigation controller it lives inside, and
    /// asks that controller's edge-swipe gesture — and every recognizer standing next to
    /// it on the same view — to match `enabled`.
    ///
    /// Reapplied on every `didMoveToWindow` and not only the first: a `NavigationStack`
    /// removes the previous top screen's view from the window on a push and returns it on a
    /// pop, so a root screen's own probe sees its window go away and come back rather than
    /// running once — and coming back is exactly when it needs to re-assert `isEnabled =
    /// false`, because the gesture recognizers belong to the navigation controller and not
    /// to either screen.
    final class ProbeView: UIView
    {
        var enabled = false
        {
            didSet { applyIfInWindow() }
        }

        override func didMoveToWindow()
        {
            super.didMoveToWindow()
            applyIfInWindow()
        }

        private func applyIfInWindow()
        {
            guard window != nil
            else
            {
                return
            }
            var responder: UIResponder? = self
            while let current = responder
            {
                if let viewController = current as? UIViewController,
                    let navigationController = viewController.navigationController
                {
                    apply(to: navigationController)
                    return
                }
                responder = current.next
            }
        }

        /// Toggles every `Parallax`-named gesture recognizer sharing the container view
        /// `interactivePopGestureRecognizer` sits on — the edge-swipe gesture and its
        /// full-width sibling both, since the edge gesture's `must-fail-for` relationship
        /// to the sibling means the sibling's own delegate refusing to begin holds the
        /// edge gesture back just as surely as the edge gesture's own delegate would.
        private func apply(to navigationController: UINavigationController)
        {
            guard let container = navigationController.interactivePopGestureRecognizer?.view
            else
            {
                return
            }
            for recognizer in container.gestureRecognizers ?? []
                where String(describing: type(of: recognizer)).contains("Parallax")
            {
                recognizer.isEnabled = enabled
                // Only take the delegate over when turning the gesture ON. UIKit's own
                // delegate is what refuses it on the root in the first place, so leaving
                // that delegate alone while `enabled` is false costs nothing —
                // `isEnabled = false` already refuses the same gesture — and keeps every
                // other screen's gesture owned by UIKit rather than by this file.
                if enabled
                {
                    recognizer.delegate = nil
                }
            }
        }
    }
}
#endif
#endif
