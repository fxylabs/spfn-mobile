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
// public-API guide for this trick: `isEnabled` turned on, and the delegate replaced.
// `Parallax` is the one thread every recognizer in this pair's private class name has had
// across the iOS versions this was checked on, so it is the filter that widens the fewest
// other recognizers on that view.
//
// ---------------------------------------------------------------------------
// A screen with no back says NOTHING, and that is the whole of P32
// ---------------------------------------------------------------------------
//
// This used to be a switch: a screen with a back turned the recognizers on, and a screen
// without one turned them off. Both halves ran off `didMoveToWindow`, and the second half
// is what broke the edge swipe on the root of a pushed flow inside a `NavigationHost`
// (docs/IMPLEMENTATION-PITFALLS.md P32). A pop puts the screen UNDERNEATH back in the
// window before the pop commits — that is what the person is sliding towards — so the host
// app's own menu, a `Screen` with no back of its own, woke up mid-gesture and disabled the
// recognizer driving that very gesture. The swipe was accepted, the menu appeared under the
// moving screen, and then the pop was cancelled and the flow snapped back open. Cells u7b
// and u10b never saw it because the screen under THEM is another screen of the same flow,
// which has a back and so turned the gesture on again.
//
// So the switch is gone. Turning the gesture ON is the only thing a screen does here, and
// the question the OFF half existed to answer — is there anything under this screen to pop
// to — is answered at the moment of the gesture instead, by ``SwipeBackDelegate``, out of
// the navigation controller's own depth. That is the one authority that cannot be stale: a
// screen's opinion is formed when it is drawn and read when somebody swipes, and those are
// two different stack depths.

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
    /// Whether this screen has a back at all. `false` is silence rather than a refusal:
    /// the refusal on a stack of one is ``SwipeBackDelegate``'s, taken at the moment of the
    /// gesture (docs/IMPLEMENTATION-PITFALLS.md P32).
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
    /// turns that controller's edge-swipe gesture — and every recognizer standing next to it
    /// on the same view — back on.
    ///
    /// Reapplied on every `didMoveToWindow` and not only the first: a `NavigationStack`
    /// removes the previous top screen's view from the window on a push and returns it on a
    /// pop, so a screen's probe sees its window go away and come back rather than running
    /// once, and the recognizers belong to the navigation controller rather than to any one
    /// screen.
    ///
    /// A screen without a back does nothing at all here, which is P32
    /// (docs/IMPLEMENTATION-PITFALLS.md): the screen underneath is put back in the window
    /// BEFORE the pop that revealed it commits, so anything it turned off there would cancel
    /// that pop.
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
            guard enabled, window != nil
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
                    enable(on: navigationController)
                    return
                }
                responder = current.next
            }
        }

        /// Turns on every `Parallax`-named gesture recognizer sharing the container view
        /// `interactivePopGestureRecognizer` sits on — the edge-swipe gesture and its
        /// full-width sibling both, since the edge gesture's `must-fail-for` relationship
        /// to the sibling means the sibling's own delegate refusing to begin holds the
        /// edge gesture back just as surely as the edge gesture's own delegate would.
        ///
        /// The delegate goes to ``SwipeBackDelegate`` rather than to `nil`. A cleared
        /// delegate begins the gesture on ANY stack, the root of the host's own navigation
        /// included, and the recognizer stays on after the flow that turned it on has gone.
        /// The shared delegate is what asks the only question that has to be asked late.
        private func enable(on navigationController: UINavigationController)
        {
            guard let container = navigationController.interactivePopGestureRecognizer?.view
            else
            {
                return
            }
            for recognizer in container.gestureRecognizers ?? []
                where String(describing: type(of: recognizer)).contains("Parallax")
            {
                recognizer.isEnabled = true
                recognizer.delegate = SwipeBackDelegate.shared
            }
        }
    }
}

/// Answers, at the moment of the swipe, whether there is a screen under this one to pop to.
///
/// One shared object and never one per screen, for two reasons that are the same reason.
/// `UIGestureRecognizer.delegate` is weak, so a delegate owned by the screen that installed
/// it goes away with that screen and the recognizer falls back to beginning on any stack;
/// and the question is about the navigation controller rather than about any screen, so the
/// answer is read off the controller the recognizer is actually attached to.
///
/// This is the half of the old `isEnabled` switch that was worth keeping. A screen decided
/// "root or not" when it was drawn, and the swipe it was deciding for happens later, at a
/// depth that may have moved twice since (docs/IMPLEMENTATION-PITFALLS.md P32).
private final class SwipeBackDelegate: NSObject, UIGestureRecognizerDelegate
{
    static let shared = SwipeBackDelegate()

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool
    {
        guard let navigationController = Self.navigationController(of: gestureRecognizer)
        else
        {
            return false
        }
        return navigationController.viewControllers.count > 1
    }

    /// The controller the recognizer belongs to, found the way the probe finds its own: up
    /// the responder chain from the view the recognizer is attached to.
    private static func navigationController(of recognizer: UIGestureRecognizer) -> UINavigationController?
    {
        var responder: UIResponder? = recognizer.view
        while let current = responder
        {
            if let navigationController = current as? UINavigationController
            {
                return navigationController
            }
            if let viewController = current as? UIViewController,
                let navigationController = viewController.navigationController
            {
                return navigationController
            }
            responder = current.next
        }
        return nil
    }
}
#endif
#endif
