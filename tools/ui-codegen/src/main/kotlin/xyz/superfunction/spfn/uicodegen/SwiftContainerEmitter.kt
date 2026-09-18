// The Swift half: the container, where every service, flow and model is built.
//
// The one file that knows how the app is wired: it holds the client, builds a default
// service per service the spec declares, a flow per flow and a factory per screen model. A
// model takes its service and its flow through its initialiser (D9), so the same model runs
// against a real client on a device and against a fake on a JVM with nothing substituted in
// between — and this is the file that decides which one it gets.
//
// It is the one emitter handed another: which models take a validator is the model emitter's
// answer, asked rather than derived a second time from the spec.
//
// Its twin is `KotlinContainerEmitter.kt`, declaration for declaration.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle

internal class SwiftContainerEmitter(target: Target, private val models: SwiftModelEmitter) : SwiftNames(target)
{
    internal fun container(spec: Spec, bundle: Bundle, inputs: Inputs): String = buildString {
        appendLine(header(inputs));
        appendLine("//");
        appendLine("// This directory is one Apple-only app target. Nothing here is compiled on Linux —");
        appendLine("// it sits outside Sources/, so the package build never sees it — which is why the");
        appendLine("// files that import SwiftUI are guarded and the ones that do not are plain.");
        appendLine();
        appendLine("import SPFNClient");
        appendLine("import SPFNUI");
        appendLine();
        appendLine("/// The app's one graph: services in, flows and screen models out.");
        appendLine("///");
        appendLine("/// Two ways in and no third. ``live(transport:keyProvider:baseURL:)`` builds the client");
        appendLine("/// the SDK's own way — one transport, one session over it, one client over that — and");
        appendLine("/// takes the key provider and the base URL from the app, which are the two things a");
        appendLine("/// generator cannot know. The memberwise initialiser takes a service directly, which is");
        appendLine("/// the door a launch fixture comes through; there is no fixture code here at all.");
        appendLine("@MainActor");
        appendLine("public final class AppContainer");
        appendLine("{");
        spec.services.forEach { appendLine("    private let ${it.name}: any ${type(it.name, "Service")}") };
        validated(spec, bundle).forEach {
            appendLine("    private let ${it.name}Validator: any FieldValidator");
        };
        appendLine();
        spec.flows.forEach { flow ->
            appendLine("    /// The `${flow.name}` flow, open on its start screen.");
            appendLine("    public let ${flow.name}Flow: Flow<${route(flow)}>");
            appendLine();
        };
        append(containerInit(spec, bundle));
        spec.screens.forEach { screen -> append(modelFactory(spec, screen, bundle)) };
        appendLine();
        append(liveFactory(spec, bundle));
        appendLine("}");
    }

    /**
     * The screens whose models take a validator the app must supply, in spec order.
     *
     * A custom rule is a SENTENCE somebody wrote, and a sentence is app code: the generator
     * knows the rule's name and nothing else about it. So it is injected where a service is
     * injected — through the container — rather than defaulted to nil in a factory, which
     * would be a screen whose rule is never asked and whose field is always accepted.
     */
    private fun validated(spec: Spec, bundle: Bundle): List<ScreenDefinition> =
        spec.screens.filter { models.takesValidator(it, bundle) && models.customRule(it) }

    private fun containerInit(spec: Spec, bundle: Bundle): String = buildString {
        val given = spec.services.map { "${it.name}: any ${type(it.name, "Service")}" } +
            validated(spec, bundle).map { "${it.name}Validator: any FieldValidator" };
        appendLine("    public init(");
        given.forEachIndexed { index, parameter ->
            appendLine("        $parameter${if (index == given.size - 1) "" else ","}");
        };
        appendLine("    )");
        appendLine("    {");
        spec.services.forEach { appendLine("        self.${it.name} = ${it.name}") };
        validated(spec, bundle).forEach {
            appendLine("        self.${it.name}Validator = ${it.name}Validator");
        };
        spec.flows.forEach { appendLine("        self.${it.name}Flow = ${type(it.name, "Flow")}()") };
        appendLine("    }");
    }

    private fun modelFactory(spec: Spec, screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val flow = spec.flows.first { it.name == screen.flow };
        val parameters = RouteParameters.of(screen, bundle);
        val arguments = mutableListOf<String>();
        if (screen.usecase)
        {
            arguments += "useCase: Default${type(screen.name, "UseCase")}(service: ${requireNotNull(screen.source).service})";
        }
        screen.services.forEach { arguments += "$it: $it" };
        arguments += "flow: ${flow.name}Flow";
        parameters.forEach { arguments += "${it.name}: ${it.name}" };
        if (models.customRule(screen))
        {
            arguments += "validator: ${screen.name}Validator";
        }
        appendLine();
        appendLine("    /// A fresh model for one appearance of `${screen.name}`.");
        appendLine("    public func ${screen.name}Model(${models.parameterList(parameters)}) -> ${type(screen.name, "Model")}");
        appendLine("    {");
        appendLine("        ${type(screen.name, "Model")}(${arguments.joinToString(", ")})");
        appendLine("    }");
    }

    private fun liveFactory(spec: Spec, bundle: Bundle): String = buildString {
        val validators = validated(spec, bundle);
        val services = (spec.services.map { "${it.name}: Default${type(it.name, "Service")}(client: client)" } +
            validators.map { "${it.name}Validator: ${it.name}Validator" }).joinToString(", ");
        appendLine("    /// The app against a real server: one transport, one session, one client.");
        appendLine("    ///");
        appendLine("    /// Throws `SPFNSessionError.untrustedBaseURL` when `baseURL` is neither https nor");
        appendLine("    /// http to loopback: the session refuses cleartext at creation, and this is where");
        appendLine("    /// a generated app creates one.");
        appendLine("    public static func live(");
        appendLine("        transport: any SPFNTransport,");
        appendLine("        keyProvider: any SPFNKeyProvider,");
        appendLine("        baseURL: String" + if (validators.isEmpty()) "" else ",");
        validators.forEachIndexed { index, screen ->
            val comma = if (index == validators.size - 1) "" else ",";
            appendLine("        ${screen.name}Validator: any FieldValidator$comma");
        };
        appendLine("    ) throws -> AppContainer");
        appendLine("    {");
        appendLine("        let session = try SPFNSession(");
        appendLine("            transport: transport,");
        appendLine("            keyProvider: keyProvider,");
        appendLine("            baseURL: baseURL");
        appendLine("        )");
        appendLine("        let client = SPFNClient(transport: transport, session: session)");
        appendLine("        return AppContainer($services)");
        appendLine("    }");
    }
}
