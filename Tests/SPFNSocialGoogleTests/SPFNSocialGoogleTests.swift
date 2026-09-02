// SPFN Mobile — the Google adapter, case table rows C5–C7.
//
// The rows run with the `SocialGoogle` trait OFF, which is the point: the adapter's own
// rules must be judgeable without Google's SDK present, and the only thing the trait
// adds is the platform flow behind the same seam. The row that matters most is C7 —
// Google's request carries the raw value where Apple's carries the hash.
//
// The module under test is Apple-only (`"linux": false` in tools/module-graph.json),
// so this file is guarded the same way its subject is — UIKit on iOS, AppKit on macOS
// — and on Linux the target compiles to an empty module with nothing here to run.
#if canImport(UIKit) || canImport(AppKit)

import Foundation
import XCTest
import SPFNClient
import SPFNSocialGoogle

final class SPFNSocialGoogleTests: XCTestCase
{
    /// A fingerprint shaped as one taken over a real key: 64 lowercase hex characters.
    /// Fixed, because what these rows test is the adapter's handling of it, not its value.
    private static let fingerprint = "aa919f16ced3a7bae097e8fde574681a9184cbc53ba1dd9ab43fa716774b690a"

    private static func googleNonce() -> SPFNSocialNonce
    {
        SPFNSocialNonce(fingerprint: fingerprint, provider: "google")
    }

    /// C5: the user completes the flow — the token comes back untouched.
    func test_C5_aCompletedSignInReturnsTheToken() async throws
    {
        let adapter = SPFNSocialGoogle(driver: RecordingGoogleDriver(outcome: .success("google-token-0001")))

        let token = try await adapter.idToken(nonce: Self.googleNonce())

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
            _ = try await dismissed.idToken(nonce: Self.googleNonce())
        }
        XCTAssertEqual(thrown as? SPFNSocialGoogleError, .cancelled)

        let broken = NSError(domain: SPFNSocialGoogle.errorDomain, code: -2)
        let failed = SPFNSocialGoogle(driver: RecordingGoogleDriver(outcome: .failure(broken)))

        let other = await failure
        {
            _ = try await failed.idToken(nonce: Self.googleNonce())
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
            _ = try await adapter.idToken(nonce: Self.googleNonce())
        }

        XCTAssertTrue(
            thrown is CancellationError,
            "a cancellation must not be classified as a refusal, got \(String(describing: thrown))"
        )
    }

    /// C7 / cell 14: the request's nonce field carries the fingerprint itself, never the
    /// hash that only Apple's flow expects.
    func test_C7_theRequestNonceIsTheFingerprintNotTheHash() async throws
    {
        let driver = RecordingGoogleDriver(outcome: .success("google-token-0001"))
        let adapter = SPFNSocialGoogle(driver: driver)
        let nonce = Self.googleNonce()

        _ = try await adapter.idToken(nonce: nonce)

        let requested = await driver.requestedNonce
        XCTAssertEqual(requested, Self.fingerprint)
        XCTAssertEqual(requested, nonce.requestValue)
        XCTAssertNotEqual(
            requested,
            SPFNSocialNonce(fingerprint: Self.fingerprint, provider: "apple").requestValue,
            "only Apple's request carries the hash"
        )
    }

    /// Cell 18: a nonce minted for another provider is refused, and the flow never runs.
    /// An apple-minted nonce carries a hash, so Google would echo a value the SPFN server
    /// never compares against.
    func test_18_aNonceMintedForAnotherProviderIsRefusedBeforeTheFlow() async throws
    {
        for provider in ["apple", "kakao", "naver"]
        {
            let driver = RecordingGoogleDriver(outcome: .success("google-token-0001"))
            let adapter = SPFNSocialGoogle(driver: driver)

            let thrown = await failure
            {
                _ = try await adapter.idToken(
                    nonce: SPFNSocialNonce(fingerprint: Self.fingerprint, provider: provider)
                )
            }

            XCTAssertEqual(thrown as? SPFNSocialGoogleError, .nonceProviderMismatch, "'\(provider)' was accepted")
            let requested = await driver.requestedNonce
            XCTAssertNil(requested, "'\(provider)': the platform flow must not have been reached")
        }
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
                _ = try await adapter.idToken(nonce: Self.googleNonce())
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

#endif
