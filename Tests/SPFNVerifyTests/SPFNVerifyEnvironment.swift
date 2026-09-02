// SPFN Mobile — what the real-server suite needs from outside itself.
//
// This suite runs against a scaffolded SPFN app — the published @spfn/auth on a real
// PostgreSQL — not against tools/reference-server. `sh tools/verify-server/run.sh`
// starts that app and exports the four variables below.
//
// Without the base URL the suite skips, loudly. A skipped XCTest is a green XCTest, so
// the skip is not the safety: every case that really ran writes a receipt, and run.sh
// turns a missing receipt into a failure.

import Foundation
import XCTest

/// The four variables the runner exports, or the reason there are none.
struct SPFNVerifyEnvironment: Sendable
{
    let baseURL: String
    let email: String
    let password: String
    let receiptsDirectory: String

    /// The variable that decides whether this suite runs at all.
    static let baseURLVariable = "SPFN_VERIFY_SERVER_URL"

    static let emailVariable = "SPFN_VERIFY_EMAIL"

    static let passwordVariable = "SPFN_VERIFY_PASSWORD"

    static let receiptsVariable = "SPFN_INTEGRATION_RECEIPTS"

    /// The environment, or a skip that has already announced itself.
    static func current() throws -> SPFNVerifyEnvironment
    {
        let environment = ProcessInfo.processInfo.environment

        guard let baseURL = environment[baseURLVariable], !baseURL.isEmpty
        else
        {
            let reason = "SPFN verify suite SKIPPED: \(baseURLVariable) is not set, so no real server was contacted."
            // Printed as well as thrown: XCTSkip is reported as success by every runner
            // this suite will meet, so a plain log only shows the skip when it is said
            // out loud.
            print(reason)
            throw XCTSkip(reason)
        }

        // Missing credentials are not a skip. The server is there, so running without
        // the seeded account would skip every case while the suite reports green.
        guard let email = environment[emailVariable], !email.isEmpty
        else
        {
            throw SPFNVerifyFailure.missing(emailVariable)
        }

        guard let password = environment[passwordVariable], !password.isEmpty
        else
        {
            throw SPFNVerifyFailure.missing(passwordVariable)
        }

        guard let receipts = environment[receiptsVariable], !receipts.isEmpty
        else
        {
            throw SPFNVerifyFailure.missing(receiptsVariable)
        }

        return SPFNVerifyEnvironment(
            baseURL: baseURL,
            email: email,
            password: password,
            receiptsDirectory: receipts
        )
    }

    /// Whether a case already recorded its receipt.
    ///
    /// The receipts are the only evidence one case has that another already ran: the
    /// cases share no state, and XCTest gives none of them a place to read the others'
    /// results from. See `approverTask` for the ordering this is asked about.
    func hasReceipt(_ name: String) -> Bool
    {
        FileManager.default.fileExists(
            atPath: URL(fileURLWithPath: receiptsDirectory, isDirectory: true)
                .appendingPathComponent(name).path
        )
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

enum SPFNVerifyFailure: Error, CustomStringConvertible
{
    case missing(String)

    var description: String
    {
        switch self
        {
        case .missing(let variable):
            return "\(variable) is not set, and the verify suite refuses to run half of itself"
        }
    }
}
