// The Kotlin half: the service layer, which is the only layer that names a call descriptor.
//
// One protocol per service the spec declares and one default implementation of it against a
// real client. Everything above the service layer sees the protocol and the generated request
// and response types, and `tools/validate/validate.sh` refuses a descriptor reference
// anywhere else under a generated tree.
//
// Its twin is `SwiftServiceEmitter.kt`, declaration for declaration.

package xyz.superfunction.spfn.uicodegen

internal class KotlinServiceEmitter(target: Target) : KotlinNames(target)
{
    /**
     * The one generated file that names a call descriptor.
     *
     * Everything above it sees this interface and the generated request and response
     * types. `tools/validate/validate.sh` refuses a `SpfnGeneratedCalls.` reference
     * anywhere under `examples/` outside this directory, so the rule is enforced rather
     * than merely written down here.
     */
    internal fun service(service: ServiceDefinition, inputs: Inputs): String = buildString {
        appendLine(header(inputs));
        appendLine();
        appendLine("package $pkg.services");
        appendLine();
        appendLine("import xyz.superfunction.spfn.client.SpfnClient");
        appendLine("import xyz.superfunction.spfn.generated.SpfnGeneratedCalls");
        service.methods.map { request(it) }.plus(service.methods.map { response(it) })
            .filter { it != "Unit" }.distinct().sorted()
            .forEach { appendLine("import xyz.superfunction.spfn.generated.$it") };
        appendLine();
        appendLine("/** The `${service.name}` service: one method per operation the spec names. */");
        appendLine("interface ${type(service.name, "Service")}");
        appendLine("{");
        service.methods.forEachIndexed { index, method ->
            if (index > 0)
            {
                appendLine();
            }
            appendLine("    /** ${method.declaration.summary} */");
            appendLine("    ${signature(method)}");
        };
        appendLine("}");
        appendLine();
        append(defaultService(service));
    }

    private fun signature(method: ServiceMethod): String
    {
        val answers = response(method);
        val returns = if (answers == "Unit") "" else ": $answers";
        return "suspend fun ${method.name}(request: ${request(method)})$returns";
    }

    /**
     * The service against a real server. An operation that declares no response type
     * answers 204 with an empty body, so its method answers `Unit` and the descriptor's
     * `SpfnNoResponse` is discarded here rather than travelling up into a screen.
     */
    private fun defaultService(service: ServiceDefinition): String = buildString {
        appendLine("/** [${type(service.name, "Service")}] against a real server, through one client. */");
        appendLine("class Default${type(service.name, "Service")}(");
        appendLine("    private val client: SpfnClient");
        appendLine(") : ${type(service.name, "Service")}");
        appendLine("{");
        service.methods.forEachIndexed { index, method ->
            if (index > 0)
            {
                appendLine();
            }
            val descriptor = "SpfnGeneratedCalls.${method.operation}";
            if (response(method) == "Unit")
            {
                appendLine("    override ${signature(method)}");
                appendLine("    {");
                appendLine("        client.execute($descriptor, request);");
                appendLine("    }");
            }
            else
            {
                appendLine("    override ${signature(method)} =");
                appendLine("        client.execute($descriptor, request)");
            }
        };
        appendLine("}");
    }
}
