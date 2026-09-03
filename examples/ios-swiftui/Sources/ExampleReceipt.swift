// SPFN Mobile — what one cell run left behind.
//
// Counterpart of
// examples/android-compose/src/main/kotlin/xyz/superfunction/spfn/example/ExampleReceipt.kt,
// and, like it, a copy of tools/harness's receipt pattern rather than a shared helper: the
// two apps write different receipts about different things into different directories, and
// coupling them would make one app's evidence format a constraint on the other's.
//
// The JSON is assembled by hand so that every field which reaches the file is named in
// this type. **Nothing here is a credential.** A cell id, a fixture name, a stack depth
// and the two versions this build is — this app never enrols and never holds a key.

import Foundation

struct ExampleReceipt: Sendable
{
    static let schema = "spfn-ui-cell-receipt/1"
    static let platform = "ios"

    /// The cell the launch named, or `none` when it named nothing this app knows.
    let cell: String

    /// The seeding that cell ran under, or `none`.
    let fixture: String

    /// How deep the flow's stack was when the receipt was written.
    let stackDepth: Int

    let timestampMillis: Int64
    let sdkVersion: String
    let contractVersion: String

    /// `receipt-<cell>-<epochMillis>.json`.
    ///
    /// Milliseconds, not seconds, for the reason the harness records: two runs of one cell
    /// that finished inside a second shared a name, and the second destroyed the first's
    /// evidence — a failure mode where the more you run, the less you have.
    var fileName: String
    {
        "receipt-\(cell)-\(timestampMillis).json"
    }

    /// The receipt as JSON, in a fixed field order this type is the whole list of.
    func toJSON() -> String
    {
        var out = "{\n"
        out += field("schema", Self.schema, last: false)
        out += field("platform", Self.platform, last: false)
        out += field("cell", cell, last: false)
        out += field("fixture", fixture, last: false)
        out += "  \"stackDepth\": \(stackDepth),\n"
        out += field("timestamp", Self.timestamp(timestampMillis), last: false)
        out += field("sdkVersion", sdkVersion, last: false)
        out += field("contractVersion", contractVersion, last: true)
        out += "}\n"
        return out
    }

    private func field(_ name: String, _ value: String, last: Bool) -> String
    {
        "  \"\(name)\": " + Self.jsonString(value) + (last ? "\n" : ",\n")
    }

    /// The run's instant, ISO-8601 in UTC.
    ///
    /// An explicit UTC zone and `Locale(identifier: "en_US_POSIX")`, both load-bearing: a
    /// default locale can carry a non-Gregorian calendar and can render digits in a
    /// non-ASCII script, and neither is a receipt anything can read
    /// (docs/IMPLEMENTATION-PITFALLS.md P9).
    static func timestamp(_ millis: Int64) -> String
    {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    /// One JSON string literal, escaped to printable ASCII.
    static func jsonString(_ value: String) -> String
    {
        var out = "\""
        for scalar in value.unicodeScalars
        {
            switch scalar
            {
            case "\"":
                out += "\\\""
            case "\\":
                out += "\\\\"
            case let printable where printable.value >= 0x20 && printable.value <= 0x7e:
                out.unicodeScalars.append(printable)
            default:
                out += unicodeEscape(scalar.value)
            }
        }
        return out + "\""
    }

    /// Hex digits as data, so no formatter and therefore no locale is involved (P9).
    private static func unicodeEscape(_ value: UInt32) -> String
    {
        let digits = Array("0123456789abcdef")
        var out = "\\u"
        for shift in stride(from: 12, through: 0, by: -4)
        {
            out.append(digits[Int((value >> UInt32(shift)) & 0xf)])
        }
        return out
    }
}

/// Where receipts land, and what happens when they cannot.
///
/// A `receipts` subdirectory of the app's Documents directory, which is what
/// `UIFileSharingEnabled` exposes to Finder and to the Files app. Its own subdirectory,
/// and a different bundle id from the harness's, so neither run can be mistaken for the
/// other's evidence.
struct ExampleReceiptStore: Sendable
{
    /// Writes one receipt and answers its file name.
    ///
    /// A failure to write is thrown, never swallowed: a run whose receipt silently did not
    /// appear is indistinguishable from a run that never happened (P7).
    func write(_ receipt: ExampleReceipt) throws -> String
    {
        let documents = try FileManager.default.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = documents.appendingPathComponent("receipts", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent(receipt.fileName)
        try receipt.toJSON().write(to: file, atomically: true, encoding: .utf8)
        return receipt.fileName
    }
}
