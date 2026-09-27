// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev (app contract)
// documentSha256:  9aaec69a8080fdf8f45188c89b1cf1128a0935bf7ae7636fb67e713c0dbf586f
// documentVersion: 1
// operations:      getItem, listItems, putItemNote
//
// Regenerate with the consumer's spfnAppContractGenerate run. Its spfnAppContractVerify
// fails the build when the document changed or this file was edited.

import SPFNCore

/// The calls and types generated from this app's contract document.
///
/// Hand a call to `SPFNClient.execute`: the SDK signs, sends, retries and classifies
/// failures exactly as it does for its own operations.
public enum FixtureAPI
{
    /// One path parameter as one path segment: RFC 3986 unreserved bytes as they are,
    /// every other UTF-8 byte as %XX. A value that is exactly `.` or `..` is encoded
    /// whole, so path normalisation cannot turn it into a step up the path.
    static func pathSegment(_ value: String) -> String
    {
        if value == "." || value == ".."
        {
            return String(repeating: "%2E", count: value.count)
        }
        return percentEncoded(value)
    }

    /// `?name=value&…` in the order given, with nil values left out; empty when all are nil.
    static func queryString(_ pairs: [(String, String?)]) -> String
    {
        let fields = pairs.compactMap { pair in pair.1.map { pair.0 + "=" + percentEncoded($0) } }
        return fields.isEmpty ? "" : "?" + fields.joined(separator: "&")
    }

    static func percentEncoded(_ value: String) -> String
    {
        let hex = Array("0123456789ABCDEF".utf8)
        var bytes: [UInt8] = []
        for byte in value.utf8
        {
            if isUnreserved(byte)
            {
                bytes.append(byte)
            }
            else
            {
                bytes.append(contentsOf: [UInt8(ascii: "%"), hex[Int(byte >> 4)], hex[Int(byte & 0x0F)]])
            }
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    static func isUnreserved(_ byte: UInt8) -> Bool
    {
        switch byte
        {
        case UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "0")...UInt8(ascii: "9"):
            return true
        case UInt8(ascii: "-"), UInt8(ascii: "."), UInt8(ascii: "_"), UInt8(ascii: "~"):
            return true
        default:
            return false
        }
    }

    /// A required key's value, which may be null; its absence is a decoding failure,
    /// because the server promised the key (a required, nullable property).
    static func present(_ members: [String: SPFNCanonicalValue], _ key: String, at path: String) throws -> SPFNCanonicalValue
    {
        guard let value = members[key]
        else
        {
            throw SPFNDecodingError.missingField(path: path)
        }
        return value
    }

    /// Absent and null alike as nil, for an optional or nullable property.
    static func nonNull(_ value: SPFNCanonicalValue?) -> SPFNCanonicalValue?
    {
        guard let value, value != .null
        else
        {
            return nil
        }
        return value
    }
}
