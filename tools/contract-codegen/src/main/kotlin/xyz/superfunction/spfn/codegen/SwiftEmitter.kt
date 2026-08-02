// Emits the Swift client from the bundle.
//
// Everything the emitter writes is a function of the bundle bytes alone: no timestamp,
// no host name, no absolute path, no map iteration order. That is what makes two runs
// byte-identical, which `spfnCodegenVerify` checks.

package xyz.superfunction.spfn.codegen

object SwiftEmitter
{
    fun emit(bundle: Bundle): Map<String, String> = mapOf(
        "Sources/SPFNGenerated/Generated/SPFNGeneratedContract.swift" to contract(bundle),
        "Sources/SPFNGenerated/Generated/SPFNGeneratedTypes.swift" to types(bundle),
        "Sources/SPFNGenerated/Generated/SPFNGeneratedOperations.swift" to operations(bundle),
        "Sources/SPFNGenerated/Generated/SPFNGeneratedErrors.swift" to errors(bundle)
    )

    private fun header(bundle: Bundle): String = Header.lines(bundle).joinToString("\n") { "// $it".trimEnd() }

    private fun contract(bundle: Bundle): String = buildString {
        appendLine(header(bundle));
        appendLine();
        appendLine("import SPFNCore");
        appendLine();
        appendLine("/// What this build was generated from.");
        appendLine("public enum SPFNGeneratedContract");
        appendLine("{");
        appendLine("    /// The generator that produced this directory.");
        appendLine("    public static let generatorVersion: String = \"${Header.GENERATOR}\"");
        appendLine();
        appendLine("    /// The pinned bundle these sources were derived from.");
        appendLine("    public static let binding = SPFNContractBinding(");
        appendLine("        importedVersion: \"${bundle.contractVersion}\",");
        appendLine("        importedManifestSha256: \"${bundle.sha256}\",");
        appendLine("        supportedRange: \"${bundle.supportedRange}\",");
        appendLine("        supportedMajor: ${bundle.contractMajor},");
        appendLine("        supportedMinor: ${bundle.contractMinor},");
        appendLine("        origin: \"${bundle.origin}\"");
        appendLine("    )");
        appendLine();
        appendLine("    /// Every operation the contract declares, in bundle order.");
        appendLine("    public static let operationIDs: [String] = [");
        bundle.operations.forEach { appendLine("        \"${it.id}\",") };
        appendLine("    ]");
        appendLine();
        appendLine("    /// The replay window the contract fixes, in milliseconds.");
        appendLine("    public static let replayWindowMillis: Int64 = ${bundle.replayWindowMillis}");
        appendLine();
        appendLine("    /// The proof-input field order the contract fixes.");
        appendLine("    public static let proofInputFields: [String] = [");
        bundle.proofInputFields.forEach { appendLine("        \"$it\",") };
        appendLine("    ]");
        append("}");
        appendLine();
    }

    private fun types(bundle: Bundle): String = buildString {
        appendLine(header(bundle));
        appendLine();
        appendLine("import SPFNCore");
        bundle.types.forEach { type ->
            appendLine();
            appendLine("public struct ${Names.swiftType(type.name)}: Equatable, Sendable");
            appendLine("{");
            type.fields.forEach { field ->
                appendLine("    public var ${field.name}: ${swiftType(field)}");
            }
            appendLine();
            appendLine("    public init(");
            type.fields.forEachIndexed { index, field ->
                val comma = if (index == type.fields.size - 1) "" else ",";
                val default = if (field.optional) " = nil" else "";
                appendLine("        ${field.name}: ${swiftType(field)}$default$comma");
            }
            appendLine("    )");
            appendLine("    {");
            type.fields.forEach { field -> appendLine("        self.${field.name} = ${field.name}") };
            appendLine("    }");
            appendLine();
            appendLine("    /// The canonical form of this value. An absent optional field is omitted,");
            appendLine("    /// never written as null, so the digest of a value never depends on how a");
            appendLine("    /// caller happened to spell \"nothing\".");
            appendLine("    public var canonicalValue: SPFNCanonicalValue");
            appendLine("    {");
            appendLine("        var members: [String: SPFNCanonicalValue] = [:]");
            type.fields.forEach { field -> appendLine(swiftEncodeField(field)) };
            appendLine("        return .object(members)");
            appendLine("    }");
            appendLine();
            appendLine("    public init(canonical: SPFNCanonicalValue, at path: String = \"\$\") throws");
            appendLine("    {");
            appendLine("        let members = try SPFNDecoding.object(canonical, at: path)");
            type.fields.forEach { field -> appendLine(swiftDecodeField(field)) };
            appendLine("    }");
            append("}");
            appendLine();
        }
    }

