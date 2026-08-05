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
        appendLine("        supportedMinor = ${bundle.contractMinor},");
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
        appendLine();
        appendLine("    /**");
        appendLine("     * `keyPolicy.ttlDays`: a registered public key expires this many days after");
        appendLine("     * registration, so the client rotates before the TTL runs out.");
        appendLine("     */");
        appendLine("    const val KEY_POLICY_TTL_DAYS: Long = ${bundle.keyPolicyTtlDays}L");
        appendLine();
        appendLine("    /** `keyPolicy.rotationOperation`: the operation that replaces a registered key. */");
        appendLine("    const val KEY_ROTATION_OPERATION_ID: String = \"${bundle.keyRotationOperationId}\"");
        appendLine();
        appendLine("    /** `clientProofV1.clientIdRule`, verbatim from the bundle. */");
        appendLine("    const val CLIENT_ID_RULE: String = \"${kotlinStringLiteral(bundle.clientIdRule)}\"");
        append("}");
        appendLine();
    }

    private fun kotlinStringLiteral(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

    private fun types(bundle: Bundle): String = buildString {
        appendLine(header(bundle));
        appendLine();
        appendLine("package xyz.superfunction.spfn.generated");
        appendLine();
        appendLine("import xyz.superfunction.spfn.core.SpfnCanonicalValue");
        appendLine("import xyz.superfunction.spfn.core.SpfnDecoding");
        appendLine("import xyz.superfunction.spfn.core.SpfnDecodingException");
        bundle.enums.forEach { declaration ->
            appendLine();
            appendLine("/**");
            appendLine(" * A value set the contract declares. Decoding is strict: an unknown value is");
            appendLine(" * reported with the raw string preserved rather than mapped onto a member,");
            appendLine(" * because the contract promises no set stays as it is — a value can be added,");
            appendLine(" * and one can be withdrawn for a weakness found later.");
            appendLine(" */");
            appendLine("enum class ${Names.kotlinType(declaration.name)}(val wireValue: String)");
            appendLine("{");
            declaration.values.forEachIndexed { index, value ->
                val terminator = if (index == declaration.values.size - 1) ";" else ",";
                appendLine("    ${Names.upperSnake(Names.enumCase(value))}(\"$value\")$terminator");
            }
            appendLine();
            appendLine("    fun canonicalValue(): SpfnCanonicalValue = SpfnCanonicalValue.Text(wireValue);");
            appendLine();
            appendLine("    companion object");
            appendLine("    {");
            appendLine("        fun decode(canonical: SpfnCanonicalValue, path: String = \"\\\$\"): ${Names.kotlinType(declaration.name)}");
            appendLine("        {");
            appendLine("            val raw = SpfnDecoding.string(canonical, path);");
            appendLine("            return entries.firstOrNull { it.wireValue == raw }");
            appendLine("                ?: throw SpfnDecodingException(\"TYPE_MISMATCH\", \"\$path is not a ${declaration.name}\");");
            appendLine("        }");
            appendLine("    }");
            append("}");
            appendLine();
        }
        bundle.types.forEach { type ->
            appendLine();
            appendLine("data class ${Names.kotlinType(type.name)}(");
            type.fields.forEachIndexed { index, field ->
                val comma = if (index == type.fields.size - 1) "" else ",";
                val default = if (field.optional) " = null" else "";
                appendLine("    val ${field.name}: ${kotlinType(bundle, field)}$default$comma");
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
            type.fields.forEach { field -> appendLine(kotlinEncodeField(bundle, field)) };
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
                appendLine("                ${field.name} = ${kotlinDecodeExpression(bundle, field)}$comma");
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
        appendLine("/**");
        appendLine(" * Every auth class the contract declares. An operation's `authProfile` names one");
        appendLine(" * of these; a value outside the list is a contract mismatch, and a caller refuses");
        appendLine(" * to send rather than downgrading to any other class.");
        appendLine(" */");
        appendLine("enum class SpfnGeneratedAuthClass(val wireName: String)");
        appendLine("{");
        bundle.authClasses.forEachIndexed { index, authClass ->
            val terminator = if (index == bundle.authClasses.size - 1) ";" else ",";
            appendLine("    ${Names.upperSnake(authClass)}(\"$authClass\")$terminator");
        }
        appendLine();
        appendLine("    companion object");
        appendLine("    {");
        appendLine("        /**");
        appendLine("         * Resolves an operation's auth class, or null for a class this contract");
        appendLine("         * does not declare. The caller fails closed on null instead of guessing.");
        appendLine("         */");
        appendLine("        fun of(operation: SpfnOperation): SpfnGeneratedAuthClass? =");
        appendLine("            entries.firstOrNull { it.wireName == operation.authProfile }");
        appendLine("    }");
        appendLine("}");
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
        appendLine(" * Which surface answers with a given code.");
        appendLine(" *");
        appendLine(" * The contract carries both sets in one list and they are not interchangeable: a");
        appendLine(" * proven call can be met by a clientProofV1 refusal and never by a rest one, and a");
        appendLine(" * call to the /_auth surface is the reverse. Code that reasons about a refusal");
        appendLine(" * reads this rather than the position a code happened to have.");
        appendLine(" */");
        appendLine("enum class SpfnGeneratedErrorSurface(val wireValue: String)");
        appendLine("{");
        val surfaces = bundle.errors.map { it.surface }.distinct().sorted();
        surfaces.forEachIndexed { index, surface ->
            val terminator = if (index == surfaces.size - 1) ";" else ",";
            appendLine("    ${Names.upperSnake(surface)}(\"$surface\")$terminator");
        }
        append("}");
        appendLine();
        appendLine();
        appendLine("/**");
        appendLine(" * Every error code the contract declares. A code outside this list is rejected");
        appendLine(" * rather than mapped onto a neighbouring one.");
        appendLine(" */");
        appendLine("enum class SpfnGeneratedErrorCode(");
        appendLine("    val wireCode: String,");
        appendLine("    val httpStatus: Int,");
        appendLine("    val isRetryable: Boolean,");
        appendLine("    val surface: SpfnGeneratedErrorSurface");
        appendLine(")");
        appendLine("{");
        bundle.errors.forEachIndexed { index, error ->
            val terminator = if (index == bundle.errors.size - 1) ";" else ",";
            appendLine(
                "    ${error.code}(\"${error.code}\", ${error.httpStatus}, ${error.retryable}, " +
                    "SpfnGeneratedErrorSurface.${Names.upperSnake(error.surface)})$terminator"
            );
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

    /**
     * The cases `Bundle.checkDeclarations` has already refused. They are unreachable
     * here, and they still get a branch: an emitter that quietly produced something for
     * one of them is the failure the 0.6.0 pin walked into, and Kotlin's exhaustiveness
     * check is what keeps a future case from being answered with a guess.
     */
    private fun unemittable(type: FieldType): Nothing =
        throw JsonException("the Kotlin emitter reached $type, which generation should have refused")

    private fun kotlinType(bundle: Bundle, field: Field): String
    {
        val base = kotlinType(bundle.fieldType(field));
        return if (field.optional) "$base?" else base;
    }

    private fun kotlinType(type: FieldType): String = when (type)
    {
        is FieldType.StringType -> "String"
        is FieldType.IntegerType -> "Long"
        is FieldType.BooleanType -> "Boolean"
        is FieldType.Named -> Names.kotlinType(type.name)
        is FieldType.EnumRef -> Names.kotlinType(type.name)
        is FieldType.ArrayOf -> "List<${kotlinType(type.element)}>"
        is FieldType.NumberType, is FieldType.MapOf -> unemittable(type)
    }

    private fun kotlinEncodeField(bundle: Bundle, field: Field): String
    {
        val expression = kotlinEncodeExpression(bundle.fieldType(field), field.name);
        if (!field.optional)
        {
            return "        members[\"${field.name}\"] = $expression;";
        }
        return buildString {
            appendLine("        if (${field.name} != null)");
            appendLine("        {");
            appendLine("            members[\"${field.name}\"] = $expression;");
            append("        }");
        };
    }

    private fun kotlinEncodeExpression(type: FieldType, accessor: String): String = when (type)
    {
        is FieldType.StringType -> "SpfnCanonicalValue.Text($accessor)"
        is FieldType.IntegerType -> "SpfnCanonicalValue.Integer($accessor)"
        is FieldType.BooleanType -> "SpfnCanonicalValue.Bool($accessor)"
        is FieldType.Named -> "$accessor.canonicalValue()"
        is FieldType.EnumRef -> "$accessor.canonicalValue()"
        is FieldType.ArrayOf -> "SpfnCanonicalValue.Arr($accessor.map { it.canonicalValue() })"
        is FieldType.NumberType, is FieldType.MapOf -> unemittable(type)
    }

    private fun kotlinDecodeExpression(bundle: Bundle, field: Field): String
    {
        // Emits `$path.<field>` so the generated decoder reports the position it failed at.
        val path = "\$path.${field.name}";
        val member = "members[\"${field.name}\"]";
        return when (val type = bundle.fieldType(field))
        {
            is FieldType.StringType ->
                if (field.optional) "SpfnDecoding.optionalString($member, \"$path\")"
                else "SpfnDecoding.string($member, \"$path\")"
            is FieldType.IntegerType ->
                if (field.optional) "SpfnDecoding.optionalInteger($member, \"$path\")"
                else "SpfnDecoding.integer($member, \"$path\")"
            is FieldType.BooleanType -> "SpfnDecoding.boolean($member, \"$path\")"
            // An optional composite reads absent and null alike as nothing, which is what
            // the encoder writes: it omits an absent optional rather than spelling it
            // null. Passing `?: Null` for an optional field instead would hand the decoder
            // a null it is bound to refuse, turning "not sent" into a failure.
            is FieldType.Named, is FieldType.EnumRef ->
                if (field.optional)
                    "$member?.takeIf { it !is SpfnCanonicalValue.Null }" +
                        "?.let { ${kotlinType(type)}.decode(it, \"$path\") }"
                else "${kotlinType(type)}.decode($member ?: SpfnCanonicalValue.Null, \"$path\")"
            is FieldType.ArrayOf ->
                "SpfnDecoding.array($member, \"$path\").map { ${kotlinType(type.element)}.decode(it, \"$path\") }"
            is FieldType.NumberType, is FieldType.MapOf -> unemittable(type)
        };
    }
}
