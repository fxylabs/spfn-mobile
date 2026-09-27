// The Kotlin type declarations for an app contract. Mirror of AppSwiftTypes.
//
// An open enumeration is a sealed interface rather than an enum class: a Kotlin enum
// cannot carry the raw value of a member it does not declare, and that value is what
// `Unknown` exists to keep.

package xyz.superfunction.spfn.codegen

object AppKotlinTypes
{
    fun typeName(shape: Shape): String = when (shape)
    {
        is Shape.Text -> "String"
        is Shape.Integer -> "Long"
        is Shape.Bool -> "Boolean"
        is Shape.Enumeration -> shape.typeName
        is Shape.Record -> shape.typeName
        is Shape.ListOf -> "List<${typeName(shape.element)}>"
    }

    private fun propertyType(property: Property): String =
        if (!property.required || property.nullable) "${typeName(property.shape)}?" else typeName(property.shape)

    // ---- enumerations -----------------------------------------------------

    fun enumeration(type: Shape.Enumeration): String = buildString {
        appendLine("/**");
        appendLine(" * An open set: a value this build does not know decodes as [Unknown], so a server that");
        appendLine(" * adds one does not fail the whole response.");
        appendLine(" */");
        appendLine("sealed interface ${type.typeName}");
        appendLine("{");
        appendLine("    val wireValue: String");
        type.values.forEach { value ->
            appendLine();
            appendLine("    data object ${Naming.kotlinCase(value)} : ${type.typeName}");
            appendLine("    {");
            appendLine("        override val wireValue: String = \"$value\"");
            appendLine("    }");
        };
        appendLine();
        appendLine("    /** A value this build does not know: a server newer than this app sent it. */");
        appendLine("    data class Unknown(override val wireValue: String) : ${type.typeName}");
        appendLine();
        appendLine("    companion object");
        appendLine("    {");
        appendLine("        fun of(wireValue: String): ${type.typeName} = when (wireValue)");
        appendLine("        {");
        type.values.forEach { appendLine("            \"$it\" -> ${Naming.kotlinCase(it)}") };
        appendLine("            else -> Unknown(wireValue)");
        appendLine("        }");
        appendLine("    }");
        appendLine("}");
    }

    // ---- records ----------------------------------------------------------

    private fun declaration(type: Shape.Record): String = buildString {
        appendLine("data class ${type.typeName}(");
        type.properties.forEachIndexed { index, property ->
            val comma = if (index == type.properties.size - 1) "" else ",";
            val default = if (!property.required) " = null" else "";
            appendLine("    val ${Naming.kotlinIdentifier(property.name)}: ${propertyType(property)}$default$comma");
        };
        appendLine(")");
    }

    /** T18: decodes; a missing required key and a wrong type are SpfnDecodingException. */
    fun response(type: Shape.Record, support: String): String = buildString {
        append(declaration(type));
        appendLine("{");
        appendLine("    companion object");
        appendLine("    {");
        appendLine("        fun decode(canonical: SpfnCanonicalValue, path: String = \"\\\$\"): ${type.typeName}");
        appendLine("        {");
        appendLine("            val members = SpfnDecoding.obj(canonical, path);");
        appendLine("            return ${type.typeName}(");
        type.properties.forEachIndexed { index, property ->
            val comma = if (index == type.properties.size - 1) "" else ",";
            appendLine("                ${Naming.kotlinIdentifier(property.name)} = ${decodeField(property, support)}$comma");
        };
        appendLine("            );");
        appendLine("        }");
        appendLine("    }");
        appendLine("}");
    }

    /** T8, T9: `present` refuses a required key's absence; `nonNull` reads null as null. */
    private fun decodeField(property: Property, support: String): String
    {
        val path = "\"\$path.${property.name}\"";
        val member = "members[\"${property.name}\"]";
        if (property.required && !property.nullable)
        {
            return decode(property.shape, member, true, path, 0);
        }
        val source = if (property.required) "$support.present(members, \"${property.name}\", $path)" else member;
        return "$support.nonNull($source)?.let { value0 -> ${decode(property.shape, "value0", false, path, 1)} }";
    }

    private fun decode(shape: Shape, value: String, optional: Boolean, path: String, depth: Int): String = when (shape)
    {
        is Shape.Text -> "SpfnDecoding.string($value, $path)"
        is Shape.Integer -> "SpfnDecoding.integer($value, $path)"
        is Shape.Bool -> "SpfnDecoding.boolean($value, $path)"
        is Shape.Enumeration -> "${shape.typeName}.of(SpfnDecoding.string($value, $path))"
        is Shape.Record -> "${shape.typeName}.decode(${if (optional) "$value ?: SpfnCanonicalValue.Null" else value}, $path)"
        is Shape.ListOf ->
            "SpfnDecoding.array($value, $path).map { value$depth -> ${decode(shape.element, "value$depth", false, path, depth + 1)} }"
    }

