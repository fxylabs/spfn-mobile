// SPFN Mobile — canonical JSON, algorithm SPFN-CANON-JSON-1.
//
// The whole point of this file is that its Kotlin counterpart
// (android/spfn-core/.../SpfnCanonicalJson.kt) produces the same bytes and the same
// errors for the same input. Anything ambiguous here becomes a cross-platform digest
// mismatch, so every rule is stated rather than inherited from a JSON library:
//
//   - object keys are ordered by their UTF-8 byte sequence, ascending
//   - no insignificant whitespace is emitted
//   - numbers are signed 64-bit integers; a fractional or non-finite number is an error
//   - `"` and `\` are escaped; C0 controls use \b \f \n \r \t where defined and \u00XX
//     otherwise; every other scalar is emitted literally as UTF-8
//   - a duplicate object key is an error rather than a last-one-wins overwrite

import Foundation

/// A JSON value restricted to what the contract can express.
public enum SPFNCanonicalValue: Equatable, Sendable
{
    case null
    case bool(Bool)
    case integer(Int64)
    case string(String)
    case array([SPFNCanonicalValue])
    case object([String: SPFNCanonicalValue])
}

/// Failures that canonicalization and strict parsing can raise.
///
/// The Kotlin side raises the same cases with the same payloads, and the conformance
/// fixtures assert on the case name, so an error is part of the contract surface.
public enum SPFNCanonicalError: Error, Equatable, Sendable
{
    case unexpectedEnd
    case invalidToken(offset: Int)
    case invalidEscape(offset: Int)
    case invalidNumber(text: String)
    case nonIntegerNumber(text: String)
    case duplicateKey(String)
    case trailingContent(offset: Int)
    case invalidUTF8

    /// The stable identifier a fixture vector names.
    public var code: String
    {
        switch self
        {
        case .unexpectedEnd:
            return "UNEXPECTED_END"
        case .invalidToken:
            return "INVALID_TOKEN"
        case .invalidEscape:
            return "INVALID_ESCAPE"
        case .invalidNumber:
            return "INVALID_NUMBER"
        case .nonIntegerNumber:
            return "NON_INTEGER_NUMBER"
        case .duplicateKey:
            return "DUPLICATE_KEY"
        case .trailingContent:
            return "TRAILING_CONTENT"
        case .invalidUTF8:
            return "INVALID_UTF8"
        }
    }
}

public enum SPFNCanonicalJSON
{
    /// Serializes a value to its canonical UTF-8 bytes.
    public static func encode(_ value: SPFNCanonicalValue) -> [UInt8]
    {
        var out: [UInt8] = []
        write(value, into: &out)
        return out
    }

    /// Serializes a value to its canonical UTF-8 string.
    public static func encodeToString(_ value: SPFNCanonicalValue) -> String
    {
        String(decoding: encode(value), as: UTF8.self)
    }

    /// Parses canonical-or-not JSON bytes into a value, strictly.
    ///
    /// Strict means: integers only, no duplicate keys, no trailing content. Parsing is
    /// deliberately not the inverse of `encode` — input arrives from a server and may
    /// carry whitespace and any key order — but `encode(parse(x))` is canonical, which
    /// is what every digest in this SDK is taken over.
    public static func parse(_ bytes: [UInt8]) throws -> SPFNCanonicalValue
    {
        var reader = Reader(bytes: bytes)
        reader.skipWhitespace()
        let value = try reader.readValue()
        reader.skipWhitespace()
        guard reader.isAtEnd
        else
        {
            throw SPFNCanonicalError.trailingContent(offset: reader.offset)
        }
        return value
    }

    /// Convenience for fixture files and response bodies held as strings.
    public static func parse(_ text: String) throws -> SPFNCanonicalValue
    {
        try parse(Array(text.utf8))
    }

    /// Orders keys the way the canonical form requires: by UTF-8 bytes, ascending.
    public static func sortedKeys(_ keys: some Collection<String>) -> [String]
    {
        keys.sorted { compareUTF8($0, $1) < 0 }
    }

