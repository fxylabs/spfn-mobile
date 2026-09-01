// SPFN Mobile — where the harness stops and the SDK's adapters start.
//
// Everything about obtaining a provider token lives in `SPFNSocialApple` and
// `SPFNSocialGoogle`: which value goes in the request's nonce field, how a dismissal is
// told apart from a refusal, what happens when no token comes back. None of it is
// repeated here and none of it is worked around. This file does two things the adapters
// deliberately do not: it finds something to present from, and it names the provider.
//
// Both adapters take the presentation source as a closure rather than a value, and this
// file honours that. The closure resolves the window at presentation time; the check
// that a window exists at all happens before it, and throws, because the closures cannot.
// An adapter handed a closure that answers a detached window would put a sheet on a
// screen nobody is looking at.
//
// `SPFNHarnessSupport` is the import that carries both adapters — see its manifest for
// why the harness cannot enable a package trait on its own.

import AuthenticationServices
import Foundation
import SPFNClient
import SPFNHarnessSupport
import UIKit

@MainActor
enum HarnessSocialSignIn
{
    /// The token to answer `SPFNKeyLifecycle.enroll` with, from inside its closure.
    ///
    /// Nothing is caught here. A cancellation, a provider refusal and a missing token
    /// each have a type the adapters already gave them, and re-wrapping them would be
    /// the exact mistake the registry's P16 row describes — the harness reporting a
    /// dismissal as a failure the person never had.
    static func idToken(provider: HarnessProvider, nonce: SPFNSocialNonce) async throws -> String
    {
        switch provider
        {
        case .apple:
            try requirePresentationSource()
            return try await SPFNSocialApple(anchor: { anchor() }).idToken(nonce: nonce)
        case .google:
            try requirePresentationSource()
            return try await SPFNSocialGoogle(presenting: { presenter() }).idToken(nonce: nonce)
        }
    }

    /// Refuses before a sheet is asked for, rather than after it fails to appear.
    private static func requirePresentationSource() throws
    {
        guard keyWindow() != nil
        else
        {
            throw HarnessError.noPresentationAnchor
        }
    }

    /// Apple presents from a window. The fallback is a fresh window rather than a crash
    /// because the closure cannot throw — and it is unreachable in practice, because
    /// `requirePresentationSource()` has already refused the case that would reach it.
    private static func anchor() -> ASPresentationAnchor
    {
        keyWindow() ?? ASPresentationAnchor()
    }

    /// Google presents from a view controller, and the same reasoning applies.
    private static func presenter() -> UIViewController
    {
        keyWindow()?.rootViewController ?? UIViewController()
    }

    /// The window this app is actually showing. A scene that is not foreground-active
    /// has no key window worth presenting over, so it is not offered.
    private static func keyWindow() -> UIWindow?
    {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }?
            .windows
            .first { $0.isKeyWindow }
    }

    /// True when this error is the person closing the sheet, or the task being cancelled
    /// around it — the two shapes the registry's P16 row keeps apart from failure.
    ///
    /// Both are checked because they are different types arriving from different places:
    /// the adapter raises its own `.cancelled` for a dismissal, and Swift raises
    /// `CancellationError` when the enclosing task is cancelled. Handling one and not the
    /// other reports half of the cancellations as failures.
    static func isCancellation(_ error: any Error) -> Bool
    {
        if error is CancellationError
        {
            return true
        }
        if let apple = error as? SPFNSocialAppleError, apple == .cancelled
        {
            return true
        }
        if let google = error as? SPFNSocialGoogleError, google == .cancelled
        {
            return true
        }
        return false
    }
}
