// The Swift half of the scaffold: one SwiftUI app's generated sources.
//
// Which app is the `Target`'s to say, exactly as in the Kotlin half: the output root is
// read from it and this file names no app.
//
// This file mirrors KotlinEmitter declaration for declaration, in the same order, with the
// same helper names. That is deliberate and it is load-bearing: SwiftUI does not compile on
// the Linux host this repository's Swift gate runs on, so everything under
// `examples/ios-swiftui/Generated` is written blind and first compiled on a Mac. A fix that
// Mac forces has to map back onto the Kotlin emitter line for line, or the two halves stop
// being one scaffold.
//
// The output is app code, not package code: every target's Swift root is outside the
// package's `Sources/`, so `swift build` here never sees it. It is still kept syntactically careful, because the
// first reader after this generator is a compiler nobody on this host can run.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle
import xyz.superfunction.spfn.codegen.FieldType
import xyz.superfunction.spfn.codegen.Names

class SwiftEmitter(target: Target)
{
    private val root: String = target.swiftRoot;

    private val readouts: Boolean = target.runnerReadouts;

    /**
     * One name and one type, which is all a stored property and an init parameter share —
     * plus, on exactly one of them, a default.
     *
     * The default belongs to the INITIALISER and never to the stored property: written on the
     * property, `private let validator: (any FieldValidator)? = nil` is a constant nil that
     * the init then cannot assign over. The two halves are written from one list, so this is
     * where the difference between them lives.
     */
    private data class Parameter(val name: String, val type: String, val defaultValue: String? = null)

    fun emit(spec: Spec, bundle: Bundle, inputs: Inputs): Map<String, String>
    {
        val files = mutableMapOf<String, String>();
        spec.services.forEach { service ->
            files["$root/Services/${type(service.name, "Service")}.swift"] = service(service, inputs);
        };
        spec.flows.forEach { flow ->
            files["$root/Flows/${type(flow.name, "Flow")}.swift"] = flow(spec, flow, bundle, inputs);
        };
        files["$root/Screens/ScreenFailure.swift"] = failure(spec, bundle, inputs);
        spec.screens.forEach { screen ->
            files["$root/Screens/${type(screen.name, "Model")}.swift"] = model(spec, screen, bundle, inputs);
            if (screen.usecase)
            {
                files["$root/Screens/${type(screen.name, "UseCase")}.swift"] = useCase(screen, bundle, inputs);
            }
            if (!spec.viewIsAuthored(screen))
            {
                files["$root/Views/${type(screen.name, "View")}.swift"] = view(screen, bundle, inputs);
            }
        };
        files["$root/AppContainer.swift"] = container(spec, bundle, inputs);
        return files;
    }

    /**
     * The view files of the flows a person writes: not emitted above, and not to be deleted.
     *
     * Named here rather than in `Main` because the path is this emitter's own spelling —
     * `Views/<Screen>View.swift` — and a second copy of it would drift from the one line
     * above that writes the file.
     */
    fun authoredViews(spec: Spec): Set<String> =
        spec.screens.filter { spec.viewIsAuthored(it) }
            .map { "$root/Views/${type(it.name, "View")}.swift" }
            .toSet()

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

    /**
     * The `FlowEntry` value, which is a name for two of the three and a call for the third.
     *
     * A sheet stands at a height and the other two do not, which is why `FlowEntry` carries a
     * payload on one case. The spec says the same thing the same way: a `sheet` entry carries
     * `sheet.detent` and nothing else may.
     */
    private fun entryValue(flow: FlowDefinition): String = when (flow.entry)
    {
        "sheet" -> ".sheet(detent: .${requireNotNull(flow.detent)})"
        else -> ".${flow.entry}"
    }

