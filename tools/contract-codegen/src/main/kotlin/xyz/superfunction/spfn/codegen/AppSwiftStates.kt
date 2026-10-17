// The Swift state descriptors for a recognised state router (state-client-design §2-13-2).
//
// One `SPFNStateDescriptor` per state, built from the generated calls of the same run: the
// read (and write) function moves the key's fields into the call's named arguments, and
// `frame` takes `at`, `value` and `data` out of the generated response. The generator only
// writes the SDK's names as text — it depends on no SDK module. AppKotlinStates is the
// mirror; a change to one lands in the other in the same commit.

package xyz.superfunction.spfn.codegen

class AppSwiftStates(private val router: AppStateRouter, private val namespace: String, private val statesNamespace: String)
{
    fun body(): String = buildString {
        appendLine();
        appendLine("/// The state descriptors generated from this app's contract document, one per state its");
        appendLine("/// `getStates` snapshot names. Hand them to the state client; add `events` to the event");
        appendLine("/// stream's configuration.");
        appendLine("public enum $statesNamespace");
        appendLine("{");
        router.states.forEach { state ->
            append(descriptor(state));
            appendLine();
        };
        appendLine("    public static let all: [any SPFNAnyStateDescriptor] = [${router.states.joinToString(", ") { Naming.swiftIdentifier(it.id) }}]");
        appendLine();
        appendLine("    public static let events: [String] = [${router.states.joinToString(", ") { "\"state.${it.id}.changed\"" }}]");
        appendLine();
        appendLine("    public static let snapshot: @Sendable (SPFNStateKey) -> SPFNCall<Void, $namespace.${router.snapshot.response?.typeName}> =");
        appendLine("        ${snapshot()}");
        appendLine("}");
    }

    private fun descriptor(state: AppState): String = buildString {
        appendLine("    public static let ${Naming.swiftIdentifier(state.id)} = SPFNStateDescriptor(");
        appendLine("        id: \"${state.id}\",");
        if (state.keyFields.isNotEmpty())
        {
            appendLine("        keyFields: [${state.keyFields.joinToString(", ") { "\"$it\"" }}],");
        }
        appendLine("        read: ${call(state.read, state.keyFields)},");
        appendLine("        frame: ${frame(state)}");
        if (state.write == null)
        {
            appendLine("    )");
            return@buildString;
        }
        // No second frame: the write's answer is its own generated type, which the read's
        // decoder reads — the recognizer proved the two response schemas equal.
        appendLine("    ).writable(write: ${call(state.write, state.keyFields)})");
    }

    /** Every key field is passed, force-unwrapped: the client never builds a call from a partial key (K-8). */
    private fun call(operation: AppOperation, keyFields: List<String>): String
    {
        val arguments = keyFields.joinToString(", ") { "${Naming.swiftIdentifier(it)}: key[\"$it\"]!" };
        return "{ ${if (keyFields.isEmpty()) "_" else "key"} in $namespace.${operation.name}($arguments) }";
    }

    private fun frame(state: AppState): String =
        "{ SPFNStateFrame(at: \$0.at, value: \$0.value, data: ${if (state.hasData) "\$0.data" else "SPFNNoStateData()"}) }"

    /** The snapshot's query fields are optional, so a field the query lacks is not sent. */
    private fun snapshot(): String
    {
        val fields = router.snapshot.query.map { it.name };
        val arguments = fields.joinToString(", ") { "${Naming.swiftIdentifier(it)}: query[\"$it\"]" };
        return "{ ${if (fields.isEmpty()) "_" else "query"} in $namespace.${router.snapshot.name}($arguments) }";
    }
}
