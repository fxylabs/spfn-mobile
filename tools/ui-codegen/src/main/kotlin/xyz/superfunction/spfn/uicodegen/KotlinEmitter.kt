// The Kotlin half of the scaffold: one Compose app's generated sources.
//
// Which app is the `Target`'s to say. The root and the package are read from it, so the
// same spec produces the example app's scaffold and the harness's from one emitter and
// one set of rules — and this file names neither of them.
//
// Structure mirrors SwiftEmitter one file at a time and one declaration at a time. That is
// not tidiness — the Swift half is written blind on a Linux host where SwiftUI does not
// compile, so a fix a Mac forces has to map back onto this file line for line. The two
// emitters are kept in the same order, with the same helper names, for that reason.
//
// The layering the emitted code holds to (docs/architecture/README.md):
//   services  — the ONLY layer that names a call descriptor
//   use cases — optional, one per screen that asks for a seam
//   models    — state and rules, no toolkit
//   views     — the toolkit, and nothing else

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle
import xyz.superfunction.spfn.codegen.FieldType
import xyz.superfunction.spfn.codegen.Names

class KotlinEmitter(target: Target)
{
    private val root: String = target.kotlinRoot;

    private val readouts: Boolean = target.runnerReadouts;
    private val pkg: String = target.kotlinPackage;

    fun emit(spec: Spec, bundle: Bundle, inputs: Inputs): Map<String, String>
    {
        val files = mutableMapOf<String, String>();
        spec.services.forEach { service ->
            files["$root/services/${type(service.name, "Service")}.kt"] = service(service, inputs);
        };
        spec.flows.forEach { flow ->
            files["$root/flows/${type(flow.name, "Flow")}.kt"] = flow(spec, flow, bundle, inputs);
        };
        files["$root/screens/ScreenFailure.kt"] = failure(bundle, inputs);
        spec.screens.forEach { screen ->
            files["$root/screens/${type(screen.name, "Model")}.kt"] = model(spec, screen, bundle, inputs);
            if (screen.usecase)
            {
                files["$root/screens/${type(screen.name, "UseCase")}.kt"] = useCase(screen, bundle, inputs);
            }
            files["$root/views/${type(screen.name, "Screen")}.kt"] = view(screen, bundle, inputs);
        };
        files["$root/AppContainer.kt"] = container(spec, bundle, inputs);
        return files;
    }

    // ---- names -------------------------------------------------------------

    private fun type(name: String, kind: String): String = UiNames.kotlinType(name, kind)

    private fun route(flow: FlowDefinition): String = type(flow.name, "Route")

    private fun routeCase(screen: ScreenDefinition): String = UiNames.pascal(screen.name)

    private fun request(method: ServiceMethod): String =
        Names.kotlinType(method.declaration.requestType ?: "Unit")

    private fun response(method: ServiceMethod): String =
        method.declaration.responseType?.let { Names.kotlinType(it) } ?: "Unit"

    private fun kotlinType(type: FieldType): String = when (type)
    {
        is FieldType.IntegerType -> "Long"
        else -> "String"
    }

    private fun header(inputs: Inputs): String = Header.slashes(inputs)

    // ---- the service -------------------------------------------------------

