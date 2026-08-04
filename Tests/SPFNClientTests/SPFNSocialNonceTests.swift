// SPFN Mobile — the nonce value type, case table rows A1–A6.
//
// The expected values are the design's own table, copied by hand rather than read back
// out of the implementation (P10): the table says the Apple request value is the
// SHA-256 hex of the raw value, that it is 64 lowercase hex characters, that the raw
// value never carries the base64 alphabet, and that no public member exposes the raw
// value. Each row below asserts exactly one of those.
//
// SpfnSocialNonceTest.kt mirrors the same rows in Kotlin, and the hex vector at the
// bottom is what pins the two encoders to each other (P9).

import CryptoKit
import Foundation
import XCTest
import SPFNClient
import SPFNCore

final class SPFNSocialNonceTests: XCTestCase
{
    /// A1: two calls, two different raw values.
    func test_A1_twoMakeCallsProduceDifferentRawValues() throws
    {
        var seen: Set<String> = []
        for _ in 0 ..< 64
        {
            seen.insert(SPFNSocialNonce.make().rawValue)
        }
        XCTAssertEqual(seen.count, 64, "make() repeated a raw value")
    }

    /// A2: the Apple request value is the SHA-256 of the raw value, in hex.
    func test_A2_appleRequestValueIsTheSha256HexOfTheRawValue() throws
    {
        let nonce = SPFNSocialNonce.make()
        let digest = SHA256.hash(data: Data(nonce.rawValue.utf8))
        let expected = digest.map { String(format: "%02x", $0) }.joined()

        XCTAssertEqual(nonce.appleRequestValue, expected)
        XCTAssertNotEqual(nonce.appleRequestValue, nonce.rawValue, "the request value is the hash, not the pre-image")
    }

    /// A3: 64 characters, lowercase hex only.
    func test_A3_appleRequestValueIs64LowercaseHexCharacters() throws
    {
        for _ in 0 ..< 16
        {
            let value = SPFNSocialNonce.make().appleRequestValue
            XCTAssertEqual(value.count, 64)
            XCTAssertTrue(SPFNSocialNonce.isLowercaseHex(value), "'\(value)' is not lowercase hex")
        }
    }

    /// A4: the raw value carries no base64 alphabet — no `+`, `/` or `=`, and no
    /// trailing `A`, the character a base64url round trip through a provider drops.
    func test_A4_theRawValueCarriesNoBase64Alphabet() throws
    {
        for _ in 0 ..< 64
        {
            let raw = SPFNSocialNonce.make().rawValue
            XCTAssertFalse(raw.contains("+"))
            XCTAssertFalse(raw.contains("/"))
            XCTAssertFalse(raw.contains("="))
            XCTAssertFalse(raw.hasSuffix("A"))
            XCTAssertTrue(SPFNSocialNonce.isLowercaseHex(raw), "'\(raw)' is not lowercase hex")
        }
    }

    /// A5: one instance read twice answers the same thing both times.
    func test_A5_readingOneInstanceTwiceGivesTheSameValues() throws
    {
        let nonce = SPFNSocialNonce.make()

        XCTAssertEqual(nonce.rawValue, nonce.rawValue)
        XCTAssertEqual(nonce.appleRequestValue, nonce.appleRequestValue)
        XCTAssertEqual(nonce.description, nonce.description)
    }

    /// A6: the public surface exposes no member that hands out the raw value.
    ///
    /// Judged from the declaration itself rather than by reflection, because the leak
    /// this row exists to prevent is a `public` accessor someone adds later, and a
    /// mirror shows stored properties whatever their access level. Every `public`
    /// declaration in the file is listed and held to an allowlist; the two descriptions
    /// are read as text and must not contain the raw value either.
    func test_A6_noPublicMemberExposesTheRawValue() throws
    {
        let source = try SocialSurface.text(at: "Sources/SPFNClient/SPFNSocialNonce.swift")
        let declarations = source
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { $0.hasPrefix("public ") || $0.hasPrefix("package ") }

        XCTAssertGreaterThanOrEqual(declarations.count, 6, "the declaration scan read implausibly little")

        let allowedPublic = [
            "public struct SPFNSocialNonce: Sendable, CustomStringConvertible, CustomDebugStringConvertible",
            "public let appleRequestValue: String",
            "public static func make() -> SPFNSocialNonce",
            "public var description: String",
            "public var debugDescription: String",
        ]
        let publicDeclarations = declarations.filter { $0.hasPrefix("public ") }
        XCTAssertEqual(
            publicDeclarations, allowedPublic,
            "the public surface of SPFNSocialNonce changed; a raw accessor is a package member or it is nothing"
        )

        let nonce = SPFNSocialNonce.make()
        XCTAssertFalse(nonce.description.contains(nonce.rawValue), "description leaks the raw value")
        XCTAssertFalse(nonce.debugDescription.contains(nonce.rawValue), "debugDescription leaks the raw value")
        XCTAssertFalse(String(describing: nonce).contains(nonce.rawValue), "interpolation leaks the raw value")
        XCTAssertFalse(String(reflecting: nonce).contains(nonce.rawValue), "reflection leaks the raw value")
    }

    /// P9: the two platforms' hex encoders answer the same for the same bytes, and the
    /// lowercase-hex guard refuses the non-ASCII digits a Unicode-aware classification
    /// would accept. The vector is written from the encoding rule, not read out of
    /// either implementation, and SpfnSocialNonceTest.kt asserts the same values.
    func test_P9_hexEncodingAndTheAsciiGuardMatchTheSharedVector() throws
    {
        XCTAssertEqual(SPFNSocialNonce.hex([]), "")
        XCTAssertEqual(SPFNSocialNonce.hex([0x00]), "00")
        XCTAssertEqual(SPFNSocialNonce.hex([0x0F]), "0f")
        XCTAssertEqual(SPFNSocialNonce.hex([0x10]), "10")
        XCTAssertEqual(SPFNSocialNonce.hex([0x7F, 0x80]), "7f80")
        XCTAssertEqual(SPFNSocialNonce.hex([0xFF, 0x00, 0xAB]), "ff00ab")

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
