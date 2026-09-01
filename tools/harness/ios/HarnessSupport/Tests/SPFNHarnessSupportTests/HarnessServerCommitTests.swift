// SPFN Mobile — what a server may put in a receipt, and everything it may not.
//
// The rule is the shared spec's: keep the header value only when it matches
// `^[0-9a-f]{7,40}$` after lowercasing, else null. The vectors below are written from
// that pattern — its two length boundaries, its alphabet, and the kinds of string a
// header can carry when the thing at the other end is not what anyone expected (P10).
//
// The rejections matter more than the acceptances. `serverCommit` is the only field in
// the receipt whose value arrives from the network, so it is the only place PII could
// enter a file that leaves the phone.

import XCTest

@testable import SPFNHarnessSupport

final class HarnessServerCommitTests: XCTestCase
{
    /// A real full-length hash and a real abbreviated one, kept verbatim.
    func testAcceptsCommitHashes()
    {
        XCTAssertEqual(
            HarnessServerCommit.accepted("8becd1636f2a4bd0f1e2f0a9c7d5b3e19a4c6d70"),
            "8becd1636f2a4bd0f1e2f0a9c7d5b3e19a4c6d70"
        )
        XCTAssertEqual(HarnessServerCommit.accepted("8becd16"), "8becd16")
    }

    /// The two boundaries the pattern names, and the two values just outside them.
    func testLengthBoundsAreSevenAndForty()
    {
        XCTAssertNil(HarnessServerCommit.accepted(String(repeating: "a", count: 6)))
        XCTAssertEqual(
            HarnessServerCommit.accepted(String(repeating: "a", count: 7)),
            String(repeating: "a", count: 7)
        )
        XCTAssertEqual(
            HarnessServerCommit.accepted(String(repeating: "a", count: 40)),
            String(repeating: "a", count: 40)
        )
        XCTAssertNil(HarnessServerCommit.accepted(String(repeating: "a", count: 41)))
    }

    /// Uppercase hex is the same hash written differently, so it is kept — lowercased,
    /// which is what the receipt records and what the pattern is stated in.
    func testUppercaseHexIsLowercased()
    {
        XCTAssertEqual(HarnessServerCommit.accepted("8BECD16"), "8becd16")
        XCTAssertEqual(HarnessServerCommit.accepted("DEADBEEF"), "deadbeef")
    }

    /// Everything outside the hex alphabet is dropped, whatever it looks like. The last
    /// three are the reason this filter exists: a header can carry an address, a name or
    /// a whole sentence, and none of them belongs in a file that leaves the phone.
    func testRejectsAnythingThatIsNotHex()
    {
        for value in [
            "8becd16-dirty",
            "release-2026-09",
            "g8becd16",
            "8becd 16",
            "0.1.0-alpha.3",
            "rayim@example.com",
            "Hoon Lim",
            "built by hoon on macbook",
        ]
        {
            XCTAssertNil(HarnessServerCommit.accepted(value), "kept a non-hex value: \(value)")
        }
    }

    /// An absent header and an empty one are the same answer, and neither is a hash.
    func testRejectsAbsentAndEmpty()
    {
        XCTAssertNil(HarnessServerCommit.accepted(nil))
        XCTAssertNil(HarnessServerCommit.accepted(""))
    }

    /// A non-ASCII digit is not a hex digit, however much it looks like one. Written
    /// because the same class of split — a character that a Unicode-aware classifier
    /// accepts and an ASCII range does not — is the registry's P9 row, and this filter is
    /// one of the two places in the harness where a character class decides something.
    func testRejectsNonASCIIDigitsThatLookLikeHex()
    {
        // Full-width and Arabic-Indic digits, which `Character.isNumber` would accept.
        XCTAssertNil(HarnessServerCommit.accepted("８becd16"))
        XCTAssertNil(HarnessServerCommit.accepted("٨becd16"))
        // A Cyrillic 'а' is not the ASCII 'a' the alphabet means.
        XCTAssertNil(HarnessServerCommit.accepted("8becd1а"))
    }

    /// The filter's answer is what the receipt carries, so the two cannot drift.
    func testAReceiptCarriesOnlyWhatTheFilterAccepted() throws
    {
        let kept = HarnessServerCommit.accepted("8BECD16")
        XCTAssertEqual(kept, "8becd16")

        let dropped = HarnessServerCommit.accepted("rayim@example.com")
        XCTAssertNil(dropped)
    }
}
