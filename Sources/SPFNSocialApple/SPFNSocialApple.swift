// SPFN Mobile — the Sign in with Apple half of a native enrollment.
//
// The SDK already owns everything around this: `SPFNSocialNonce` mints the binding
// value and `SPFNKeyLifecycle.enroll` generates the key, sends the registration and
// persists the identity the server issues. The one thing neither of them can do is
// stand in front of the user and come back with a provider token. That is all this
// module does, and its whole public surface is one call.
//
// Two rules shape it, and both come from decisions rather than from taste:
//
//   - The request's nonce field carries the nonce's `requestValue`, which for an
//     apple-minted nonce is the SHA-256 of the key fingerprint. Apple follows the OIDC
//     rule here: the request nonce is hashed and the id_token it signs carries that
//     hash, so the SPFN server — which sees the fingerprint in the enrollment body —
//     can only match if the request carried the hash. A nonce minted for another
//     provider is refused rather than sent.
//   - Nothing here reads, stores or logs anything but the token. Apple hands the
//     display fields to the credential exactly once, on first authorization, and this
//     SDK is not where they belong: an app that wants them asks for them itself and
//     sends them through a profile operation (decision 1).
//
// This module is iOS-only, and the module graph says so: `androidModule` is null on its
// row. Apple ships no native sign-in SDK for Android, so an Android half would have
// owned a one-line nonce accessor and a seam the app fills in anyway. An Android app
// signing in with Apple needs `SpfnSocialNonce`, its `requestValue` and
// `enroll(provider = "apple", …)` — all three live in spfn-client and always did.

import AuthenticationServices
import Foundation
import SPFNClient

/// Why an Apple sign-in did not produce a token.
///
/// A cancellation is a separate case because it is the one outcome that is not a
/// failure: the app shows nothing and waits. The associated value on
/// `authorizationFailed` is Apple's own numeric code, never its message text — a
/// provider's message is the fastest way for a token or an account identifier to end
/// up in a log.
public enum SPFNSocialAppleError: Error, Equatable, Sendable
{
    /// The user dismissed the sheet. Not an error to report, only one to stop on.
    case cancelled

    /// The authorization completed but carried no identity token, so there is nothing
    /// to enroll with.
    case identityTokenMissing

    /// Any other refusal from the platform flow, carrying Apple's numeric code.
    case authorizationFailed(code: Int)

    /// The nonce was minted for a different provider, so its value is the raw
    /// fingerprint rather than the hash Apple's flow requires. Refused here because the
    /// server's answer to sending it would be a 400 outside the contract's error codes,
    /// reaching the app as an unknown code naming nothing.
    case nonceProviderMismatch
}

/// The seam between this adapter and the platform flow.
///
/// It exists so the adapter's own rules — which value goes in the request, how a
/// cancellation is told apart from a failure, what happens when no token comes back —
/// are testable without a signed app, a device and a human tapping a sheet.
public protocol SPFNSocialAppleDriver: Sendable
{
    /// Runs the platform's authorization flow with `requestNonce` in the request's
    /// nonce field, and answers with the credential's identity token, or nil when the
    /// credential carried none.
    func identityToken(requestNonce: String) async throws -> String?
}

/// The one thing this module offers an app: a nonce goes in, a provider token comes out.
public struct SPFNSocialApple: Sendable
{
    private let driver: any SPFNSocialAppleDriver

    /// The default flow, presented from `anchor`.
    public init(anchor: @escaping @MainActor @Sendable () -> ASPresentationAnchor)
    {
        self.driver = SPFNSocialAppleAuthorizationDriver(anchor: anchor)
    }

    /// The flow an app already drives itself, or a suite drives instead.
    public init(driver: any SPFNSocialAppleDriver)
    {
        self.driver = driver
    }

    /// The token to answer `SPFNKeyLifecycle.enroll` with, from inside its closure.
    ///
    /// The nonce is passed as the value type rather than as a string precisely so this
    /// call cannot put the wrong shape in the request: `requestValue` is already what
    /// this provider expects, and there is no other value on the type to reach for.
    public func idToken(nonce: SPFNSocialNonce) async throws -> String
    {
        // A nonce minted for another provider carries the raw fingerprint, and Apple
        // signs the hash of whatever it is given — so the token would come back bound to
        // a value the SPFN server never compares against.
        guard nonce.provider == SPFNSocialNonce.appleProvider
        else
        {
            throw SPFNSocialAppleError.nonceProviderMismatch
        }

        let token: String?
        do
        {
            token = try await driver.identityToken(requestNonce: nonce.requestValue)
        }
        catch let cancellation as CancellationError
        {
            // A cancelled task is not a refused sign-in. Classifying it would answer
            // the caller with `.authorizationFailed(code: 0)` and swallow the
            // cancellation the caller itself asked for.
            throw cancellation
        }
        catch
        {
            throw Self.classify(error)
        }
        guard let token, !token.isEmpty
        else
        {
            throw SPFNSocialAppleError.identityTokenMissing
        }
        return token
    }

