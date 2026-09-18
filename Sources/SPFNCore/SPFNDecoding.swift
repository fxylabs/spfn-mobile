// SPFN Mobile — the field readers every generated decoder is built out of.
//
// Hand-written and stable while the per-operation decoders that call them are produced by
// tools/contract-codegen from the pinned bundle. They are here so generated code stays a
// thin, obviously-correct listing of the contract rather than a place where logic hides,
// and so hand-written and generated code report one decoding error type between them.

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
