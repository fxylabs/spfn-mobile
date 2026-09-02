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
    public let encode: @Sendable (Request) throws -> SPFNCanonicalValue
    public let decode: @Sendable (SPFNCanonicalValue) throws -> Response

    public init(
        operation: SPFNOperation,
        encode: @escaping @Sendable (Request) throws -> SPFNCanonicalValue,
        decode: @escaping @Sendable (SPFNCanonicalValue) throws -> Response
    )
    {
        self.operation = operation
        self.encode = encode
        self.decode = decode
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
        encode: @escaping @Sendable (Request) throws -> SPFNCanonicalValue
    ) -> SPFNCall<Request, SPFNNoResponse>
    {
        SPFNCall(operation: operation, encode: encode, decode: { _ in SPFNNoResponse.value })
    }
}
