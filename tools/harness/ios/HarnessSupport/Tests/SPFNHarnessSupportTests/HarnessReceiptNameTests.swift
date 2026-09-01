// SPFN Mobile — what a receipt is called, and why two of them cannot be one.
//
// Every expected value here is written from the shared spec's file-name pattern by hand.
// Nothing asks `HarnessReceipt` what it produces and then asserts that it produced it,
// which is the failure the registry's P10 row describes: a table copied from the subject
// it is meant to judge is green and is not evidence.
//
// The spec's pattern:
//
//     receipt-<provider>-<case>-<epochMillis>.json
//
// It used to say `<epochSeconds>`, and the change is the point of the first test below:
// two attempts at the same case that finished inside one second collided on a name, and
// the second write destroyed the first attempt's evidence.

import Foundation
import XCTest

@testable import SPFNHarnessSupport

final class HarnessReceiptNameTests: XCTestCase
{
    /// One known instant, one name written out by hand from the spec's pattern.
    ///
    /// 1_767_225_600.123 seconds after the epoch is 1_767_225_600_123 milliseconds after
    /// it, so the name below is arithmetic anyone can redo, not a value read off a run.
    func testFileNameIsTheSpecPatternInMilliseconds()
    {
        let receipt = Self.receipt(
            provider: .google,
            deviceCase: .firstEnroll,
            at: Date(timeIntervalSince1970: 1_767_225_600.123)
        )

        XCTAssertEqual(receipt.fileName, "receipt-google-first-enroll-1767225600123.json")
    }

    /// The correction itself. Two attempts one millisecond apart must be two files.
    ///
    /// The second assertion is what gives the first one teeth: at the granularity this
    /// pattern used to have, these two names were equal. A test that only checked
    /// "the names differ" would also have passed against a nanosecond stamp, a counter,
    /// or a UUID — none of which is what the spec now says.
    func testTwoAttemptsOneMillisecondApartGetDifferentNames()
    {
        let first = Self.receipt(at: Date(timeIntervalSince1970: 1_767_225_600.001))
        let second = Self.receipt(at: Date(timeIntervalSince1970: 1_767_225_600.002))

        XCTAssertNotEqual(first.fileName, second.fileName)
        XCTAssertEqual(first.fileName, "receipt-apple-user-cancel-1767225600001.json")
        XCTAssertEqual(second.fileName, "receipt-apple-user-cancel-1767225600002.json")
    }

    /// The same two attempts, written for real: two files on disk, neither overwritten.
    ///
    /// The dates are fixed rather than taken from the clock. A test that wrote twice as
    /// fast as it could would be asserting on how fast this machine is, and would go red
    /// on a slow one and green on a fast one for reasons that have nothing to do with the
    /// rule under test.
    func testTwoRapidWritesBothSurvive() throws
    {
        let directory = try Self.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }

        let first = try Self.receipt(at: Date(timeIntervalSince1970: 1_767_225_600.001))
            .write(into: directory)
        let second = try Self.receipt(at: Date(timeIntervalSince1970: 1_767_225_600.002))
            .write(into: directory)

        XCTAssertNotEqual(first, second)
        let written = try FileManager.default.contentsOfDirectory(atPath: directory.path).sorted()
        XCTAssertEqual(
            written,
            [
                "receipt-apple-user-cancel-1767225600001.json",
                "receipt-apple-user-cancel-1767225600002.json",
            ]
        )
    }

    /// Rounded, not truncated. A time a hair under a millisecond boundary belongs to the
    /// millisecond it is nearest, which is what keeps two stamps 1 ms apart 1 ms apart.
    func testMillisecondsAreRoundedRatherThanTruncated()
    {
        XCTAssertEqual(HarnessReceipt.epochMillis(Date(timeIntervalSince1970: 1.0006)), 1001)
        XCTAssertEqual(HarnessReceipt.epochMillis(Date(timeIntervalSince1970: 1.0004)), 1000)
    }

    /// Every case and provider name reaches the file name unchanged, in lowercase ASCII.
    /// Written from the spec's own two lists rather than from `allCases`, so a case
    /// renamed in code and not in the spec fails here instead of renaming the expectation.
    func testEveryCaseAndProviderNameIsTheSpecsOwn()
    {
        let names = HarnessDeviceCase.allCases.map(\.rawValue)
        XCTAssertEqual(
            names,
            ["first-enroll", "re-login", "user-cancel", "network-failure", "server-reject"]
        )
        XCTAssertEqual(HarnessProvider.allCases.map(\.rawValue), ["apple", "google"])
    }

    // MARK: - Fixtures

    private static func receipt(
        provider: HarnessProvider = .apple,
        deviceCase: HarnessDeviceCase = .userCancel,
        at date: Date
    ) -> HarnessReceipt
    {
        HarnessReceipt(
            provider: provider,
            deviceCase: deviceCase,
            outcome: .cancelled,
            responseCode: nil,
            errorCode: "apple:cancelled",
            isNewUser: false,
            keyIDMatch: false,
            keyRemainsAfterFailure: false,
            serverBaseURL: "http://192.0.2.10:8790",
            serverCommit: nil,
            recordedAt: date
        )
    }

    private static func temporaryDirectory() throws -> URL
    {
        let url = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
            .appendingPathComponent("spfn-harness-receipts-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
