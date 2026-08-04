// SPFN Mobile — the Google Sign-In half of a native enrollment.
//
// Same shape as the Apple adapter and one deliberate difference: Google's request
// carries the RAW nonce, not its hash. Apple is the exception in this SDK, and the
// exception lives in one place — `SPFNSocialNonce.appleRequestValue` — so an adapter
// that reaches for the raw value is doing the ordinary thing rather than the risky one.
// The value type is what makes that checkable: neither shape is an app's to pick.
//
// The external dependency is trait-gated. A consumer that does not enable the
// `SocialGoogle` trait does not resolve, check out or link Google's SDK, and this file
// still compiles: everything except the platform flow itself is outside `#if
// SocialGoogle`, which is also what lets the suite drive the adapter's rules with the
// trait off.
//
// Nothing here reads or logs anything but the token (decision 1).

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

    /// The token to hand `SPFNKeyLifecycle.enroll` together with the same nonce.
    public func idToken(nonce: SPFNSocialNonce) async throws -> String
    {
        let token: String?
        do
        {
            // The raw value, not the hash: Apple is the only provider that hashes.
            token = try await driver.identityToken(requestNonce: nonce.rawValue)
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
