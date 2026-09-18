// The Kotlin half: the container, where every service, flow and model is built.
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
// Its twin is `SwiftContainerEmitter.kt`, declaration for declaration.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle

internal class KotlinContainerEmitter(target: Target, private val models: KotlinModelEmitter) : KotlinNames(target)
{
    /**
     * What the app holds: one service, one flow per flow, and a model factory per screen.
     *
     * Two ways in and no third. `live` builds the client the SDK's own way — one
     * transport, one session over it, one client over that — and takes the key provider
     * and the base URL from the app, which are the two things a generator cannot know.
     * The primary constructor takes a service directly, which is the door a launch
     * fixture comes through; there is no fixture code here at all, so a build with no
     * fixture has nothing inert to carry.
     */
    internal fun container(spec: Spec, bundle: Bundle, inputs: Inputs): String = buildString {
        appendLine(header(inputs));
        appendLine();
        appendLine("package $pkg");
        appendLine();
        appendLine("import xyz.superfunction.spfn.client.SpfnClient");
        appendLine("import xyz.superfunction.spfn.client.SpfnKeyProvider");
        appendLine("import xyz.superfunction.spfn.client.SpfnSession");
        appendLine("import xyz.superfunction.spfn.client.SpfnTransport");
        spec.flows.forEach { appendLine("import $pkg.flows.${type(it.name, "Flow")}") };
        spec.flows.forEach { appendLine("import $pkg.flows.${route(it)}") };
        spec.screens.forEach { screen ->
            appendLine("import $pkg.screens.${type(screen.name, "Model")}");
            if (screen.usecase)
            {
                appendLine("import $pkg.screens.Default${type(screen.name, "UseCase")}");
            }
        };
        spec.services.forEach {
            appendLine("import $pkg.services.Default${type(it.name, "Service")}");
            appendLine("import $pkg.services.${type(it.name, "Service")}");
        };
        appendLine("import xyz.superfunction.spfn.ui.Flow");
        if (validated(spec, bundle).isNotEmpty())
        {
            appendLine("import xyz.superfunction.spfn.ui.FieldValidator");
        }
        appendLine();
        appendLine("/** The app's one graph: services in, flows and screen models out. */");
        appendLine("class AppContainer(");
        append(models.constructorLines(containerParameters(spec, bundle)));
        appendLine(")");
        appendLine("{");
        spec.flows.forEach { flow ->
            appendLine("    /** The `${flow.name}` flow, open on its start screen. */");
            appendLine("    val ${flow.name}Flow: Flow<${route(flow)}> = ${type(flow.name, "Flow")}();");
            appendLine();
        };
        spec.screens.forEach { screen -> append(modelFactory(spec, screen, bundle)) };
        append(liveFactory(spec, bundle));
        appendLine("}");
    }

    /**
     * The screens whose models take a validator the app must supply, in spec order.
     *
     * A custom rule is a SENTENCE somebody wrote, and a sentence is app code: the generator
     * knows the rule's name and nothing else about it. So it is injected where a service is
     * injected — through the container — rather than defaulted to null in a factory, which
     * would be a screen whose rule is never asked and whose field is always accepted.
     */
    private fun validated(spec: Spec, bundle: Bundle): List<ScreenDefinition> =
        spec.screens.filter { models.takesValidator(it, bundle) && models.customRule(it) }

    private fun containerParameters(spec: Spec, bundle: Bundle): List<String> =
        spec.services.map { "${it.name}: ${type(it.name, "Service")}" } +
            validated(spec, bundle).map { "${it.name}Validator: FieldValidator" }

    private fun modelFactory(spec: Spec, screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val flow = spec.flows.first { it.name == screen.flow };
        val parameters = RouteParameters.of(screen, bundle);
        val arguments = mutableListOf<String>();
        if (screen.usecase)
        {
            arguments += "Default${type(screen.name, "UseCase")}(${requireNotNull(screen.source).service})";
        }
        arguments += screen.services;
        arguments += "${flow.name}Flow";
        parameters.forEach { arguments += it.name };
        if (models.customRule(screen))
        {
            arguments += "${screen.name}Validator";
        }
        appendLine("    /** A fresh model for one appearance of `${screen.name}`. */");
        appendLine("    fun ${screen.name}Model(${models.parameterList(parameters)}): ${type(screen.name, "Model")} =");
        appendLine("        ${type(screen.name, "Model")}(${arguments.joinToString(", ")});");
        appendLine();
    }

    private fun liveFactory(spec: Spec, bundle: Bundle): String = buildString {
        val validators = validated(spec, bundle);
        appendLine("    companion object");
        appendLine("    {");
        appendLine("        /** The app against a real server: one transport, one session, one client. */");
        appendLine("        fun live(");
        appendLine("            transport: SpfnTransport,");
        appendLine("            keyProvider: SpfnKeyProvider,");
        appendLine("            baseUrl: String" + if (validators.isEmpty()) "" else ",");
        validators.forEachIndexed { index, screen ->
            val comma = if (index == validators.size - 1) "" else ",";
            appendLine("            ${screen.name}Validator: FieldValidator$comma");
        };
        appendLine("        ): AppContainer");
        appendLine("        {");
        appendLine("            val session = SpfnSession(");
        appendLine("                transport = transport,");
        appendLine("                keyProvider = keyProvider,");
        appendLine("                baseUrl = baseUrl");
        appendLine("            );");
        appendLine("            val client = SpfnClient(transport = transport, session = session);");
        val given = spec.services.map { "Default${type(it.name, "Service")}(client)" } +
            validators.map { "${it.name}Validator" };
        appendLine("            return AppContainer(${given.joinToString(", ")});");
        appendLine("        }");
        appendLine("    }");
    }
}
