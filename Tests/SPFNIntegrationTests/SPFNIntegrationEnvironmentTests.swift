// SPFN Mobile — what the Swift suite accepts as a control token.
//
// The only case in this target that needs no server, deliberately: the question is what
// the suite refuses before it reaches one, and a run that reached a server was already
// given something usable. So this runs under `swift test` with nothing else present, which
// is where a rule about refusing bad input belongs.
//
// The token ends up in a header field value. A colon, a space or a line break there is not
// a bad token — it is a second header field, in a request nobody wrote.

import Foundation
import XCTest

final class SPFNIntegrationEnvironmentTests: XCTestCase
{
    func testHexTokenIsAccepted() throws
    {
        try SPFNIntegrationEnvironment.validateControlToken("0f1e2d3c4b5a6978")
    }

    /// The three punctuation marks the set allows, so the rule is not "hex only" by accident.
    func testDotUnderscoreAndHyphenAreAccepted() throws
    {
        try SPFNIntegrationEnvironment.validateControlToken("spfn.control_token-0001")
    }

    func testColonIsRefused()
    {
        assertRefused("token:with-a-colon")
    }

    func testLineBreakIsRefused()
    {
        assertRefused("token\nx-spfn-reference-control: forged")
    }

    func testSpaceIsRefused()
    {
        assertRefused("two words")
    }

    func testNonASCIIIsRefused()
    {
        assertRefused("token-é")
    }

    private func assertRefused(_ token: String, file: StaticString = #filePath, line: UInt = #line)
    {
        XCTAssertThrowsError(try SPFNIntegrationEnvironment.validateControlToken(token), file: file, line: line)
        {
            error in
            guard case SPFNIntegrationFailure.malformedToken = error
            else
            {
                XCTFail("expected a malformed token failure, got \(error)", file: file, line: line)
                return
            }
        }
    }
}