    /// Apple's cancellation is a numeric code in its own error domain, and every other
    /// refusal keeps its code and loses its text.
    package static func classify(_ error: any Error) -> SPFNSocialAppleError
    {
        if let already = error as? SPFNSocialAppleError
        {
            return already
        }
        let failure = error as NSError
        guard failure.domain == ASAuthorizationError.errorDomain
        else
        {
            return .authorizationFailed(code: failure.code)
        }
        if failure.code == ASAuthorizationError.canceled.rawValue
        {
            return .cancelled
        }
        return .authorizationFailed(code: failure.code)
    }
}

/// The platform flow itself: one authorization request, one credential, one token.
///
/// `ASAuthorizationController` does not retain its delegate, and a session that is
/// released while the sheet is up leaves the continuation suspended forever. So a
/// running session holds itself in `live` and drops itself the moment it answers.
///
/// The platform answers a dismissed sheet with a delegate callback, but it answers a
/// cancelled *task* with nothing at all — no callback arrives, and without a handler the
/// continuation stays suspended and the session stays in `live` for the life of the
/// process. So cancellation is handled here rather than waited on.
@MainActor
final class SPFNSocialAppleAuthorizationSession: NSObject,
    ASAuthorizationControllerDelegate,
    ASAuthorizationControllerPresentationContextProviding
{
    private static var live: [SPFNSocialAppleAuthorizationSession] = []

    private let anchor: @MainActor @Sendable () -> ASPresentationAnchor
    private var continuation: CheckedContinuation<String?, any Error>?
    private var controller: ASAuthorizationController?

    init(anchor: @escaping @MainActor @Sendable () -> ASPresentationAnchor)
    {
        self.anchor = anchor
    }

    func identityToken(requestNonce: String) async throws -> String?
    {
        // No scopes are requested: the identity token is the whole of what this SDK
        // reads, and asking for more would collect what it has decided not to hold.
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = []
        request.nonce = requestNonce

        return try await withTaskCancellationHandler
        {
            // A task already cancelled before the sheet goes up must not raise one.
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation
            { continuation in
                self.continuation = continuation
                Self.live.append(self)
                let controller = ASAuthorizationController(authorizationRequests: [request])
                self.controller = controller
                controller.delegate = self
                controller.presentationContextProvider = self
                controller.performRequests()
            }
        }
        onCancel:
        {
            // The handler runs off the main actor and synchronously, so the work hops
            // rather than blocking. A resume that lost the race is already a no-op.
            Task { @MainActor in self.cancelRunningAuthorization() }
        }
    }

    /// Answers the caller with the cancellation it asked for, then takes the sheet down.
    /// Resuming first means a delegate callback that arrives on the way out finds the
    /// continuation already spent and changes nothing.
    private func cancelRunningAuthorization()
    {
        let running = controller
        finish(with: .failure(CancellationError()))
        running?.cancel()
    }

    func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    )
    {
        let credential = authorization.credential as? ASAuthorizationAppleIDCredential
        let token = credential?.identityToken.flatMap { String(data: $0, encoding: .utf8) }
        finish(with: .success(token))
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: any Error)
    {
        finish(with: .failure(error))
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor
    {
        anchor()
    }

    /// Resumes exactly once: a platform that calls back twice must not trap the process.
    private func finish(with result: Result<String?, any Error>)
    {
        guard let pending = continuation
        else
        {
            return
        }
        continuation = nil
        controller = nil
        Self.live.removeAll { $0 === self }
        pending.resume(with: result)
    }
}

/// The default driver: a fresh session per call, so two concurrent sign-ins cannot
/// share one continuation.
struct SPFNSocialAppleAuthorizationDriver: SPFNSocialAppleDriver
{
    let anchor: @MainActor @Sendable () -> ASPresentationAnchor

    func identityToken(requestNonce: String) async throws -> String?
    {
        try await SPFNSocialAppleAuthorizationSession(anchor: anchor)
            .identityToken(requestNonce: requestNonce)
    }
}