    /**
     * The one generated file that names a call descriptor.
     *
     * Everything above it sees this interface and the generated request and response
     * types. `tools/validate/validate.sh` refuses a `SpfnGeneratedCalls.` reference
     * anywhere under `examples/` outside this directory, so the rule is enforced rather
     * than merely written down here.
     */
    private fun service(service: ServiceDefinition, inputs: Inputs): String = buildString {
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

    // ---- the flow ----------------------------------------------------------

    private fun flow(spec: Spec, flow: FlowDefinition, bundle: Bundle, inputs: Inputs): String = buildString {
        val screens = spec.screensOf(flow);
        appendLine(header(inputs));
        appendLine();
        appendLine("package $pkg.flows");
        appendLine();
        appendLine("import androidx.compose.runtime.Composable");
        appendLine("import androidx.compose.runtime.remember");
        appendLine("import $pkg.AppContainer");
        screens.sortedBy { it.name }.forEach {
            appendLine("import $pkg.views.${type(it.name, "Screen")}");
        };
        appendLine("import xyz.superfunction.spfn.ui.Flow");
        appendLine("import xyz.superfunction.spfn.ui.FlowEntry");
        appendLine("import xyz.superfunction.spfn.ui.FlowHost");
        appendLine("import xyz.superfunction.spfn.ui.FlowRoute");
        if (flow.entry == "sheet")
        {
            appendLine("import xyz.superfunction.spfn.ui.SheetDetent");
        }
        appendLine();
        append(routeType(flow, screens, bundle));
        appendLine();
        append(flowFactory(flow));
        appendLine();
        append(flowHost(flow, screens, bundle));
    }

    /**
     * The `FlowEntry` value, which is a name for two of the three and a call for the third.
     *
     * A sheet stands at a height and the other two do not, which is why `FlowEntry` is a
     * sealed interface rather than an enum. The spec says the same thing the same way: a
     * `sheet` entry carries `sheet.detent` and nothing else may.
     */
    private fun entryValue(flow: FlowDefinition): String = when (flow.entry)
    {
        "sheet" -> "FlowEntry.Sheet(SheetDetent.${UiNames.pascal(requireNotNull(flow.detent))})"
        else -> "FlowEntry.${UiNames.pascal(flow.entry)}"
    }

    private fun routeType(flow: FlowDefinition, screens: List<ScreenDefinition>, bundle: Bundle): String =
        buildString {
            appendLine("/**");
            appendLine(" * Where the `${flow.name}` flow can stand.");
            appendLine(" *");
            appendLine(" * A screen that reads carries what its read needs; a screen that reads nothing");
            appendLine(" * carries nothing and is a `data object`, so two entries for it are the same entry.");
            appendLine(" */");
            appendLine("sealed interface ${route(flow)} : FlowRoute");
            appendLine("{");
            screens.sortedBy { it.name }.forEachIndexed { index, screen ->
                if (index > 0)
                {
                    appendLine();
                }
                val parameters = RouteParameters.of(screen, bundle);
                if (parameters.isEmpty())
                {
                    appendLine("    data object ${routeCase(screen)} : ${route(flow)}");
                }
                else
                {
                    val fields = parameters.joinToString(", ") { "val ${it.name}: ${kotlinType(it.type)}" };
                    appendLine("    data class ${routeCase(screen)}($fields) : ${route(flow)}");
                }
            };
            appendLine("}");
        }

    private fun flowFactory(flow: FlowDefinition): String = buildString {
        appendLine("/** How this flow is presented, and therefore what a back on its last route means. */");
        appendLine("val ${UiNames.pascal(flow.name)}Entry: FlowEntry = ${entryValue(flow)};");
        appendLine();
        appendLine("/** A closed-over factory, so the flow opens on the screen the spec named as its start. */");
        appendLine("@Suppress(\"FunctionName\")");
        appendLine("fun ${type(flow.name, "Flow")}(): Flow<${route(flow)}> =");
        appendLine("    Flow(listOf(${route(flow)}.${UiNames.pascal(flow.start)}))");
    }

    /**
     * Route to model to view, and nothing else.
     *
     * A screen with a source loads it here, once per route, keyed on the route: a screen
     * loads its own read however it appeared, which is what makes a deep entry —
     * `open(at:)` onto a whole stack — behave exactly like a push.
     */
    private fun flowHost(flow: FlowDefinition, screens: List<ScreenDefinition>, bundle: Bundle): String =
        buildString {
            appendLine("/** Renders the `${flow.name}` flow: one route, one model, one view. */");
            appendLine("@Composable");
            appendLine("fun ${type(flow.name, "FlowHost")}(container: AppContainer)");
            appendLine("{");
            appendLine("    FlowHost(container.${flow.name}Flow, ${UiNames.pascal(flow.name)}Entry) { route ->");
            appendLine("        when (route)");
            appendLine("        {");
            screens.sortedBy { it.name }.forEach { screen -> append(hostBranch(flow, screen, bundle)) };
            appendLine("        }");
            appendLine("    }");
            appendLine("}");
        }

    private fun hostBranch(flow: FlowDefinition, screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val parameters = RouteParameters.of(screen, bundle);
        val arguments = parameters.joinToString(", ") { "route.${it.name}" };
        appendLine("            is ${route(flow)}.${routeCase(screen)} ->");
        appendLine("            {");
        appendLine("                val model = remember(route) { container.${screen.name}Model($arguments) };");
        appendLine("                ${type(screen.name, "Screen")}(model);");
        appendLine("            }");
    }

    // ---- the models --------------------------------------------------------

    private fun model(spec: Spec, screen: ScreenDefinition, bundle: Bundle, inputs: Inputs): String
    {
        val flow = spec.flows.first { it.name == screen.flow };
        return if (screen.isLoadable) loadableModel(spec, flow, screen, bundle, inputs)
        else busyModel(spec, flow, screen, bundle, inputs);
    }

    private fun modelPreamble(
        flow: FlowDefinition,
        screen: ScreenDefinition,
        inputs: Inputs,
        stateImport: String
    ): String = buildString {
        appendLine(header(inputs));
        appendLine();
        appendLine("package $pkg.screens");
        appendLine();
        if (screen.calls)
        {
            appendLine("import kotlinx.coroutines.CancellationException");
        }
        appendLine("import kotlinx.coroutines.flow.MutableStateFlow");
        appendLine("import kotlinx.coroutines.flow.StateFlow");
        appendLine("import kotlinx.coroutines.flow.asStateFlow");
        requestImports(screen).forEach { appendLine("import xyz.superfunction.spfn.generated.$it") };
        appendLine("import $pkg.flows.${route(flow)}");
        screen.services.forEach { appendLine("import $pkg.services.${type(it, "Service")}") };
        appendLine("import xyz.superfunction.spfn.ui.$stateImport");
        appendLine("import xyz.superfunction.spfn.ui.Flow");
    }

    /**
     * A screen model's constructor, in the order a reader expects it: the optional use
     * case, then one parameter per service the screen calls, then the flow, then whatever
     * the route carries. Each service is named after itself, because a screen with two of
     * them has no `service`.
     */
    private fun modelParameters(flow: FlowDefinition, screen: ScreenDefinition, bundle: Bundle): List<String>
    {
        val parameters = mutableListOf<String>();
        if (screen.usecase)
        {
            parameters += "useCase: ${type(screen.name, "UseCase")}";
        }
        screen.services.forEach { parameters += "$it: ${type(it, "Service")}" };
        parameters += "flow: Flow<${route(flow)}>";
        RouteParameters.of(screen, bundle).forEach { parameters += "${it.name}: ${kotlinType(it.type)}" };
        return parameters;
    }

    /** The parameter list as a constructor's lines, with the commas where a person puts them. */
    private fun constructorLines(parameters: List<String>): String =
        parameters.joinToString(",\n") { "    private val $it" } + "\n"

    private fun requestImports(screen: ScreenDefinition): List<String>
    {
        val methods = listOfNotNull(screen.source) + screen.actions.mapNotNull { it.call };
        return methods.map { request(it) }
            .plus(methods.mapNotNull { it.declaration.responseType?.let(Names::kotlinType) })
            .filter { it != "Unit" }
            .distinct()
            .sorted();
    }

    /**
     * A screen that reads nothing: its state is one write's state.
     *
     * `Busy.Busy` is written through the interface's own nested name because the object
     * shadows the interface inside its own file; here, outside it, the fully-qualified
     * nested name is what refers to the state rather than to the type.
     */
    private fun busyModel(
        spec: Spec,
        flow: FlowDefinition,
        screen: ScreenDefinition,
        bundle: Bundle,
        inputs: Inputs
    ): String = buildString {
        val name = type(screen.name, "Model");
        append(modelPreamble(flow, screen, inputs, "Busy"));
        appendLine();
        appendLine("/**");
        appendLine(" * The `${screen.name}` screen's state and rules, with no toolkit in sight.");
        appendLine(" *");
        appendLine(" * Constructor injection, so a test drives this class against a fake service and a");
        appendLine(" * real [Flow] with no device, no composition and no server.");
        appendLine(" */");
        appendLine("class $name(");
        append(constructorLines(modelParameters(flow, screen, bundle)));
        appendLine(")");
        appendLine("{");
        appendLine("    private val mutableState: MutableStateFlow<Busy> = MutableStateFlow(Busy.Idle);");
        appendLine();
        appendLine("    /** What this screen's write is doing. */");
        appendLine("    val state: StateFlow<Busy> = mutableState.asStateFlow();");
        appendLine();
        appendLine("    /** The flow's stack, so the screen can print its depth as a readout. */");
        appendLine("    val stack: StateFlow<List<${route(flow)}>> = flow.stack;");
        if (screen.calls)
        {
            appendLine();
            append(generationField());
        }
        screen.actions.forEach { action -> append(busyAction(spec, flow, screen, action, bundle)) };
        if (screen.calls)
        {
            if (collects(screen, bundle))
            {
                append(clearError());
            }
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
        flow: FlowDefinition,
        screen: ScreenDefinition,
        action: ActionDefinition,
        bundle: Bundle
    ): String = buildString {
        appendLine("    /** ${navigationSentence(action)} */");
        appendLine("    fun ${action.name}()");
        appendLine("    {");
        if (screen.calls)
        {
            appendLine("        generation++;");
        }
        appendLine("        ${navigationCall(spec, flow, action, bundle)};");
        appendLine("    }");
    }

    private fun generationField(): String = buildString {
        appendLine("    /**");
        appendLine("     * Which request is the current one.");
        appendLine("     *");
        appendLine("     * Bumped by everything that starts or abandons a call, and checked again when the");
        appendLine("     * answer comes back. An answer whose token is stale — a superseded call, or a call");
        appendLine("     * whose flow has since closed — is dropped rather than written into a screen");
        appendLine("     * nobody is looking at any more.");
        appendLine("     */");
        appendLine("    private var generation: Int = 0;");
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
    private fun isCurrent(flow: FlowDefinition, screen: ScreenDefinition, bundle: Bundle): String = buildString {
        appendLine("    /**");
        appendLine("     * Whether an answer bearing [token] still belongs to a screen that is on show.");
        appendLine("     *");
        appendLine("     * Three questions: is this the current request, is the flow still presented, and");
        appendLine("     * is this screen's own route the one on top of the stack. The last is not implied");
        appendLine("     * by the others — a route popped while a call was in flight leaves both of them");
        appendLine("     * true — and it asks for the top rather than for membership, because a screen");
        appendLine("     * buried under a second copy of its own route is not on show either.");
        appendLine("     */");
        appendLine("    private fun isCurrent(token: Int): Boolean = token == generation && isOnShow();");
        appendLine();
        appendLine("    /**");
        appendLine("     * Whether this screen's own route is the one the person is standing on.");
        appendLine("     *");
        appendLine("     * Split out of [isCurrent] because a second caller needs it without a token: the");
        appendLine("     * view calls [clearError] when the text changes, and that is not an answer to a");
        appendLine("     * request — it has no generation to compare — while it is still something that must");
        appendLine("     * not write into a screen nobody is looking at.");
        appendLine("     */");
        appendLine("    private fun isOnShow(): Boolean =");
        appendLine("        flow.isPresented.value &&");
        appendLine("            flow.stack.value.lastOrNull() == ${routeValue(flow, screen, bundle)}");
    }

    /**
     * Dropping a refusal because the person started fixing it.
     *
     * The VIEW decides when — `SpfnTextField`'s `onValueChange` — and the model decides
     * whether. Written the other way round, with the model clearing its own error inside a
     * text setter, it would clear the error of a screen that has since been popped: the same
     * R9 family the answer guard is for, arriving through the keyboard instead of through the
     * network (docs/IMPLEMENTATION-PITFALLS.md P24).
     */
    private fun clearError(): String = buildString {
        appendLine();
        appendLine("    /**");
        appendLine("     * Drops this screen's refusal, so editing the input clears the line under it.");
        appendLine("     *");
        appendLine("     * A no-op on a screen that is not the one on show, and a no-op when there is no");
        appendLine("     * refusal to drop: it never interrupts a write.");
        appendLine("     */");
        appendLine("    fun clearError()");
        appendLine("    {");
        appendLine("        if (isOnShow() && mutableState.value is Busy.Error)");
        appendLine("        {");
        appendLine("            mutableState.value = Busy.Idle;");
        appendLine("        }");
        appendLine("    }");
    }

    /** Whether any action on this screen takes a typed input, and therefore draws a field. */
    private fun collects(screen: ScreenDefinition, bundle: Bundle): Boolean =
        screen.actions.any { RouteParameters.inputs(screen, it, bundle).isNotEmpty() }

    /** This screen's own route, as the value the stack would hold while it is on show. */
    private fun routeValue(flow: FlowDefinition, screen: ScreenDefinition, bundle: Bundle): String
    {
        val parameters = RouteParameters.of(screen, bundle);
        val arguments = parameters.joinToString(", ") { "${it.name} = ${it.name}" };
        return "${route(flow)}.${routeCase(screen)}" + if (parameters.isEmpty()) "" else "($arguments)";
    }

    private fun busyAction(
        spec: Spec,
        flow: FlowDefinition,
        screen: ScreenDefinition,
        action: ActionDefinition,
        bundle: Bundle
    ): String = buildString {
        appendLine();
        val inputs = RouteParameters.inputs(screen, action, bundle);
        val parameters = inputs.joinToString(", ") { "${it.name}: ${kotlinType(it.type)}" };
        if (action.call == null)
        {
            append(navigationOnlyAction(spec, flow, screen, action, bundle));
            return@buildString;
        }
        appendLine("    /**");
        appendLine("     * ${action.call.declaration.summary}");
        appendLine("     *");
        appendLine("     * Ignored while a write is already in flight, and refused outright when a required");
        appendLine("     * input is blank — a refusal the screen states without sending anything.");
        appendLine("     */");
        appendLine("    suspend fun ${action.name}($parameters)");
        appendLine("    {");
        appendLine("        if (mutableState.value is Busy.Busy)");
        appendLine("        {");
        appendLine("            return;");
        appendLine("        }");
        inputs.filter { it.type is FieldType.StringType }.forEach { input ->
            appendLine("        if (${input.name}.isBlank())");
            appendLine("        {");
            appendLine("            mutableState.value = Busy.Error(ScreenFailure.validation(\"${input.name}\"));");
            appendLine("            return;");
            appendLine("        }");
        };
        appendLine("        val token = ++generation;");
        appendLine("        mutableState.value = Busy.Busy;");
        appendLine("        try");
        appendLine("        {");
        appendLine("            ${action.call.service}.${action.call.name}(${requestLiteral(action.call, screen, bundle)});");
        appendLine("        }");
        append(catchClauses("Busy"));
        appendLine("        if (!isCurrent(token))");
        appendLine("        {");
        appendLine("            return;");
        appendLine("        }");
        appendLine("        mutableState.value = Busy.Idle;");
        if (action.then != null)
        {
            appendLine("        ${navigationCall(spec, flow, action, bundle)};");
        }
        appendLine("    }");
    }

    /** A request built from the route's own fields and the action's own parameters. */
    private fun requestLiteral(method: ServiceMethod, screen: ScreenDefinition, bundle: Bundle): String
    {
        val requestType = method.declaration.requestType ?: return "Unit";
        val fields = bundle.typeNamed(requestType).fields.filter { !it.optional };
        return "${Names.kotlinType(requestType)}(" +
            fields.joinToString(", ") { "${it.name} = ${it.name}" } + ")";
    }

    private fun navigationCall(
        spec: Spec,
        flow: FlowDefinition,
        action: ActionDefinition,
        bundle: Bundle
    ): String = when (val then = action.then)
    {
        null -> ""
        Navigation.Close -> "flow.close()"
        Navigation.Pop -> "flow.pop()"
        is Navigation.Push ->
        {
            // A screen that carries nothing is emitted as a `data object`, which is a VALUE
            // and not a constructor: `TourTwo()` is an unresolved reference rather than an
            // empty argument list, the way `TourTwo(userCode = …)` is a call. Every push
            // target had a payload until the showcase flows arrived, so this line said `()`
            // for four years' worth of one shape.
            val target = RouteParameters.of(spec.screenNamed(then.screen), bundle);
            val case = "${route(flow)}.${UiNames.pascal(then.screen)}";
            if (target.isEmpty()) "flow.push($case)"
            else "flow.push($case(" + target.joinToString(", ") { "${it.name} = ${it.name}" } + "))"
        }
    }

    private fun navigationSentence(action: ActionDefinition): String = when (action.then)
    {
        Navigation.Close -> "Closes the flow. Its stack empties, so nothing of it is presented."
        Navigation.Pop -> "Drops this route. On the flow's first route this does nothing."
        is Navigation.Push -> "Moves on to the next screen."
        null -> "Does nothing to the flow."
    }

    /**
     * A screen that reads: its state is that read's state.
     *
     * There is no `Empty`, and that is the contract's doing rather than a simplification —
     * the bundle models a response as one named type or none at all, so nothing in it can
     * say "this operation answers with a list" (examples/ui-spec/SCHEMA.md, the 1단계 rule).
     *
     * A write in flight is held on a separate flag rather than in the state, because this
     * screen's vocabulary has no `busy` member and putting the write into `Loading` would
     * blank a value the screen is still showing. The flag is what makes "a write over a
     * value the screen has not read yet is ignored" and "a second press is ignored" the
     * same guard.
     */
    private fun loadableModel(
        spec: Spec,
        flow: FlowDefinition,
        screen: ScreenDefinition,
        bundle: Bundle,
        inputs: Inputs
    ): String = buildString {
        val source = requireNotNull(screen.source);
        val value = response(source);
        append(modelPreamble(flow, screen, inputs, "Loadable"));
        appendLine();
        appendLine("/**");
        appendLine(" * The `${screen.name}` screen's state and rules, with no toolkit in sight.");
        appendLine(" *");
        appendLine(" * Constructor injection, so a test drives this class against a fake service and a");
        appendLine(" * real [Flow] with no device, no composition and no server.");
        appendLine(" */");
        appendLine("class ${type(screen.name, "Model")}(");
        append(constructorLines(modelParameters(flow, screen, bundle)));
        appendLine(")");
        appendLine("{");
        appendLine("    private val mutableState: MutableStateFlow<Loadable<$value>> =");
        appendLine("        MutableStateFlow(Loadable.Loading);");
        appendLine();
        appendLine("    /** What this screen's read has produced so far. */");
        appendLine("    val state: StateFlow<Loadable<$value>> = mutableState.asStateFlow();");
        appendLine();
        appendLine("    /** The flow's stack, so the screen can print its depth as a readout. */");
        appendLine("    val stack: StateFlow<List<${route(flow)}>> = flow.stack;");
        appendLine();
        append(generationField());
        appendLine();
        appendLine("    /**");
        appendLine("     * Whether one of this screen's writes is in flight.");
        appendLine("     *");
        appendLine("     * Readable, because the control that started it draws itself busy from this and a");
        appendLine("     * control that span off a flag of its own could disagree with the model about");
        appendLine("     * whether the press it is refusing was taken. It is a `MutableStateFlow` rather");
        appendLine("     * than a `Boolean` for the reason `state` is: a composition reads it.");
        appendLine("     */");
        appendLine("    private val mutableWriting: MutableStateFlow<Boolean> = MutableStateFlow(false);");
        appendLine();
        appendLine("    /** Whether one of this screen's writes is in flight. */");
        appendLine("    val writing: StateFlow<Boolean> = mutableWriting.asStateFlow();");
        appendLine();
        append(readMethod(screen, bundle));
        screen.actions.forEach { action -> append(loadableAction(spec, flow, screen, action, bundle)) };
        appendLine();
        append(isCurrent(flow, screen, bundle));
        appendLine("}");
    }

    private fun readMethod(screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val source = requireNotNull(screen.source);
        val call = if (screen.usecase) "useCase.${source.name}(${sourceArguments(screen, bundle)})"
        else "${source.service}.${source.name}(${requestLiteral(source, screen, bundle)})";
        appendLine("    /** Reads this screen's source. Called once when the screen appears, however it appeared. */");
        appendLine("    suspend fun load()");
        appendLine("    {");
        appendLine("        val token = ++generation;");
        appendLine("        mutableState.value = Loadable.Loading;");
        appendLine("        val value = try");
        appendLine("        {");
        appendLine("            $call;");
        appendLine("        }");
        append(catchClauses("Loadable", terminator = ";"));
        appendLine("        if (isCurrent(token))");
        appendLine("        {");
        appendLine("            mutableState.value = Loadable.Ready(value);");
        appendLine("        }");
        appendLine("    }");
    }

    /**
     * What a call's `try` is followed by, in the two clauses every screen method needs.
     *
     * Cancellation first, and rethrown: a coroutine is cancelled BY an exception, so a
     * handler that classified it would tell the screen a call failed while telling the
     * caller's scope it was never cancelled (docs/IMPLEMENTATION-PITFALLS.md P16). Kotlin
     * matches catch clauses in order, so this one has to be written above the wide one.
     *
     * Then `Exception` and not `SpfnClientError`, which is what these were until 2f. The
     * SDK throws more than that one hierarchy — `SpfnClockSynchronizationException` is an
     * `IllegalStateException` — and everything outside it left a generated model through
     * `submit` and took the process with it. `ScreenFailure.envelope` classifies the whole
     * of `Throwable` for the same reason, and by the same rule: the SDK type's own name
     * and never any text a server chose.
     *
     * @param state `Busy` or `Loadable`, whichever this screen's own state is.
     * @param before a statement the failure branch runs first, for a method holding a flag.
     * @param terminator `;` where the `try` is an expression assigned to a value.
     */
    private fun catchClauses(state: String, before: String? = null, terminator: String = ""): String =
        buildString {
            appendLine("        catch (cancelled: CancellationException)");
            appendLine("        {");
            appendLine("            throw cancelled;");
            appendLine("        }");
            appendLine("        catch (failure: Exception)");
            appendLine("        {");
            if (before != null)
            {
                appendLine("            $before");
            }
            appendLine("            if (isCurrent(token))");
            appendLine("            {");
            appendLine("                mutableState.value = $state.Error(ScreenFailure.envelope(failure));");
            appendLine("            }");
            appendLine("            return;");
            appendLine("        }$terminator");
        }

    private fun sourceArguments(screen: ScreenDefinition, bundle: Bundle): String =
        RouteParameters.of(screen, bundle).joinToString(", ") { it.name }

    private fun loadableAction(
        spec: Spec,
        flow: FlowDefinition,
        screen: ScreenDefinition,
        action: ActionDefinition,
        bundle: Bundle
    ): String = buildString {
        appendLine();
        if (action.call == null)
        {
            append(navigationOnlyAction(spec, flow, screen, action, bundle));
            return@buildString;
        }
        if (action.call.reference == screen.source?.reference && action.then == null)
        {
            appendLine("    /** Reads the source again. Ignored while a write of this screen's is in flight. */");
            appendLine("    suspend fun ${action.name}()");
            appendLine("    {");
            appendLine("        if (mutableWriting.value)");
            appendLine("        {");
            appendLine("            return;");
            appendLine("        }");
            appendLine("        load();");
            appendLine("    }");
            return@buildString;
        }
        append(writeAction(spec, flow, screen, action, bundle));
    }

    /**
     * A write over the value this screen read.
     *
     * Guarded twice and both guards are the same sentence: the screen must be showing a
     * value, and no other write of its own may be in flight. That is what makes a press
     * during a read, a press during another write, and a double press one rule.
     */
    private fun writeAction(
        spec: Spec,
        flow: FlowDefinition,
        screen: ScreenDefinition,
        action: ActionDefinition,
        bundle: Bundle
    ): String = buildString {
        val call = requireNotNull(action.call);
        appendLine("    /**");
        appendLine("     * ${call.declaration.summary}");
        appendLine("     *");
        appendLine("     * Ignored unless this screen is showing a value and no write of its own is running.");
        appendLine("     */");
        appendLine("    suspend fun ${action.name}()");
        appendLine("    {");
        appendLine("        if (mutableWriting.value || mutableState.value !is Loadable.Ready)");
        appendLine("        {");
        appendLine("            return;");
        appendLine("        }");
        appendLine("        val token = ++generation;");
        appendLine("        mutableWriting.value = true;");
        appendLine("        try");
        appendLine("        {");
        appendLine("            ${call.service}.${call.name}(${requestLiteral(call, screen, bundle)});");
        appendLine("        }");
        append(catchClauses("Loadable", before = "mutableWriting.value = false;"));
        appendLine("        mutableWriting.value = false;");
        appendLine("        if (!isCurrent(token))");
        appendLine("        {");
        appendLine("            return;");
        appendLine("        }");
        if (action.then != null)
        {
            appendLine("        ${navigationCall(spec, flow, action, bundle)};");
        }
        appendLine("    }");
    }

    // ---- the use case ------------------------------------------------------

    /**
     * The seam a screen asks for with `usecase: true`.
     *
     * It stands between the model and the service so the hand-written layer has somewhere
     * to put a rule that is neither the screen's nor the wire's. The default one is a
     * pass-through, which is the honest starting point: it adds a name, not behaviour.
     */
    private fun useCase(screen: ScreenDefinition, bundle: Bundle, inputs: Inputs): String = buildString {
        val source = requireNotNull(screen.source);
        val parameters = RouteParameters.of(screen, bundle);
        val name = type(screen.name, "UseCase");
        appendLine(header(inputs));
        appendLine();
        appendLine("package $pkg.screens");
        appendLine();
        requestImports(screen).forEach { appendLine("import xyz.superfunction.spfn.generated.$it") };
        appendLine("import $pkg.services.${type(source.service, "Service")}");
        appendLine();
        appendLine("/** What `${screen.name}` reads, named as the app's own act rather than as an operation. */");
        appendLine("interface $name");
        appendLine("{");
        appendLine("    suspend fun ${source.name}(${parameterList(parameters)}): ${response(source)}");
        appendLine("}");
        appendLine();
        appendLine("/** The pass-through. It adds a seam, not a rule. */");
        appendLine("class Default$name(");
        appendLine("    private val service: ${type(source.service, "Service")}");
        appendLine(") : $name");
        appendLine("{");
        appendLine("    override suspend fun ${source.name}(${parameterList(parameters)}): ${response(source)} =");
        appendLine("        service.${source.name}(${requestLiteral(source, screen, bundle)})");
        appendLine("}");
    }

    private fun parameterList(parameters: List<RouteParameters.Parameter>): String =
        parameters.joinToString(", ") { "${it.name}: ${kotlinType(it.type)}" }

    // ---- the failure mapping ----------------------------------------------

    /**
     * Every refusal a screen can show, as one envelope type.
     *
     * `Loadable.Error` and `Busy.Error` carry core's envelope, so a screen's own refusal —
     * a blank required input, which never reached a server — has to be one too. It is
     * given a code of this generator's own rather than borrowing a contract code that
     * would read as something a server said.
     */
    private fun failure(bundle: Bundle, inputs: Inputs): String = buildString {
        appendLine(header(inputs));
        appendLine();
        appendLine("package $pkg.screens");
        appendLine();
        appendLine("import xyz.superfunction.spfn.client.SpfnClientError");
        appendLine("import xyz.superfunction.spfn.core.SpfnErrorEnvelope");
        appendLine("import xyz.superfunction.spfn.ui.SpfnStrings");
        appendLine();
        appendLine("/** Turns what a call threw into the envelope a screen state carries. */");
        appendLine("object ScreenFailure");
        appendLine("{");
        appendLine("    /** A refusal this screen made itself. Nothing was sent. */");
        appendLine("    const val VALIDATION: String = \"SPFN_UI_VALIDATION\";");
        appendLine();
        appendLine("    /** A call that failed on a ground the server did not put in an envelope. */");
        appendLine("    const val CALL_FAILED: String = \"SPFN_UI_CALL_FAILED\";");
        appendLine();
        appendLine("    /** The screen's own refusal of a required input. [field] is the field's name. */");
        appendLine("    fun validation(field: String): SpfnErrorEnvelope =");
        appendLine("        SpfnErrorEnvelope(code = VALIDATION, message = field, requestId = \"\");");
        appendLine();
        appendLine("    /**");
        appendLine("     * The server's own envelope where there is one, and a local one where there is");
        appendLine("     * not. The message carries the name of the SDK type that failed and never any");
        appendLine("     * server text.");
        appendLine("     *");
        appendLine("     * [Throwable] and not [SpfnClientError]: the SDK throws more than that one");
        appendLine("     * hierarchy, and a screen that could not name what it caught would have nothing");
        appendLine("     * to show for it.");
        appendLine("     */");
        appendLine("    fun envelope(failure: Throwable): SpfnErrorEnvelope = when (failure)");
        appendLine("    {");
        appendLine("        is SpfnClientError.Auth -> failure.failure.envelope");
        appendLine("        is SpfnClientError.Server -> failure.failure.envelope");
        appendLine("        else -> SpfnErrorEnvelope(");
        appendLine("            code = CALL_FAILED,");
        appendLine("            message = failure::class.simpleName ?: CALL_FAILED,");
        appendLine("            requestId = \"\"");
        appendLine("        )");
        appendLine("    };");
        append(classification(bundle));
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
     * The words themselves are `SpfnStrings`'s. Nothing here reads `envelope.message` except
     * [fieldMessage], whose message field is this generator's own field name and never a
     * server's text (decision C7).
     */
    private fun classification(bundle: Bundle): String = buildString {
        appendLine();
        appendLine("    /** The code names a device the server is not holding a request for. */");
        appendLine("    const val DEVICE_NOT_FOUND_KEY: String = \"deviceNotFound\";");
        appendLine();
        appendLine("    /** Nothing was reached, or what came back was not readable. */");
        appendLine("    const val NETWORK_KEY: String = \"network\";");
        appendLine();
        appendLine("    /** The server refused this device's credentials. */");
        appendLine("    const val UNAUTHORIZED_KEY: String = \"unauthorized\";");
        appendLine();
        appendLine("    /** The screen refused its own input. Nothing was sent. */");
        appendLine("    const val VALIDATION_KEY: String = \"validation\";");
        appendLine();
        appendLine("    /** Anything this build classifies as nothing more specific. */");
        appendLine("    const val UNEXPECTED_KEY: String = \"unexpected\";");
        appendLine();
        appendLine("    /**");
        appendLine("     * Which of the five keys [envelope] is shown under.");
        appendLine("     *");
        appendLine("     * The two families below are the contract's own 401s and 404s, listed from the");
        appendLine("     * pinned bundle at generation time.");
        appendLine("     */");
        appendLine("    fun messageKey(envelope: SpfnErrorEnvelope): String = when (envelope.code)");
        appendLine("    {");
        appendLine("        VALIDATION -> VALIDATION_KEY");
        appendLine("        CALL_FAILED -> NETWORK_KEY");
        appendCases(this, bundle, 401, "UNAUTHORIZED_KEY");
        appendCases(this, bundle, 404, "DEVICE_NOT_FOUND_KEY");
        appendLine("        else -> UNEXPECTED_KEY");
        appendLine("    };");
        appendLine();
        appendLine("    /**");
        appendLine("     * The sentence for [envelope], looked up in [SpfnStrings].");
        appendLine("     *");
        appendLine("     * Never the server's own words: `message` is text a server chose and a screen that");
        appendLine("     * drew it would publish whatever the server felt like saying (decision C7).");
        appendLine("     */");
        appendLine("    fun message(envelope: SpfnErrorEnvelope): String = when (messageKey(envelope))");
        appendLine("    {");
        appendLine("        DEVICE_NOT_FOUND_KEY -> SpfnStrings.errorDeviceNotFound");
        appendLine("        NETWORK_KEY -> SpfnStrings.errorNetwork");
        appendLine("        UNAUTHORIZED_KEY -> SpfnStrings.errorUnauthorized");
        appendLine("        VALIDATION_KEY -> SpfnStrings.errorValidation");
        appendLine("        else -> SpfnStrings.errorUnexpected");
        appendLine("    };");
        appendLine();
        appendLine("    /** Whether this failure belongs under a field rather than to the screen. */");
        appendLine("    fun isFieldRefusal(envelope: SpfnErrorEnvelope): Boolean = envelope.code == VALIDATION;");
        appendLine();
        appendLine("    /**");
        appendLine("     * The sentence to draw under [field], or null when this failure is not that field's.");
        appendLine("     *");
        appendLine("     * The one read of `message` in this file, and it is safe because the value there is");
        appendLine("     * this generator's own field name: [validation] above is what put it there.");
        appendLine("     */");
        appendLine("    fun fieldMessage(envelope: SpfnErrorEnvelope?, field: String): String? =");
        appendLine("        if (envelope != null && envelope.code == VALIDATION && envelope.message == field)");
        appendLine("        {");
        appendLine("            SpfnStrings.errorValidation");
        appendLine("        }");
        appendLine("        else");
        appendLine("        {");
        appendLine("            null");
        appendLine("        };");
    }

    /** One `when` branch per contract error carrying [status], or nothing when there are none. */
    private fun appendCases(out: StringBuilder, bundle: Bundle, status: Long, key: String)
    {
        val codes = bundle.errors.filter { it.httpStatus == status }.map { it.code }.sorted();
        if (codes.isEmpty())
        {
            return;
        }
        out.appendLine("        " + codes.joinToString(", ") { "\"$it\"" } + " -> $key");
    }

    // ---- the views ---------------------------------------------------------

    /**
     * One screen's view: a `Screen` frame, and SPFNUI components inside it.
     *
     * Nothing here draws a control of its own any more. A field is a `SpfnTextField`, a
     * control is the button its `role` names, a refusal is a `StatusText` and a read's four
     * states are a `LoadableView` — so the touch minimum, the keyboard contract and the
     * palette are the SDK's, written once and checked once, rather than re-emitted into every
     * generated view where a fix would have to be made in the generator and shipped.
     *
     * Selectors are unchanged and deliberately so: a control is still found by the test tag
     * `<screen>.<action>` and a readout by its text. `tools/harness/flows/d1-approve.yaml`
     * and its two siblings drive these screens against a live server by exactly those
     * strings, and a component swap that moved them would be a device regression nothing on
     * this host could see. The tags reach Maestro as resource ids because the app's root
     * turns test tags into them.
     */
    private fun view(screen: ScreenDefinition, bundle: Bundle, inputs: Inputs): String = buildString {
        val typed = screen.actions.flatMap { RouteParameters.inputs(screen, it, bundle) }.distinctBy { it.name };
        val controls = screen.actions.filter { it != screen.reread };
        appendLine(header(inputs));
        appendLine();
        appendLine("package $pkg.views");
        appendLine();
        viewImports(screen, typed, controls).forEach { appendLine("import $it") };
        appendLine();
        appendLine("/** The `${screen.name}` screen, drawn out of spfn-ui's components. */");
        appendLine("@Composable");
        appendLine("fun ${type(screen.name, "Screen")}(model: ${type(screen.name, "Model")})");
        appendLine("{");
        // Every local is emitted only where something below reads it: this module compiles
        // with `allWarningsAsErrors`, and an unused local is a warning, so a screen shape
        // that needed none of them would fail the build rather than emit a dead line.
        val usesState = screen.isLoadable || readouts || typed.isNotEmpty() ||
            controls.any { it.call != null };
        if (usesState)
        {
            appendLine("    val state = model.state.collectAsState().value;");
        }
        if (readouts)
        {
            appendLine("    val stack = model.stack.collectAsState().value;");
        }
        if (screen.isLoadable && controls.any { it.call != null })
        {
            appendLine("    val writing = model.writing.collectAsState().value;");
        }
        if (screen.actions.any { it.call != null })
        {
            appendLine("    val scope = rememberCoroutineScope();");
        }
        typed.forEach { input ->
            appendLine("    var ${input.name} by remember { mutableStateOf(\"\") };");
        };
        if (screen.source != null)
        {
            appendLine();
            appendLine("    // A screen loads its own read once, however it appeared: pushed onto the stack,");
            appendLine("    // or already on it because the flow was opened at a whole stack at once.");
            appendLine("    LaunchedEffect(model) { model.load() };");
        }
        appendLine();
        appendLine("    Screen(title = ${quoted(screen.title)}${trailingArgument(screen)}, scroll = ${screen.scroll})");
        appendLine("    {");
        // `spacedBy` and not a padding on each child, because the Swift emitter's own body
        // is `VStack(alignment: .leading, spacing: SPFNTokens.space4)` and the two halves of
        // one screen are supposed to be the same screen. Without it every paragraph, readout
        // and control on an Android screen touched the one above it while the iOS shot of the
        // same cell had a step of air between them.
        appendLine(
            "        Column(" +
                "modifier = Modifier.fillMaxWidth().padding(SpfnTokens.space4), " +
                "verticalArrangement = Arrangement.spacedBy(SpfnTokens.space4))"
        );
        appendLine("        {");
        // The readouts come FIRST, and that is a rule about reach rather than about layout.
        // A body long enough to need scrolling puts everything under it below the fold, and a
        // runner that could not read `stack=` until it had scrolled could not tell an app
        // that had not started from a screen it had not reached yet.
        if (readouts)
        {
            appendLine("            SpfnText(text = \"state=\" + stateName(state), role = TextRole.Mono);");
            appendLine("            SpfnText(text = \"stack=\" + stack.size, role = TextRole.Mono);");
        }
        if (screen.isLoadable)
        {
            append(loadableSlot(screen));
        }
        // The static body, one component per paragraph. The words are the generator's, out of
        // `BodyText`, because a spec carrying its own prose is one nobody can read the
        // structure out of; the spec named the key.
        screen.body.forEach { paragraph ->
            appendLine("            SpfnText(text = ${quoted(paragraph)});");
        };
        typed.forEach { input -> append(field(screen, input, bundle)) };
        if (!screen.isLoadable && typed.isNotEmpty())
        {
            append(statusLine(screen));
        }
        controls.forEach { action -> append(control(screen, action, bundle)) };
        appendLine("        }");
        appendLine("    }");
        appendLine("}");
        if (readouts)
        {
            appendLine();
            append(stateName(screen));
        }
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
        if (screen.suppressesClose) ", trailing = {}" else ""

    /**
     * The read's four states, and the retry control inside the error one.
     *
     * The re-read action is drawn HERE and nowhere else. Emitted as a control of its own as
     * well, it would put two nodes under `<screen>.<retry>` and a runner asked for that id
     * would refuse to pick between them.
     */
    private fun loadableSlot(screen: ScreenDefinition): String = buildString {
        val retry = screen.reread;
        appendLine("            LoadableView(");
        appendLine("                state = state,");
        if (retry != null)
        {
            appendLine("                retryId = \"${screen.name}.${retry.name}\",");
            appendLine("                onRetry = { scope.launch { model.${retry.name}() } },");
        }
        appendLine("                message = ScreenFailure::message");
        appendLine("            )");
        appendLine("            {");
        appendLine("                // What a value looks like is the human's, outside `generated/`.");
        appendLine("            }");
    }

    /**
     * A refusal that is the SCREEN's rather than one field's.
     *
     * A field's own refusal is drawn under the field by `SpfnTextField`, so drawing it here as
     * well would say the same thing twice in two places.
     */
    private fun statusLine(screen: ScreenDefinition): String = buildString {
        appendLine("            val failure = (state as? Busy.Error)?.error;");
        appendLine("            if (failure != null && !ScreenFailure.isFieldRefusal(failure))");
        appendLine("            {");
        appendLine("                StatusText(");
        appendLine("                    kind = StatusKind.Error,");
        appendLine("                    text = ScreenFailure.message(failure),");
        appendLine("                    id = \"${screen.name}.status\"");
        appendLine("                );");
        appendLine("            }");
    }

    private fun viewImports(
        screen: ScreenDefinition,
        typed: List<RouteParameters.Parameter>,
        controls: List<ActionDefinition>
    ): List<String>
    {
        val imports = mutableListOf(
            "androidx.compose.foundation.layout.Arrangement",
            "androidx.compose.foundation.layout.Column",
            "androidx.compose.foundation.layout.fillMaxWidth",
            "androidx.compose.foundation.layout.padding",
            "androidx.compose.runtime.Composable",
            "androidx.compose.runtime.collectAsState",
            "androidx.compose.ui.Modifier",
            "$pkg.screens.${type(screen.name, "Model")}",
            "xyz.superfunction.spfn.ui.components.Screen",
            "xyz.superfunction.spfn.ui.tokens.SpfnTokens"
        );
        // Named only where something reads it. A showcase screen calls nothing and collects
        // nothing, so it has no failure to classify — and this module compiles with
        // `allWarningsAsErrors`, where an unused import is a build failure rather than lint.
        if (screen.isLoadable || typed.isNotEmpty())
        {
            imports += "$pkg.screens.ScreenFailure";
        }
        if (screen.isLoadable && readouts)
        {
            imports += "xyz.superfunction.spfn.ui.Loadable";
        }
        if (!screen.isLoadable && (readouts || typed.isNotEmpty() || controls.any { it.call != null }))
        {
            imports += "xyz.superfunction.spfn.ui.Busy";
        }
        if (readouts)
        {
            imports += "xyz.superfunction.spfn.ui.components.TextRole";
        }
        if (readouts || screen.body.isNotEmpty())
        {
            imports += "xyz.superfunction.spfn.ui.components.SpfnText";
        }
        if (screen.actions.any { it.call != null })
        {
            imports += "androidx.compose.runtime.rememberCoroutineScope";
            imports += "kotlinx.coroutines.launch";
        }
        if (screen.source != null)
        {
            imports += "androidx.compose.runtime.LaunchedEffect";
            imports += "xyz.superfunction.spfn.ui.components.LoadableView";
        }
        if (typed.isNotEmpty())
        {
            imports += "androidx.compose.runtime.getValue";
            imports += "androidx.compose.runtime.mutableStateOf";
            imports += "androidx.compose.runtime.remember";
            imports += "androidx.compose.runtime.setValue";
            imports += "xyz.superfunction.spfn.ui.components.FieldKind";
            imports += "xyz.superfunction.spfn.ui.components.SpfnTextField";
        }
        if (!screen.isLoadable && typed.isNotEmpty())
        {
            imports += "xyz.superfunction.spfn.ui.components.StatusKind";
            imports += "xyz.superfunction.spfn.ui.components.StatusText";
        }
        controls.forEach { imports += "xyz.superfunction.spfn.ui.components.${button(it.role)}" };
        return imports.distinct().sorted();
    }

    /**
     * One typed input, decorated by whatever `screens.<s>.inputs.<i>` said.
     *
     * `onSubmit` and the submitting action are the same call written twice, which is the whole
     * of `submitOnReturn`: the return key does what the button does, so a person who finishes
     * typing does not have to reach for the control.
     */
    private fun field(screen: ScreenDefinition, input: RouteParameters.Parameter, bundle: Bundle): String =
        buildString {
            val declared = screen.inputNamed(input.name);
            val submitting = screen.actions.firstOrNull { action ->
                RouteParameters.inputs(screen, action, bundle).any { it.name == input.name }
            };
            appendLine("            SpfnTextField(");
            appendLine("                label = ${quoted(declared.label)},");
            appendLine("                id = \"${screen.name}.${input.name}\",");
            appendLine("                value = ${input.name},");
            appendLine("                onValueChange = { edited -> ${input.name} = edited; model.clearError(); },");
            appendLine("                kind = FieldKind.${UiNames.pascal(declared.kind)},");
            appendLine("                error = ScreenFailure.fieldMessage(");
            appendLine("                    (state as? Busy.Error)?.error,");
            appendLine("                    \"${input.name}\"");
            appendLine("                ),");
            appendLine("                submitOnReturn = ${declared.submitOnReturn && submitting != null},");
            appendLine("                autofocus = ${declared.autofocus}" + if (declared.submitOnReturn && submitting != null) "," else "");
            if (declared.submitOnReturn && submitting != null)
            {
                appendLine("                onSubmit = { ${invocation(screen, submitting, bundle)} }");
            }
            appendLine("            );");
        }

    /**
     * One control, as the button its role names.
     *
     * `busy` is what the model already knows and the screen used to hide: a write in flight
     * disables the control that started it and spins on it, which is the same rule R2 states
     * for the model, drawn.
     */
    private fun control(screen: ScreenDefinition, action: ActionDefinition, bundle: Bundle): String = buildString {
        appendLine("            ${button(action.role)}(");
        appendLine("                title = \"${action.name}\",");
        appendLine("                id = \"${screen.name}.${action.name}\",");
        if (action.call != null)
        {
            appendLine("                busy = ${busyExpression(screen)},");
        }
        appendLine("                onTap = { ${invocation(screen, action, bundle)} }");
        appendLine("            );");
    }

    /** Whether a write of this screen's is in flight, in the shape the model publishes it. */
    private fun busyExpression(screen: ScreenDefinition): String =
        if (screen.isLoadable) "writing" else "state is Busy.Busy"

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
        val arguments = RouteParameters.inputs(screen, action, bundle).joinToString(", ") { it.name };
        if (action.call == null)
        {
            return "model.${action.name}()";
        }
        return "scope.launch { model.${action.name}($arguments) }";
    }

    /** One Kotlin string literal, for a title an author wrote. */
    private fun quoted(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

    /** The state readout's vocabulary, which is the state type's own member names. */
    private fun stateName(screen: ScreenDefinition): String = buildString {
        val stateType = if (screen.isLoadable) "Loadable<*>" else "Busy";
        appendLine("/** The one word a runner reads this screen's state as. */");
        appendLine("private fun stateName(state: $stateType): String = when (state)");
        appendLine("{");
        if (screen.isLoadable)
        {
            appendLine("    is Loadable.Loading -> \"loading\"");
            appendLine("    is Loadable.Ready -> \"ready\"");
            appendLine("    is Loadable.Empty -> \"empty\"");
            appendLine("    is Loadable.Error -> \"error\"");
        }
        else
        {
            appendLine("    is Busy.Idle -> \"idle\"");
            appendLine("    is Busy.Busy -> \"busy\"");
            appendLine("    is Busy.Error -> \"error\"");
        }
        appendLine("}");
    }

    // ---- the container -----------------------------------------------------

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
    private fun container(spec: Spec, bundle: Bundle, inputs: Inputs): String = buildString {
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
        appendLine();
        appendLine("/** The app's one graph: services in, flows and screen models out. */");
        appendLine("class AppContainer(");
        append(constructorLines(spec.services.map { "${it.name}: ${type(it.name, "Service")}" }));
        appendLine(")");
        appendLine("{");
        spec.flows.forEach { flow ->
            appendLine("    /** The `${flow.name}` flow, open on its start screen. */");
            appendLine("    val ${flow.name}Flow: Flow<${route(flow)}> = ${type(flow.name, "Flow")}();");
            appendLine();
        };
        spec.screens.forEach { screen -> append(modelFactory(spec, screen, bundle)) };
        append(liveFactory(spec));
        appendLine("}");
    }

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
        appendLine("    /** A fresh model for one appearance of `${screen.name}`. */");
        appendLine("    fun ${screen.name}Model(${parameterList(parameters)}): ${type(screen.name, "Model")} =");
        appendLine("        ${type(screen.name, "Model")}(${arguments.joinToString(", ")});");
        appendLine();
    }

    private fun liveFactory(spec: Spec): String = buildString {
        appendLine("    companion object");
        appendLine("    {");
        appendLine("        /** The app against a real server: one transport, one session, one client. */");
        appendLine("        fun live(");
        appendLine("            transport: SpfnTransport,");
        appendLine("            keyProvider: SpfnKeyProvider,");
        appendLine("            baseUrl: String");
        appendLine("        ): AppContainer");
        appendLine("        {");
        appendLine("            val session = SpfnSession(");
        appendLine("                transport = transport,");
        appendLine("                keyProvider = keyProvider,");
        appendLine("                baseUrl = baseUrl");
        appendLine("            );");
        appendLine("            val client = SpfnClient(transport = transport, session = session);");
        val services = spec.services.joinToString(", ") { "Default${type(it.name, "Service")}(client)" };
        appendLine("            return AppContainer($services);");
        appendLine("        }");
        appendLine("    }");
    }
}
