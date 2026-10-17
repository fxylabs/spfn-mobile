// SPFN Mobile — one operation with the codecs that turn its types into contract bytes.
//
// In core rather than beside the client, because the generated module is where the
// per-operation values live and it depends on core alone. The type itself needs nothing
// the client owns: an operation, a canonical value, and the unit answer a bodyless
// operation gives back — all three are here.
//
// android/spfn-core/.../SpfnCall.kt is the same type in Kotlin.

/// One operation with the two functions that turn its types into contract bytes.
///
/// The client is generic over request and response rather than knowing any operation, so
/// the per-operation values that fill this in are generated — `SPFNGeneratedCalls` — with
/// neither this file nor the execute path changing. Keeping the operation inside the
/// descriptor is what stops a caller pairing `echo.send` with the codec for `items.list`.
public struct SPFNCall<Request: Sendable, Response: Sendable>: Sendable
{
    public let operation: SPFNOperation

    /// The query, as `(name, value)` pairs in the order they are sent; a `nil` value is
    /// left out of the URL rather than sent as `name=`.
    ///
    /// Beside the operation rather than inside its path, because the two go different
    /// places: the proof signs `operation.path` alone — the server verifies the path it
    /// routed, without its query — while the request is sent to `pathAndQuery`. A query
    /// folded into the path would be signed by the client and not by the server.
    public let query: [(String, String?)]

    /// The request body as a canonical value, or `nil` for an operation the contract
    /// declares no request type for.
    ///
    /// `nil` is not an empty object. It means the request carries no body at all — no
    /// bytes and no `content-type` — and its proof signs the absent-body digest, the
    /// bundle's `clientProofV1.proofInput.bodySha256`: "the literal string of 64 zero
    /// characters when an operation has no body". A declared body that happens to be
    /// empty still encodes as `{}` and is digested like any other body.
    public let encode: @Sendable (Request) throws -> SPFNCanonicalValue?
    public let decode: @Sendable (SPFNCanonicalValue) throws -> Response

    public init(
        operation: SPFNOperation,
        query: [(String, String?)] = [],
        encode: @escaping @Sendable (Request) throws -> SPFNCanonicalValue?,
        decode: @escaping @Sendable (SPFNCanonicalValue) throws -> Response
    )
    {
        self.operation = operation
        self.query = query
        self.encode = encode
        self.decode = decode
    }

    /// What the request is sent to: `operation.path`, then `?name=value&…` when any query
    /// value is present. Each value is percent-encoded — RFC 3986 unreserved bytes as they
    /// are, every other UTF-8 byte as `%XX` — so a value cannot add a field of its own.
    public var pathAndQuery: String
    {
        let fields = query.compactMap { name, value in value.map { name + "=" + percentEncoded($0) } }
        return fields.isEmpty ? operation.path : operation.path + "?" + fields.joined(separator: "&")
    }
}

private func percentEncoded(_ value: String) -> String
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

private func isUnreserved(_ byte: UInt8) -> Bool
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

extension SPFNCall where Response == SPFNNoResponse
{
    /// A call on an operation the contract declares no response type for.
    ///
    /// The decoder is fixed here rather than left to the caller. There is nothing to
    /// decode, so the only correct decoder is the one that answers with the unit value,
    /// and writing that closure at each call site is how one of them ends up different.
    public static func noResponse(
        operation: SPFNOperation,
        query: [(String, String?)] = [],
        encode: @escaping @Sendable (Request) throws -> SPFNCanonicalValue?
    ) -> SPFNCall<Request, SPFNNoResponse>
    {
        SPFNCall(operation: operation, query: query, encode: encode, decode: { _ in SPFNNoResponse.value })
    }
}
