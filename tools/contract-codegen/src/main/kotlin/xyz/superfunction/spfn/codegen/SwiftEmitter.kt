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
        appendLine();
        appendLine("    /// `keyPolicy.ttlDays`: a registered public key expires this many days after");
        appendLine("    /// registration, so the client rotates before the TTL runs out.");
        appendLine("    public static let keyPolicyTtlDays: Int64 = ${bundle.keyPolicyTtlDays}");
        appendLine();
        appendLine("    /// `keyPolicy.rotationOperation`: the operation that replaces a registered key.");
        appendLine("    public static let keyRotationOperationID: String = \"${bundle.keyRotationOperationId}\"");
        appendLine();
        appendLine("    /// `clientProofV1.clientIdRule`, verbatim from the bundle.");
        appendLine("    public static let clientIdRule: String = \"${swiftStringLiteral(bundle.clientIdRule)}\"");
        append("}");
        appendLine();
    }

    private fun swiftStringLiteral(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun types(bundle: Bundle): String = buildString {
        appendLine(header(bundle));
        appendLine();
        appendLine("import SPFNCore");
        bundle.enums.forEach { declaration ->
            appendLine();
            appendLine("/// A value set the contract declares. Decoding is strict: an unknown value is");
            appendLine("/// reported with the raw string preserved rather than mapped onto a member,");
            appendLine("/// because the contract promises no set stays as it is — a value can be added,");
            appendLine("/// and one can be withdrawn for a weakness found later.");
            appendLine("public enum ${Names.swiftType(declaration.name)}: String, CaseIterable, Sendable");
            appendLine("{");
            declaration.values.forEach { appendLine("    case ${Names.enumCase(it)} = \"$it\"") };
            appendLine();
            appendLine("    public var canonicalValue: SPFNCanonicalValue");
            appendLine("    {");
            appendLine("        .string(rawValue)");
            appendLine("    }");
            appendLine();
            appendLine("    public init(canonical: SPFNCanonicalValue, at path: String = \"\$\") throws");
            appendLine("    {");
            appendLine("        let raw = try SPFNDecoding.string(canonical, at: path)");
            appendLine("        guard let value = ${Names.swiftType(declaration.name)}(rawValue: raw)");
            appendLine("        else");
            appendLine("        {");
            appendLine("            throw SPFNDecodingError.typeMismatch(path: path, expected: \"${declaration.name}\")");
            appendLine("        }");
            appendLine("        self = value");
            appendLine("    }");
            append("}");
            appendLine();
        }
        bundle.types.forEach { type ->
            appendLine();
            appendLine("public struct ${Names.swiftType(type.name)}: Equatable, Sendable");
            appendLine("{");
            type.fields.forEach { field ->
                appendLine("    public var ${field.name}: ${swiftType(bundle, field)}");
            }
            appendLine();
            appendLine("    public init(");
            type.fields.forEachIndexed { index, field ->
                val comma = if (index == type.fields.size - 1) "" else ",";
                val default = if (field.optional) " = nil" else "";
                appendLine("        ${field.name}: ${swiftType(bundle, field)}$default$comma");
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
            type.fields.forEach { field -> appendLine(swiftEncodeField(bundle, field)) };
            appendLine("        return .object(members)");
            appendLine("    }");
            appendLine();
            appendLine("    public init(canonical: SPFNCanonicalValue, at path: String = \"\$\") throws");
            appendLine("    {");
            appendLine("        let members = try SPFNDecoding.object(canonical, at: path)");
            type.fields.forEach { field -> appendLine(swiftDecodeField(bundle, field)) };
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
        appendLine("/// Every auth class the contract declares. An operation's `authProfile` names one");
        appendLine("/// of these; a value outside the list is a contract mismatch, and a caller refuses");
        appendLine("/// to send rather than downgrading to any other class.");
        appendLine("public enum SPFNGeneratedAuthClass: String, CaseIterable, Sendable");
        appendLine("{");
        bundle.authClasses.forEach { appendLine("    case ${it} = \"${it}\"") };
        appendLine("}");
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
        appendLine();
        appendLine("    /// Resolves an operation's auth class, or nil for a class this contract does");
        appendLine("    /// not declare. The caller fails closed on nil instead of guessing.");
        appendLine("    public static func authClass(of operation: SPFNOperation) -> SPFNGeneratedAuthClass?");
        appendLine("    {");
        appendLine("        SPFNGeneratedAuthClass(rawValue: operation.authProfile)");
        appendLine("    }");
        append("}");
        appendLine();
    }

    private fun errors(bundle: Bundle): String = buildString {
        appendLine(header(bundle));
        appendLine();
        appendLine("import SPFNCore");
        appendLine();
        appendLine("/// Which surface answers with a given code.");
        appendLine("///");
        appendLine("/// The contract carries both sets in one list and they are not interchangeable: a");
        appendLine("/// proven call can be met by a `clientProofV1` refusal and never by a `rest` one,");
        appendLine("/// and a call to the /_auth surface is the reverse. Code that reasons about a");
        appendLine("/// refusal reads this rather than the position a code happened to have.");
        appendLine("public enum SPFNGeneratedErrorSurface: String, CaseIterable, Sendable");
        appendLine("{");
        bundle.errors.map { it.surface }.distinct().sorted().forEach {
            appendLine("    case ${Names.lowerCamel(it)} = \"$it\"");
        }
        appendLine("}");
        appendLine();
        appendLine("/// Every error code the contract declares. A code outside this list is rejected");
        appendLine("/// rather than mapped onto a neighbouring one.");
        appendLine("public enum SPFNGeneratedErrorCode: String, CaseIterable, Sendable");
        appendLine("{");
        bundle.errors.forEach { appendLine("    case ${Names.enumCase(it.code)} = \"${it.code}\"") };
        appendLine();
        appendLine("    /// The surface that answers with this code.");
        appendLine("    public var surface: SPFNGeneratedErrorSurface");
        appendLine("    {");
        appendLine("        switch self");
        appendLine("        {");
        bundle.errors.forEach { error ->
            appendLine("        case .${Names.enumCase(error.code)}:");
            appendLine("            return .${Names.lowerCamel(error.surface)}");
        }
        appendLine("        }");
        appendLine("    }");
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

    /**
     * The cases `Bundle.checkDeclarations` has already refused. They are unreachable
     * here, and they still get a branch: an emitter that quietly produced something for
     * one of them is the failure the 0.6.0 pin walked into, and Kotlin's exhaustiveness
     * check is what keeps a future case from being answered with a guess.
     */
    private fun unemittable(type: FieldType): Nothing =
        throw JsonException("the Swift emitter reached $type, which generation should have refused")

    private fun swiftType(bundle: Bundle, field: Field): String
    {
        val base = swiftType(bundle.fieldType(field));
        return if (field.optional) "$base?" else base;
    }

    private fun swiftType(type: FieldType): String = when (type)
    {
        is FieldType.StringType -> "String"
        is FieldType.IntegerType -> "Int64"
        is FieldType.BooleanType -> "Bool"
        is FieldType.Named -> Names.swiftType(type.name)
        is FieldType.EnumRef -> Names.swiftType(type.name)
        is FieldType.ArrayOf -> "[${swiftType(type.element)}]"
        is FieldType.NumberType, is FieldType.MapOf -> unemittable(type)
    }

    private fun swiftEncodeField(bundle: Bundle, field: Field): String
    {
        val expression = swiftEncodeExpression(bundle.fieldType(field), field.name);
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
        is FieldType.EnumRef -> "$accessor.canonicalValue"
        is FieldType.ArrayOf -> ".array($accessor.map { \$0.canonicalValue })"
        is FieldType.NumberType, is FieldType.MapOf -> unemittable(type)
    }

    private fun swiftDecodeField(bundle: Bundle, field: Field): String
    {
        val path = "\\(path).${field.name}";
        val member = "members[\"${field.name}\"]";
        return when (val type = bundle.fieldType(field))
        {
            is FieldType.StringType ->
                if (field.optional) "        self.${field.name} = try SPFNDecoding.optionalString($member, at: \"$path\")"
                else "        self.${field.name} = try SPFNDecoding.string($member, at: \"$path\")"
            is FieldType.IntegerType ->
                if (field.optional) "        self.${field.name} = try SPFNDecoding.optionalInteger($member, at: \"$path\")"
                else "        self.${field.name} = try SPFNDecoding.integer($member, at: \"$path\")"
            is FieldType.BooleanType ->
                "        self.${field.name} = try SPFNDecoding.boolean($member, at: \"$path\")"
            // An optional composite reads absent and null alike as nothing, which is what
            // the encoder writes: it omits an absent optional rather than spelling it
            // null. Decoding `?? .null` for an optional field instead would hand the
            // decoder a null it is bound to refuse, turning "not sent" into a failure.
            is FieldType.Named, is FieldType.EnumRef ->
                if (field.optional)
                    "        self.${field.name} = try $member.flatMap { \$0 == .null ? nil : \$0 }" +
                        ".map { try ${swiftType(type)}(canonical: \$0, at: \"$path\") }"
                else "        self.${field.name} = try ${swiftType(type)}(canonical: $member ?? .null, at: \"$path\")"
            is FieldType.ArrayOf ->
                "        self.${field.name} = try SPFNDecoding.array($member, at: \"$path\").map { try ${swiftType(type.element)}(canonical: \$0, at: \"$path\") }"
            is FieldType.NumberType, is FieldType.MapOf -> unemittable(type)
        };
    }
}
