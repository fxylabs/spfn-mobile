// SPFN Mobile — the Apple adapter, case table rows C1–C4.
//
// The platform flow is driven through the adapter's seam, so what is under test is the
// adapter's own rules — which value the request carries, how a dismissal is told apart
// from a failure, and what happens when a completed authorization brings no token.
// Nothing here needs a device, and nothing here would be more true on one: the rows are
// about this module's decisions, not about Apple's UI.
//
// The module under test is Apple-only (`"linux": false` in tools/module-graph.json),
// so this file is guarded the same way its subject is: on Linux the target compiles
// to an empty module and there is nothing here to run.
#if canImport(AuthenticationServices)

import AuthenticationServices
import Foundation
import XCTest
import SPFNClient
import SPFNSocialApple

final class SPFNSocialAppleTests: XCTestCase
{
    /// A fingerprint shaped as one taken over a real key: 64 lowercase hex characters.
    /// Fixed, because what these rows test is the adapter's handling of it, not its value.
    private static let fingerprint = "aa919f16ced3a7bae097e8fde574681a9184cbc53ba1dd9ab43fa716774b690a"

    private static func appleNonce() -> SPFNSocialNonce
    {
        SPFNSocialNonce(fingerprint: fingerprint, provider: "apple")
    }

    /// C1: the user completes the flow — the token comes back untouched.
    func test_C1_aCompletedAuthorizationReturnsTheToken() async throws
    {
        let driver = RecordingAppleDriver(outcome: .success("apple-token-0001"))
        let adapter = SPFNSocialApple(driver: driver)

        let token = try await adapter.idToken(nonce: Self.appleNonce())

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
            _ = try await dismissed.idToken(nonce: Self.appleNonce())
        }
        XCTAssertEqual(thrown as? SPFNSocialAppleError, .cancelled)

        let broken = NSError(
            domain: ASAuthorizationError.errorDomain,
            code: ASAuthorizationError.failed.rawValue
        )
        let failed = SPFNSocialApple(driver: RecordingAppleDriver(outcome: .failure(broken)))

        let other = await failure
        {
            _ = try await failed.idToken(nonce: Self.appleNonce())
        }
        XCTAssertEqual(
            other as? SPFNSocialAppleError,
            .authorizationFailed(code: ASAuthorizationError.failed.rawValue),
            "every other refusal must stay distinguishable from a dismissal"
        )
    }

    /// A cancelled task is not a dismissed sheet and not a refusal. `classify` reads an
    /// unknown error as an `NSError` and would answer `CancellationError` with
    /// `.authorizationFailed(code: 0)`, telling the caller Apple refused something the
    /// caller itself called off. It passes through as what it is.
    ///
    /// The Kotlin adapter has the same row, under `CancellationException`.
    func test_aCancelledTaskIsNotReportedAsAnAuthorizationFailure() async throws
    {
        let adapter = SPFNSocialApple(driver: RecordingAppleDriver(outcome: .failure(CancellationError())))

        let thrown = await failure
        {
            _ = try await adapter.idToken(nonce: Self.appleNonce())
        }

        XCTAssertTrue(
            thrown is CancellationError,
            "a cancellation must not be classified as a refusal, got \(String(describing: thrown))"
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
                _ = try await adapter.idToken(nonce: Self.appleNonce())
            }
            XCTAssertEqual(thrown as? SPFNSocialAppleError, .identityTokenMissing)
        }
    }

    /// C4 / cell 13: the request's nonce field carries the nonce's request value — the
    /// hash — and never the fingerprint the enrollment body carries.
    func test_C4_theRequestNonceIsTheHashNotTheFingerprint() async throws
    {
        let driver = RecordingAppleDriver(outcome: .success("apple-token-0001"))
        let adapter = SPFNSocialApple(driver: driver)
        let nonce = Self.appleNonce()

        _ = try await adapter.idToken(nonce: nonce)

        let requested = await driver.requestedNonce
        XCTAssertEqual(requested, nonce.requestValue)
        XCTAssertNotEqual(requested, Self.fingerprint, "Apple's request must carry the hash, not the pre-image")
    }

    /// Cell 17: a nonce minted for another provider is refused, and the sheet never goes
    /// up. Such a nonce carries the raw fingerprint, so Apple would sign the hash of the
    /// wrong value and the server's refusal would be a code the app cannot read.
    func test_17_aNonceMintedForAnotherProviderIsRefusedBeforeTheSheet() async throws
    {
        for provider in ["google", "kakao", "naver"]
        {
            let driver = RecordingAppleDriver(outcome: .success("apple-token-0001"))
            let adapter = SPFNSocialApple(driver: driver)

            let thrown = await failure
            {
                _ = try await adapter.idToken(
                    nonce: SPFNSocialNonce(fingerprint: Self.fingerprint, provider: provider)
                )
            }

            XCTAssertEqual(thrown as? SPFNSocialAppleError, .nonceProviderMismatch, "'\(provider)' was accepted")
            let requested = await driver.requestedNonce
            XCTAssertNil(requested, "'\(provider)': the platform flow must not have been reached")
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

#endif
