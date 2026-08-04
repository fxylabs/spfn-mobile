// SPFN Mobile — the nonce value type, case table cells 13–16.
//
// The expected values are the design's own table, written here rather than read back out
// of the implementation (P10): the table says an apple-minted nonce's request value is
// the SHA-256 hex of the fingerprint, that every other provider's is the fingerprint
// itself, that the body always carries the fingerprint, and that the value is 64
// lowercase hex characters. Each row below asserts exactly one of those.
//
// SpfnSocialNonceTest.kt mirrors the same rows in Kotlin, and the guard vector at the
// bottom is what pins the two platforms' character classification to each other (P9).
// The byte-to-hex vector lives in SPFNCoreTests, beside the encoder it asserts against.

import CryptoKit
import Foundation
import XCTest
import SPFNClient
import SPFNCore

final class SPFNSocialNonceTests: XCTestCase
{
    /// A fingerprint shaped exactly as one taken over a real SPKI DER: the SHA-256 of
    /// some bytes, in lowercase hex. Fixed so every row below reads the same input.
    private static let fingerprint = SPFNDigest.sha256Hex(Array("spfn-mobile-test-key".utf8))

    /// Cell 13: apple's request value is the SHA-256 of the fingerprint, in hex.
    func test_13_appleRequestValueIsTheSha256HexOfTheFingerprint() throws
    {
        let nonce = SPFNSocialNonce(fingerprint: Self.fingerprint, provider: "apple")
        let digest = SHA256.hash(data: Data(Self.fingerprint.utf8))
        let expected = digest.map { String(format: "%02x", $0) }.joined()

        XCTAssertEqual(nonce.requestValue, expected)
        XCTAssertNotEqual(
            nonce.requestValue, Self.fingerprint,
            "apple's request carries the hash, not the pre-image"
        )
    }

    /// Cell 14: every other provider's request value is the fingerprint itself.
    ///
    /// kakao and naver are listed because they are the providers an app reaches through
    /// its own SDK — this SDK ships no adapter for them, and the whole reason
    /// `requestValue` is public is that such an app needs the value.
    func test_14_everyOtherProviderCarriesTheFingerprintItself() throws
    {
        for provider in ["google", "kakao", "naver", "github"]
        {
            let nonce = SPFNSocialNonce(fingerprint: Self.fingerprint, provider: provider)
            XCTAssertEqual(nonce.requestValue, Self.fingerprint, "\(provider) must carry the raw fingerprint")
            XCTAssertEqual(nonce.provider, provider)
        }
    }

    /// Cell 15: whatever the provider, the value the enrollment body carries is the
    /// fingerprint. Asserted on the type rather than only through a flow, because this
    /// is the equality the server checks and the one an app can never see fail.
    func test_15_theBodyValueIsAlwaysTheFingerprint() throws
    {
        for provider in ["apple", "google", "kakao", "naver"]
        {
            let nonce = SPFNSocialNonce(fingerprint: Self.fingerprint, provider: provider)
            XCTAssertEqual(nonce.fingerprint, Self.fingerprint)
        }
    }

    /// Cell 16: 64 characters, lowercase hex, whichever shape the provider gets — and
    /// no base64 alphabet in either. `+`, `/` and `=` would not survive a URL round trip,
    /// and a trailing `A` is the character Naver drops from a nonce it echoes back
    /// (spfn-primitives #57), which lowercase hex cannot produce.
    func test_16_bothShapesAre64LowercaseHexCharacters() throws
    {
        for provider in ["apple", "google", "kakao", "naver"]
        {
            let value = SPFNSocialNonce(fingerprint: Self.fingerprint, provider: provider).requestValue
            XCTAssertEqual(value.count, 64, "\(provider): expected 64 characters")
            XCTAssertTrue(SPFNSocialNonce.isLowercaseHex(value), "\(provider): '\(value)' is not lowercase hex")
            XCTAssertFalse(value.contains("+"))
            XCTAssertFalse(value.contains("/"))
            XCTAssertFalse(value.contains("="))
            XCTAssertFalse(value.hasSuffix("A"))
        }
    }

