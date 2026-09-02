// The Swift half of the scaffold: the SwiftUI example app's generated sources.
//
// This file mirrors KotlinEmitter declaration for declaration, in the same order, with the
// same helper names. That is deliberate and it is load-bearing: SwiftUI does not compile on
// the Linux host this repository's Swift gate runs on, so everything under
// `examples/ios-swiftui/Generated` is written blind and first compiled on a Mac. A fix that
// Mac forces has to map back onto the Kotlin emitter line for line, or the two halves stop
// being one scaffold.
//
// The output is app code, not package code: `Generated/` is outside `Sources/`, so
// `swift build` here never sees it. It is still kept syntactically careful, because the
// first reader after this generator is a compiler nobody on this host can run.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle
import xyz.superfunction.spfn.codegen.FieldType
import xyz.superfunction.spfn.codegen.Names

object SwiftEmitter
{
    const val ROOT: String = "examples/ios-swiftui/Generated";

    /** One name and one type, which is all a stored property and an init parameter share. */
    private data class Parameter(val name: String, val type: String)

    fun emit(spec: Spec, bundle: Bundle, inputs: Inputs): Map<String, String>
    {
        val files = mutableMapOf<String, String>();
        spec.services.forEach { service ->
            files["$ROOT/Services/${type(service.name, "Service")}.swift"] = service(service, inputs);
        };
        spec.flows.forEach { flow ->
            files["$ROOT/Flows/${type(flow.name, "Flow")}.swift"] = flow(spec, flow, bundle, inputs);
        };
        files["$ROOT/Screens/ScreenFailure.swift"] = failure(inputs);
        spec.screens.forEach { screen ->
            files["$ROOT/Screens/${type(screen.name, "Model")}.swift"] = model(spec, screen, bundle, inputs);
            if (screen.usecase)
            {
                files["$ROOT/Screens/${type(screen.name, "UseCase")}.swift"] = useCase(screen, bundle, inputs);
            }
            files["$ROOT/Views/${type(screen.name, "View")}.swift"] = view(screen, bundle, inputs);
        };
        files["$ROOT/AppContainer.swift"] = container(spec, bundle, inputs);
        return files;
    }

    // ---- names -------------------------------------------------------------

    private fun type(name: String, kind: String): String = UiNames.swiftType(name, kind)

    private fun route(flow: FlowDefinition): String = type(flow.name, "Route")

    private fun routeCase(screen: ScreenDefinition): String = screen.name

    private fun request(method: ServiceMethod): String =
        Names.swiftType(method.declaration.requestType ?: "Void")

    private fun response(method: ServiceMethod): String =
        method.declaration.responseType?.let { Names.swiftType(it) } ?: "Void"

    private fun swiftType(type: FieldType): String = when (type)
    {
        is FieldType.IntegerType -> "Int64"
        else -> "String"
    }

    private fun header(inputs: Inputs): String = Header.slashes(inputs)

    // ---- the service -------------------------------------------------------