    private fun operations(bundle: Bundle): String = buildString {
        appendLine(header(bundle));
        appendLine();
        appendLine("import SPFNCore");
        appendLine();
        appendLine("public enum SPFNGeneratedOperations");
        appendLine("{");
        bundle.operations.forEachIndexed { index, operation ->
            if (index > 0)
            {
                appendLine();
            }
            appendLine("    /// ${operation.summary}");
            appendLine("    public static let ${Names.lowerCamel(operation.id)} = SPFNOperation(");
            appendLine("        id: \"${operation.id}\",");
            appendLine("        method: \"${operation.method}\",");
            appendLine("        path: \"${operation.path}\",");
            appendLine("        authProfile: \"${operation.authProfile}\",");
            appendLine("        requiresSession: ${operation.requiresSession}");
            appendLine("    )");
        }
        appendLine();
        appendLine("    /// Every operation, in bundle order.");
        appendLine("    public static let all: [SPFNOperation] = [");
        bundle.operations.forEach { appendLine("        ${Names.lowerCamel(it.id)},") };
        appendLine("    ]");
        appendLine();
        appendLine("    /// Looks an operation up by contract id. Returns nil rather than a nearest");
        appendLine("    /// match: an unknown id is a contract mismatch, not a typo to be forgiven.");
        appendLine("    public static func operation(id: String) -> SPFNOperation?");
        appendLine("    {");
        appendLine("        all.first { \$0.id == id }");
        appendLine("    }");
        append("}");
        appendLine();
    }

    private fun errors(bundle: Bundle): String = buildString {
        appendLine(header(bundle));
        appendLine();
        appendLine("import SPFNCore");
        appendLine();
        appendLine("/// Every error code the contract declares. A code outside this list is rejected");
        appendLine("/// rather than mapped onto a neighbouring one.");
        appendLine("public enum SPFNGeneratedErrorCode: String, CaseIterable, Sendable");
        appendLine("{");
        bundle.errors.forEach { appendLine("    case ${Names.enumCase(it.code)} = \"${it.code}\"") };
        appendLine();
        appendLine("    public var httpStatus: Int");
        appendLine("    {");
        appendLine("        switch self");
        appendLine("        {");
        bundle.errors.forEach { error ->
            appendLine("        case .${Names.enumCase(error.code)}:");
            appendLine("            return ${error.httpStatus}");
        }
        appendLine("        }");
        appendLine("    }");
        appendLine();
        appendLine("    public var isRetryable: Bool");
        appendLine("    {");
        appendLine("        switch self");
        appendLine("        {");
        bundle.errors.forEach { error ->
            appendLine("        case .${Names.enumCase(error.code)}:");
            appendLine("            return ${error.retryable}");
        }
        appendLine("        }");
        appendLine("    }");
        appendLine();
        appendLine("    /// Resolves a wire code, or throws with the raw string preserved.");
        appendLine("    public static func decode(_ raw: String) throws -> SPFNGeneratedErrorCode");
        appendLine("    {");
        appendLine("        guard let code = SPFNGeneratedErrorCode(rawValue: raw)");
        appendLine("        else");
        appendLine("        {");
        appendLine("            throw SPFNDecodingError.unknownErrorCode(raw)");
        appendLine("        }");
        appendLine("        return code");
        appendLine("    }");
        append("}");
        appendLine();
    }

    private fun swiftType(field: Field): String
    {
        val base = swiftType(FieldType.parse(field.type));
        return if (field.optional) "$base?" else base;
    }

    private fun swiftType(type: FieldType): String = when (type)
    {
        is FieldType.StringType -> "String"
        is FieldType.IntegerType -> "Int64"
        is FieldType.BooleanType -> "Bool"
        is FieldType.Named -> Names.swiftType(type.name)
        is FieldType.ArrayOf -> "[${swiftType(type.element)}]"
    }

    private fun swiftEncodeField(field: Field): String
    {
        val expression = swiftEncodeExpression(FieldType.parse(field.type), field.name);
        if (!field.optional)
        {
            return "        members[\"${field.name}\"] = $expression";
        }
        return buildString {
            appendLine("        if let ${field.name}");
            appendLine("        {");
            appendLine("            members[\"${field.name}\"] = $expression");
            append("        }");
        };
    }

    private fun swiftEncodeExpression(type: FieldType, accessor: String): String = when (type)
    {
        is FieldType.StringType -> ".string($accessor)"
        is FieldType.IntegerType -> ".integer($accessor)"
        is FieldType.BooleanType -> ".bool($accessor)"
        is FieldType.Named -> "$accessor.canonicalValue"
        is FieldType.ArrayOf -> ".array($accessor.map { \$0.canonicalValue })"
    }

    private fun swiftDecodeField(field: Field): String
    {
        val path = "\\(path).${field.name}";
        val member = "members[\"${field.name}\"]";
        return when (val type = FieldType.parse(field.type))
        {
            is FieldType.StringType ->
                if (field.optional) "        self.${field.name} = try SPFNDecoding.optionalString($member, at: \"$path\")"
                else "        self.${field.name} = try SPFNDecoding.string($member, at: \"$path\")"
            is FieldType.IntegerType ->
                if (field.optional) "        self.${field.name} = try SPFNDecoding.optionalInteger($member, at: \"$path\")"
                else "        self.${field.name} = try SPFNDecoding.integer($member, at: \"$path\")"
            is FieldType.BooleanType ->
                "        self.${field.name} = try SPFNDecoding.boolean($member, at: \"$path\")"
            is FieldType.Named ->
                "        self.${field.name} = try ${Names.swiftType(type.name)}(canonical: $member ?? .null, at: \"$path\")"
            is FieldType.ArrayOf ->
                "        self.${field.name} = try SPFNDecoding.array($member, at: \"$path\").map { try ${swiftType(type.element)}(canonical: \$0, at: \"$path\") }"
        };
    }
}