    /** T16: encodes; T8 writes an explicit null, T9 omits the key. */
    fun body(type: Shape.Record): String = buildString {
        append(declaration(type));
        appendLine("{");
        appendLine("    fun canonicalValue(): SpfnCanonicalValue");
        appendLine("    {");
        appendLine("        val members = LinkedHashMap<String, SpfnCanonicalValue>();");
        type.properties.forEach { appendLine(encodeField(it)) };
        appendLine("        return SpfnCanonicalValue.Obj(members);");
        appendLine("    }");
        appendLine("}");
    }

    private fun encodeField(property: Property): String
    {
        val access = "this.${Naming.kotlinIdentifier(property.name)}";
        val member = "members[\"${property.name}\"]";
        return when
        {
            property.required && !property.nullable -> "        $member = ${encode(property.shape, access, 0)};"
            property.required ->
                "        $member = $access?.let { value0 -> ${encode(property.shape, "value0", 1)} } ?: SpfnCanonicalValue.Null;"
            else -> "        $access?.let { value0 -> $member = ${encode(property.shape, "value0", 1)} };"
        };
    }

    private fun encode(shape: Shape, value: String, depth: Int): String = when (shape)
    {
        is Shape.Text -> "SpfnCanonicalValue.Text($value)"
        is Shape.Integer -> "SpfnCanonicalValue.Integer($value)"
        is Shape.Bool -> "SpfnCanonicalValue.Bool($value)"
        is Shape.Enumeration -> "SpfnCanonicalValue.Text($value.wireValue)"
        is Shape.Record -> "$value.canonicalValue()"
        is Shape.ListOf ->
            "SpfnCanonicalValue.Arr($value.map { value$depth -> ${encode(shape.element, "value$depth", depth + 1)} })"
    }
}

/** The helpers the generated calls and types share, each only when one of them uses it. */
object AppKotlinSupport
{
    fun helpers(support: String, usage: AppUsage): String = buildString {
        appendLine();
        appendLine("/** Helpers the calls and types generated from this app's contract document share. */");
        appendLine("internal object $support");
        appendLine("{");
        append(
            listOfNotNull(
                if (usage.pathSegments) PATH_SEGMENT else null,
                if (usage.query) QUERY_STRING else null,
                if (usage.percentEncoding) PERCENT_ENCODED else null,
                if (usage.present) PRESENT else null,
                if (usage.nonNull) NON_NULL else null
            ).joinToString("\n") { it.trimEnd() + "\n" }
        );
        appendLine("}");
    }

    private val PATH_SEGMENT = """
        |    /**
        |     * One path parameter as one path segment: RFC 3986 unreserved bytes as they are, every
        |     * other UTF-8 byte as %XX. A value that is exactly `.` or `..` is encoded whole, so path
        |     * normalisation cannot turn it into a step up the path.
        |     */
        |    fun pathSegment(value: String): String
        |    {
        |        if (value == "." || value == "..")
        |        {
        |            return "%2E".repeat(value.length);
        |        }
        |        return percentEncoded(value);
        |    }
        |""".trimMargin()

    private val QUERY_STRING = """
        |    /** `?name=value&…` in the order given, with null values left out; empty when all are null. */
        |    fun queryString(pairs: List<Pair<String, String?>>): String
        |    {
        |        val fields = pairs.mapNotNull { (name, value) -> value?.let { name + "=" + percentEncoded(it) } };
        |        return if (fields.isEmpty()) "" else "?" + fields.joinToString("&");
        |    }
        |""".trimMargin()

    private val PERCENT_ENCODED = """
        |    fun percentEncoded(value: String): String
        |    {
        |        val out = StringBuilder();
        |        value.toByteArray(Charsets.UTF_8).forEach { signed ->
        |            val byte = signed.toInt() and 0xFF;
        |            if (isUnreserved(byte))
        |            {
        |                out.append(byte.toChar());
        |            }
        |            else
        |            {
        |                out.append('%').append(HEX[byte shr 4]).append(HEX[byte and 0x0F]);
        |            }
        |        };
        |        return out.toString();
        |    }
        |
        |    private fun isUnreserved(byte: Int): Boolean =
        |        byte in 'A'.code..'Z'.code || byte in 'a'.code..'z'.code || byte in '0'.code..'9'.code ||
        |            byte == '-'.code || byte == '.'.code || byte == '_'.code || byte == '~'.code
        |
        |    private const val HEX = "0123456789ABCDEF";
        |""".trimMargin()

    private val PRESENT = """
        |    /**
        |     * A required key's value, which may be null; its absence is a decoding failure, because
        |     * the server promised the key (a required, nullable property).
        |     */
        |    fun present(members: Map<String, SpfnCanonicalValue>, key: String, path: String): SpfnCanonicalValue =
        |        members[key] ?: throw SpfnDecodingException("MISSING_FIELD", "missing field at ${'$'}path");
        |""".trimMargin()

    private val NON_NULL = """
        |    /** Absent and null alike as null, for an optional or nullable property. */
        |    fun nonNull(value: SpfnCanonicalValue?): SpfnCanonicalValue? = value?.takeIf { it !is SpfnCanonicalValue.Null }
        |""".trimMargin()
}
