// SPFN Mobile — operation and error shapes shared by hand-written and generated code.
//
// These types are hand-written and stable. The per-operation values that fill them in
// live in SPFNGenerated and are produced by tools/contract-codegen from the pinned
// bundle, so adding an operation never means editing this file.

/// One contract operation, as the pinned bundle describes it.
public struct SPFNOperation: Equatable, Sendable
{
    public let id: String
    public let method: String
    public let path: String
    public let authProfile: String
    public let requiresSession: Bool

    /// Whether the contract declares a response body for this operation.
    ///
    /// `false` is the contract's own bodyless shape, stated by `restOperations.responseBody`
    /// from 0.10.0 on: "An operation that declares no responseType answers 204 with an empty
    /// body and there is nothing to decode". `SPFNClient` reads this rather than the
    /// operation id, so a later bodyless operation needs no change to the execute path.
    ///
    /// No default. Every descriptor states it, because a defaulted `true` would make the
    /// bodyless case something a hand-built descriptor forgets rather than something it
    /// declares.
    public let declaresResponse: Bool

    public init(
        id: String,
        method: String,
        path: String,
        authProfile: String,
        requiresSession: Bool,
        declaresResponse: Bool
    )
    {
        self.id = id
        self.method = method
        self.path = path
        self.authProfile = authProfile
        self.requiresSession = requiresSession
        self.declaresResponse = declaresResponse
    }
}

/// What an operation that declares no response body answers with.
///
/// A type of its own rather than `Void`: `Void` is not `Equatable` and cannot be the
/// `Response` of an `SPFNCall`, and a caller that got `()` back could not tell it apart
/// from a function that returns nothing at all. This value means "the server accepted it
/// and said nothing", which is a result.
///
/// Kotlin's counterpart is the `SpfnNoResponse` object, not `Unit`, for the same reason
/// on that side: a Java caller sees a named type rather than `kotlin.Unit`.
public struct SPFNNoResponse: Equatable, Sendable
{
    public static let value = SPFNNoResponse()

    public init() {}
}

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

/// Decoding failures shared by generated response types.
public enum SPFNDecodingError: Error, Equatable, Sendable
{
    case missingField(path: String)
    case typeMismatch(path: String, expected: String)
    case unknownErrorCode(String)
    /// `admittedRange` is what the SDK will accept, which is the contract's declared
    /// range only when the pin is a release. Reporting the declared range instead would
    /// name a window a pre-release-pinned client refuses.
    case unsupportedContractVersion(found: String, admittedRange: String)

    public var code: String
    {
        switch self
        {
        case .missingField:
            return "MISSING_FIELD"
        case .typeMismatch:
            return "TYPE_MISMATCH"
        case .unknownErrorCode:
            return "UNKNOWN_ERROR_CODE"
        case .unsupportedContractVersion:
            return "CONTRACT_UNSUPPORTED"
        }
    }
}

/// Field readers used by generated decoders. Kept here so generated code stays a thin,
/// obviously-correct listing of the contract rather than a place where logic hides.
public enum SPFNDecoding
{
    public static func object(_ value: SPFNCanonicalValue?, at path: String) throws -> [String: SPFNCanonicalValue]
    {
        guard let value, value != .null
        else
        {
            throw SPFNDecodingError.missingField(path: path)
        }
        guard case .object(let members) = value
        else
        {
            throw SPFNDecodingError.typeMismatch(path: path, expected: "object")
        }
        return members
    }

    public static func string(_ value: SPFNCanonicalValue?, at path: String) throws -> String
    {
        guard let value, value != .null
        else
        {
            throw SPFNDecodingError.missingField(path: path)
        }
        guard case .string(let text) = value
        else
        {
            throw SPFNDecodingError.typeMismatch(path: path, expected: "string")
        }
        return text
    }

    public static func optionalString(_ value: SPFNCanonicalValue?, at path: String) throws -> String?
    {
        guard let value, value != .null
        else
        {
            return nil
        }
        guard case .string(let text) = value
        else
        {
            throw SPFNDecodingError.typeMismatch(path: path, expected: "string")
        }
        return text
    }

    public static func integer(_ value: SPFNCanonicalValue?, at path: String) throws -> Int64
    {
        guard let value, value != .null
        else
        {
            throw SPFNDecodingError.missingField(path: path)
        }
        guard case .integer(let number) = value
        else
        {
            throw SPFNDecodingError.typeMismatch(path: path, expected: "integer")
        }
        return number
    }

    public static func optionalInteger(_ value: SPFNCanonicalValue?, at path: String) throws -> Int64?
    {
        guard let value, value != .null
        else
        {
            return nil
        }
        guard case .integer(let number) = value
        else
        {
            throw SPFNDecodingError.typeMismatch(path: path, expected: "integer")
        }
        return number
    }

    public static func boolean(_ value: SPFNCanonicalValue?, at path: String) throws -> Bool
    {
        guard let value, value != .null
        else
        {
            throw SPFNDecodingError.missingField(path: path)
        }
        guard case .bool(let flag) = value
        else
        {
            throw SPFNDecodingError.typeMismatch(path: path, expected: "boolean")
        }
        return flag
    }

    /// An optional boolean: absent and null both read as nothing.
    ///
    /// Its own reader rather than `boolean` with a fallback, for the reason every other
    /// `optional*` above exists: a default would turn "the server said nothing" into
    /// "the server said false", and the first field to need this — an approved device
    /// poll's `passwordChangeRequired`, absent on the pending branch — is one where the
    /// two readings are a login rule apart.
    public static func optionalBoolean(_ value: SPFNCanonicalValue?, at path: String) throws -> Bool?
    {
        guard let value, value != .null
        else
        {
            return nil
        }
        guard case .bool(let flag) = value
        else
        {
            throw SPFNDecodingError.typeMismatch(path: path, expected: "boolean")
        }
        return flag
    }

    public static func array(_ value: SPFNCanonicalValue?, at path: String) throws -> [SPFNCanonicalValue]
    {
        guard let value, value != .null
        else
        {
            throw SPFNDecodingError.missingField(path: path)
        }
        guard case .array(let elements) = value
        else
        {
            throw SPFNDecodingError.typeMismatch(path: path, expected: "array")
        }
        return elements
    }
}