    /// One instance read twice answers the same thing both times.
    func test_readingOneInstanceTwiceGivesTheSameValues() throws
    {
        let nonce = SPFNSocialNonce(fingerprint: Self.fingerprint, provider: "apple")

        XCTAssertEqual(nonce.requestValue, nonce.requestValue)
        XCTAssertEqual(nonce.fingerprint, nonce.fingerprint)
        XCTAssertEqual(nonce.description, nonce.description)
    }

    /// The public surface is exactly the three members the design names, and nothing an
    /// app can call mints a nonce.
    ///
    /// Judged from the declaration itself rather than by reflection, because what this
    /// row exists to prevent is a `public` member someone adds later — a second value to
    /// choose between, or an initialiser that lets an app enrol a nonce belonging to no
    /// key it holds. A mirror shows stored properties whatever their access level, so it
    /// would not see the difference.
    func test_thePublicSurfaceIsTheThreeMembersTheDesignNames() throws
    {
        let source = try SocialSurface.text(at: "Sources/SPFNClient/SPFNSocialNonce.swift")
        let declarations = source
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { $0.hasPrefix("public ") || $0.hasPrefix("package ") }

        XCTAssertGreaterThanOrEqual(declarations.count, 6, "the declaration scan read implausibly little")

        let allowedPublic = [
            "public struct SPFNSocialNonce: Sendable, CustomStringConvertible, CustomDebugStringConvertible",
            "public let provider: String",
            "public let requestValue: String",
            "public var description: String",
            "public var debugDescription: String",
        ]
        let publicDeclarations = declarations.filter { $0.hasPrefix("public ") }
        XCTAssertEqual(
            publicDeclarations, allowedPublic,
            "the public surface of SPFNSocialNonce changed; a second value or a public initialiser is not it"
        )

        let nonce = SPFNSocialNonce(fingerprint: Self.fingerprint, provider: "apple")
        XCTAssertFalse(nonce.description.contains(nonce.fingerprint), "description leaks the fingerprint")
        XCTAssertFalse(nonce.debugDescription.contains(nonce.fingerprint), "debugDescription leaks the fingerprint")
        XCTAssertFalse(String(describing: nonce).contains(nonce.fingerprint), "interpolation leaks the fingerprint")
        XCTAssertFalse(String(reflecting: nonce).contains(nonce.fingerprint), "reflection leaks the fingerprint")
    }

    /// P9: the lowercase-hex guard refuses the non-ASCII digits a Unicode-aware
    /// classification would accept. `Character.isHexDigit` accepts Arabic-Indic and
    /// full-width digits that the Kotlin counterpart's explicit range does not, so the
    /// refused list is the shared vector and SpfnSocialNonceTest.kt carries it too.
    ///
    /// The byte-to-hex vector that used to sit here moved to SPFNCoreTests: it now
    /// asserts against `SPFNDigest.hex`, the encoder a fingerprint actually goes through,
    /// rather than against the copy this type used to carry.
    func test_P9_theAsciiGuardMatchesTheSharedVector() throws
    {
        XCTAssertTrue(SPFNSocialNonce.isLowercaseHex("0123456789abcdef"))
        for refused in ["", "ABCDEF", "0x1f", "g", "00 ", "٠١٢", "０１２", "ｆ", "00\u{0000}"]
        {
            XCTAssertFalse(SPFNSocialNonce.isLowercaseHex(refused), "'\(refused)' was accepted as lowercase hex")
        }
    }
}

/// Repository-relative reads for the surface rows. `#filePath` is the only anchor a
/// test target has that does not depend on where the runner was launched from.
enum SocialSurface
{
    static let root: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent()   // Tests/SPFNClientTests
        .deletingLastPathComponent()   // Tests
        .deletingLastPathComponent()   // repository root

    static func text(at relativePath: String) throws -> String
    {
        try String(contentsOf: root.appendingPathComponent(relativePath), encoding: .utf8)
    }
}
