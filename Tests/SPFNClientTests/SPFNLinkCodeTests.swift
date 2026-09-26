// SPFN Mobile — reading a link code off a typed entry or a scanned URL.
//
// One table, and SpfnLinkCodeTest.kt holds the same rows in the same order. It is not a
// file under Contracts/fixtures: that directory holds only vectors a third implementation
// derived from the contract text, and these rules are this SDK's own, not the contract's.
// So the two copies are kept equal by hand, and a row added to one is added to the other.

import XCTest
import SPFNClient

final class SPFNLinkCodeTests: XCTestCase
{
    private static let allowedHosts: Set<String> = ["link.example.com", "xn--bcher-kva.example"]

    /// Input, what `parse` answers, and why.
    private static let table: [(String, String?, String)] = [
        ("ABCD-EFGH", "ABCD-EFGH", "the code as a signed-in device shows it"),
        ("abcdefgh", "ABCD-EFGH", "lower case, no dash"),
        (" abcd efgh \n", "ABCD-EFGH", "spaces inside, whitespace around"),
        ("ab-cd-ef-gh", "ABCD-EFGH", "dashes anywhere"),
        ("ABCD-EFG", nil, "seven characters"),
        ("ABCD-EFGHJ", nil, "nine characters"),
        ("ABCD-EFG1", nil, "a character outside the alphabet"),
        ("ABCD-EFGß", nil, "a letter that upper-cases to two alphabet letters"),
        ("ABCD\u{2013}EFGH", nil, "an en dash is not a dash"),
        ("link.example.com/ABCD-EFGH", nil, "a URL without a scheme is not a typed code"),
        ("https://link.example.com/ABCD-EFGH", "ABCD-EFGH", "the code as the only segment"),
        ("https://link.example.com/l/abcd-efgh", "ABCD-EFGH", "the code as the last of several segments"),
        ("HTTPS://LINK.Example.COM/l/ABCDEFGH", "ABCD-EFGH", "scheme and host in mixed case"),
        ("https://xn--bcher-kva.example/ABCD-EFGH", "ABCD-EFGH", "an IDN host in its allowed xn-- form"),
        ("  https://link.example.com/ABCD-EFGH\n", "ABCD-EFGH", "whitespace around a URL"),
        ("http://link.example.com/ABCD-EFGH", nil, "http"),
        ("ftp://link.example.com/ABCD-EFGH", nil, "another scheme"),
        ("https://other.example.com/ABCD-EFGH", nil, "a host not allowed"),
        ("https://link.example.com.other.example/ABCD-EFGH", nil, "an allowed host as a prefix"),
        ("https://b\u{00fc}cher.example/ABCD-EFGH", nil, "an IDN host in Unicode"),
        ("https://user@link.example.com/ABCD-EFGH", nil, "userinfo"),
        ("https://link.example.com:443/ABCD-EFGH", nil, "a port"),
        ("https://link.example.com", nil, "no path"),
        ("https://link.example.com/ABCD-EFGH/extra", nil, "a segment after the code"),
        ("https://link.example.com/ABCD-EFGH/", nil, "a trailing slash"),
        ("https://link.example.com//ABCD-EFGH", nil, "an empty segment"),
        ("https://link.example.com/?code=ABCD-EFGH", nil, "the code only in the query"),
        ("https://link.example.com/ABCD-EFGH?ref=qr", nil, "a query after the code"),
        ("https://link.example.com/ABCD-EFGH#top", nil, "a fragment"),
        ("https://link.example.com/ABCD%2DEFGH", nil, "percent-encoding"),
        ("https://link.example.com\\ABCD-EFGH", nil, "a backslash for a slash"),
        ("https://link.example.com/ab cd-efgh", nil, "a space inside a URL"),
    ]

    func testEveryRowOfTheTable()
    {
        for (input, expected, why) in Self.table
        {
            XCTAssertEqual(SPFNLinkCode.parse(input, allowedHosts: Self.allowedHosts), expected, why)
        }
    }

    /// The allowed set is compared in lower case too, so an app that spells its host the
    /// way a person writes it is not locked out by its own spelling.
    func testAnAllowedHostIsComparedWithoutCase()
    {
        XCTAssertEqual(
            SPFNLinkCode.parse("https://link.example.com/ABCD-EFGH", allowedHosts: ["Link.Example.COM"]),
            "ABCD-EFGH"
        )
    }

    func testNoAllowedHostRefusesEveryURLAndStillReadsATypedCode()
    {
        XCTAssertNil(SPFNLinkCode.parse("https://link.example.com/ABCD-EFGH", allowedHosts: []))
        XCTAssertEqual(SPFNLinkCode.parse("abcd-efgh", allowedHosts: []), "ABCD-EFGH")
    }
}
