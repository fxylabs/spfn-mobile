// Emits the Kotlin client from the bundle.
//
// Mirror of SwiftEmitter. The two must stay structurally identical, because the whole
// value of dual generation is that a contract change lands on both platforms in the
// same run rather than being ported by hand later.

package xyz.superfunction.spfn.codegen

object KotlinEmitter
{
    private const val ROOT = "android/spfn-generated/src/main/kotlin/xyz/superfunction/spfn/generated"

    fun emit(bundle: Bundle): Map<String, String> = mapOf(
        "$ROOT/SpfnGeneratedContract.kt" to contract(bundle),
        "$ROOT/SpfnGeneratedTypes.kt" to types(bundle),
        "$ROOT/SpfnGeneratedOperations.kt" to operations(bundle),
        "$ROOT/SpfnGeneratedErrors.kt" to errors(bundle)
    )

    private fun header(bundle: Bundle): String = Header.lines(bundle).joinToString("\n") { "// $it".trimEnd() }

    private fun contract(bundle: Bundle): String = buildString {
        appendLine(header(bundle));
        appendLine();
        appendLine("package xyz.superfunction.spfn.generated");
        appendLine();
        appendLine("import xyz.superfunction.spfn.core.SpfnContractBinding");
        appendLine();
        appendLine("/** What this build was generated from. */");
        appendLine("object SpfnGeneratedContract");
        appendLine("{");
        appendLine("    /** The generator that produced this directory. */");
        appendLine("    const val GENERATOR_VERSION: String = \"${Header.GENERATOR}\"");
        appendLine();
        appendLine("    /** The pinned bundle these sources were derived from. */");
        appendLine("    val BINDING: SpfnContractBinding = SpfnContractBinding(");
        appendLine("        importedVersion = \"${bundle.contractVersion}\",");
        appendLine("        importedManifestSha256 = \"${bundle.sha256}\",");
        appendLine("        supportedRange = \"${bundle.supportedRange}\",");
        appendLine("        supportedMajor = ${bundle.contractMajor},");
        appendLine("        origin = \"${bundle.origin}\"");
        appendLine("    )");
        appendLine();
        appendLine("    /** Every operation the contract declares, in bundle order. */");
        appendLine("    val OPERATION_IDS: List<String> = listOf(");
        bundle.operations.forEachIndexed { index, operation ->
            val comma = if (index == bundle.operations.size - 1) "" else ",";
            appendLine("        \"${operation.id}\"$comma");
        }
        appendLine("    )");
        appendLine();
        appendLine("    /** The replay window the contract fixes, in milliseconds. */");
        appendLine("    const val REPLAY_WINDOW_MILLIS: Long = ${bundle.replayWindowMillis}L");
        appendLine();
        appendLine("    /** The proof-input field order the contract fixes. */");
        appendLine("    val PROOF_INPUT_FIELDS: List<String> = listOf(");
        bundle.proofInputFields.forEachIndexed { index, field ->
            val comma = if (index == bundle.proofInputFields.size - 1) "" else ",";
            appendLine("        \"$field\"$comma");
        }
        appendLine("    )");
        append("}");
        appendLine();
    }

    private fun types(bundle: Bundle): String = buildString {
        appendLine(header(bundle));
        appendLine();
        appendLine("package xyz.superfunction.spfn.generated");
        appendLine();
        appendLine("import xyz.superfunction.spfn.core.SpfnCanonicalValue");
        appendLine("import xyz.superfunction.spfn.core.SpfnDecoding");
        bundle.types.forEach { type ->
            appendLine();
            appendLine("data class ${Names.kotlinType(type.name)}(");
            type.fields.forEachIndexed { index, field ->
                val comma = if (index == type.fields.size - 1) "" else ",";
                val default = if (field.optional) " = null" else "";
                appendLine("    val ${field.name}: ${kotlinType(field)}$default$comma");
            }
            appendLine(")");
            appendLine("{");
            appendLine("    /**");
            appendLine("     * The canonical form of this value. An absent optional field is omitted,");
            appendLine("     * never written as null, so the digest of a value never depends on how a");
            appendLine("     * caller happened to spell \"nothing\".");
            appendLine("     */");
            appendLine("    fun canonicalValue(): SpfnCanonicalValue");
            appendLine("    {");
            appendLine("        val members = LinkedHashMap<String, SpfnCanonicalValue>();");
            type.fields.forEach { field -> appendLine(kotlinEncodeField(field)) };
            appendLine("        return SpfnCanonicalValue.Obj(members);");
            appendLine("    }");
            appendLine();
            appendLine("    companion object");
            appendLine("    {");
            appendLine("        fun decode(canonical: SpfnCanonicalValue, path: String = \"\\\$\"): ${Names.kotlinType(type.name)}");
            appendLine("        {");
            appendLine("            val members = SpfnDecoding.obj(canonical, path);");
            appendLine("            return ${Names.kotlinType(type.name)}(");
            type.fields.forEachIndexed { index, field ->
                val comma = if (index == type.fields.size - 1) "" else ",";
                appendLine("                ${field.name} = ${kotlinDecodeExpression(field)}$comma");
            }
            appendLine("            );");
            appendLine("        }");
            appendLine("    }");
            append("}");
            appendLine();
        }
    }

