// SPFN Mobile — the stream transport boundary.
//
// SPFNTransport sends one request and returns one whole response; a stream never ends on
// its own schedule, so it gets a boundary of its own: the status and headers once, then
// the body as chunks for as long as the connection lasts (design §3-9). The error
// vocabulary is SPFNTransportError's four cases and nothing new.
//
// `timeoutMillis` is the deadline for the connection and the headers. After them the
// adapter sets no deadline of its own: the state machine's silence watchdog is the one
// place a quiet stream is judged, so the two platforms cannot disagree about when a
// healthy stream is cut (docs/architecture/event-stream-design.md §8 H-5).
//
// android/spfn-client/.../SpfnStreamTransport.kt is the same boundary in Kotlin.

public protocol SPFNStreamTransport: Sendable
{
    /// Opens the stream and returns once the headers are in, or throws SPFNTransportError.
    func open(_ request: SPFNTransportRequest) async throws -> SPFNStreamResponse
}

public struct SPFNStreamResponse: Sendable, CustomStringConvertible
{
    public let statusCode: Int
    public let headers: [(String, String)]
    /// The body, in the order it arrived. Finishes when the server ends the stream; throws
    /// when the connection fails.
    public let chunks: AsyncThrowingStream<[UInt8], any Error>
    private let onCancel: @Sendable () -> Void

    public init(
        statusCode: Int,
        headers: [(String, String)],
        chunks: AsyncThrowingStream<[UInt8], any Error>,
        cancel: @escaping @Sendable () -> Void
    )
    {
        self.statusCode = statusCode
        self.headers = headers
        self.chunks = chunks
        self.onCancel = cancel
    }

    /// Closes the connection. Idempotent; a consumer still reading sees the stream end.
    public func cancel()
    {
        onCancel()
    }

    public var description: String
    {
        "SPFNStreamResponse(status: \(statusCode), headers: \(headers.count))"
    }
}