    private static func compareUTF8(_ lhs: String, _ rhs: String) -> Int
    {
        let left = Array(lhs.utf8)
        let right = Array(rhs.utf8)
        for index in 0 ..< min(left.count, right.count)
        {
            if left[index] != right[index]
            {
                return left[index] < right[index] ? -1 : 1
            }
        }
        if left.count == right.count
        {
            return 0
        }
        return left.count < right.count ? -1 : 1
    }

    private static func write(_ value: SPFNCanonicalValue, into out: inout [UInt8])
    {
        switch value
        {
        case .null:
            out.append(contentsOf: Array("null".utf8))
        case .bool(let flag):
            out.append(contentsOf: Array((flag ? "true" : "false").utf8))
        case .integer(let number):
            out.append(contentsOf: Array(String(number).utf8))
        case .string(let text):
            writeString(text, into: &out)
        case .array(let elements):
            out.append(UInt8(ascii: "["))
            for (index, element) in elements.enumerated()
            {
                if index > 0
                {
                    out.append(UInt8(ascii: ","))
                }
                write(element, into: &out)
            }
            out.append(UInt8(ascii: "]"))
        case .object(let members):
            out.append(UInt8(ascii: "{"))
            for (index, key) in sortedKeys(members.keys).enumerated()
            {
                if index > 0
                {
                    out.append(UInt8(ascii: ","))
                }
                writeString(key, into: &out)
                out.append(UInt8(ascii: ":"))
                write(members[key]!, into: &out)
            }
            out.append(UInt8(ascii: "}"))
        }
    }

    private static func writeString(_ text: String, into out: inout [UInt8])
    {
        out.append(UInt8(ascii: "\""))
        for scalar in text.unicodeScalars
        {
            switch scalar
            {
            case "\"":
                out.append(contentsOf: Array("\\\"".utf8))
            case "\\":
                out.append(contentsOf: Array("\\\\".utf8))
            case "\u{08}":
                out.append(contentsOf: Array("\\b".utf8))
            case "\u{0C}":
                out.append(contentsOf: Array("\\f".utf8))
            case "\n":
                out.append(contentsOf: Array("\\n".utf8))
            case "\r":
                out.append(contentsOf: Array("\\r".utf8))
            case "\t":
                out.append(contentsOf: Array("\\t".utf8))
            default:
                if scalar.value < 0x20
                {
                    out.append(contentsOf: Array(String(format: "\\u%04x", scalar.value).utf8))
                }
                else
                {
                    out.append(contentsOf: Array(String(scalar).utf8))
                }
            }
        }
        out.append(UInt8(ascii: "\""))
    }
}

/// Minimal strict JSON reader. Hand-written on purpose: a stock parser on each platform
/// would disagree about duplicate keys, number width and error identity, which is
/// exactly what the parity gate is supposed to pin down.
private struct Reader
{
    let bytes: [UInt8]
    var offset: Int = 0

    var isAtEnd: Bool
    {
        offset >= bytes.count
    }

    mutating func skipWhitespace()
    {
        while offset < bytes.count
        {
            let byte = bytes[offset]
            guard byte == 0x20 || byte == 0x09 || byte == 0x0A || byte == 0x0D
            else
            {
                return
            }
            offset += 1
        }
    }

    mutating func readValue() throws -> SPFNCanonicalValue
    {
        guard offset < bytes.count
        else
        {
            throw SPFNCanonicalError.unexpectedEnd
        }

        switch bytes[offset]
        {
        case UInt8(ascii: "{"):
            return try readObject()
        case UInt8(ascii: "["):
            return try readArray()
        case UInt8(ascii: "\""):
            return .string(try readString())
        case UInt8(ascii: "t"):
            try expect("true")
            return .bool(true)
        case UInt8(ascii: "f"):
            try expect("false")
            return .bool(false)
        case UInt8(ascii: "n"):
            try expect("null")
            return .null
        default:
            return try readNumber()
        }
    }