    private fun operations(bundle: Bundle): String = buildString {
        appendLine(header(bundle));
        appendLine();
        appendLine("package xyz.superfunction.spfn.generated");
        appendLine();
        appendLine("import xyz.superfunction.spfn.core.SpfnOperation");
        appendLine();
        appendLine("object SpfnGeneratedOperations");
        appendLine("{");
        bundle.operations.forEachIndexed { index, operation ->
            if (index > 0)
            {
                appendLine();
            }
            appendLine("    /** ${operation.summary} */");
            appendLine("    val ${Names.lowerCamel(operation.id)}: SpfnOperation = SpfnOperation(");
            appendLine("        id = \"${operation.id}\",");
            appendLine("        method = \"${operation.method}\",");
            appendLine("        path = \"${operation.path}\",");
            appendLine("        authProfile = \"${operation.authProfile}\",");
            appendLine("        requiresSession = ${operation.requiresSession}");
            appendLine("    )");
        }
        appendLine();
        appendLine("    /** Every operation, in bundle order. */");
        appendLine("    val all: List<SpfnOperation> = listOf(");
        bundle.operations.forEachIndexed { index, operation ->
            val comma = if (index == bundle.operations.size - 1) "" else ",";
            appendLine("        ${Names.lowerCamel(operation.id)}$comma");
        }
        appendLine("    )");
        appendLine();
        appendLine("    /**");
        appendLine("     * Looks an operation up by contract id. Returns null rather than a nearest");
        appendLine("     * match: an unknown id is a contract mismatch, not a typo to be forgiven.");
        appendLine("     */");
        appendLine("    fun operation(id: String): SpfnOperation? = all.firstOrNull { it.id == id }");
        append("}");
        appendLine();
    }

    private fun errors(bundle: Bundle): String = buildString {
        appendLine(header(bundle));
        appendLine();
        appendLine("package xyz.superfunction.spfn.generated");
        appendLine();
        appendLine("import xyz.superfunction.spfn.core.SpfnDecodingException");
        appendLine();
        appendLine("/**");
        appendLine(" * Every error code the contract declares. A code outside this list is rejected");
        appendLine(" * rather than mapped onto a neighbouring one.");
        appendLine(" */");
        appendLine("enum class SpfnGeneratedErrorCode(");
        appendLine("    val wireCode: String,");
        appendLine("    val httpStatus: Int,");
        appendLine("    val isRetryable: Boolean");
        appendLine(")");
        appendLine("{");
        bundle.errors.forEachIndexed { index, error ->
            val terminator = if (index == bundle.errors.size - 1) ";" else ",";
            appendLine("    ${error.code}(\"${error.code}\", ${error.httpStatus}, ${error.retryable})$terminator");
        }
        appendLine();
        appendLine("    companion object");
        appendLine("    {");
        appendLine("        /** Resolves a wire code, or throws with the raw string preserved. */");
        appendLine("        fun decode(raw: String): SpfnGeneratedErrorCode =");
        appendLine("            entries.firstOrNull { it.wireCode == raw }");
        appendLine("                ?: throw SpfnDecodingException(\"UNKNOWN_ERROR_CODE\", \"unknown error code '\$raw'\");");
        appendLine("    }");
        append("}");
        appendLine();
    }

    private fun kotlinType(field: Field): String
    {
        val base = kotlinType(FieldType.parse(field.type));
        return if (field.optional) "$base?" else base;
    }

    private fun kotlinType(type: FieldType): String = when (type)
    {
        is FieldType.StringType -> "String"
        is FieldType.IntegerType -> "Long"
        is FieldType.BooleanType -> "Boolean"
        is FieldType.Named -> Names.kotlinType(type.name)
        is FieldType.ArrayOf -> "List<${kotlinType(type.element)}>"
    }

    private fun kotlinEncodeField(field: Field): String
    {
        val expression = kotlinEncodeExpression(FieldType.parse(field.type), field.name);
        if (!field.optional)
        {
            return "        members[\"${field.name}\"] = $expression;";
        }
        return buildString {
            appendLine("        if (${field.name} != null)");
            appendLine("        {");
            appendLine("            members[\"${field.name}\"] = ${kotlinEncodeExpression(FieldType.parse(field.type), field.name)};");
            append("        }");
        };
    }

    private fun kotlinEncodeExpression(type: FieldType, accessor: String): String = when (type)
    {
        is FieldType.StringType -> "SpfnCanonicalValue.Text($accessor)"
        is FieldType.IntegerType -> "SpfnCanonicalValue.Integer($accessor)"
        is FieldType.BooleanType -> "SpfnCanonicalValue.Bool($accessor)"
        is FieldType.Named -> "$accessor.canonicalValue()"
        is FieldType.ArrayOf -> "SpfnCanonicalValue.Arr($accessor.map { it.canonicalValue() })"
    }

    private fun kotlinDecodeExpression(field: Field): String
    {
        // Emits `$path.<field>` so the generated decoder reports the position it failed at.
        val path = "\$path.${field.name}";
        val member = "members[\"${field.name}\"]";
        return when (val type = FieldType.parse(field.type))
        {
            is FieldType.StringType ->
                if (field.optional) "SpfnDecoding.optionalString($member, \"$path\")"
                else "SpfnDecoding.string($member, \"$path\")"
            is FieldType.IntegerType ->
                if (field.optional) "SpfnDecoding.optionalInteger($member, \"$path\")"
                else "SpfnDecoding.integer($member, \"$path\")"
            is FieldType.BooleanType -> "SpfnDecoding.boolean($member, \"$path\")"
            is FieldType.Named ->
                "${Names.kotlinType(type.name)}.decode($member ?: SpfnCanonicalValue.Null, \"$path\")"
            is FieldType.ArrayOf ->
                "SpfnDecoding.array($member, \"$path\").map { ${kotlinType(type.element)}.decode(it, \"$path\") }"
        };
    }
}
