// Emits Kotlin from an app contract.
//
// Mirror of AppSwiftEmitter: three files in the package the consumer names — the helpers,
// the types, and an object holding the calls. Every call is an `SpfnCall` handed to
// `SpfnClient.execute`. Imports are computed from what each file uses, because an unused
// import is a warning and the SDK's modules compile with allWarningsAsErrors.

package xyz.superfunction.spfn.codegen

class AppKotlinEmitter(
    private val contract: AppContract,
    private val namespace: String,
    private val packageName: String
)
{
    private val support = "${namespace}Support";

    fun emit(): Map<String, String> = mapOf(
        "$support.kt" to file(AppKotlinSupport.helpers(support, AppUsage(contract))),
        "${namespace}Types.kt" to file(types()),
        "${namespace}Calls.kt" to file(calls())
    )

    private fun header(): String = AppHeader.lines(contract).joinToString("\n") { "// $it".trimEnd() }

    private fun file(body: String): String = buildString {
        appendLine(header());
        appendLine();
        appendLine("package $packageName");
        val imports = CORE_TYPES.filter { Regex("\\b$it\\b").containsMatchIn(body) };
        if (imports.isNotEmpty())
        {
            appendLine();
            imports.forEach { appendLine("import xyz.superfunction.spfn.core.$it") };
        }
        append(body);
    }

    // ---- types ------------------------------------------------------------

    private fun types(): String = contract.types.joinToString("") { type ->
        "\n" + when (type)
        {
            is Shape.Enumeration -> AppKotlinTypes.enumeration(type)
            is Shape.Record -> if (type.role == Role.RESPONSE) AppKotlinTypes.response(type, support)
            else AppKotlinTypes.body(type)
            else -> throw JsonException("only records and enumerations are emitted as types")
        }
    }

    // ---- calls ------------------------------------------------------------

    private fun calls(): String = buildString {
        appendLine();
        appendLine("/**");
        appendLine(" * The calls generated from this app's contract document. Hand one to");
        appendLine(" * `SpfnClient.execute`: the SDK signs, sends, retries and classifies failures exactly");
        appendLine(" * as it does for its own operations.");
        appendLine(" */");
        appendLine("object $namespace");
        appendLine("{");
        contract.operations.forEachIndexed { index, operation ->
            if (index > 0)
            {
                appendLine();
            }
            append(call(operation));
        };
        append("}");
        appendLine();
    }

    private fun call(operation: AppOperation): String = buildString {
        val request = operation.body?.typeName ?: "Unit";
        val response = operation.response?.typeName ?: "SpfnNoResponse";
        appendLine("    /** `${operation.method} ${operation.path}`${operation.since?.let { " — since $it" } ?: ""}. */");
        if (operation.response == null)
        {
            appendLine("    fun ${operation.name}(${arguments(operation)}): SpfnCall<$request, $response> = SpfnCall.noResponse(");
            appendLine("        operation = ${descriptor(operation)},");
            appendLine("        encode = ${encoder(operation)}");
            appendLine("    )");
        }
        else
        {
            appendLine("    fun ${operation.name}(${arguments(operation)}): SpfnCall<$request, $response> = SpfnCall(");
            appendLine("        operation = ${descriptor(operation)},");
            appendLine("        encode = ${encoder(operation)},");
            appendLine("        decode = { value -> $response.decode(value) }");
            appendLine("    )");
        }
    }

    private fun arguments(operation: AppOperation): String =
        (operation.pathParameters + operation.query).joinToString(", ") { property ->
            val type = AppKotlinTypes.typeName(property.shape);
            val name = Naming.kotlinIdentifier(property.name);
            if (property.required) "$name: $type" else "$name: $type? = null"
        }

    private fun encoder(operation: AppOperation): String =
        if (operation.body == null) "{ _ -> SpfnCanonicalValue.Obj(emptyMap()) }" else "{ body -> body.canonicalValue() }"

    private fun descriptor(operation: AppOperation): String = listOf(
        "SpfnOperation(",
        "            id = \"${operation.name}\",",
        "            method = \"${operation.method}\",",
        "            path = ${pathExpression(operation)},",
        "            authProfile = \"${operation.auth}\",",
        "            requiresSession = ${operation.requiresSession},",
        "            declaresResponse = ${operation.response != null}",
        "        )"
    ).joinToString("\n")

    private fun pathExpression(operation: AppOperation): String
    {
        val parameters = operation.pathParameters.associateBy { it.name };
        val pieces = operation.pathPieces.map { piece ->
            when (piece)
            {
                is PathPiece.Literal -> "\"${piece.text}\""
                is PathPiece.Parameter -> "$support.pathSegment(${stringOf(parameters.getValue(piece.name))})"
            }
        };
        val query = if (operation.query.isEmpty()) emptyList()
        else listOf("$support.queryString(listOf(${operation.query.joinToString(", ") { queryPair(it) }}))");
        return (pieces + query).joinToString(" + ");
    }

    private fun queryPair(property: Property): String
    {
        val name = Naming.kotlinIdentifier(property.name);
        val nullSafe = if (property.required) "" else "?";
        val value = when (property.shape)
        {
            is Shape.Text -> name
            is Shape.Enumeration -> "$name$nullSafe.wireValue"
            else -> "$name$nullSafe.toString()"
        };
        return "\"${property.name}\" to $value";
    }

    private fun stringOf(property: Property): String
    {
        val name = Naming.kotlinIdentifier(property.name);
        return if (property.shape is Shape.Text) name else "$name.toString()";
    }

    companion object
    {
        private val CORE_TYPES = listOf(
            "SpfnCall", "SpfnCanonicalValue", "SpfnDecoding", "SpfnDecodingException", "SpfnNoResponse", "SpfnOperation"
        );
    }
}
