// The Swift half: the service layer, which is the only layer that names a call descriptor.
//
// One protocol per service the spec declares and one default implementation of it against a
// real client. Everything above the service layer sees the protocol and the generated request
// and response types, and `tools/validate/validate.sh` refuses a descriptor reference
// anywhere else under a generated tree.
//
// Its twin is `KotlinServiceEmitter.kt`, declaration for declaration.

package xyz.superfunction.spfn.uicodegen

internal class SwiftServiceEmitter(target: Target) : SwiftNames(target)
{
    internal fun service(service: ServiceDefinition, inputs: Inputs): String = buildString {
        appendLine(header(inputs));
        appendLine();
        appendLine("import SPFNClient");
        appendLine("import SPFNGenerated");
        appendLine();
        appendLine("/// The `${service.name}` service: one method per operation the spec names.");
        appendLine("///");
        appendLine("/// The one generated file that names a call descriptor. Everything above it sees this");
        appendLine("/// protocol and the generated request and response types, and");
        appendLine("/// `tools/validate/validate.sh` refuses a `SPFNGeneratedCalls.` reference anywhere");
        appendLine("/// under `examples/` outside this directory.");
        // `Sendable`, because the models that hold one are `@MainActor` and its methods
        // are not: an `async` call out of the main actor carries the service with it, and
        // Swift 6 refuses that for a value it cannot prove safe. `SPFNClient` is a
        // Sendable struct, so the default implementation is one for free; a hand-written
        // fake with counters of its own is an `actor`, which an `async` requirement admits.
        appendLine("public protocol ${type(service.name, "Service")}: Sendable");
        appendLine("{");
        service.methods.forEachIndexed { index, method ->
            if (index > 0)
            {
                appendLine();
            }
            appendLine("    /// ${method.declaration.summary}");
            appendLine("    ${signature(method)}");
        };
        appendLine("}");
        appendLine();
        append(defaultService(service));
    }

    private fun signature(method: ServiceMethod): String
    {
        val answers = response(method);
        val returns = if (answers == "Void") "" else " -> $answers";
        return "func ${method.name}(_ request: ${request(method)}) async throws$returns";
    }

    private fun defaultService(service: ServiceDefinition): String = buildString {
        appendLine("/// ``${type(service.name, "Service")}`` against a real server, through one client.");
        appendLine("///");
        appendLine("/// An operation that declares no response type answers 204 with an empty body, so its");
        appendLine("/// method answers `Void` and the descriptor's `SPFNNoResponse` is discarded here rather");
        appendLine("/// than travelling up into a screen.");
        appendLine("public struct Default${type(service.name, "Service")}: ${type(service.name, "Service")}, Sendable");
        appendLine("{");
        appendLine("    private let client: SPFNClient");
        appendLine();
        appendLine("    public init(client: SPFNClient)");
        appendLine("    {");
        appendLine("        self.client = client");
        appendLine("    }");
        service.methods.forEach { method ->
            appendLine();
            val descriptor = "SPFNGeneratedCalls.${method.operation}";
            appendLine("    public ${signature(method)}");
            appendLine("    {");
            if (response(method) == "Void")
            {
                appendLine("        _ = try await client.execute($descriptor, request: request)");
            }
            else
            {
                appendLine("        try await client.execute($descriptor, request: request)");
            }
            appendLine("    }");
        };
        appendLine("}");
    }
}