    private mutating func expect(_ literal: String) throws
    {
        let expected = Array(literal.utf8)
        guard offset + expected.count <= bytes.count
        else
        {
            throw SPFNCanonicalError.unexpectedEnd
        }
        for (index, byte) in expected.enumerated() where bytes[offset + index] != byte
        {
            throw SPFNCanonicalError.invalidToken(offset: offset)
        }
        offset += expected.count
    }

    private mutating func readObject() throws -> SPFNCanonicalValue
    {
        offset += 1
        var members: [String: SPFNCanonicalValue] = [:]
        skipWhitespace()

        if offset < bytes.count, bytes[offset] == UInt8(ascii: "}")
        {
            offset += 1
            return .object(members)
        }

        while true
        {
            skipWhitespace()
            let key = try readString()
            guard members[key] == nil
            else
            {
                throw SPFNCanonicalError.duplicateKey(key)
            }
            skipWhitespace()
            try expectByte(UInt8(ascii: ":"))
            skipWhitespace()
            members[key] = try readValue()
            skipWhitespace()

            guard offset < bytes.count
            else
            {
                throw SPFNCanonicalError.unexpectedEnd
            }
            if bytes[offset] == UInt8(ascii: ",")
            {
                offset += 1
                continue
            }
            try expectByte(UInt8(ascii: "}"))
            return .object(members)
        }
    }

    private mutating func readArray() throws -> SPFNCanonicalValue
    {
        offset += 1
        var elements: [SPFNCanonicalValue] = []
        skipWhitespace()

        if offset < bytes.count, bytes[offset] == UInt8(ascii: "]")
        {
            offset += 1
            return .array(elements)
        }

        while true
        {
            skipWhitespace()
            elements.append(try readValue())
            skipWhitespace()

            guard offset < bytes.count
            else
            {
                throw SPFNCanonicalError.unexpectedEnd
            }
            if bytes[offset] == UInt8(ascii: ",")
            {
                offset += 1
                continue
            }
            try expectByte(UInt8(ascii: "]"))
            return .array(elements)
        }
    }

    private mutating func expectByte(_ byte: UInt8) throws
    {
        guard offset < bytes.count
        else
        {
            throw SPFNCanonicalError.unexpectedEnd
        }
        guard bytes[offset] == byte
        else
        {
            throw SPFNCanonicalError.invalidToken(offset: offset)
        }
        offset += 1
    }

    private mutating func readString() throws -> String
    {
        try expectByte(UInt8(ascii: "\""))
        var scalars: [UInt8] = []

        while true
        {
            guard offset < bytes.count
            else
            {
                throw SPFNCanonicalError.unexpectedEnd
            }
            let byte = bytes[offset]

            if byte == UInt8(ascii: "\"")
            {
                offset += 1
                guard let text = String(bytes: scalars, encoding: .utf8)
                else
                {
                    throw SPFNCanonicalError.invalidUTF8
                }
                return text
            }

            if byte == UInt8(ascii: "\\")
            {
                offset += 1
                try readEscape(into: &scalars)
                continue
            }

            if byte < 0x20
            {
                throw SPFNCanonicalError.invalidToken(offset: offset)
            }

            scalars.append(byte)
            offset += 1
        }
    }

    private mutating func readEscape(into scalars: inout [UInt8]) throws
    {
        guard offset < bytes.count
        else
        {
            throw SPFNCanonicalError.unexpectedEnd
        }
        let escape = bytes[offset]
        offset += 1

        switch escape
        {
        case UInt8(ascii: "\""):
            scalars.append(UInt8(ascii: "\""))
        case UInt8(ascii: "\\"):
            scalars.append(UInt8(ascii: "\\"))
        case UInt8(ascii: "/"):
            scalars.append(UInt8(ascii: "/"))
        case UInt8(ascii: "b"):
            scalars.append(0x08)
        case UInt8(ascii: "f"):
            scalars.append(0x0C)
        case UInt8(ascii: "n"):
            scalars.append(0x0A)
        case UInt8(ascii: "r"):
            scalars.append(0x0D)
        case UInt8(ascii: "t"):
            scalars.append(0x09)
        case UInt8(ascii: "u"):
            let scalar = try readEscapedScalar()
            scalars.append(contentsOf: Array(String(scalar).utf8))
        default:
            throw SPFNCanonicalError.invalidEscape(offset: offset - 1)
        }
    }

