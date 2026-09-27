// The Swift type declarations for an app contract: open enumerations, response records
// that only decode, and body records that only encode.
//
// Decoding goes through SPFNDecoding, the readers the core generated types use, so a
// mismatch is the same SPFNDecodingError and the client reports it as the same
// `notTheDeclaredResponse`.

package xyz.superfunction.spfn.codegen

object AppSwiftTypes
{
    fun typeName(shape: Shape): String = when (shape)
    {
        is Shape.Text -> "String"
        is Shape.Integer -> "Int64"
        is Shape.Bool -> "Bool"
        is Shape.Enumeration -> shape.typeName
        is Shape.Record -> shape.typeName
        is Shape.ListOf -> "[${typeName(shape.element)}]"
    }

    private fun propertyType(property: Property): String =
        if (!property.required || property.nullable) "${typeName(property.shape)}?" else typeName(property.shape)

    // ---- enumerations -----------------------------------------------------

    fun enumeration(type: Shape.Enumeration): String = buildString {
        appendLine("    /// An open set: a value this build does not know decodes as `unknown`, so a server");
        appendLine("    /// that adds one does not fail the whole response.");
        appendLine("    public enum ${type.typeName}: Hashable, Sendable");
        appendLine("    {");
        type.values.forEach { appendLine("        case ${Naming.swiftIdentifier(Naming.swiftCase(it))}") };
        appendLine("        case unknown(String)");
        appendLine();
        appendLine("        public init(wireValue: String)");
        appendLine("        {");
        appendLine("            switch wireValue");
        appendLine("            {");
        type.values.forEach {
            appendLine("            case \"$it\":");
            appendLine("                self = .${Naming.swiftIdentifier(Naming.swiftCase(it))}");
        };
        appendLine("            default:");
        appendLine("                self = .unknown(wireValue)");
        appendLine("            }");
        appendLine("        }");
        appendLine();
        appendLine("        public var wireValue: String");
        appendLine("        {");
        appendLine("            switch self");
        appendLine("            {");
        type.values.forEach {
            appendLine("            case .${Naming.swiftIdentifier(Naming.swiftCase(it))}:");
            appendLine("                return \"$it\"");
        };
        appendLine("            case .unknown(let value):");
        appendLine("                return value");
        appendLine("            }");
        appendLine("        }");
        appendLine("    }");
    }

    // ---- records ----------------------------------------------------------

    private fun declaration(type: Shape.Record): String = buildString {
        appendLine("    public struct ${type.typeName}: Equatable, Sendable");
        appendLine("    {");
        type.properties.forEach { appendLine("        public var ${Naming.swiftIdentifier(it.name)}: ${propertyType(it)}") };
        appendLine();
        appendLine("        public init(");
        type.properties.forEachIndexed { index, property ->
            val comma = if (index == type.properties.size - 1) "" else ",";
            val default = if (!property.required) " = nil" else "";
            appendLine("            ${Naming.swiftIdentifier(property.name)}: ${propertyType(property)}$default$comma");
        };
        appendLine("        )");
        appendLine("        {");
        type.properties.forEach {
            val name = Naming.swiftIdentifier(it.name);
            appendLine("            self.$name = $name");
        };
        appendLine("        }");
    }

    /** T18: decodes; a missing required key and a wrong type are SPFNDecodingError. */
    fun response(type: Shape.Record, namespace: String): String = buildString {
        append(declaration(type));
        appendLine();
        appendLine("        public init(canonical: SPFNCanonicalValue, at path: String = \"\$\") throws");
        appendLine("        {");
        appendLine("            let members = try SPFNDecoding.object(canonical, at: path)");
        type.properties.forEach { appendLine("            self.${Naming.swiftIdentifier(it.name)} = try ${decodeField(it, namespace)}") };
        appendLine("        }");
        appendLine("    }");
    }

    /**
     * T8, T9: a required key is read through `present`, which refuses its absence; a
     * nullable or optional value goes through `nonNull`, which reads `null` as nil.
     */
    private fun decodeField(property: Property, namespace: String): String
    {
        val path = "\"\\(path).${property.name}\"";
        val member = "members[\"${property.name}\"]";
        val source = if (property.required && property.nullable) "$namespace.present(members, \"${property.name}\", at: $path)" else member;
        if (!property.required || property.nullable)
        {
            return "$namespace.nonNull($source).map { ${decode(property.shape, "\$0", false, path)} }";
        }
        return decode(property.shape, member, true, path).removePrefix("try ");
    }

    /** Every expression starts with `try`; the caller strips the leading one it already wrote. */
    private fun decode(shape: Shape, value: String, optional: Boolean, path: String): String = when (shape)
    {
        is Shape.Text -> "try SPFNDecoding.string($value, at: $path)"
        is Shape.Integer -> "try SPFNDecoding.integer($value, at: $path)"
        is Shape.Bool -> "try SPFNDecoding.boolean($value, at: $path)"
        is Shape.Enumeration -> "try ${shape.typeName}(wireValue: SPFNDecoding.string($value, at: $path))"
        is Shape.Record -> "try ${shape.typeName}(canonical: ${if (optional) "$value ?? .null" else value}, at: $path)"
        is Shape.ListOf -> "try SPFNDecoding.array($value, at: $path).map { ${decode(shape.element, "\$0", false, path)} }"
    }