    private fun service(service: ServiceDefinition, inputs: Inputs): String = buildString {
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

    // ---- the flow ----------------------------------------------------------

    private fun flow(spec: Spec, flow: FlowDefinition, bundle: Bundle, inputs: Inputs): String = buildString {
        val screens = spec.screensOf(flow).sortedBy { it.name };
        appendLine("#if canImport(SwiftUI)");
        appendLine(header(inputs));
        appendLine("//");
        appendLine("// Guarded whole, first line of code to last, the way every SwiftUI file in this");
        appendLine("// repository is: SwiftUI is Apple's and the validator holds the guard to the file.");
        appendLine();
        appendLine("import SPFNUI");
        appendLine("import SwiftUI");
        appendLine();
        append(routeType(flow, screens, bundle));
        appendLine();
        append(flowFactory(flow));
        appendLine();
        append(flowHost(flow, screens, bundle));
        appendLine("#endif");
    }

    private fun routeType(flow: FlowDefinition, screens: List<ScreenDefinition>, bundle: Bundle): String =
        buildString {
            appendLine("/// Where the `${flow.name}` flow can stand.");
            appendLine("///");
            appendLine("/// A screen that reads carries what its read needs; a screen that reads nothing");
            appendLine("/// carries nothing. `Hashable` is synthesised either way — every payload here is a");
            appendLine("/// required string or integer, and both are `Hashable` — which is what");
            appendLine("/// `NavigationStack(path:)` identifies a stack entry by.");
            appendLine("public enum ${route(flow)}: FlowRoute");
            appendLine("{");
            screens.forEach { screen ->
                val parameters = RouteParameters.of(screen, bundle);
                if (parameters.isEmpty())
                {
                    appendLine("    case ${routeCase(screen)}");
                }
                else
                {
                    val payload = parameters.joinToString(", ") { "${it.name}: ${swiftType(it.type)}" };
                    appendLine("    case ${routeCase(screen)}($payload)");
                }
            };
            appendLine("}");
        }

    private fun flowFactory(flow: FlowDefinition): String = buildString {
        appendLine("/// How this flow is presented, and therefore what a back on its last route means.");
        appendLine("public let ${flow.name}Entry: FlowEntry = .${flow.entry}");
        appendLine();
        appendLine("/// A factory, so the flow opens on the screen the spec named as its start.");
        appendLine("@MainActor");
        appendLine("public func ${type(flow.name, "Flow")}() -> Flow<${route(flow)}>");
        appendLine("{");
        appendLine("    Flow(initial: [.${flow.start}])");
        appendLine("}");
    }

    private fun flowHost(flow: FlowDefinition, screens: List<ScreenDefinition>, bundle: Bundle): String =
        buildString {
            appendLine("/// Renders the `${flow.name}` flow: one route, one model, one view.");
            appendLine("///");
            appendLine("/// A screen with a source loads it here, once per route: a screen loads its own read");
            appendLine("/// however it appeared, which is what makes a deep entry — `open(at:)` onto a whole");
            appendLine("/// stack — behave exactly like a push.");
            appendLine("@MainActor");
            appendLine("public struct ${type(flow.name, "FlowHost")}: View");
            appendLine("{");
            appendLine("    private let container: AppContainer");
            appendLine();
            appendLine("    public init(container: AppContainer)");
            appendLine("    {");
            appendLine("        self.container = container");
            appendLine("    }");
            appendLine();
            appendLine("    public var body: some View");
            appendLine("    {");
            appendLine("        FlowHost(flow: container.${flow.name}Flow, entry: ${flow.name}Entry)");
            appendLine("        { route in");
            appendLine("            switch route");
            appendLine("            {");
            screens.forEach { screen -> append(hostBranch(screen, bundle)) };
            appendLine("            }");
            appendLine("        }");
            appendLine("    }");
            appendLine("}");
        }

    private fun hostBranch(screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val parameters = RouteParameters.of(screen, bundle);
        if (parameters.isEmpty())
        {
            appendLine("            case .${routeCase(screen)}:");
            appendLine("                ${type(screen.name, "View")}(model: container.${screen.name}Model())");
            return@buildString;
        }
        val bindings = parameters.joinToString(", ") { "let ${it.name}" };
        val arguments = parameters.joinToString(", ") { "${it.name}: ${it.name}" };
        appendLine("            case .${routeCase(screen)}($bindings):");
        appendLine("                ${type(screen.name, "View")}(model: container.${screen.name}Model($arguments))");
    }

    // ---- the models --------------------------------------------------------

    private fun model(spec: Spec, screen: ScreenDefinition, bundle: Bundle, inputs: Inputs): String
    {
        val flow = spec.flows.first { it.name == screen.flow };
        return if (screen.isLoadable) loadableModel(spec, flow, screen, bundle, inputs)
        else busyModel(spec, flow, screen, bundle, inputs);
    }

    private fun modelPreamble(screen: ScreenDefinition, inputs: Inputs): String = buildString {
        appendLine(header(inputs));
        appendLine();
        appendLine("import Foundation");
        appendLine("import Observation");
        if (screen.calls)
        {
            appendLine("import SPFNClient");
            appendLine("import SPFNGenerated");
        }
        appendLine("import SPFNUI");
    }

    private fun busyModel(
        spec: Spec,
        flow: FlowDefinition,
        screen: ScreenDefinition,
        bundle: Bundle,
        inputs: Inputs
    ): String = buildString {
        append(modelPreamble(screen, inputs));
        appendLine();
        appendLine("/// The `${screen.name}` screen's state and rules, with no toolkit in sight.");
        appendLine("///");
        appendLine("/// Constructor injection, so a test drives this class against a fake service and a");
        appendLine("/// real `Flow` with no device, no view and no server.");
        appendLine("@MainActor");
        appendLine("@Observable");
        val parameters = modelParameters(flow, screen, bundle);
        appendLine("public final class ${type(screen.name, "Model")}");
        appendLine("{");
        appendLine("    /// What this screen's write is doing.");
        appendLine("    public private(set) var state: Busy = .idle");
        appendLine();
        append(storedProperties(parameters));
        if (screen.calls)
        {
            appendLine();
            append(generationField());
        }
        appendLine();
        append(modelInit(parameters));
        appendLine();
        appendLine("    /// The flow's stack, so the screen can print its depth as a readout.");
        appendLine("    public var stack: [${route(flow)}] { flow.stack }");
        screen.actions.forEach { action -> append(busyAction(spec, screen, action, bundle)) };
        if (screen.calls)
        {
            appendLine();
            append(isCurrent(flow, screen, bundle));
        }
        appendLine("}");
    }

    /**
     * An action that only navigates, on either kind of model.
     *
     * It abandons whatever this screen had in flight before it moves, which is what the
     * generation bump is. A screen that calls nothing has no generation to bump and no
     * answer to abandon, so on one of those the body is the navigation alone.
     */
    private fun navigationOnlyAction(
        spec: Spec,
        screen: ScreenDefinition,
        action: ActionDefinition,
        bundle: Bundle
    ): String = buildString {
        appendLine("    /// ${navigationSentence(action)}");
        appendLine("    public func ${action.name}()");
        appendLine("    {");
        if (screen.calls)
        {
            appendLine("        generation += 1");
        }
        appendLine("        ${navigationCall(spec, action, bundle)}");
        appendLine("    }");
    }

    private fun generationField(): String = buildString {
        appendLine("    /// Which request is the current one.");
        appendLine("    ///");
        appendLine("    /// Bumped by everything that starts or abandons a call, and checked again when the");
        appendLine("    /// answer comes back. An answer whose token is stale — a superseded call, or a call");
        appendLine("    /// whose flow has since closed — is dropped rather than written into a screen");
        appendLine("    /// nobody is looking at any more.");
        appendLine("    private var generation: Int = 0");
    }

    /**
     * The guard every answer passes through, and the three questions it is.
     *
     * The third is not implied by the other two, which is what R9 is about: popping the
     * route a call was sent from leaves the flow presented and the generation untouched —
     * the pop was the system's back gesture, not this model's own action — so an answer
     * arriving afterwards would write into a screen nobody is standing on and run its
     * `then` from there (docs/IMPLEMENTATION-PITFALLS.md P24).
     */
    private fun isCurrent(flow: FlowDefinition, screen: ScreenDefinition, bundle: Bundle): String = buildString {
        appendLine("    /// Whether an answer bearing `token` still belongs to a screen that is on show.");
        appendLine("    ///");
        appendLine("    /// Three questions: is this the current request, is the flow still presented, and");
        appendLine("    /// is this screen's own route still on the stack. The last is not implied by the");
        appendLine("    /// others — a route popped while a call was in flight leaves both of them true.");
        appendLine("    private func isCurrent(_ token: Int) -> Bool");
        appendLine("    {");
        appendLine("        token == generation");
        appendLine("            && flow.isPresented");
        appendLine("            && flow.stack.contains(${routeValue(flow, screen, bundle)})");
        appendLine("    }");
    }

    /** This screen's own route, as the value the stack would hold while it is on show. */
    private fun routeValue(flow: FlowDefinition, screen: ScreenDefinition, bundle: Bundle): String
    {
        val parameters = RouteParameters.of(screen, bundle);
        val arguments = parameters.joinToString(", ") { "${it.name}: ${it.name}" };
        return "${route(flow)}.${routeCase(screen)}" + if (parameters.isEmpty()) "" else "($arguments)";
    }

    private fun busyAction(
        spec: Spec,
        screen: ScreenDefinition,
        action: ActionDefinition,
        bundle: Bundle
    ): String = buildString {
        appendLine();
        val typed = RouteParameters.inputs(screen, action, bundle);
        val parameters = typed.joinToString(", ") { "${it.name}: ${swiftType(it.type)}" };
        if (action.call == null)
        {
            append(navigationOnlyAction(spec, screen, action, bundle));
            return@buildString;
        }
        appendLine("    /// ${action.call.declaration.summary}");
        appendLine("    ///");
        appendLine("    /// Ignored while a write is already in flight, and refused outright when a required");
        appendLine("    /// input is blank — a refusal the screen states without sending anything.");
        appendLine("    public func ${action.name}($parameters) async");
        appendLine("    {");
        appendLine("        if state == .busy");
        appendLine("        {");
        appendLine("            return");
        appendLine("        }");
        typed.filter { it.type is FieldType.StringType }.forEach { input ->
            appendLine("        if ${input.name}.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty");
            appendLine("        {");
            appendLine("            state = .error(ScreenFailure.validation(\"${input.name}\"))");
            appendLine("            return");
            appendLine("        }");
        };
        appendLine("        generation += 1");
        appendLine("        let token = generation");
        appendLine("        state = .busy");
        appendLine("        do");
        appendLine("        {");
        appendLine("            ${discard(action.call)}try await ${action.call.service}.${action.call.name}(${requestLiteral(action.call, bundle)})");
        appendLine("        }");
        appendLine("        catch");
        appendLine("        {");
        appendLine("            if isCurrent(token)");
        appendLine("            {");
        appendLine("                state = .error(ScreenFailure.envelope(error))");
        appendLine("            }");
        appendLine("            return");
        appendLine("        }");
        appendLine("        guard isCurrent(token)");
        appendLine("        else");
        appendLine("        {");
        appendLine("            return");
        appendLine("        }");
        appendLine("        state = .idle");
        if (action.then != null)
        {
            appendLine("        ${navigationCall(spec, action, bundle)}");
        }
        appendLine("    }");
    }

    private fun discard(method: ServiceMethod): String = if (response(method) == "Void") "" else "_ = "

    private fun requestLiteral(method: ServiceMethod, bundle: Bundle): String
    {
        val requestType = method.declaration.requestType ?: return "()";
        val fields = bundle.typeNamed(requestType).fields.filter { !it.optional };
        return "${Names.swiftType(requestType)}(" + fields.joinToString(", ") { "${it.name}: ${it.name}" } + ")";
    }

    private fun navigationCall(spec: Spec, action: ActionDefinition, bundle: Bundle): String =
        when (val then = action.then)
        {
            null -> ""
            Navigation.Close -> "flow.close()"
            Navigation.Pop -> "flow.pop()"
            is Navigation.Push ->
            {
                val target = RouteParameters.of(spec.screenNamed(then.screen), bundle);
                val arguments = target.joinToString(", ") { "${it.name}: ${it.name}" };
                if (arguments.isEmpty()) "flow.push(.${then.screen})" else "flow.push(.${then.screen}($arguments))"
            }
        }

    private fun navigationSentence(action: ActionDefinition): String = when (action.then)
    {
        Navigation.Close -> "Closes the flow. Its stack empties, so nothing of it is presented."
        Navigation.Pop -> "Drops this route. On the flow's first route this does nothing."
        is Navigation.Push -> "Moves on to the next screen."
        null -> "Does nothing to the flow."
    }

    private fun loadableModel(
        spec: Spec,
        flow: FlowDefinition,
        screen: ScreenDefinition,
        bundle: Bundle,
        inputs: Inputs
    ): String = buildString {
        val source = requireNotNull(screen.source);
        val value = response(source);
        val parameters = modelParameters(flow, screen, bundle);
        append(modelPreamble(screen, inputs));
        appendLine();
        appendLine("/// The `${screen.name}` screen's state and rules, with no toolkit in sight.");
        appendLine("///");
        appendLine("/// There is no `empty`, and that is the contract's doing rather than a simplification:");
        appendLine("/// the bundle models a response as one named type or none at all, so nothing in it can");
        appendLine("/// say \"this operation answers with a list\" (examples/ui-spec/SCHEMA.md).");
        appendLine("///");
        appendLine("/// A write in flight is held on a separate flag rather than in the state, because this");
        appendLine("/// screen's vocabulary has no `busy` member and putting the write into `loading` would");
        appendLine("/// blank a value the screen is still showing.");
        appendLine("@MainActor");
        appendLine("@Observable");
        appendLine("public final class ${type(screen.name, "Model")}");
        appendLine("{");
        appendLine("    /// What this screen's read has produced so far.");
        appendLine("    public private(set) var state: Loadable<$value> = .loading");
        appendLine();
        append(storedProperties(parameters));
        appendLine();
        append(generationField());
        appendLine();
        appendLine("    /// Whether one of this screen's writes is in flight.");
        appendLine("    private var writing: Bool = false");
        appendLine();
        append(modelInit(parameters));
        appendLine();
        appendLine("    /// The flow's stack, so the screen can print its depth as a readout.");
        appendLine("    public var stack: [${route(flow)}] { flow.stack }");
        appendLine();
        append(readMethod(screen, bundle));
        screen.actions.forEach { action -> append(loadableAction(spec, screen, action, bundle)) };
        appendLine();
        append(isCurrent(flow, screen, bundle));
        appendLine("}");
    }

    /**
     * A screen model's parameters, in the order a reader expects them: the optional use
     * case, then one per service the screen calls, then the flow, then whatever the route
     * carries. Each service is named after itself, because a screen with two of them has
     * no `service`. The Kotlin half builds the same list in the same order.
     */
    private fun modelParameters(flow: FlowDefinition, screen: ScreenDefinition, bundle: Bundle): List<Parameter>
    {
        val parameters = mutableListOf<Parameter>();
        if (screen.usecase)
        {
            parameters += Parameter("useCase", "any ${type(screen.name, "UseCase")}");
        }
        screen.services.forEach { parameters += Parameter(it, "any ${type(it, "Service")}") };
        parameters += Parameter("flow", "Flow<${route(flow)}>");
        RouteParameters.of(screen, bundle).forEach { parameters += Parameter(it.name, swiftType(it.type)) };
        return parameters;
    }

    private fun storedProperties(parameters: List<Parameter>): String =
        parameters.joinToString("\n") { "    private let ${it.name}: ${it.type}" } + "\n"

    private fun modelInit(parameters: List<Parameter>): String = buildString {
        appendLine("    public init(");
        parameters.forEachIndexed { index, parameter ->
            appendLine("        ${parameter.name}: ${parameter.type}${if (index == parameters.size - 1) "" else ","}");
        };
        appendLine("    )");
        appendLine("    {");
        parameters.forEach { appendLine("        self.${it.name} = ${it.name}") };
        appendLine("    }");
    }

    private fun readMethod(screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val source = requireNotNull(screen.source);
        val call = if (screen.usecase) "useCase.${source.name}(${sourceArguments(screen, bundle)})"
        else "${source.service}.${source.name}(${requestLiteral(source, bundle)})";
        appendLine("    /// Reads this screen's source. Called once when the screen appears, however it appeared.");
        appendLine("    public func load() async");
        appendLine("    {");
        appendLine("        generation += 1");
        appendLine("        let token = generation");
        appendLine("        state = .loading");
        appendLine("        let value: ${response(source)}");
        appendLine("        do");
        appendLine("        {");
        appendLine("            value = try await $call");
        appendLine("        }");
        appendLine("        catch");
        appendLine("        {");
        appendLine("            if isCurrent(token)");
        appendLine("            {");
        appendLine("                state = .error(ScreenFailure.envelope(error))");
        appendLine("            }");
        appendLine("            return");
        appendLine("        }");
        appendLine("        if isCurrent(token)");
        appendLine("        {");
        appendLine("            state = .ready(value)");
        appendLine("        }");
        appendLine("    }");
    }

    private fun sourceArguments(screen: ScreenDefinition, bundle: Bundle): String =
        RouteParameters.of(screen, bundle).joinToString(", ") { "${it.name}: ${it.name}" }

    private fun loadableAction(
        spec: Spec,
        screen: ScreenDefinition,
        action: ActionDefinition,
        bundle: Bundle
    ): String = buildString {
        appendLine();
        if (action.call == null)
        {
            append(navigationOnlyAction(spec, screen, action, bundle));
            return@buildString;
        }
        if (action.call.reference == screen.source?.reference && action.then == null)
        {
            appendLine("    /// Reads the source again. Ignored while a write of this screen's is in flight.");
            appendLine("    public func ${action.name}() async");
            appendLine("    {");
            appendLine("        if writing");
            appendLine("        {");
            appendLine("            return");
            appendLine("        }");
            appendLine("        await load()");
            appendLine("    }");
            return@buildString;
        }
        append(writeAction(spec, action, bundle));
    }

    private fun writeAction(spec: Spec, action: ActionDefinition, bundle: Bundle): String = buildString {
        val call = requireNotNull(action.call);
        appendLine("    /// ${call.declaration.summary}");
        appendLine("    ///");
        appendLine("    /// Ignored unless this screen is showing a value and no write of its own is running.");
        appendLine("    public func ${action.name}() async");
        appendLine("    {");
        appendLine("        guard !writing, case .ready = state");
        appendLine("        else");
        appendLine("        {");
        appendLine("            return");
        appendLine("        }");
        appendLine("        generation += 1");
        appendLine("        let token = generation");
        appendLine("        writing = true");
        appendLine("        do");
        appendLine("        {");
        appendLine("            ${discard(call)}try await ${call.service}.${call.name}(${requestLiteral(call, bundle)})");
        appendLine("        }");
        appendLine("        catch");
        appendLine("        {");
        appendLine("            writing = false");
        appendLine("            if isCurrent(token)");
        appendLine("            {");
        appendLine("                state = .error(ScreenFailure.envelope(error))");
        appendLine("            }");
        appendLine("            return");
        appendLine("        }");
        appendLine("        writing = false");
        appendLine("        guard isCurrent(token)");
        appendLine("        else");
        appendLine("        {");
        appendLine("            return");
        appendLine("        }");
        if (action.then != null)
        {
            appendLine("        ${navigationCall(spec, action, bundle)}");
        }
        appendLine("    }");
    }

    // ---- the use case ------------------------------------------------------

    private fun useCase(screen: ScreenDefinition, bundle: Bundle, inputs: Inputs): String = buildString {
        val source = requireNotNull(screen.source);
        val parameters = RouteParameters.of(screen, bundle);
        val name = type(screen.name, "UseCase");
        appendLine(header(inputs));
        appendLine();
        appendLine("import SPFNGenerated");
        appendLine();
        appendLine("/// What `${screen.name}` reads, named as the app's own act rather than as an operation.");
        appendLine("///");
        appendLine("/// It stands between the model and the service so the hand-written layer has somewhere");
        appendLine("/// to put a rule that is neither the screen's nor the wire's.");
        appendLine("public protocol $name: Sendable");
        appendLine("{");
        appendLine("    func ${source.name}(${parameterList(parameters)}) async throws -> ${response(source)}");
        appendLine("}");
        appendLine();
        appendLine("/// The pass-through. It adds a seam, not a rule.");
        appendLine("public struct Default$name: $name, Sendable");
        appendLine("{");
        appendLine("    private let service: any ${type(source.service, "Service")}");
        appendLine();
        appendLine("    public init(service: any ${type(source.service, "Service")})");
        appendLine("    {");
        appendLine("        self.service = service");
        appendLine("    }");
        appendLine();
        appendLine("    public func ${source.name}(${parameterList(parameters)}) async throws -> ${response(source)}");
        appendLine("    {");
        appendLine("        try await service.${source.name}(${requestLiteral(source, bundle)})");
        appendLine("    }");
        appendLine("}");
    }

    private fun parameterList(parameters: List<RouteParameters.Parameter>): String =
        parameters.joinToString(", ") { "${it.name}: ${swiftType(it.type)}" }

    // ---- the failure mapping ----------------------------------------------

    private fun failure(inputs: Inputs): String = buildString {
        appendLine(header(inputs));
        appendLine();
        appendLine("import SPFNClient");
        appendLine("import SPFNCore");
        appendLine();
        appendLine("/// Turns what a call threw into the envelope a screen state carries.");
        appendLine("///");
        appendLine("/// `Loadable.error` and `Busy.error` carry core's envelope, so a screen's own refusal —");
        appendLine("/// a blank required input, which never reached a server — has to be one too. It is");
        appendLine("/// given a code of this generator's own rather than borrowing a contract code that");
        appendLine("/// would read as something a server said.");
        appendLine("public enum ScreenFailure");
        appendLine("{");
        appendLine("    /// A refusal this screen made itself. Nothing was sent.");
        appendLine("    public static let validationCode = \"SPFN_UI_VALIDATION\"");
        appendLine();
        appendLine("    /// A call that failed on a ground the server did not put in an envelope.");
        appendLine("    public static let callFailedCode = \"SPFN_UI_CALL_FAILED\"");
        appendLine();
        appendLine("    /// The screen's own refusal of a required input. `field` is the field's name.");
        appendLine("    public static func validation(_ field: String) -> SPFNErrorEnvelope");
        appendLine("    {");
        appendLine("        SPFNErrorEnvelope(code: validationCode, message: field, requestID: \"\")");
        appendLine("    }");
        appendLine();
        appendLine("    /// The server's own envelope where there is one, and a local one where there is");
        appendLine("    /// not. The message carries the SDK's own case name and never any server text.");
        appendLine("    public static func envelope(_ error: Error) -> SPFNErrorEnvelope");
        appendLine("    {");
        appendLine("        switch error");
        appendLine("        {");
        appendLine("        case SPFNClientError.auth(let failure):");
        appendLine("            return failure.envelope");
        appendLine("        case SPFNClientError.server(let failure):");
        appendLine("            return failure.envelope");
        appendLine("        default:");
        appendLine("            return SPFNErrorEnvelope(");
        appendLine("                code: callFailedCode,");
        appendLine("                message: String(describing: type(of: error)),");
        appendLine("                requestID: \"\"");
        appendLine("            )");
        appendLine("        }");
        appendLine("    }");
        appendLine("}");
    }

    // ---- the views ---------------------------------------------------------

    private fun view(screen: ScreenDefinition, bundle: Bundle, inputs: Inputs): String = buildString {
        val typed = screen.actions.flatMap { RouteParameters.inputs(screen, it, bundle) }.distinctBy { it.name };
        appendLine("#if canImport(SwiftUI)");
        appendLine(header(inputs));
        appendLine("//");
        appendLine("// Every element here exists because a runner has to reach it or read it: one control");
        appendLine("// per action, one field per typed input, and the two readouts. Layout is the human's,");
        appendLine("// outside `Generated/`. Selectors follow the harness's rule — a control by the id");
        appendLine("// `<screen>.<action>`, a readout by its text (tools/harness/ios/Sources/HarnessView.swift).");
        appendLine();
        appendLine("import SPFNUI");
        appendLine("import SwiftUI");
        appendLine();
        appendLine("/// The `${screen.name}` screen: one control per action, and the two readouts.");
        appendLine("@MainActor");
        appendLine("public struct ${type(screen.name, "View")}: View");
        appendLine("{");
        appendLine("    @State private var model: ${type(screen.name, "Model")}");
        typed.forEach { appendLine("    @State private var ${it.name}: String = \"\"") };
        appendLine();
        appendLine("    public init(model: ${type(screen.name, "Model")})");
        appendLine("    {");
        appendLine("        _model = State(initialValue: model)");
        appendLine("    }");
        appendLine();
        appendLine("    public var body: some View");
        appendLine("    {");
        appendLine("        VStack(alignment: .leading, spacing: 8)");
        appendLine("        {");
        appendLine("            Text(\"state=\" + stateName(model.state))");
        appendLine("            Text(\"stack=\" + String(model.stack.count))");
        typed.forEach { input -> append(field(screen, input)) };
        screen.actions.forEach { action -> append(control(screen, action, bundle)) };
        appendLine("        }");
        if (screen.source != null)
        {
            appendLine("        // A screen loads its own read once, however it appeared: pushed onto the");
            appendLine("        // stack, or already on it because the flow was opened at a whole stack.");
            appendLine("        .task");
            appendLine("        {");
            appendLine("            await model.load()");
            appendLine("        }");
        }
        appendLine("    }");
        appendLine("}");
        appendLine();
        if (screen.actions.isNotEmpty() || typed.isNotEmpty())
        {
            append(touchTarget());
            appendLine();
        }
        append(stateName(screen));
        appendLine("#endif");
    }

    /**
     * The minimum touch target every control and field is given.
     *
     * The counterpart of KotlinEmitter's, and stated for the same reason: a control smaller
     * than the platform minimum is reachable only through an expanded hit area, and expanded
     * hit areas of neighbouring controls overlap. Compose reported one control's bounds on
     * top of another's and cell u5 tapped the wrong node
     * (docs/IMPLEMENTATION-PITFALLS.md P21); SwiftUI's own controls happen to clear 44pt
     * already, so writing it here changes nothing on this platform except that the rule is
     * now written down on both.
     */
    private fun touchTarget(): String = buildString {
        appendLine("/// The platform's minimum touch target, given to every control and field.");
        appendLine("///");
        appendLine("/// A control smaller than this is reachable only through a hit area larger than itself,");
        appendLine("/// and neighbouring hit areas then overlap: the bounds reported for one control sit on");
        appendLine("/// a neighbour's, and a runner tapping the reported centre taps the neighbour");
        appendLine("/// (docs/IMPLEMENTATION-PITFALLS.md P21).");
        appendLine("private let touchTarget: CGFloat = 44");
    }

    private fun field(screen: ScreenDefinition, input: RouteParameters.Parameter): String = buildString {
        appendLine("            TextField(\"${input.name}\", text: \$${input.name})");
        appendLine("                .accessibilityIdentifier(\"${screen.name}.${input.name}\")");
        appendLine("                .frame(minHeight: touchTarget)");
    }

    private fun control(screen: ScreenDefinition, action: ActionDefinition, bundle: Bundle): String = buildString {
        val id = "${screen.name}.${action.name}";
        val arguments = RouteParameters.inputs(screen, action, bundle)
            .joinToString(", ") { "${it.name}: ${it.name}" };
        appendLine("            Button(\"${action.name}\")");
        appendLine("            {");
        if (action.call == null)
        {
            appendLine("                model.${action.name}()");
        }
        else
        {
            appendLine("                Task { await model.${action.name}($arguments) }");
        }
        appendLine("            }");
        appendLine("            .accessibilityIdentifier(\"$id\")");
        appendLine("            .frame(minHeight: touchTarget)");
    }

    private fun stateName(screen: ScreenDefinition): String = buildString {
        val stateType = if (screen.isLoadable) "Loadable<Value>" else "Busy";
        appendLine("/// The one word a runner reads this screen's state as.");
        if (screen.isLoadable)
        {
            appendLine("private func stateName<Value: Sendable>(_ state: $stateType) -> String");
        }
        else
        {
            appendLine("private func stateName(_ state: $stateType) -> String");
        }
        appendLine("{");
        appendLine("    switch state");
        appendLine("    {");
        if (screen.isLoadable)
        {
            appendLine("    case .loading: return \"loading\"");
            appendLine("    case .ready: return \"ready\"");
            appendLine("    case .empty: return \"empty\"");
            appendLine("    case .error: return \"error\"");
        }
        else
        {
            appendLine("    case .idle: return \"idle\"");
            appendLine("    case .busy: return \"busy\"");
            appendLine("    case .error: return \"error\"");
        }
        appendLine("    }");
        appendLine("}");
    }

    // ---- the container -----------------------------------------------------

    private fun container(spec: Spec, bundle: Bundle, inputs: Inputs): String = buildString {
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
        appendLine();
        spec.flows.forEach { flow ->
            appendLine("    /// The `${flow.name}` flow, open on its start screen.");
            appendLine("    public let ${flow.name}Flow: Flow<${route(flow)}>");
            appendLine();
        };
        append(containerInit(spec));
        spec.screens.forEach { screen -> append(modelFactory(spec, screen, bundle)) };
        appendLine();
        append(liveFactory(spec));
        appendLine("}");
    }

    private fun containerInit(spec: Spec): String = buildString {
        appendLine("    public init(");
        spec.services.forEachIndexed { index, service ->
            appendLine("        ${service.name}: any ${type(service.name, "Service")}${if (index == spec.services.size - 1) "" else ","}");
        };
        appendLine("    )");
        appendLine("    {");
        spec.services.forEach { appendLine("        self.${it.name} = ${it.name}") };
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
        appendLine();
        appendLine("    /// A fresh model for one appearance of `${screen.name}`.");
        appendLine("    public func ${screen.name}Model(${parameterList(parameters)}) -> ${type(screen.name, "Model")}");
        appendLine("    {");
        appendLine("        ${type(screen.name, "Model")}(${arguments.joinToString(", ")})");
        appendLine("    }");
    }

    private fun liveFactory(spec: Spec): String = buildString {
        val services = spec.services.joinToString(", ") {
            "${it.name}: Default${type(it.name, "Service")}(client: client)"
        };
        appendLine("    /// The app against a real server: one transport, one session, one client.");
        appendLine("    public static func live(");
        appendLine("        transport: any SPFNTransport,");
        appendLine("        keyProvider: any SPFNKeyProvider,");
        appendLine("        baseURL: String");
        appendLine("    ) -> AppContainer");
        appendLine("    {");
        appendLine("        let session = SPFNSession(");
        appendLine("            transport: transport,");
        appendLine("            keyProvider: keyProvider,");
        appendLine("            baseURL: baseURL");
        appendLine("        )");
        appendLine("        let client = SPFNClient(transport: transport, session: session)");
        appendLine("        return AppContainer($services)");
        appendLine("    }");
    }
}
