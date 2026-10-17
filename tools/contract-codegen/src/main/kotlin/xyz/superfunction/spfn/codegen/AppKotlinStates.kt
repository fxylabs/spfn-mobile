// The Kotlin state descriptors for a recognised state router. Mirror of AppSwiftStates.

package xyz.superfunction.spfn.codegen

class AppKotlinStates(private val router: AppStateRouter, private val namespace: String, private val statesNamespace: String)
{
    fun body(): String = buildString {
        appendLine();
        appendLine("/**");
        appendLine(" * The state descriptors generated from this app's contract document, one per state its");
        appendLine(" * `getStates` snapshot names. Hand them to the state client; add [events] to the event");
        appendLine(" * stream's configuration.");
        appendLine(" */");
        appendLine("object $statesNamespace");
        appendLine("{");
        router.states.forEach { state ->
            append(descriptor(state));
            appendLine();
        };
        appendLine("    val all: List<SpfnStateDescriptor<*, *>> = listOf(${router.states.joinToString(", ") { Naming.kotlinIdentifier(it.id) }});");
        appendLine();
        appendLine("    val events: List<String> = listOf(${router.states.joinToString(", ") { "\"state.${it.id}.changed\"" }});");
        appendLine();
        appendLine("    val snapshot: (SpfnStateKey) -> SpfnCall<Unit, ${router.snapshot.response?.typeName}> =");
        appendLine("        ${snapshot()};");
        appendLine("}");
    }

    private fun descriptor(state: AppState): String = buildString {
        appendLine("    val ${Naming.kotlinIdentifier(state.id)} = SpfnStateDescriptor(");
        appendLine("        id = \"${state.id}\",");
        if (state.keyFields.isNotEmpty())
        {
            appendLine("        keyFields = listOf(${state.keyFields.joinToString(", ") { "\"$it\"" }}),");
        }
        appendLine("        read = ${call(state.read, state.keyFields)},");
        appendLine("        frame = ${frame(state)},");
        if (state.write == null)
        {
            appendLine("    );");
            return@buildString;
        }
        appendLine("    ).writable(write = ${call(state.write, state.keyFields)});");
    }

    /** Every key field is passed through `getValue`: the client never builds a call from a partial key (K-8). */
    private fun call(operation: AppOperation, keyFields: List<String>): String
    {
        if (keyFields.isEmpty())
        {
            return "{ $namespace.${operation.name}() }";
        }
        val arguments = keyFields.joinToString(", ") { "${Naming.kotlinIdentifier(it)} = key.getValue(\"$it\")" };
        return "{ key -> $namespace.${operation.name}($arguments) }";
    }

    private fun frame(state: AppState): String =
        "{ SpfnStateFrame(it.at, it.value, ${if (state.hasData) "it.data" else "Unit"}) }"

    /** The snapshot's query fields are optional, so a field the query lacks is not sent. */
    private fun snapshot(): String
    {
        val fields = router.snapshot.query.map { it.name };
        if (fields.isEmpty())
        {
            return "{ $namespace.${router.snapshot.name}() }";
        }
        val arguments = fields.joinToString(", ") { "${Naming.kotlinIdentifier(it)} = query[\"$it\"]" };
        return "{ query -> $namespace.${router.snapshot.name}($arguments) }";
    }

    companion object
    {
        /** Imported from `xyz.superfunction.spfn.state` when the file uses them. */
        val STATE_TYPES = listOf("SpfnStateDescriptor", "SpfnStateFrame", "SpfnStateKey");
    }
}