    /// Reads one `\uXXXX` payload, joining a surrogate pair into the scalar it encodes.
    ///
    /// A lone surrogate is refused rather than replaced. Silently substituting U+FFFD
    /// is how two platforms end up with different bytes for the same input.
    private mutating func readEscapedScalar() throws -> Unicode.Scalar
    {
        let high = try readHex4()

        if high >= 0xD800, high <= 0xDBFF
        {
            guard offset + 1 < bytes.count,
                  bytes[offset] == UInt8(ascii: "\\"),
                  bytes[offset + 1] == UInt8(ascii: "u")
            else
            {
                throw SPFNCanonicalError.invalidEscape(offset: offset)
            }
            offset += 2
            let low = try readHex4()
            guard low >= 0xDC00, low <= 0xDFFF
            else
            {
                throw SPFNCanonicalError.invalidEscape(offset: offset)
            }
            let combined = 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00)
            guard let scalar = Unicode.Scalar(combined)
            else
            {
                throw SPFNCanonicalError.invalidEscape(offset: offset)
            }
            return scalar
        }

        guard let scalar = Unicode.Scalar(high), !(high >= 0xDC00 && high <= 0xDFFF)
        else
        {
            throw SPFNCanonicalError.invalidEscape(offset: offset)
        }
        return scalar
    }

    private mutating func readHex4() throws -> UInt32
    {
        guard offset + 4 <= bytes.count
        else
        {
            throw SPFNCanonicalError.unexpectedEnd
        }
        var value: UInt32 = 0
        for _ in 0 ..< 4
        {
            let byte = bytes[offset]
            let digit: UInt32
            switch byte
            {
            case UInt8(ascii: "0") ... UInt8(ascii: "9"):
                digit = UInt32(byte - UInt8(ascii: "0"))
            case UInt8(ascii: "a") ... UInt8(ascii: "f"):
                digit = UInt32(byte - UInt8(ascii: "a")) + 10
            case UInt8(ascii: "A") ... UInt8(ascii: "F"):
                digit = UInt32(byte - UInt8(ascii: "A")) + 10
            default:
                throw SPFNCanonicalError.invalidEscape(offset: offset)
            }
            value = value * 16 + digit
            offset += 1
        }
        return value
    }

    private mutating func readNumber() throws -> SPFNCanonicalValue
    {
        let start = offset
        if offset < bytes.count, bytes[offset] == UInt8(ascii: "-")
        {
            offset += 1
        }

        var sawDigit = false
        var isInteger = true

        while offset < bytes.count
        {
            let byte = bytes[offset]
            if byte >= UInt8(ascii: "0") && byte <= UInt8(ascii: "9")
            {
                sawDigit = true
                offset += 1
                continue
            }
            if byte == UInt8(ascii: ".") || byte == UInt8(ascii: "e") || byte == UInt8(ascii: "E")
                || byte == UInt8(ascii: "+") || byte == UInt8(ascii: "-")
            {
                isInteger = false
                offset += 1
                continue
            }
            break
        }

        let text = String(decoding: bytes[start ..< offset], as: UTF8.self)

        guard sawDigit
        else
        {
            throw SPFNCanonicalError.invalidToken(offset: start)
        }
        guard isInteger
        else
        {
            throw SPFNCanonicalError.nonIntegerNumber(text: text)
        }
        guard let number = Int64(text)
        else
        {
            throw SPFNCanonicalError.invalidNumber(text: text)
        }
        return .integer(number)
    }
}
