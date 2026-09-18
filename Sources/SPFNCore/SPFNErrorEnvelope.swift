// SPFN Mobile — the error envelope every SPFN endpoint answers with.
//
// Hand-written and stable, like the operation descriptor beside it: the envelope is the
// one response shape that is not per-operation, so nothing generates it. It sits in core
// rather than in the client because generated decoders read it, and the redaction stays
// in the same file as the type because every field is text a server chose — separating
// the two would leave a plain struct whose `description` prints a payload.

/// The canonical error envelope every SPFN endpoint answers with.
///
/// Every field is text a server chose. A server can put anything in `message` or
/// `requestId` — including a session identifier it echoed back — so none of them may
/// reach a log by default. The redaction at the bottom of this file is what makes that
/// true; a caller that wants a field reads the property and decides for itself.
public struct SPFNErrorEnvelope: Equatable, Sendable
{
    public let code: String
    public let message: String
    public let requestID: String

    public init(code: String, message: String, requestID: String)
    {
        self.code = code
        self.message = message
        self.requestID = requestID
    }

    /// Reads the envelope out of a parsed response body.
    ///
    /// An unrecognised code is not mapped onto a neighbouring one — that is the
    /// generated `SPFNGeneratedErrorCode`'s job, and it rejects instead of guessing.
    public static func decode(_ value: SPFNCanonicalValue) throws -> SPFNErrorEnvelope
    {
        let root = try SPFNDecoding.object(value, at: "$")
        let error = try SPFNDecoding.object(
            root["error"] ?? .null,
            at: "$.error"
        )
        return SPFNErrorEnvelope(
            code: try SPFNDecoding.string(error["code"], at: "$.error.code"),
            message: try SPFNDecoding.string(error["message"], at: "$.error.message"),
            requestID: try SPFNDecoding.string(error["requestId"], at: "$.error.requestId")
        )
    }

    /// The canonical form of this envelope, so a client can assert on exact bytes.
    public var canonicalValue: SPFNCanonicalValue
    {
        .object([
            "error": .object([
                "code": .string(code),
                "message": .string(message),
                "requestId": .string(requestID),
            ]),
        ])
    }
}

// The default description of a struct prints every stored property, and `dump` and
// `String(reflecting:)` reach the same values through the synthesized mirror even when
// only `description` is overridden. All three doors are closed here rather than one:
// the payload is server-controlled text, and closing one door would just move the leak.
//
// `code`, `message` and `requestID` stay ordinary public properties, so classifying an
// error is unaffected. Only printing one by accident is.
extension SPFNErrorEnvelope: CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable
{
    public var description: String
    {
        "SPFNErrorEnvelope(code: redacted, message: redacted, requestID: redacted)"
    }

    public var debugDescription: String
    {
        description
    }

    public var customMirror: Mirror
    {
        Mirror(self, unlabeledChildren: [Any]())
    }
}