    private fun flowFactory(flow: FlowDefinition): String = buildString {
        appendLine("/// How this flow is presented, and therefore what a back on its last route means.");
        appendLine("public let ${flow.name}Entry: FlowEntry = ${entryValue(flow)}");
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

    /** Which of the four models this screen gets. The Kotlin half asks in the same order. */
    private fun model(spec: Spec, screen: ScreenDefinition, bundle: Bundle, inputs: Inputs): String
    {
        val flow = spec.flows.first { it.name == screen.flow };
        return when
        {
            screen.isPaged -> pagedModel(spec, flow, screen, bundle, inputs)
            screen.isLoadable -> loadableModel(spec, flow, screen, bundle, inputs)
            ScreenShape.isForm(screen, bundle) -> formModel(spec, flow, screen, bundle, inputs)
            else -> busyModel(spec, flow, screen, bundle, inputs)
        };
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
        if (checks(screen))
        {
            appendLine();
            append(rulesTable(screen, bundle));
        }
        screen.actions.forEach { action -> append(busyAction(spec, screen, action, bundle)) };
        if (screen.calls)
        {
            if (collects(screen, bundle))
            {
                append(clearError());
            }
            if (checks(screen))
            {
                appendLine();
                append(refusalHelpers());
            }
            appendLine();
            append(isCurrent(flow, screen, bundle));
        }
        appendLine("}");
    }

    /** Whether any action on this screen takes a typed input, and therefore draws a field. */
    private fun collects(screen: ScreenDefinition, bundle: Bundle): Boolean =
        screen.actions.any { RouteParameters.inputs(screen, it, bundle).isNotEmpty() }

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
     *
     * It asks for the TOP of the stack rather than for membership in it. `Flow` accepts
     * any nonempty order — `push`, `replace` and `open(at:)` all take a route this screen
     * already has one of — so a stack can hold a second copy of this screen's own route
     * above it. Membership says yes to that, and the answer would then apply this screen's
     * `then` over the screen the person is actually standing on. On show means on top.
     */
    private fun isCurrent(
        flow: FlowDefinition,
        screen: ScreenDefinition,
        bundle: Bundle,
        clearedBy: String? = "clearError()"
    ): String = buildString {
        appendLine("    /// Whether an answer bearing `token` still belongs to a screen that is on show.");
        appendLine("    ///");
        appendLine("    /// Three questions: is this the current request, is the flow still presented, and");
        appendLine("    /// is this screen's own route the one on top of the stack. The last is not implied");
        appendLine("    /// by the others — a route popped while a call was in flight leaves both of them");
        appendLine("    /// true — and it asks for the top rather than for membership, because a screen");
        appendLine("    /// buried under a second copy of its own route is not on show either.");
        appendLine("    private func isCurrent(_ token: Int) -> Bool");
        appendLine("    {");
        appendLine("        token == generation && isOnShow");
        appendLine("    }");
        appendLine();
        appendLine("    /// Whether this screen's own route is the one the person is standing on.");
        appendLine("    ///");
        if (clearedBy != null)
        {
            appendLine("    /// Split out of `isCurrent` because a second caller needs it without a token: the");
            appendLine("    /// view calls `$clearedBy` when the text changes, and that is not an answer to a");
            appendLine("    /// request — it has no generation to compare — while it is still something that must");
            appendLine("    /// not write into a screen nobody is looking at.");
        }
        else
        {
            appendLine("    /// A property of its own rather than a clause inside `isCurrent`, because what");
            appendLine("    /// \"on show\" means is one question: a model that asked it inline would end up");
            appendLine("    /// asking it a little differently in each method that needs an answer.");
        }
        appendLine("    private var isOnShow: Bool");
        appendLine("    {");
        appendLine("        flow.isPresented && flow.stack.last == ${routeValue(flow, screen, bundle)}");
        appendLine("    }");
    }

    /**
     * Dropping a refusal because the person started fixing it.
     *
     * The VIEW decides when — `SpfnTextField`'s `onChange` — and the model decides whether.
     * Written the other way round, with the model clearing its own error inside a text
     * setter, it would clear the error of a screen that has since been popped: the same R9
     * family the answer guard is for, arriving through the keyboard instead of through the
     * network (docs/IMPLEMENTATION-PITFALLS.md P24).
     */
    private fun clearError(): String = buildString {
        appendLine();
        appendLine("    /// Drops this screen's refusal, so editing the input clears the line under it.");
        appendLine("    ///");
        appendLine("    /// A no-op on a screen that is not the one on show, and a no-op when there is no");
        appendLine("    /// refusal to drop: it never interrupts a write.");
        appendLine("    public func clearError()");
        appendLine("    {");
        appendLine("        guard isOnShow, case .error = state");
        appendLine("        else");
        appendLine("        {");
        appendLine("            return");
        appendLine("        }");
        appendLine("        state = .idle");
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
        if (checks(screen))
        {
            append(checkedGuard(typed));
        }
        else
        {
            typed.filter { it.type is FieldType.StringType }.forEach { input ->
                appendLine("        if ${input.name}.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty");
                appendLine("        {");
                appendLine("            state = .error(ScreenFailure.validation(\"${input.name}\"))");
                appendLine("            return");
                appendLine("        }");
            };
        }
        appendLine("        generation += 1");
        appendLine("        let token = generation");
        appendLine("        state = .busy");
        appendLine("        do");
        appendLine("        {");
        appendLine("            ${discard(action.call)}try await ${action.call.service}.${action.call.name}(${requestLiteral(action.call, bundle)})");
        appendLine("        }");
        // A bare `catch` here and in the two below, where Kotlin needs two clauses: Swift
        // has one error type and every SDK failure is already an `Error`, so nothing this
        // hierarchy can throw escapes. `CancellationError` is caught with the rest and
        // cannot be rethrown — these methods do not throw — which is the one thing the two
        // languages do not say alike (docs/IMPLEMENTATION-PITFALLS.md P15, P16). Swift
        // cancellation is cooperative, so a cancelled task here shows a failure rather than
        // resuming a caller that believes it was never cancelled.
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
        appendLine("    ///");
        appendLine("    /// Readable, because the control that started it draws itself busy from this and a");
        appendLine("    /// control that spun off a flag of its own could disagree with the model about");
        appendLine("    /// whether the press it is refusing was taken.");
        appendLine("    public private(set) var writing: Bool = false");
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
        if (takesValidator(screen, bundle))
        {
            // Required — no default — exactly when a rule names a custom one, which is the
            // Kotlin half's rule said in the language that spells an optional existential
            // `(any FieldValidator)?`.
            parameters += Parameter(
                "validator",
                "(any FieldValidator)?",
                defaultValue = if (customRule(screen)) null else "nil"
            );
        }
        return parameters;
    }

    /** Whether any of this screen's fields is checked against `rules` the spec wrote. */
    private fun checks(screen: ScreenDefinition): Boolean = screen.inputs.any { it.rules != null }

    /** Whether any of those rules names a custom one, which is what makes it a hard argument. */
    private fun customRule(screen: ScreenDefinition): Boolean =
        screen.inputs.any { it.rules?.custom != null }

    /** Whether this screen's model checks its fields, and therefore takes a validator. */
    private fun takesValidator(screen: ScreenDefinition, bundle: Bundle): Boolean =
        ScreenShape.isForm(screen, bundle) || checks(screen)

    private fun storedProperties(parameters: List<Parameter>): String =
        parameters.joinToString("\n") { "    private let ${it.name}: ${it.type}" } + "\n"

    private fun modelInit(parameters: List<Parameter>): String = buildString {
        appendLine("    public init(");
        parameters.forEachIndexed { index, parameter ->
            val given = parameter.defaultValue?.let { " = $it" } ?: "";
            appendLine("        ${parameter.name}: ${parameter.type}$given${if (index == parameters.size - 1) "" else ","}");
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


    // ---- the checked fields ------------------------------------------------

    /**
     * What every field of this screen is checked against, as one table. Mirrors the Kotlin
     * half declaration for declaration: every typed input is in it, and an input the spec said
     * nothing about gets the quiet answer.
     */
    private fun rulesTable(screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val fields = ScreenShape.inputs(screen, bundle);
        appendLine("    /// What each of this screen's fields is checked against.");
        appendLine("    public let rules: [String: FieldRules] = [");
        fields.forEachIndexed { index, field ->
            val comma = if (index == fields.size - 1) "" else ",";
            appendLine("        \"${field.name}\": ${fieldRules(screen, field)}$comma");
        };
        appendLine("    ]");
    }

    private fun fieldRules(screen: ScreenDefinition, field: RouteParameters.Parameter): String
    {
        val declared = screen.inputNamed(field.name);
        val rules = declared.rules;
        val arguments = mutableListOf("required: ${rules?.required ?: true}");
        rules?.minLength?.let { arguments += "minLength: $it" };
        rules?.maxLength?.let { arguments += "maxLength: $it" };
        arguments += "kind: .${declared.kind}";
        rules?.custom?.let { arguments += "custom: ${quoted(it)}" };
        return "FieldRules(" + arguments.joinToString(", ") + ")";
    }

    /** The values a check is given. The Kotlin half's comment applies here word for word. */
    private fun fieldValues(fields: List<RouteParameters.Parameter>, text: Boolean): String =
        "[" + fields.joinToString(", ") { field ->
            val parsed = !text && ScreenShape.isInteger(field);
            "\"${field.name}\": " + if (parsed) "String(${field.name})" else field.name
        } + "]"

    /** The `Busy` screen's own check, when its spec wrote rules for a field. */
    private fun checkedGuard(fields: List<RouteParameters.Parameter>): String = buildString {
        appendLine("        let checked = Form.check(");
        appendLine("            values: ${fieldValues(fields, text = false)},");
        appendLine("            rules: rules,");
        appendLine("            custom: validator");
        appendLine("        )");
        appendLine("        if let refusal = refusals(checked).first");
        appendLine("        {");
        appendLine("            state = .error(ScreenFailure.validation(refusal.field, rule: refusal.rule))");
        appendLine("            return");
        appendLine("        }");
    }

    /**
     * How a checked screen reads its own refusals. Sorted by field name for the reason the
     * Kotlin half gives, and the reason is stronger here: this platform's `Form.fields` is a
     * `Dictionary` and has no order of its own at all.
     */
    private fun refusalHelpers(): String = buildString {
        appendLine("    /// Which fields the last check refused, by field name, with the rule that refused each.");
        appendLine("    private func refusals(_ form: Form) -> [(field: String, rule: String)]");
        appendLine("    {");
        appendLine("        form.fields.keys.sorted().compactMap");
        // The closure's return type is written out, because a multi-statement closure with a
        // `guard` in it is one Swift will not infer a tuple result for.
        appendLine("        { field -> (field: String, rule: String)? in");
        appendLine("            guard let error = form.fields[field] ?? nil");
        appendLine("            else");
        appendLine("            {");
        appendLine("                return nil");
        appendLine("            }");
        appendLine("            return (field: field, rule: ruleName(error))");
        appendLine("        }");
        appendLine("    }");
        appendLine();
        appendLine("    /// The one word a refusal is read as, which is the name of the rule that made it.");
        appendLine("    private func ruleName(_ error: FieldError) -> String");
        appendLine("    {");
        appendLine("        switch error");
        appendLine("        {");
        appendLine("        case .required: return \"required\"");
        appendLine("        case .minLength: return \"minLength\"");
        appendLine("        case .maxLength: return \"maxLength\"");
        appendLine("        case .kind: return \"kind\"");
        appendLine("        case .custom: return \"custom\"");
        appendLine("        }");
        appendLine("    }");
    }

    // ---- the paged model ---------------------------------------------------

    /**
     * A screen that reads its source a page at a time: its state is a `Paged<Item>`.
     *
     * Mirrors the Kotlin half method for method. The cursor is the model's, the generation
     * token is the `Loadable` model's own, and `readouts` is what an authored view draws —
     * there is no generated view for a list, because what a row shows is the design of the
     * screen and a grammar cannot write it.
     */
    private fun pagedModel(
        spec: Spec,
        flow: FlowDefinition,
        screen: ScreenDefinition,
        bundle: Bundle,
        inputs: Inputs
    ): String = buildString {
        val list = requireNotNull(screen.list);
        val row = Names.swiftType(list.itemType);
        val parameters = modelParameters(flow, screen, bundle);
        append(modelPreamble(screen, inputs));
        appendLine();
        appendLine("/// The `${screen.name}` screen's state and rules, with no toolkit in sight.");
        appendLine("///");
        appendLine("/// Constructor injection, so a test drives this class against a fake service and a");
        appendLine("/// real `Flow` with no device, no view and no server.");
        appendLine("///");
        appendLine("/// The cursor is held here and nowhere else: `Paged` is told what the next one is and");
        appendLine("/// keeps only whether there WAS one, because a screen shows rows and never a cursor.");
        appendLine("@MainActor");
        appendLine("@Observable");
        appendLine("public final class ${type(screen.name, "Model")}");
        appendLine("{");
        appendLine("    /// What this screen's paged read has produced so far.");
        appendLine("    public private(set) var state: Paged<$row> = .loading");
        appendLine();
        append(storedProperties(parameters));
        appendLine();
        append(generationField());
        appendLine();
        append(cursorField());
        appendLine();
        append(modelInit(parameters));
        appendLine();
        appendLine("    /// The flow's stack, so the screen can print its depth as a readout.");
        appendLine("    public var stack: [${route(flow)}] { flow.stack }");
        appendLine();
        append(pagedReadouts(row));
        appendLine();
        append(loadPage(screen, bundle));
        appendLine();
        append(loadMore(screen, bundle));
        appendLine();
        append(pagedRetries());
        screen.actions.forEach { action ->
            appendLine();
            append(navigationOnlyAction(spec, screen, action, bundle));
        };
        appendLine();
        append(isCurrent(flow, screen, bundle, clearedBy = null));
        appendLine("}");
    }

    private fun cursorField(): String = buildString {
        appendLine("    /// Where the next page starts, or nil when there is no next page.");
        appendLine("    ///");
        appendLine("    /// Cleared by `load()` rather than only written by it. A reload that kept the cursor");
        appendLine("    /// it had would ask the server for page two of a list whose page one it is in the");
        appendLine("    /// act of reading again, and append the answer to nothing.");
        appendLine("    private var cursor: String?");
    }

    private fun pagedReadouts(row: String): String = buildString {
        appendLine("    /// What a runner reads this screen as: how deep the flow stands, what the first page");
        appendLine("    /// did, what a further page is doing, how many rows are on screen, and whether the");
        appendLine("    /// server said there are more.");
        appendLine("    ///");
        appendLine("    /// On the MODEL and not in a view, because a paged flow's views are a person's: a");
        appendLine("    /// readout each implementer spelled for themselves would be a case table asserting");
        appendLine("    /// on text two apps write differently.");
        appendLine("    public var readouts: [String]");
        appendLine("    {");
        appendLine("        [");
        appendLine("            \"stack=\\(flow.stack.count)\",");
        appendLine("            \"state=\\(pageName(state.page))\",");
        appendLine("            \"more=\\(moreName(state.more))\",");
        appendLine("            \"count=\\(rowCount(state.page))\",");
        appendLine("            \"hasMore=\\(state.hasMore)\"");
        appendLine("        ]");
        appendLine("    }");
        appendLine();
        appendLine("    /// The one word a runner reads the FIRST page's state as.");
        appendLine("    private func pageName(_ page: Loadable<[$row]>) -> String");
        appendLine("    {");
        appendLine("        switch page");
        appendLine("        {");
        appendLine("        case .loading: return \"loading\"");
        appendLine("        case .ready: return \"ready\"");
        appendLine("        case .empty: return \"empty\"");
        appendLine("        case .error: return \"error\"");
        appendLine("        }");
        appendLine("    }");
        appendLine();
        appendLine("    /// The one word a runner reads a FURTHER page's state as.");
        appendLine("    private func moreName(_ more: Busy) -> String");
        appendLine("    {");
        appendLine("        switch more");
        appendLine("        {");
        appendLine("        case .idle: return \"idle\"");
        appendLine("        case .busy: return \"busy\"");
        appendLine("        case .error: return \"error\"");
        appendLine("        }");
        appendLine("    }");
        appendLine();
        appendLine("    /// How many rows are on screen, which is none until the first page arrives.");
        appendLine("    private func rowCount(_ page: Loadable<[$row]>) -> Int");
        appendLine("    {");
        appendLine("        if case .ready(let rows) = page");
        appendLine("        {");
        appendLine("            return rows.count");
        appendLine("        }");
        appendLine("        return 0");
        appendLine("    }");
    }

    private fun loadPage(screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val source = requireNotNull(screen.source);
        val list = requireNotNull(screen.list);
        appendLine("    /// Reads the FIRST page. Called once when the screen appears, however it appeared.");
        appendLine("    ///");
        appendLine("    /// The cursor is dropped before the call and not after it, so this always asks for");
        appendLine("    /// the first page — which is what makes `reload()` a reload rather than an append.");
        appendLine("    public func load() async");
        appendLine("    {");
        appendLine("        generation += 1");
        appendLine("        let token = generation");
        appendLine("        cursor = nil");
        appendLine("        state = .loading");
        appendLine("        let page: ${response(source)}");
        appendLine("        do");
        appendLine("        {");
        appendLine("            page = try await ${pageCall(screen, bundle, "nil")}");
        appendLine("        }");
        append(pagedCatch("firstPageFailed"));
        appendLine("        guard isCurrent(token)");
        appendLine("        else");
        appendLine("        {");
        appendLine("            return");
        appendLine("        }");
        appendLine("        cursor = page.${list.next}");
        appendLine("        state = state.firstPage(page.${list.items}, next: page.${list.next})");
        appendLine("    }");
    }

    private fun loadMore(screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val source = requireNotNull(screen.source);
        val list = requireNotNull(screen.list);
        appendLine("    /// Reads the page after the rows already on screen.");
        appendLine("    ///");
        appendLine("    /// Ignored unless `canLoadMore` — there are rows, the server said there are more, and");
        appendLine("    /// no page is already in flight. The state's own `appending()` ignores it a second");
        appendLine("    /// time, and both guards are wanted: a list asks for its next page when the end of it");
        appendLine("    /// comes into view, and the end comes into view whenever the list is laid out again.");
        appendLine("    public func loadMore() async");
        appendLine("    {");
        appendLine("        guard state.canLoadMore");
        appendLine("        else");
        appendLine("        {");
        appendLine("            return");
        appendLine("        }");
        appendLine("        generation += 1");
        appendLine("        let token = generation");
        appendLine("        let from = cursor");
        appendLine("        state = state.appending()");
        appendLine("        let page: ${response(source)}");
        appendLine("        do");
        appendLine("        {");
        appendLine("            page = try await ${pageCall(screen, bundle, "from")}");
        appendLine("        }");
        append(pagedCatch("appendFailed"));
        appendLine("        guard isCurrent(token)");
        appendLine("        else");
        appendLine("        {");
        appendLine("            return");
        appendLine("        }");
        appendLine("        cursor = page.${list.next}");
        appendLine("        state = state.appended(page.${list.items}, next: page.${list.next})");
        appendLine("    }");
    }

    private fun pagedRetries(): String = buildString {
        appendLine("    /// Asks again for the page that failed. The footer's own control calls this.");
        appendLine("    public func retryMore() async");
        appendLine("    {");
        appendLine("        await loadMore()");
        appendLine("    }");
        appendLine();
        appendLine("    /// Reads the list again from its first page, cursor and all.");
        appendLine("    public func reload() async");
        appendLine("    {");
        appendLine("        await load()");
        appendLine("    }");
    }

    private fun pagedCatch(named: String): String = buildString {
        appendLine("        catch");
        appendLine("        {");
        appendLine("            if isCurrent(token)");
        appendLine("            {");
        appendLine("                state = state.$named(ScreenFailure.envelope(error))");
        appendLine("            }");
        appendLine("            return");
        appendLine("        }");
    }

    private fun pageCall(screen: ScreenDefinition, bundle: Bundle, from: String): String
    {
        val source = requireNotNull(screen.source);
        val list = requireNotNull(screen.list);
        if (!screen.usecase)
        {
            return "${source.service}.${source.name}(${pagedRequest(screen, bundle, "${list.limitValue}", from)})";
        }
        val arguments = RouteParameters.of(screen, bundle).map { "${it.name}: ${it.name}" } +
            "${list.limitField}: ${list.limitValue}" + "${list.cursor}: $from";
        return "useCase.${source.name}(" + arguments.joinToString(", ") + ")";
    }

    /** One page's request: what the route carries, the size the spec wrote, and the cursor. */
    private fun pagedRequest(
        screen: ScreenDefinition,
        bundle: Bundle,
        limit: String,
        from: String
    ): String
    {
        val list = requireNotNull(screen.list);
        val requestType = requireNotNull(requireNotNull(screen.source).declaration.requestType);
        val arguments = bundle.typeNamed(requestType).fields.mapNotNull { field ->
            when
            {
                field.name == list.limitField -> "${field.name}: $limit"
                field.name == list.cursor -> "${field.name}: $from"
                !field.optional -> "${field.name}: ${field.name}"
                else -> null
            }
        };
        return "${Names.swiftType(requestType)}(" + arguments.joinToString(", ") + ")";
    }

    // ---- the form model ----------------------------------------------------

    /**
     * A screen that collects a screenful of input and sends it: its state is a `Form`.
     *
     * Mirrors the Kotlin half method for method, including the integer conversion — `Int32`
     * here and `toIntOrNull` there, which is the same 32 bits said twice. `Form`'s `Number`
     * kind checks the SHAPE and says nothing about width, precisely because this language's
     * `Int` is 64 bits and the other's is 32 (Form.swift's header, P9).
     */
    private fun formModel(
        spec: Spec,
        flow: FlowDefinition,
        screen: ScreenDefinition,
        bundle: Bundle,
        inputs: Inputs
    ): String = buildString {
        val fields = ScreenShape.inputs(screen, bundle);
        val submit = ScreenShape.submitAction(screen, bundle);
        val parameters = modelParameters(flow, screen, bundle);
        append(modelPreamble(screen, inputs));
        appendLine();
        appendLine("/// The `${screen.name}` screen's state and rules, with no toolkit in sight.");
        appendLine("///");
        appendLine("/// Constructor injection, so a test drives this class against a fake service and a");
        appendLine("/// real `Flow` with no device, no view and no server.");
        appendLine("///");
        appendLine("/// Every field is checked at once and a refusal is stated without anything being sent;");
        appendLine("/// editing one field clears that field's refusal and no other.");
        appendLine("@MainActor");
        appendLine("@Observable");
        appendLine("public final class ${type(screen.name, "Model")}");
        appendLine("{");
        appendLine("    /// What this screen's fields and its write are doing.");
        appendLine("    public private(set) var state: Form = Form()");
        appendLine();
        append(storedProperties(parameters));
        appendLine();
        append(generationField());
        appendLine();
        append(modelInit(parameters));
        appendLine();
        appendLine("    /// The flow's stack, so the screen can print its depth as a readout.");
        appendLine("    public var stack: [${route(flow)}] { flow.stack }");
        appendLine();
        append(rulesTable(screen, bundle));
        appendLine();
        append(formReadouts());
        appendLine();
        append(editMethod());
        appendLine();
        append(submitMethod(spec, screen, submit, fields, bundle));
        screen.actions.filter { it != submit }.forEach { action ->
            appendLine();
            append(navigationOnlyAction(spec, screen, action, bundle));
        };
        appendLine();
        append(refusalHelpers());
        appendLine();
        append(isCurrent(flow, screen, bundle, clearedBy = "edit(_:)"));
        appendLine("}");
    }

    private fun formReadouts(): String = buildString {
        appendLine("    /// What a runner reads this screen as: how deep the flow stands, what the write is");
        appendLine("    /// doing, and which fields are refused and by which rule.");
        appendLine("    public var readouts: [String]");
        appendLine("    {");
        appendLine("        [");
        appendLine("            \"stack=\\(flow.stack.count)\",");
        appendLine("            \"state=\\(submitName(state.submit))\",");
        appendLine("            \"fields=\\(refusedFields(state))\"");
        appendLine("        ]");
        appendLine("    }");
        appendLine();
        appendLine("    /// The one word a runner reads this form's write as.");
        appendLine("    private func submitName(_ submit: Busy) -> String");
        appendLine("    {");
        appendLine("        switch submit");
        appendLine("        {");
        appendLine("        case .idle: return \"idle\"");
        appendLine("        case .busy: return \"busy\"");
        appendLine("        case .error: return \"error\"");
        appendLine("        }");
        appendLine("    }");
        appendLine();
        appendLine("    /// Which fields are refused and by which rule, by field name, or `ok`.");
        appendLine("    private func refusedFields(_ form: Form) -> String");
        appendLine("    {");
        appendLine("        let refused = refusals(form).map { \"\\($0.field):\\($0.rule)\" }");
        appendLine("        return refused.isEmpty ? \"ok\" : refused.joined(separator: \",\")");
        appendLine("    }");
    }

    private fun editMethod(): String = buildString {
        appendLine("    /// The field was edited, so its refusal is stale and goes.");
        appendLine("    ///");
        appendLine("    /// Per field, which is what a form has instead of `clearError()`: a person fixing one");
        appendLine("    /// field is not telling the screen anything about the other three. A field the last");
        appendLine("    /// check did not look at is left alone rather than invented.");
        appendLine("    public func edit(_ field: String)");
        appendLine("    {");
        appendLine("        state = state.edited(field)");
        appendLine("    }");
    }

    private fun submitMethod(
        spec: Spec,
        screen: ScreenDefinition,
        action: ActionDefinition,
        fields: List<RouteParameters.Parameter>,
        bundle: Bundle
    ): String = buildString {
        val call = requireNotNull(action.call);
        val parameters = fields.joinToString(", ") { "${it.name}: String" };
        appendLine("    /// ${call.declaration.summary}");
        appendLine("    ///");
        appendLine("    /// Ignored while a write is already in flight (R2), and refused outright when any");
        appendLine("    /// field breaks its rules — every field at once, and nothing is sent.");
        appendLine("    ///");
        appendLine("    /// Every parameter is text, including the ones the contract types as integers: what");
        appendLine("    /// a person typed is a string, and turning it into a number is a step this method");
        appendLine("    /// takes after the check and can still refuse.");
        appendLine("    public func ${action.name}($parameters) async");
        appendLine("    {");
        appendLine("        guard state.canSubmit");
        appendLine("        else");
        appendLine("        {");
        appendLine("            return");
        appendLine("        }");
        appendLine("        let checked = Form.check(");
        appendLine("            values: ${fieldValues(fields, text = true)},");
        appendLine("            rules: rules,");
        appendLine("            custom: validator");
        appendLine("        )");
        appendLine("        if !checked.isValid");
        appendLine("        {");
        appendLine("            state = checked");
        appendLine("            return");
        appendLine("        }");
        fields.filter { ScreenShape.isInteger(it) }.forEach { field -> append(conversion(field)) };
        appendLine("        generation += 1");
        appendLine("        let token = generation");
        appendLine("        state = checked.submitting()");
        appendLine("        do");
        appendLine("        {");
        appendLine("            ${discard(call)}try await ${call.service}.${call.name}(${formRequest(call, fields, bundle)})");
        appendLine("        }");
        appendLine("        catch");
        appendLine("        {");
        appendLine("            if isCurrent(token)");
        appendLine("            {");
        appendLine("                state = state.submitFailed(ScreenFailure.envelope(error))");
        appendLine("            }");
        appendLine("            return");
        appendLine("        }");
        appendLine("        guard isCurrent(token)");
        appendLine("        else");
        appendLine("        {");
        appendLine("            return");
        appendLine("        }");
        appendLine("        state = state.submitted()");
        if (action.then != null)
        {
            appendLine("        ${navigationCall(spec, action, bundle)}");
        }
        appendLine("    }");
    }

    /**
     * One integer field, turned into the 32 bits both platforms carry.
     *
     * `Int32` and not `Int`, which here is 64 bits: the Kotlin half's `toIntOrNull` is 32, and
     * a width that differed would make `3000000000` a request on one phone and a refusal on
     * the other. The refusal is the field's own `kind`, so a person is told where it is.
     */
    private fun conversion(field: RouteParameters.Parameter): String = buildString {
        appendLine("        guard let ${field.name}Value = Int32(${field.name})");
        appendLine("        else");
        appendLine("        {");
        appendLine("            var refused = checked.fields");
        appendLine("            let refusal: FieldError = .kind(.number)");
        appendLine("            refused.updateValue(refusal, forKey: \"${field.name}\")");
        appendLine("            state = Form(fields: refused, submit: checked.submit)");
        appendLine("            return");
        appendLine("        }");
    }

    /** A form's request: text where the contract says text, and the converted value elsewhere. */
    private fun formRequest(
        method: ServiceMethod,
        fields: List<RouteParameters.Parameter>,
        bundle: Bundle
    ): String
    {
        val requestType = method.declaration.requestType ?: return "()";
        val converted = fields.filter { ScreenShape.isInteger(it) }.map { it.name }.toSet();
        val arguments = bundle.typeNamed(requestType).fields.filter { !it.optional }.map { field ->
            val value = if (field.name in converted) "Int64(${field.name}Value)" else field.name;
            "${field.name}: $value";
        };
        return "${Names.swiftType(requestType)}(" + arguments.joinToString(", ") + ")";
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
        appendLine("    func ${source.name}(${useCaseParameters(screen, parameters)}) async throws -> ${response(source)}");
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
        appendLine("    public func ${source.name}(${useCaseParameters(screen, parameters)}) async throws -> ${response(source)}");
        appendLine("    {");
        appendLine("        try await service.${source.name}(${useCaseRequest(screen, bundle, source)})");
        appendLine("    }");
        appendLine("}");
    }

    private fun parameterList(parameters: List<RouteParameters.Parameter>): String =
        parameters.joinToString(", ") { "${it.name}: ${swiftType(it.type)}" }

    /** What a use case is asked for, which on a paged screen is a size and a cursor more. */
    private fun useCaseParameters(screen: ScreenDefinition, parameters: List<RouteParameters.Parameter>): String
    {
        val list = screen.list ?: return parameterList(parameters);
        return (parameters.map { "${it.name}: ${swiftType(it.type)}" } +
            "${list.limitField}: Int64" + "${list.cursor}: String?").joinToString(", ");
    }

    /** The request a pass-through use case builds, page or whole. */
    private fun useCaseRequest(screen: ScreenDefinition, bundle: Bundle, source: ServiceMethod): String
    {
        val list = screen.list ?: return requestLiteral(source, bundle);
        return pagedRequest(screen, bundle, list.limitField, list.cursor);
    }

    // ---- the failure mapping ----------------------------------------------

    private fun failure(spec: Spec, bundle: Bundle, inputs: Inputs): String = buildString {
        val rules = spec.screens.any { screen -> screen.inputs.any { it.rules != null } };
        appendLine(header(inputs));
        appendLine();
        appendLine("import SPFNClient");
        appendLine("import SPFNCore");
        appendLine("import SPFNUI");
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
        if (rules)
        {
            append(ruleRefusal());
            appendLine();
        }
        appendLine("    /// The server's own envelope where there is one, and a local one where there is");
        appendLine("    /// not. The message carries the name of the SDK type that failed and never any");
        appendLine("    /// server text.");
        appendLine("    ///");
        appendLine("    /// `Error` and not `SPFNClientError`: the SDK throws more than that one type, and");
        appendLine("    /// a screen that could not name what it caught would have nothing to show for it.");
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
        append(classification(bundle, rules));
        appendLine("}");
    }

    /**
     * The five keys a failure can be SHOWN under, and how a code becomes one.
     *
     * Derived from the pinned bundle, not written here: the codes are grouped by the HTTP
     * status the contract gives them, so a contract that adds a 401 adds it to the
     * unauthorized family without anybody remembering to. What is a judgement — that a 401
     * family is worth its own sentence and a 409 family is not — is the grouping below and is
     * stated once.
     *
     * The words themselves are `SPFNStrings`'s. Nothing here reaches `envelope.message`
     * except `fieldMessage`, whose message field is this generator's own field name and never
     * a server's text (decision C7).
     */
    private fun classification(bundle: Bundle, rules: Boolean): String = buildString {
        appendLine();
        appendLine("    /// The code names a device the server is not holding a request for.");
        appendLine("    public static let deviceNotFoundKey = \"deviceNotFound\"");
        appendLine();
        appendLine("    /// Nothing was reached, or what came back was not readable.");
        appendLine("    public static let networkKey = \"network\"");
        appendLine();
        appendLine("    /// The server refused this device's credentials.");
        appendLine("    public static let unauthorizedKey = \"unauthorized\"");
        appendLine();
        appendLine("    /// The screen refused its own input. Nothing was sent.");
        appendLine("    public static let validationKey = \"validation\"");
        appendLine();
        appendLine("    /// Anything this build classifies as nothing more specific.");
        appendLine("    public static let unexpectedKey = \"unexpected\"");
        appendLine();
        appendLine("    /// Which of the five keys `envelope` is shown under.");
        appendLine("    ///");
        appendLine("    /// The two families below are the contract's own 401s and 404s, listed from the");
        appendLine("    /// pinned bundle at generation time.");
        appendLine("    public static func messageKey(_ envelope: SPFNErrorEnvelope) -> String");
        appendLine("    {");
        appendLine("        switch envelope.code");
        appendLine("        {");
        appendLine("        case validationCode:");
        appendLine("            return validationKey");
        appendLine("        case callFailedCode:");
        appendLine("            return networkKey");
        appendCases(this, bundle, 401, "unauthorizedKey");
        appendCases(this, bundle, 404, "deviceNotFoundKey");
        appendLine("        default:");
        appendLine("            return unexpectedKey");
        appendLine("        }");
        appendLine("    }");
        appendLine();
        appendLine("    /// The sentence for `envelope`, looked up in `SPFNStrings`.");
        appendLine("    ///");
        appendLine("    /// Never the server's own words: `message` is text a server chose and a screen that");
        appendLine("    /// drew it would publish whatever the server felt like saying (decision C7).");
        appendLine("    public static func message(_ envelope: SPFNErrorEnvelope) -> String");
        appendLine("    {");
        appendLine("        switch messageKey(envelope)");
        appendLine("        {");
        appendLine("        case deviceNotFoundKey:");
        appendLine("            return SPFNStrings.errorDeviceNotFound");
        appendLine("        case networkKey:");
        appendLine("            return SPFNStrings.errorNetwork");
        appendLine("        case unauthorizedKey:");
        appendLine("            return SPFNStrings.errorUnauthorized");
        appendLine("        case validationKey:");
        appendLine("            return SPFNStrings.errorValidation");
        appendLine("        default:");
        appendLine("            return SPFNStrings.errorUnexpected");
        appendLine("        }");
        appendLine("    }");
        appendLine();
        appendLine("    /// Whether this failure belongs under a field rather than to the screen.");
        appendLine("    public static func isFieldRefusal(_ envelope: SPFNErrorEnvelope) -> Bool");
        appendLine("    {");
        appendLine("        envelope.code == validationCode");
        appendLine("    }");
        appendLine();
        appendLine("    /// The sentence to draw under `field`, or nil when this failure is not that field's.");
        appendLine("    ///");
        appendLine("    /// The one read of `message` in this file, and it is safe because the value there is");
        appendLine("    /// this generator's own field name: `validation(_:)` above is what put it there.");
        appendLine("    public static func fieldMessage(_ envelope: SPFNErrorEnvelope?, field: String) -> String?");
        appendLine("    {");
        if (rules)
        {
            // The closure names its parameter rather than taking `$0`, because the `$0` of a
            // closure nested inside another closure's argument list is the inner one and reads
            // as the outer one.
            appendLine("        guard let envelope = envelope, envelope.code == validationCode,");
            appendLine("            String(envelope.message.prefix(while: { character in character != \":\" })) == field");
        }
        else
        {
            appendLine("        guard let envelope = envelope, envelope.code == validationCode, envelope.message == field");
        }
        appendLine("        else");
        appendLine("        {");
        appendLine("            return nil");
        appendLine("        }");
        appendLine("        return SPFNStrings.errorValidation");
        appendLine("    }");
    }

    /**
     * The other refusal a screen makes itself: a field its `rules` turned down, named by the
     * RULE that turned it down. The Kotlin half's comment applies here word for word.
     */
    private fun ruleRefusal(): String = buildString {
        appendLine("    /// A field its own rules refused. The message is `<field>:<rule>`.");
        appendLine("    public static func validation(_ field: String, rule: String) -> SPFNErrorEnvelope");
        appendLine("    {");
        appendLine("        SPFNErrorEnvelope(code: validationCode, message: \"\\(field):\\(rule)\", requestID: \"\")");
        appendLine("    }");
    }

    /** One `case` line per contract error carrying [status], or nothing when there are none. */
    private fun appendCases(out: StringBuilder, bundle: Bundle, status: Long, key: String)
    {
        val codes = bundle.errors.filter { it.httpStatus == status }.map { it.code }.sorted();
        if (codes.isEmpty())
        {
            return;
        }
        out.appendLine("        case " + codes.joinToString(", ") { "\"$it\"" } + ":");
        out.appendLine("            return $key");
    }

    // ---- the views ---------------------------------------------------------

    /**
     * One screen's view: a `Screen` frame, and SPFNUI components inside it.
     *
     * Nothing here draws a control of its own any more. A field is a `SpfnTextField`, a
     * control is the button its `role` names, a refusal is a `StatusText` and a read's four
     * states are a `LoadableView` — so the touch minimum, the keyboard contract and the
     * palette are the SDK's, written once and checked once, rather than re-emitted into
     * every generated view where a fix would have to be made in the generator and shipped.
     *
     * Selectors are unchanged and deliberately so: a control is still found by the id
     * `<screen>.<action>` and a readout by its text. `tools/harness/flows/d1-approve.yaml`
     * and its two siblings drive these screens against a live server by exactly those
     * strings, and a component swap that moved them would be a device regression nothing on
     * this host could see.
     */
    private fun view(screen: ScreenDefinition, bundle: Bundle, inputs: Inputs): String = buildString {
        val typed = screen.actions.flatMap { RouteParameters.inputs(screen, it, bundle) }.distinctBy { it.name };
        val controls = screen.actions.filter { it != screen.reread };
        appendLine("#if canImport(SwiftUI)");
        appendLine(header(inputs));
        appendLine("//");
        appendLine("// Every element here exists because a runner has to reach it or read it: one control");
        appendLine("// per action, one field per typed input" + if (readouts) ", and the two readouts." else ".");
        if (readouts)
        {
            appendLine("// The readouts stand FIRST so a body long enough to scroll cannot put them out of");
            appendLine("// reach: a runner reads them before it has done anything at all.");
        }
        appendLine("// What a VALUE looks like is the human's, outside `Generated/` — the ready slot below is");
        appendLine("// deliberately empty. Selectors follow the harness's rule: a control by the id");
        appendLine("// `<screen>.<action>`, a readout by its text (tools/harness/ios/Sources/HarnessView.swift).");
        appendLine();
        appendLine("import SPFNCore");
        appendLine("import SPFNUI");
        appendLine("import SwiftUI");
        appendLine();
        appendLine("/// The `${screen.name}` screen, drawn out of SPFNUI's components.");
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
        appendLine("        Screen(title: ${quoted(screen.title)}${trailingArgument(screen)}, scroll: ${screen.scroll})");
        appendLine("        {");
        appendLine("            VStack(alignment: .leading, spacing: SPFNTokens.space4)");
        appendLine("            {");
        // The readouts come FIRST, and that is a rule about reach rather than about layout.
        // A body long enough to need scrolling puts everything under it below the fold, and a
        // runner that could not read `stack=` until it had scrolled could not tell an app
        // that had not started from a screen it had not reached yet.
        if (readouts)
        {
            appendLine("                readouts");
        }
        if (screen.isLoadable)
        {
            append(loadableSlot(screen));
        }
        // The static body, one component per paragraph. The words are the generator's, out
        // of `BodyText`, because a spec carrying its own prose is one nobody can read the
        // structure out of; the spec named the key.
        screen.body.forEach { paragraph -> appendLine("                SpfnText(${quoted(paragraph)})") };
        typed.forEach { input -> append(field(screen, input, bundle)) };
        if (!screen.isLoadable && typed.isNotEmpty())
        {
            appendLine("                status");
        }
        controls.forEach { action -> append(control(screen, action, bundle)) };
        appendLine("            }");
        appendLine("            .padding(SPFNTokens.space4)");
        if (screen.source != null)
        {
            appendLine("            // A screen loads its own read once, however it appeared: pushed onto the");
            appendLine("            // stack, or already on it because the flow was opened at a whole stack.");
            appendLine("            .task");
            appendLine("            {");
            appendLine("                await model.load()");
            appendLine("            }");
        }
        appendLine("        }");
        appendLine("    }");
        if (!screen.isLoadable && typed.isNotEmpty())
        {
            appendLine();
            append(failureAccessors(screen));
        }
        if (readouts)
        {
            appendLine();
            append(readoutSlot());
        }
        appendLine("}");
        if (readouts)
        {
            appendLine();
            append(stateName(screen));
        }
        appendLine("#endif");
    }

    /**
     * The header's trailing slot, emitted only where the spec suppresses the flow's own.
     *
     * `Flow.wayOut` gives a back to every route above the root and a close to the root of a
     * flow presented over something, so almost every screen wants the default. An empty slot
     * passed everywhere would erase every way out in the app; it is passed exactly where a
     * root that would have had a close said `header.close: false`.
     */
    private fun trailingArgument(screen: ScreenDefinition): String =
        if (screen.suppressesClose) ", trailing: AnyView(EmptyView())" else ""

    /**
     * The read's four states, and the retry control inside the error one.
     *
     * The re-read action is drawn HERE and nowhere else. Emitted as a control of its own as
     * well, it would put two nodes under `<screen>.<retry>` and a runner asked for that id
     * would refuse to pick between them.
     */
    private fun loadableSlot(screen: ScreenDefinition): String = buildString {
        val retry = screen.reread;
        appendLine("                LoadableView(");
        appendLine("                    model.state,");
        if (retry != null)
        {
            appendLine("                    retryIdentifier: \"${screen.name}.${retry.name}\",");
            appendLine("                    onRetry: { Task { await model.${retry.name}() } },");
        }
        appendLine("                    message: ScreenFailure.message");
        appendLine("                )");
        appendLine("                { _ in");
        appendLine("                    // What a value looks like is the human's, outside `Generated/`.");
        appendLine("                    EmptyView()");
        appendLine("                }");
    }

    /** The screen's own refusal, where it is not one a field carries. */
    private fun failureAccessors(screen: ScreenDefinition): String = buildString {
        appendLine("    /// The envelope this screen is carrying, or nil.");
        appendLine("    private var failure: SPFNErrorEnvelope?");
        appendLine("    {");
        appendLine("        if case .error(let envelope) = model.state");
        appendLine("        {");
        appendLine("            return envelope");
        appendLine("        }");
        appendLine("        return nil");
        appendLine("    }");
        appendLine();
        appendLine("    /// A refusal that is the SCREEN's rather than one field's.");
        appendLine("    ///");
        appendLine("    /// A field's own refusal is drawn under the field by `SpfnTextField`, so drawing it");
        appendLine("    /// here as well would say the same thing twice in two places.");
        appendLine("    @ViewBuilder");
        appendLine("    private var status: some View");
        appendLine("    {");
        appendLine("        if let failure = failure, !ScreenFailure.isFieldRefusal(failure)");
        appendLine("        {");
        appendLine("            StatusText(");
        appendLine("                kind: .error,");
        appendLine("                text: ScreenFailure.message(failure),");
        appendLine("                identifier: \"${screen.name}.status\"");
        appendLine("            )");
        appendLine("        }");
        appendLine("    }");
    }

    /**
     * The two readouts, drawn only for a target that asked for them.
     *
     * They are test equipment: the one thing both runners can read and neither can guess, and
     * two lines of diagnostics on a screen a person is meant to use. `--runner-readouts`
     * decides, per consumer, and both consumers that ship today set it (decision C6).
     */
    private fun readoutSlot(): String = buildString {
        appendLine("    /// What a runner reads this screen's state and its flow's depth as.");
        appendLine("    @ViewBuilder");
        appendLine("    private var readouts: some View");
        appendLine("    {");
        appendLine("        SpfnText(\"state=\" + stateName(model.state), role: .mono)");
        appendLine("        SpfnText(\"stack=\" + String(model.stack.count), role: .mono)");
        appendLine("    }");
    }

    /**
     * One typed input, decorated by whatever `screens.<s>.inputs.<i>` said.
     *
     * `onSubmit` and the submitting action are the same call written twice, which is the
     * whole of `submitOnReturn`: the return key does what the button does, so a person who
     * finishes typing does not have to reach for the control.
     */
    private fun field(screen: ScreenDefinition, input: RouteParameters.Parameter, bundle: Bundle): String =
        buildString {
            val declared = screen.inputNamed(input.name);
            val submitting = screen.actions.firstOrNull { action ->
                RouteParameters.inputs(screen, action, bundle).any { it.name == input.name }
            };
            appendLine("                SpfnTextField(");
            appendLine("                    label: ${quoted(declared.label)},");
            appendLine("                    kind: .${declared.kind},");
            appendLine("                    identifier: \"${screen.name}.${input.name}\",");
            appendLine("                    text: \$${input.name},");
            appendLine("                    error: ScreenFailure.fieldMessage(failure, field: \"${input.name}\"),");
            appendLine("                    submitOnReturn: ${declared.submitOnReturn && submitting != null},");
            appendLine("                    autofocus: ${declared.autofocus},");
            if (declared.submitOnReturn && submitting != null)
            {
                appendLine("                    onSubmit: { ${invocation(screen, submitting, bundle)} },");
            }
            appendLine("                    onChange: { _ in model.clearError() }");
            appendLine("                )");
        }

    /**
     * One control, as the button its role names.
     *
     * `busy` is what the model already knows and the screen used to hide: a write in flight
     * disables the control that started it and spins on it, which is the same rule R2 states
     * for the model, drawn.
     */
    private fun control(screen: ScreenDefinition, action: ActionDefinition, bundle: Bundle): String = buildString {
        val id = "${screen.name}.${action.name}";
        appendLine("                ${button(action.role)}(");
        appendLine("                    title: \"${action.name}\",");
        appendLine("                    identifier: \"$id\",");
        if (action.call != null)
        {
            appendLine("                    busy: ${busyExpression(screen)},");
        }
        appendLine("                    onTap: { ${invocation(screen, action, bundle)} }");
        appendLine("                )");
    }

    /** Whether a write of this screen's is in flight, in the shape the model publishes it. */
    private fun busyExpression(screen: ScreenDefinition): String =
        if (screen.isLoadable) "model.writing" else "model.state == .busy"

    /** The component a spec role names. */
    private fun button(role: String): String = when (role)
    {
        "primary" -> "PrimaryButton"
        "destructive" -> "DestructiveButton"
        "text" -> "TextButton"
        else -> "SecondaryButton"
    }

    /** Calling one action from a control or a return key, suspending or not. */
    private fun invocation(screen: ScreenDefinition, action: ActionDefinition, bundle: Bundle): String
    {
        val arguments = RouteParameters.inputs(screen, action, bundle)
            .joinToString(", ") { "${it.name}: ${it.name}" };
        if (action.call == null)
        {
            return "model.${action.name}()";
        }
        return "Task { await model.${action.name}($arguments) }";
    }

    /** One Swift string literal, for a title an author wrote. */
    private fun quoted(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

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
        spec.screens.filter { takesValidator(it, bundle) && customRule(it) }

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
        if (customRule(screen))
        {
            arguments += "validator: ${screen.name}Validator";
        }
        appendLine();
        appendLine("    /// A fresh model for one appearance of `${screen.name}`.");
        appendLine("    public func ${screen.name}Model(${parameterList(parameters)}) -> ${type(screen.name, "Model")}");
        appendLine("    {");
        appendLine("        ${type(screen.name, "Model")}(${arguments.joinToString(", ")})");
        appendLine("    }");
    }

    private fun liveFactory(spec: Spec, bundle: Bundle): String = buildString {
        val validators = validated(spec, bundle);
        val services = (spec.services.map { "${it.name}: Default${type(it.name, "Service")}(client: client)" } +
            validators.map { "${it.name}Validator: ${it.name}Validator" }).joinToString(", ");
        appendLine("    /// The app against a real server: one transport, one session, one client.");
        appendLine("    public static func live(");
        appendLine("        transport: any SPFNTransport,");
        appendLine("        keyProvider: any SPFNKeyProvider,");
        appendLine("        baseURL: String" + if (validators.isEmpty()) "" else ",");
        validators.forEachIndexed { index, screen ->
            val comma = if (index == validators.size - 1) "" else ",";
            appendLine("        ${screen.name}Validator: any FieldValidator$comma");
        };
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
