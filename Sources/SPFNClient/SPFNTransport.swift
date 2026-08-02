// SPFN Mobile — the transport boundary.
//
// One responsibility: send one HTTP request and return one HTTP response. It performs
// no retry, no re-handshake and no error classification. A 401 and a 500 are ordinary
// responses here; deciding what they mean belongs to the session and execute layers
// above this one, which is why this file has no vocabulary for either.
//
// android/spfn-client/.../SpfnTransport.kt is the same boundary in Kotlin. The two
// carry the same semantics under different idioms, and the two test suites use
// corresponding case names so the parity is checkable rather than asserted.

/// One outbound HTTP request, already fully assembled by the caller.
///
/// Headers are an ordered list rather than a dictionary on purpose: a proof is taken
/// over an exact request, and a dictionary silently reorders and deduplicates.
///
/// A repeated field name is refused before anything is sent. The two platforms' HTTP
/// stacks put different bytes on the wire for the same repeated name, so the layers above
/// assemble each header exactly once instead.
public struct SPFNTransportRequest: Sendable
{
    /// Uppercase HTTP method. Passed through verbatim; the transport never rewrites it.
    public let method: String

    /// Absolute request URL, including any query.
    public let url: String

    /// Header fields in the order they were assembled. A name may appear only once,
    /// compared without regard to case; a repeated name is refused before sending.
    public let headers: [(String, String)]

    /// The request body, or `nil` for a request that carries no body at all.
    ///
    /// `nil` and `[]` are different values and stay different: the proof layer digests an
    /// absent body differently from an empty one.
    public let body: [UInt8]?

    /// Deadline for the whole call, in milliseconds.
    public let timeoutMillis: Int64

    public init(
        method: String,
        url: String,
        headers: [(String, String)] = [],
        body: [UInt8]? = nil,
        timeoutMillis: Int64
    )
    {
        self.method = method
        self.url = url
        self.headers = headers
        self.body = body
        self.timeoutMillis = timeoutMillis
    }
}

/// One inbound HTTP response, unclassified.
///
/// A non-2xx status arrives here as a value, not as an error. `body` is always present
/// because an HTTP response always has one, possibly of zero length.
public struct SPFNTransportResponse: Sendable
{
    public let statusCode: Int
    public let headers: [(String, String)]
    public let body: [UInt8]

    public init(statusCode: Int, headers: [(String, String)], body: [UInt8])
    {
        self.statusCode = statusCode
        self.headers = headers
        self.body = body
    }
}

/// The only failures a transport reports. Everything the server actually answered is a
/// `SPFNTransportResponse`, so this list covers exactly the cases where no response exists.
public enum SPFNTransportError: Error, Equatable, Sendable
{
    /// The request never completed at the network level. The associated value names the
    /// failure class and never carries any part of the request.
    case connectivity(String)

    /// The call exceeded `timeoutMillis`. Kept distinct from `connectivity` because the
    /// layers above retry the two differently.
    case timedOut

    /// The surrounding `Task` was cancelled and the underlying call was cancelled with it.
    case cancelled

    /// A response arrived but was not a usable HTTP response.
    case invalidResponse(String)
}

/// Sends exactly one request. Implementations do not retry.
///
/// The layers above own retry, re-handshake and classification, so an implementation that
/// retried internally would make their accounting wrong.
public protocol SPFNTransport: Sendable
{
    func execute(_ request: SPFNTransportRequest) async throws -> SPFNTransportResponse
}

// MARK: - Redaction

// Header values carry proof material and session credentials, and bodies carry whatever
// the operation carries. Neither may reach a log, and the default reflection-based
// description of a struct prints both, so both types describe themselves by shape only.
// The URL is omitted too: a nonce can live in a query parameter.

extension SPFNTransportRequest: CustomStringConvertible, CustomDebugStringConvertible
{
    public var description: String
    {
        "SPFNTransportRequest(method: \(method), headers: \(headers.count), body: \(Self.describe(body)))"
    }

    public var debugDescription: String
    {
        description
    }

    private static func describe(_ body: [UInt8]?) -> String
    {
        body.map { "\($0.count) bytes" } ?? "absent"
    }
}

extension SPFNTransportResponse: CustomStringConvertible, CustomDebugStringConvertible
{
    public var description: String
    {
        "SPFNTransportResponse(status: \(statusCode), headers: \(headers.count), body: \(body.count) bytes)"
    }

    public var debugDescription: String
    {
        description
    }
}
