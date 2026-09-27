// Emits Swift from an app contract.
//
// Three files under the namespace the consumer names: the namespace with its helpers, the
// types, and the calls. Every call is an `SPFNCall` handed to `SPFNClient.execute`, so an
// app's operation meets the same proof, retry and error classification as a core one.
// AppKotlinEmitter is the mirror; a change to one lands in the other in the same commit.

package xyz.superfunction.spfn.codegen

class AppSwiftEmitter(private val contract: AppContract, private val namespace: String)
{
    fun emit(): Map<String, String> = mapOf(
        "$namespace.swift" to support(),
        "${namespace}Types.swift" to types(),
        "${namespace}Calls.swift" to calls()
    )

    private fun header(): String = AppHeader.lines(contract).joinToString("\n") { "// $it".trimEnd() }

    private fun file(body: String): String = header() + "\n\nimport SPFNCore\n" + body

    // ---- the namespace and its helpers ------------------------------------

    private fun support(): String = file(buildString {
        appendLine();
        appendLine("/// The calls and types generated from this app's contract document.");
        appendLine("///");
        appendLine("/// Hand a call to `SPFNClient.execute`: the SDK signs, sends, retries and classifies");
        appendLine("/// failures exactly as it does for its own operations.");
        appendLine("public enum $namespace");
        appendLine("{");
        append(AppSwiftSupport.helpers(AppUsage(contract)));
        append("}");
        appendLine();
    })

    // ---- types ------------------------------------------------------------

    private fun types(): String = file(buildString {
        appendLine();
        appendLine("extension $namespace");
        appendLine("{");
        contract.types.forEachIndexed { index, type ->
            if (index > 0)
            {
                appendLine();
            }
            append(type(type));
        };
        append("}");
        appendLine();
    })

    private fun type(type: Shape): String = when (type)
    {
        is Shape.Enumeration -> AppSwiftTypes.enumeration(type)
        is Shape.Record -> if (type.role == Role.RESPONSE) AppSwiftTypes.response(type, namespace)
        else AppSwiftTypes.body(type)
        else -> throw JsonException("only records and enumerations are emitted as types")
    }

    // ---- calls ------------------------------------------------------------

    private fun calls(): String = file(buildString {
        appendLine();
        appendLine("extension $namespace");
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
    })

    private fun call(operation: AppOperation): String = buildString {
        val request = operation.body?.typeName ?: "Void";
        val response = operation.response?.typeName ?: "SPFNNoResponse";
        appendLine("    /// `${operation.method} ${operation.path}`${operation.since?.let { " — since $it" } ?: ""}.");
        appendLine("    public static func ${operation.name}(${arguments(operation)}) -> SPFNCall<$request, $response>");
        appendLine("    {");
        if (operation.response == null)
        {
            appendLine("        SPFNCall<$request, SPFNNoResponse>.noResponse(");
            appendLine("            operation: ${descriptor(operation)},");
            appendLine("            encode: ${encoder(operation)}");
            appendLine("        )");
        }
        else
        {
            appendLine("        SPFNCall(");
            appendLine("            operation: ${descriptor(operation)},");
            appendLine("            encode: ${encoder(operation)},");
            appendLine("            decode: { try $response(canonical: \$0) }");
            appendLine("        )");
        }
        appendLine("    }");
    }

    private fun arguments(operation: AppOperation): String =
        (operation.pathParameters + operation.query).joinToString(", ") { property ->
            val type = AppSwiftTypes.typeName(property.shape);
            val name = Naming.swiftIdentifier(property.name);
            if (property.required) "$name: $type" else "$name: $type? = nil"
        }

    private fun encoder(operation: AppOperation): String =
        if (operation.body == null) "{ _ in SPFNCanonicalValue.object([:]) }" else "{ \$0.canonicalValue() }"

    /** The descriptor, laid out one field per line inside the call's argument list. */
    private fun descriptor(operation: AppOperation): String = listOf(
        "SPFNOperation(",
        "                id: \"${operation.name}\",",
        "                method: \"${operation.method}\",",
        "                path: ${pathExpression(operation)},",
        "                authProfile: \"${operation.auth}\",",
        "                requiresSession: ${operation.requiresSession},",
        "                declaresResponse: ${operation.response != null}",
        "            )"
    ).joinToString("\n")

    private fun pathExpression(operation: AppOperation): String
    {
        val parameters = operation.pathParameters.associateBy { it.name };
        val pieces = operation.pathPieces.map { piece ->
            when (piece)
            {
                is PathPiece.Literal -> "\"${piece.text}\""
                is PathPiece.Parameter -> "$namespace.pathSegment(${stringOf(parameters.getValue(piece.name))})"
            }
        };
        val query = if (operation.query.isEmpty()) emptyList()
        else listOf("$namespace.queryString([${operation.query.joinToString(", ") { queryPair(it) }}])");
        return (pieces + query).joinToString(" + ");
    }

    private fun queryPair(property: Property): String = "(\"${property.name}\", ${optionalStringOf(property)})"

    private fun stringOf(property: Property): String
    {
        val name = Naming.swiftIdentifier(property.name);
        return when (property.shape)
        {
            is Shape.Text -> name
            is Shape.Enumeration -> "$name.wireValue"
            else -> "String($name)"
        };
    }

    private fun optionalStringOf(property: Property): String
    {
        if (property.required)
        {
            return stringOf(property);
        }
        val name = Naming.swiftIdentifier(property.name);
        return when (property.shape)
        {
            is Shape.Text -> name
            is Shape.Enumeration -> "$name?.wireValue"
            else -> "$name.map { String(\$0) }"
        };
    }
}

/** Which helpers the generated namespace needs, so a file carries none it never calls. */
class AppUsage(contract: AppContract)
{
    val pathSegments: Boolean = contract.operations.any { it.pathParameters.isNotEmpty() }

    val query: Boolean = contract.operations.any { it.query.isNotEmpty() }

    val percentEncoding: Boolean = pathSegments || query

    private val responses = contract.types.filterIsInstance<Shape.Record>().filter { it.role == Role.RESPONSE };

    val present: Boolean = responses.any { record -> record.properties.any { it.required && it.nullable } }

    val nonNull: Boolean = responses.any { record -> record.properties.any { !it.required || it.nullable } }
}
