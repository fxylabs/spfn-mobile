// SPFN Mobile — the Google adapter, case table rows C5–C7.
//
// The rows run with the `SocialGoogle` trait OFF, which is the point: the adapter's own
// rules must be judgeable without Google's SDK present, and the only thing the trait
// adds is the platform flow behind the same seam. The row that matters most is C7 —
// Google's request carries the raw value where Apple's carries the hash.

import Foundation
import XCTest
import SPFNClient
import SPFNSocialGoogle

final class SPFNSocialGoogleTests: XCTestCase
{
    /// C5: the user completes the flow — the token comes back untouched.
    func test_C5_aCompletedSignInReturnsTheToken() async throws
    {
        let adapter = SPFNSocialGoogle(driver: RecordingGoogleDriver(outcome: .success("google-token-0001")))

        let token = try await adapter.idToken(nonce: SPFNSocialNonce.make())

        XCTAssertEqual(token, "google-token-0001")
    }

    /// C6: the user dismisses the flow — a cancellation, distinct from every other
    /// refusal, which keeps its numeric code.
    func test_C6_aDismissalIsACancellation() async throws
    {
        let cancelled = NSError(domain: SPFNSocialGoogle.errorDomain, code: SPFNSocialGoogle.cancelledCode)
        let dismissed = SPFNSocialGoogle(driver: RecordingGoogleDriver(outcome: .failure(cancelled)))

        let thrown = await failure
        {
            _ = try await dismissed.idToken(nonce: SPFNSocialNonce.make())
        }
        XCTAssertEqual(thrown as? SPFNSocialGoogleError, .cancelled)

        let broken = NSError(domain: SPFNSocialGoogle.errorDomain, code: -2)
        let failed = SPFNSocialGoogle(driver: RecordingGoogleDriver(outcome: .failure(broken)))

        let other = await failure
        {
            _ = try await failed.idToken(nonce: SPFNSocialNonce.make())
        }
        XCTAssertEqual(other as? SPFNSocialGoogleError, .signInFailed(code: -2))

        // With the trait off the cancellation code is this file's restatement of
        // Google's; with the trait on it comes from Google's SDK. The value is pinned
        // here so a silent drift between the two shows up as a failure rather than as a
        // cancellation that stops being recognised.
        XCTAssertEqual(SPFNSocialGoogle.cancelledCode, -5)
        XCTAssertEqual(SPFNSocialGoogle.errorDomain, "com.google.GIDSignIn")
    }

    /// The same row the Apple adapter carries: a cancelled task passes through as a
    /// cancellation rather than being classified into `.signInFailed(code: 0)`.
    func test_aCancelledTaskIsNotReportedAsASignInFailure() async throws
    {
        let adapter = SPFNSocialGoogle(driver: RecordingGoogleDriver(outcome: .failure(CancellationError())))

        let thrown = await failure
        {
            _ = try await adapter.idToken(nonce: SPFNSocialNonce.make())
        }

        XCTAssertTrue(
            thrown is CancellationError,
            "a cancellation must not be classified as a refusal, got \(String(describing: thrown))"
        )
    }

    /// C7: the request's nonce field carries the RAW value, never the Apple hash.
    func test_C7_theRequestNonceIsTheRawValueNotTheHash() async throws
    {
        let driver = RecordingGoogleDriver(outcome: .success("google-token-0001"))
        let adapter = SPFNSocialGoogle(driver: driver)
        let nonce = SPFNSocialNonce.make()

        _ = try await adapter.idToken(nonce: nonce)

        let requested = await driver.requestedNonce
        XCTAssertEqual(requested, nonce.rawValue)
        XCTAssertNotEqual(requested, nonce.appleRequestValue, "only Apple's request carries the hash")
    }

    /// A sign-in that answers with no token fails explicitly rather than enrolling an
    /// empty string. The Apple row C3 states the rule; this holds the other adapter to
    /// it, because the two files are each other's check.
    func test_aSignInWithoutATokenFailsExplicitly() async throws
    {
        for outcome in [GoogleOutcome.success(nil), GoogleOutcome.success("")]
        {
            let adapter = SPFNSocialGoogle(driver: RecordingGoogleDriver(outcome: outcome))
            let thrown = await failure
            {
                _ = try await adapter.idToken(nonce: SPFNSocialNonce.make())
            }
            XCTAssertEqual(thrown as? SPFNSocialGoogleError, .identityTokenMissing)
        }
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

enum GoogleOutcome
{
    case success(String?)
    case failure(any Error)
}

/// The platform flow, scripted: it records the value it was asked to put in the request
/// and answers with the outcome the row under test needs.
actor RecordingGoogleDriver: SPFNSocialGoogleDriver
{
    private(set) var requestedNonce: String?
    private let outcome: GoogleOutcome

    init(outcome: GoogleOutcome)
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
