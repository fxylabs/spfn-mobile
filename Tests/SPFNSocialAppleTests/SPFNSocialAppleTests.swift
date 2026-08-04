// SPFN Mobile — the Apple adapter, case table rows C1–C4.
//
// The platform flow is driven through the adapter's seam, so what is under test is the
// adapter's own rules — which value the request carries, how a dismissal is told apart
// from a failure, and what happens when a completed authorization brings no token.
// Nothing here needs a device, and nothing here would be more true on one: the rows are
// about this module's decisions, not about Apple's UI.

import AuthenticationServices
import Foundation
import XCTest
import SPFNClient
import SPFNSocialApple

final class SPFNSocialAppleTests: XCTestCase
{
    /// C1: the user completes the flow — the token comes back untouched.
    func test_C1_aCompletedAuthorizationReturnsTheToken() async throws
    {
        let driver = RecordingAppleDriver(outcome: .success("apple-token-0001"))
        let adapter = SPFNSocialApple(driver: driver)

        let token = try await adapter.idToken(nonce: SPFNSocialNonce.make())

        XCTAssertEqual(token, "apple-token-0001")
    }

    /// C2: the user dismisses the sheet — a cancellation, and a case of its own, so an
    /// app can stay silent for it while reporting everything else.
    func test_C2_aDismissalIsACancellationDistinctFromAFailure() async throws
    {
        let cancelled = NSError(
            domain: ASAuthorizationError.errorDomain,
            code: ASAuthorizationError.canceled.rawValue
        )
        let dismissed = SPFNSocialApple(driver: RecordingAppleDriver(outcome: .failure(cancelled)))

        let thrown = await failure
        {
            _ = try await dismissed.idToken(nonce: SPFNSocialNonce.make())
        }
        XCTAssertEqual(thrown as? SPFNSocialAppleError, .cancelled)

        let broken = NSError(
            domain: ASAuthorizationError.errorDomain,
            code: ASAuthorizationError.failed.rawValue
        )
        let failed = SPFNSocialApple(driver: RecordingAppleDriver(outcome: .failure(broken)))

        let other = await failure
        {
            _ = try await failed.idToken(nonce: SPFNSocialNonce.make())
        }
        XCTAssertEqual(
            other as? SPFNSocialAppleError,
            .authorizationFailed(code: ASAuthorizationError.failed.rawValue),
            "every other refusal must stay distinguishable from a dismissal"
        )
    }

    /// C3: an authorization that carries no identity token fails explicitly rather than
    /// returning an empty string that would be sent to the server as one.
    func test_C3_anAuthorizationWithoutAnIdentityTokenFailsExplicitly() async throws
    {
        for outcome in [AppleOutcome.success(nil), AppleOutcome.success("")]
        {
            let adapter = SPFNSocialApple(driver: RecordingAppleDriver(outcome: outcome))
            let thrown = await failure
            {
                _ = try await adapter.idToken(nonce: SPFNSocialNonce.make())
            }
            XCTAssertEqual(thrown as? SPFNSocialAppleError, .identityTokenMissing)
        }
    }

    /// C4: the request's nonce field carries the Apple request value — the hash — and
    /// never the raw value the enrollment body carries.
    func test_C4_theRequestNonceIsTheAppleRequestValueNotTheRawValue() async throws
    {
        let driver = RecordingAppleDriver(outcome: .success("apple-token-0001"))
        let adapter = SPFNSocialApple(driver: driver)
        let nonce = SPFNSocialNonce.make()

        _ = try await adapter.idToken(nonce: nonce)

        let requested = await driver.requestedNonce
        XCTAssertEqual(requested, nonce.appleRequestValue)
        XCTAssertNotEqual(requested, nonce.rawValue, "Apple's request must carry the hash, not the pre-image")
    }

    private func failure(
        _ body: () async throws -> Void,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async -> (any Error)?
    {
        do
        {
            try await body()
        }
        catch
        {
            return error
        }
        XCTFail("expected a throw", file: file, line: line)
        return nil
    }
}

enum AppleOutcome
{
    case success(String?)
    case failure(any Error)
}

/// The platform flow, scripted: it records the value it was asked to put in the request
/// and answers with the outcome the row under test needs.
actor RecordingAppleDriver: SPFNSocialAppleDriver
{
    private(set) var requestedNonce: String?
    private let outcome: AppleOutcome

    init(outcome: AppleOutcome)
    {
        self.outcome = outcome
    }

    func identityToken(requestNonce: String) async throws -> String?
    {
        requestedNonce = requestNonce
        switch outcome
        {
        case .success(let token):
            return token
        case .failure(let error):
            throw error
        }
    }
}
