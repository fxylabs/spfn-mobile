// SPFN Mobile — what the Swift integration suite needs from outside itself.
//
// This suite is the only one in the repository that cannot run on its own: it needs a
// reference server on the other end of a socket. `sh tools/reference-server/run-integration.sh`
// starts one and exports the three variables below.
//
// Without them the suite skips — and says so, loudly, on standard output. A skipped XCTest
// is a green XCTest, so the skip is not the safety here: the receipt is. Every case that
// really ran writes a file the runner checks afterwards, and a suite that skipped
// everything leaves an empty directory that the runner turns into a failure.

import Foundation
import XCTest

/// The three variables the runner exports, or the reason there are none.
struct SPFNIntegrationEnvironment: Sendable
{
    let baseURL: String
    let controlToken: String
    let receiptsDirectory: String

    /// The variable that decides whether this suite runs at all.
    static let baseURLVariable = "SPFN_REFERENCE_SERVER_URL"

    static let controlTokenVariable = "SPFN_REFERENCE_CONTROL_TOKEN"

    static let receiptsVariable = "SPFN_INTEGRATION_RECEIPTS"

    /// The environment, or a skip that has already announced itself.
    static func current() throws -> SPFNIntegrationEnvironment
    {
        let environment = ProcessInfo.processInfo.environment

        guard let baseURL = environment[baseURLVariable], !baseURL.isEmpty
        else
        {
            let reason = "SPFN integration suite SKIPPED: \(baseURLVariable) is not set, so no reference server was contacted."
            // Printed as well as thrown. XCTSkip is reported as success by every runner
            // this suite will meet, so the only thing that makes the skip visible in a
            // plain log is saying it out loud.
            print(reason)
            throw XCTSkip(reason)
        }

        guard let controlToken = environment[controlTokenVariable], !controlToken.isEmpty
        else
        {
            // A missing token is not a skip. The server is there, so running the suite
            // without the ability to revoke or expire anything would quietly drop three
            // of the five cases while reporting five passes.
            throw SPFNIntegrationFailure.missing(controlTokenVariable)
        }

        try validateControlToken(controlToken)

        guard let receipts = environment[receiptsVariable], !receipts.isEmpty
        else
        {
            throw SPFNIntegrationFailure.missing(receiptsVariable)
        }

        return SPFNIntegrationEnvironment(
            baseURL: baseURL,
            controlToken: controlToken,
            receiptsDirectory: receipts
        )
    }

    /// What a control token is allowed to be made of.
    ///
    /// Every token this repository generates is hex, so the set is wider than anything that
    /// has to pass it. It is narrow on purpose: the token is written into a header field
    /// value, and a colon, a space or a line break there is not a bad token but a second
    /// header field, which is a request nobody wrote. Non-ASCII is refused for the same
    /// reason — a header field value has no encoding to declare it in.
    ///
    /// `tools/reference-server/run-integration.sh` enforces the same set before it runs
    /// anything, and `SpfnReferenceTarget.kt` does for the Android suite. Three enforcers,
    /// one set: change it in all three or in none.
    static let tokenCharacters = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZ")
        .union(CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz"))
        .union(CharacterSet(charactersIn: "0123456789._-"))

    static let tokenCharactersDescription = "A-Z a-z 0-9 . _ -"

    /// Refuses a token this suite cannot put in a header field without changing the request.
    ///
    /// The variable is read here rather than only in the runner because `swift test` takes
    /// the same environment directly, and a rule the runner enforces alone is a rule that
    /// holds until somebody runs the suite the other way.
    static func validateControlToken(_ token: String) throws
    {
        guard token.unicodeScalars.allSatisfy({ tokenCharacters.contains($0) })
        else
        {
            throw SPFNIntegrationFailure.malformedToken(controlTokenVariable)
        }
    }

    /// Records that one case really ran. See the file comment for why this exists.
    func record(_ name: String) throws
    {
        let directory = URL(fileURLWithPath: receiptsDirectory, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try "\(name)\n".write(
            to: directory.appendingPathComponent(name),
            atomically: true,
            encoding: .utf8
        )
    }
}

enum SPFNIntegrationFailure: Error, CustomStringConvertible
{
    case missing(String)
    case malformedToken(String)
    case control(String)

    var description: String
    {
        switch self
        {
        case .missing(let variable):
            return "\(variable) is not set, and the integration suite refuses to run half of itself"
        case .malformedToken(let variable):
            return "\(variable) holds characters that cannot be carried in an HTTP header field "
                + "value; allowed: \(SPFNIntegrationEnvironment.tokenCharactersDescription)"
        case .control(let reason):
            return "the reference server's control surface refused: \(reason)"
        }
    }
}
