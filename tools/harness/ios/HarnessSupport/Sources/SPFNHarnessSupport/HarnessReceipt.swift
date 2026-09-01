// SPFN Mobile — the one thing a device run leaves behind.
//
// A person taps, a sheet appears, a server answers, and then the run is over and the
// only witness is whoever was holding the phone. So every attempt writes a JSON file to
// the app's Documents directory, which project.yml exposes to Finder and to the Files
// app. That file is the evidence; the screen is a convenience.
//
// The schema is the shared spec's, written out by hand from it rather than derived from
// anything this app does — the Android half writes the same fields from the same
// document and neither reads the other (P10). Field names, the file name pattern and
// the five case names all come from there verbatim.
//
// **No receipt may carry a token, an email address or a name.** That is not a rule this
// file follows, it is a rule its shape enforces: the fields below are booleans, integers,
// a timestamp, an SDK-classified error name and two version constants. There is no field
// a token could be placed in, and nothing here is handed a provider's message text — the
// adapters keep numeric codes and drop the text before this file ever sees an error. The
// one value that does arrive from the network — `serverCommit` — is filtered by
// `HarnessServerCommit` before it reaches this type at all.

import Foundation
import SPFNCore
import SPFNGenerated

/// What happened, in the spec's three words.
public enum HarnessReceiptOutcome: String, Sendable
{
    case enrolled
    case cancelled
    case failed
}

/// What the harness itself refuses while writing evidence, as opposed to what the SDK
/// refuses while earning it.
public enum HarnessReceiptError: Error, Equatable, Sendable
{
    /// No Documents directory, so the receipt has nowhere to go. Thrown rather than
    /// swallowed: a receipt that could not be written is not a case that did not run.
    case noDocumentsDirectory
}

public struct HarnessReceipt: Sendable
{
    public static let schema = "spfn-device-receipt/1"

    public let provider: HarnessProvider
    public let deviceCase: HarnessDeviceCase
    public let outcome: HarnessReceiptOutcome

    /// The status of the last response the transport actually saw, or nil when no
    /// response existed — which is itself the answer for the network-failure cell.
    public let responseCode: Int?

    /// The SDK's own classification, never a translation of it, and nil on success.
    public let errorCode: String?

    public let isNewUser: Bool
    public let keyIDMatch: Bool
    public let keyRemainsAfterFailure: Bool
    public let serverBaseURL: String

    /// Already filtered — see `HarnessServerCommit.accepted`. Nothing else may be put here.
    public let serverCommit: String?

    public let recordedAt: Date

    public init(
        provider: HarnessProvider,
        deviceCase: HarnessDeviceCase,
        outcome: HarnessReceiptOutcome,
        responseCode: Int?,
        errorCode: String?,
        isNewUser: Bool,
        keyIDMatch: Bool,
        keyRemainsAfterFailure: Bool,
        serverBaseURL: String,
        serverCommit: String?,
        recordedAt: Date
    )
    {
        self.provider = provider
        self.deviceCase = deviceCase
        self.outcome = outcome
        self.responseCode = responseCode
        self.errorCode = errorCode
        self.isNewUser = isNewUser
        self.keyIDMatch = keyIDMatch
        self.keyRemainsAfterFailure = keyRemainsAfterFailure
        self.serverBaseURL = serverBaseURL
        self.serverCommit = serverCommit
        self.recordedAt = recordedAt
    }

    /// The file this receipt belongs in. Lowercase ASCII and decimal digits only, so the
    /// name is the same on any device in any locale.
    ///
    /// Milliseconds since the epoch, which is the shared spec's granularity. It was whole
    /// seconds, and it changed because two attempts at the same case that finished inside
    /// one second landed on one name and the second write destroyed the first attempt's
    /// evidence — a cancelled sheet takes well under a second to dismiss twice.
    public var fileName: String
    {
        "receipt-\(provider.rawValue)-\(deviceCase.rawValue)-\(Self.epochMillis(recordedAt)).json"
    }

    /// Whole milliseconds, rounded rather than truncated, so a time a hair under a
    /// boundary and a time a hair over it do not both belong to the lower millisecond.
    /// `Int64` because a Double large enough to be a timestamp has already lost the
    /// sub-millisecond digits it would need to be trusted as one.
    static func epochMillis(_ date: Date) -> Int64
    {
        Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    /// The receipt as the spec's object. `NSNull` rather than an absent key: a reader
    /// that finds no `errorCode` cannot tell a success from a writer that forgot.
    private var body: [String: Any]
    {
        [
            "schema": Self.schema,
            "provider": provider.rawValue,
            "platform": "ios",
            "case": deviceCase.rawValue,
            "outcome": outcome.rawValue,
            "responseCode": responseCode ?? NSNull(),
            "errorCode": errorCode ?? NSNull(),
            "isNewUser": isNewUser,
            "keyIdMatch": keyIDMatch,
            "keyRemainsAfterFailure": keyRemainsAfterFailure,
            "timestamp": Self.timestamp(recordedAt),
            "serverBaseURL": serverBaseURL,
            "serverCommit": serverCommit ?? NSNull(),
            "sdkVersion": SPFNVersion.current,
            "contractVersion": SPFNGeneratedContract.binding.importedVersion,
        ]
    }

    /// ISO-8601 in UTC. `ISO8601DateFormatter` is fixed-format by definition and reads no
    /// locale, which a `DateFormatter` with a pattern would have done — and a receipt
    /// whose timestamp changed shape with the phone's region would be a receipt no
    /// assertion could read.
    private static func timestamp(_ date: Date) -> String
    {
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: date)
    }

    /// Writes the receipt and answers where it went.
    ///
    /// Every failure throws. A receipt that could not be written must not read as a run
    /// that produced no receipt: the first is a broken harness and the second is a case
    /// that was never run, and the screen has to be able to say which one happened.
    @discardableResult
    public func write(into directory: URL? = nil) throws -> URL
    {
        let folder = try directory ?? Self.documentsDirectory()
        let destination = folder.appendingPathComponent(fileName, isDirectory: false)
        let data = try JSONSerialization.data(
            withJSONObject: body,
            options: [.prettyPrinted, .sortedKeys]
        )
        try data.write(to: destination, options: .atomic)
        return destination
    }

    private static func documentsDirectory() throws -> URL
    {
        guard let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
        else
        {
            throw HarnessReceiptError.noDocumentsDirectory
        }
        return url
    }
}
