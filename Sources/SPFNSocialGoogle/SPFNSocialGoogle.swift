// SPFN Mobile — the Google Sign-In half of a native enrollment.
//
// Same shape as the Apple adapter and one deliberate difference: Google's request
// carries the key fingerprint itself, not its hash. Apple is the exception in this SDK,
// and the exception lives in one place — `SPFNSocialNonce.requestValue`, which the nonce
// computed from the provider it was minted for. So both adapters read the same member
// and neither picks a shape; what an adapter does check is that the nonce it was handed
// was minted for its own provider.
//
// The external dependency is trait-gated. A consumer that does not enable the
// `SocialGoogle` trait does not resolve, check out or link Google's SDK, and this file
// still compiles: everything except the platform flow itself is outside `#if
// SocialGoogle`, which is also what lets the suite drive the adapter's rules with the
// trait off.
//
// Nothing here reads or logs anything but the token (decision 1).
//
// This module is Apple-only, which tools/module-graph.json states as `"linux":
// false` on its row. SwiftPM cannot leave a target out of a platform, so the whole
// file is guarded on the frameworks the platform flow is presented from — UIKit on
// iOS, AppKit on macOS — and the target compiles to an empty module where neither
// exists. Guarding on UIKit alone would empty the module on macOS too, where this
// adapter builds and its rows run.
#if canImport(UIKit) || canImport(AppKit)

import Foundation
import SPFNClient

#if SocialGoogle
import GoogleSignIn
#endif

/// Why a Google sign-in did not produce a token. Cancellation is separate for the same
/// reason as on Apple: it is an outcome, not a failure. Google's numeric code is kept;
/// its message text is not.
public enum SPFNSocialGoogleError: Error, Equatable, Sendable
{
    /// The user dismissed the flow.
    case cancelled

    /// The flow completed but carried no identity token.
    case identityTokenMissing

    /// Any other refusal from the platform flow, carrying Google's numeric code.
    case signInFailed(code: Int)

    /// The nonce was minted for a different provider. See the guard in `idToken`.
    case nonceProviderMismatch
}

/// The seam between this adapter and Google's flow, so the adapter's rules are testable
/// with the trait off and without a device.
public protocol SPFNSocialGoogleDriver: Sendable
{
    /// Runs the platform flow with `requestNonce` in the request's nonce field, and
    /// answers with the identity token, or nil when the result carried none.
    func identityToken(requestNonce: String) async throws -> String?
}

/// The one thing this module offers an app: a nonce goes in, a provider token comes out.
public struct SPFNSocialGoogle: Sendable
{
    /// Google's own error domain. Restated rather than imported so classification
    /// compiles with the trait off; with the trait on, `cancelledCode` below comes from
    /// Google's SDK instead of from this file.
    package static let errorDomain = "com.google.GIDSignIn"

    /// The provider name an enrollment for this adapter is started under, and the one a
    /// nonce must have been minted for.
    package static let provider = "google"

    #if SocialGoogle
    package static let cancelledCode = GIDSignInError.canceled.rawValue
    #else
    package static let cancelledCode = -5
    #endif

    private let driver: any SPFNSocialGoogleDriver

    /// The flow an app already drives itself, or a suite drives instead.
    public init(driver: any SPFNSocialGoogleDriver)
    {
        self.driver = driver
    }

    /// The token to answer `SPFNKeyLifecycle.enroll` with, from inside its closure.
    public func idToken(nonce: SPFNSocialNonce) async throws -> String
    {
        // Google's flow reads the raw fingerprint, and an apple-minted nonce carries a
        // hash instead — the token would come back bound to a value the SPFN server
        // never compares against, and its refusal is a code the app cannot read.
        guard nonce.provider == Self.provider
        else
        {
            throw SPFNSocialGoogleError.nonceProviderMismatch
        }

        let token: String?
        do
        {
            // `requestValue` is the raw fingerprint here: Apple is the only provider
            // whose request carries a hash instead.
            token = try await driver.identityToken(requestNonce: nonce.requestValue)
        }
        catch let cancellation as CancellationError
        {
            // A cancelled task is not a refused sign-in. Classifying it would answer
            // the caller with `.signInFailed(code: 0)` and swallow the cancellation the
            // caller itself asked for.
            throw cancellation
        }
        catch
        {
            throw Self.classify(error)
        }
        guard let token, !token.isEmpty
        else
        {
            throw SPFNSocialGoogleError.identityTokenMissing
        }
        return token
    }

    /// Google reports a dismissal as one numeric code in its own domain; every other
    /// refusal keeps its code and loses its text.
    package static func classify(_ error: any Error) -> SPFNSocialGoogleError
    {
        if let already = error as? SPFNSocialGoogleError
        {
            return already
        }
        let failure = error as NSError
        guard failure.domain == errorDomain
        else
        {
            return .signInFailed(code: failure.code)
        }
        if failure.code == cancelledCode
        {
            return .cancelled
        }
        return .signInFailed(code: failure.code)
    }
}

#if SocialGoogle
#if canImport(UIKit)
import UIKit

/// What Google's flow is presented from on this platform.
public typealias SPFNGooglePresentingContext = UIViewController
#else
import AppKit

public typealias SPFNGooglePresentingContext = NSWindow
#endif

extension SPFNSocialGoogle
{
    /// The default flow, presented from `context`.
    public init(presenting context: @escaping @MainActor @Sendable () -> SPFNGooglePresentingContext)
    {
        self.init(driver: SPFNSocialGoogleSignInDriver(context: context))
    }
}

/// The platform flow itself: one sign-in, one result, one token.
struct SPFNSocialGoogleSignInDriver: SPFNSocialGoogleDriver
{
    let context: @MainActor @Sendable () -> SPFNGooglePresentingContext

    func identityToken(requestNonce: String) async throws -> String?
    {
        try await Self.signIn(presenting: context, nonce: requestNonce)
    }

    /// Google's flow is presented from the main actor, and the result is read there
    /// too: nothing but the token string crosses back out.
    @MainActor
    private static func signIn(
        presenting context: @MainActor @Sendable () -> SPFNGooglePresentingContext,
        nonce: String
    ) async throws -> String?
    {
        let result = try await GIDSignIn.sharedInstance.signIn(
            withPresenting: context(),
            hint: nil,
            additionalScopes: nil,
            nonce: nonce
        )
        return result.user.idToken?.tokenString
    }
}
#endif

#endif