    /** T16: encodes; T8 writes an explicit null, T9 omits the key. */
    fun body(type: Shape.Record): String = buildString {
        append(declaration(type));
        appendLine();
        appendLine("        public func canonicalValue() -> SPFNCanonicalValue");
        appendLine("        {");
        appendLine("            var members: [String: SPFNCanonicalValue] = [:]");
        type.properties.forEach { append(encodeField(it)) };
        appendLine("            return .object(members)");
        appendLine("        }");
        appendLine("    }");
    }

    private fun encodeField(property: Property): String = buildString {
        val access = "self.${Naming.swiftIdentifier(property.name)}";
        val member = "members[\"${property.name}\"]";
        if (property.required && !property.nullable)
        {
            appendLine("            $member = ${encode(property.shape, access)}");
            return@buildString;
        }
        appendLine("            if let value = $access");
        appendLine("            {");
        appendLine("                $member = ${encode(property.shape, "value")}");
        appendLine("            }");
        if (property.required)
        {
            appendLine("            else");
            appendLine("            {");
            appendLine("                $member = .null");
            appendLine("            }");
        }
    }

    private fun encode(shape: Shape, value: String): String = when (shape)
    {
        is Shape.Text -> ".string($value)"
        is Shape.Integer -> ".integer($value)"
        is Shape.Bool -> ".bool($value)"
        is Shape.Enumeration -> ".string($value.wireValue)"
        is Shape.Record -> "$value.canonicalValue()"
        is Shape.ListOf -> ".array($value.map { ${encode(shape.element, "\$0")} })"
    }
}

/** The helpers the generated namespace carries, each only when a call or type uses it. */
object AppSwiftSupport
{
    fun helpers(usage: AppUsage): String = listOfNotNull(
        if (usage.pathSegments) PATH_SEGMENT else null,
        if (usage.query) QUERY_STRING else null,
        if (usage.percentEncoding) PERCENT_ENCODED else null,
        if (usage.present) PRESENT else null,
        if (usage.nonNull) NON_NULL else null
    ).joinToString("\n") { it.trimEnd() + "\n" }

    private val PATH_SEGMENT = """
        |    /// One path parameter as one path segment: RFC 3986 unreserved bytes as they are,
        |    /// every other UTF-8 byte as %XX. A value that is exactly `.` or `..` is encoded
        |    /// whole, so path normalisation cannot turn it into a step up the path.
        |    static func pathSegment(_ value: String) -> String
        |    {
        |        if value == "." || value == ".."
        |        {
        |            return String(repeating: "%2E", count: value.count)
        |        }
        |        return percentEncoded(value)
        |    }
        |""".trimMargin()

    private val QUERY_STRING = """
        |    /// `?name=value&…` in the order given, with nil values left out; empty when all are nil.
        |    static func queryString(_ pairs: [(String, String?)]) -> String
        |    {
        |        let fields = pairs.compactMap { pair in pair.1.map { pair.0 + "=" + percentEncoded(${'$'}0) } }
        |        return fields.isEmpty ? "" : "?" + fields.joined(separator: "&")
        |    }
        |""".trimMargin()

    private val PERCENT_ENCODED = """
        |    static func percentEncoded(_ value: String) -> String
        |    {
        |        let hex = Array("0123456789ABCDEF".utf8)
        |        var bytes: [UInt8] = []
        |        for byte in value.utf8
        |        {
        |            if isUnreserved(byte)
        |            {
        |                bytes.append(byte)
        |            }
        |            else
        |            {
        |                bytes.append(contentsOf: [UInt8(ascii: "%"), hex[Int(byte >> 4)], hex[Int(byte & 0x0F)]])
        |            }
        |        }
        |        return String(decoding: bytes, as: UTF8.self)
        |    }
        |
        |    static func isUnreserved(_ byte: UInt8) -> Bool
        |    {
        |        switch byte
        |        {
        |        case UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "0")...UInt8(ascii: "9"):
        |            return true
        |        case UInt8(ascii: "-"), UInt8(ascii: "."), UInt8(ascii: "_"), UInt8(ascii: "~"):
        |            return true
        |        default:
        |            return false
        |        }
        |    }
        |""".trimMargin()

    private val PRESENT = """
        |    /// A required key's value, which may be null; its absence is a decoding failure,
        |    /// because the server promised the key (a required, nullable property).
        |    static func present(_ members: [String: SPFNCanonicalValue], _ key: String, at path: String) throws -> SPFNCanonicalValue
        |    {
        |        guard let value = members[key]
        |        else
        |        {
        |            throw SPFNDecodingError.missingField(path: path)
        |        }
        |        return value
        |    }
        |""".trimMargin()

    private val NON_NULL = """
        |    /// Absent and null alike as nil, for an optional or nullable property.
        |    static func nonNull(_ value: SPFNCanonicalValue?) -> SPFNCanonicalValue?
        |    {
        |        guard let value, value != .null
        |        else
        |        {
        |            return nil
        |        }
        |        return value
        |    }
        |""".trimMargin()
}
