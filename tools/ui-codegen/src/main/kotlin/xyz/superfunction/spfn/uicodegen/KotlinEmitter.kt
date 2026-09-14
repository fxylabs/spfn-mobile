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
        files["$root/screens/ScreenFailure.kt"] = failure(spec, bundle, inputs);
        spec.screens.forEach { screen ->
            files["$root/screens/${type(screen.name, "Model")}.kt"] = model(spec, screen, bundle, inputs);
            if (screen.usecase)
            {
                files["$root/screens/${type(screen.name, "UseCase")}.kt"] = useCase(screen, bundle, inputs);
            }
            if (!spec.viewIsAuthored(screen))
            {
                files["$root/views/${type(screen.name, "Screen")}.kt"] = view(screen, bundle, inputs);
            }
        };
        files["$root/AppContainer.kt"] = container(spec, bundle, inputs);
        return files;
    }

    /**
     * The view files of the flows a person writes: not emitted above, and not to be deleted.
     *
     * Named here rather than in `Main` because the path is this emitter's own spelling —
     * `views/<Screen>Screen.kt` — and a second copy of it would drift from the one line
     * above that writes the file.
     */
    fun authoredViews(spec: Spec): Set<String> =
        spec.screens.filter { spec.viewIsAuthored(it) }
            .map { "$root/views/${type(it.name, "Screen")}.kt" }
            .toSet()

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

    /**
     * What a model that checks its fields names out of the vocabulary.
     *
     * `FieldValidator` is on the list whether or not a rule names a custom one, because the
     * constructor parameter is there either way — a screen with no custom rule takes a
     * validator it never consults, so that adding one later is a spec edit rather than a
     * change to what the app hands the model.
     */
    private val CHECKED_IMPORTS: List<String> =
        listOf("FieldError", "FieldRules", "FieldValidator", "Form")

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

    /**
     * Which of the four models this screen gets, in the order the answers exclude each other.
     *
     * Paged first because a `list` is a read whatever else the screen does; then the ordinary
     * read; then the form, which is a screen that writes a screenful of input and reads
     * nothing; and the `Busy` write as what is left. `ScreenShape` is asked rather than
     * re-derived, so the rule table and this emitter cannot disagree about what a screen is.
     */
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

    /**
     * @param stateImports the vocabulary types this model's state is written in, sorted.
     *   A list rather than one name because a `Paged` model names three of them and a form
     *   names five; a `Loadable` or `Busy` model still names exactly one, which is why a v1
     *   spec's preamble is the line for line the one it was.
     * @param components the `ui.components` names the model itself uses, which today is
     *   `FieldKind` and only on a screen whose fields are checked.
     * @param values the generated contract types this model names beyond its own requests and
     *   responses: the ROW type of a paged read, which appears in `Paged<T>` and nowhere else.
     */
    private fun modelPreamble(
        flow: FlowDefinition,
        screen: ScreenDefinition,
        inputs: Inputs,
        stateImports: List<String>,
        components: List<String> = emptyList(),
        values: List<String> = emptyList()
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
        (requestImports(screen) + values).distinct().sorted()
            .forEach { appendLine("import xyz.superfunction.spfn.generated.$it") };
        appendLine("import $pkg.flows.${route(flow)}");
        screen.services.forEach { appendLine("import $pkg.services.${type(it, "Service")}") };
        stateImports.sorted().forEach { appendLine("import xyz.superfunction.spfn.ui.$it") };
        appendLine("import xyz.superfunction.spfn.ui.Flow");
        components.sorted().forEach { appendLine("import xyz.superfunction.spfn.ui.components.$it") };
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
        if (takesValidator(screen, bundle))
        {
            // Required — no default — exactly when a rule names a custom one. A screen whose
            // spec asked for a rule this generator cannot write, given a model that quietly
            // accepted `null` for it, is a screen whose rule is never asked and whose field is
            // accepted: nothing fails and the form is not the one somebody wrote.
            parameters += "validator: FieldValidator?" + if (customRule(screen)) "" else " = null";
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

    /** The vocabulary a `Busy` model names: its own state, and the check when it has one. */
    private fun busyImports(screen: ScreenDefinition): List<String> =
        listOf("Busy") + if (checks(screen)) CHECKED_IMPORTS else emptyList()

    /** The vocabulary a `Form` model names, which is the check's plus its own state. */
    private fun formImports(): List<String> = listOf("Busy") + CHECKED_IMPORTS

    /** `FieldKind` is named by a rules table and by nothing else under `ui.components`. */
    private fun checkImports(screen: ScreenDefinition): List<String> =
        if (checks(screen)) listOf("FieldKind") else emptyList()

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
        append(modelPreamble(flow, screen, inputs, busyImports(screen), components = checkImports(screen)));
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
        if (checks(screen))
        {
            appendLine();
            append(rulesTable(screen, bundle));
        }
        screen.actions.forEach { action -> append(busyAction(spec, flow, screen, action, bundle)) };
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
    private fun isCurrent(
        flow: FlowDefinition,
        screen: ScreenDefinition,
        bundle: Bundle,
        clearedBy: String? = "clearError"
    ): String = buildString {
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
        if (clearedBy != null)
        {
            appendLine("     * Split out of [isCurrent] because a second caller needs it without a token: the");
            appendLine("     * view calls [$clearedBy] when the text changes, and that is not an answer to a");
            appendLine("     * request — it has no generation to compare — while it is still something that must");
            appendLine("     * not write into a screen nobody is looking at.");
        }
        else
        {
            appendLine("     * A property of its own rather than a clause inside [isCurrent], because what");
            appendLine("     * \"on show\" means is one question: a model that asked it inline would end up");
            appendLine("     * asking it a little differently in each method that needs an answer.");
        }
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
        if (checks(screen))
        {
            append(checkedGuard(inputs));
        }
        else
        {
            inputs.filter { it.type is FieldType.StringType }.forEach { input ->
                appendLine("        if (${input.name}.isBlank())");
                appendLine("        {");
                appendLine("            mutableState.value = Busy.Error(ScreenFailure.validation(\"${input.name}\"));");
                appendLine("            return;");
                appendLine("        }");
            };
        }
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
        append(modelPreamble(flow, screen, inputs, listOf("Loadable")));
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


    // ---- the checked fields ------------------------------------------------

    /**
     * What every field of this screen is checked against, as one table.
     *
     * EVERY typed input is in it and not only the ones the spec decorated, because the rules
     * are what decide which fields exist at all: `Form.check` looks at a field the table names
     * and at no other, so an input left out would be a field nobody ever refused and a
     * `fields=` readout that never mentioned it. An input the spec said nothing about gets the
     * quiet answer — required, no lengths, its own `kind`, no custom rule.
     */
    private fun rulesTable(screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val fields = ScreenShape.inputs(screen, bundle);
        appendLine("    /** What each of this screen's fields is checked against. */");
        appendLine("    val rules: Map<String, FieldRules> = mapOf(");
        fields.forEachIndexed { index, field ->
            val comma = if (index == fields.size - 1) "" else ",";
            appendLine("        \"${field.name}\" to ${fieldRules(screen, field)}$comma");
        };
        appendLine("    );");
    }

    /** One `FieldRules`, spelled out: what the spec said, and this field's own kind. */
    private fun fieldRules(screen: ScreenDefinition, field: RouteParameters.Parameter): String
    {
        val declared = screen.inputNamed(field.name);
        val rules = declared.rules;
        val arguments = mutableListOf("required = ${rules?.required ?: true}");
        rules?.minLength?.let { arguments += "minLength = $it" };
        rules?.maxLength?.let { arguments += "maxLength = $it" };
        arguments += "kind = FieldKind.${UiNames.pascal(declared.kind)}";
        rules?.custom?.let { arguments += "custom = ${quoted(it)}" };
        return "FieldRules(" + arguments.joinToString(", ") + ")";
    }

    /**
     * The values a check is given: what is in each field, as text.
     *
     * A form hands every field over as text, because that is what a person typed and the
     * conversion is a step the model takes AFTER the check. A `Busy` screen's method still
     * takes the contract's own types — that is the signature it had before rules existed —
     * so an integer there arrives already parsed and is written back out.
     */
    private fun fieldValues(fields: List<RouteParameters.Parameter>, text: Boolean): String =
        "mapOf(" + fields.joinToString(", ") { field ->
            val parsed = !text && ScreenShape.isInteger(field);
            "\"${field.name}\" to ${field.name}" + if (parsed) ".toString()" else ""
        } + ")"

    /**
     * The `Busy` screen's own check, when its spec wrote rules for a field.
     *
     * The rule R1 states is unchanged — an input the screen refuses is refused before
     * anything is sent, and the screen says so — and what moves is WHY: a blank is now one of
     * several answers `Form.check` can give, and the envelope carries the rule's name beside
     * the field's so a readout and a log can say which one refused. A screen whose spec wrote
     * no rules keeps the blank check it had, byte for byte.
     */
    private fun checkedGuard(fields: List<RouteParameters.Parameter>): String = buildString {
        appendLine("        val checked = Form.check(${fieldValues(fields, text = false)}, rules, validator);");
        appendLine("        val refusal = refusals(checked).firstOrNull();");
        appendLine("        if (refusal != null)");
        appendLine("        {");
        appendLine("            mutableState.value =");
        appendLine("                Busy.Error(ScreenFailure.validation(refusal.first, refusal.second));");
        appendLine("            return;");
        appendLine("        }");
    }

    /**
     * How a checked screen reads its own refusals, on both models that have any.
     *
     * One pair of helpers rather than two copies, because the readout and the envelope have
     * to agree about which rule refused which field. The order is by FIELD NAME and that is
     * not tidiness: this platform's `Form.fields` holds the rules' own insertion order and
     * Swift's is a `Dictionary`, so a list taken in the order it was found would be two
     * different answers for one state.
     */
    private fun refusalHelpers(): String = buildString {
        appendLine("    /** Which fields the last check refused, by field name, with the rule that refused each. */");
        appendLine("    private fun refusals(form: Form): List<Pair<String, String>> =");
        appendLine("        form.fields.entries.sortedBy { it.key }");
        appendLine("            .mapNotNull { entry -> entry.value?.let { entry.key to ruleName(it) } };");
        appendLine();
        appendLine("    /** The one word a refusal is read as, which is the name of the rule that made it. */");
        appendLine("    private fun ruleName(error: FieldError): String = when (error)");
        appendLine("    {");
        appendLine("        is FieldError.Required -> \"required\"");
        appendLine("        is FieldError.MinLength -> \"minLength\"");
        appendLine("        is FieldError.MaxLength -> \"maxLength\"");
        appendLine("        is FieldError.Kind -> \"kind\"");
        appendLine("        is FieldError.Custom -> \"custom\"");
        appendLine("    };");
    }

    // ---- the paged model ---------------------------------------------------

    /**
     * A screen that reads its source a page at a time: its state is a `Paged<T>`.
     *
     * Everything the `Loadable` model does about lateness it does too — one generation token,
     * bumped by every call and by every action that abandons one, and the same three-question
     * `isCurrent` (P16, P24, P26). What it adds is the CURSOR, which is the model's alone:
     * `Paged` keeps whether there was one and never its value, because a screen shows rows and
     * a cursor is not a row.
     *
     * There is no view under this. A list is rows of something, and what a row shows is the
     * whole design of the screen — so the flow's views are written by hand from its contract
     * document, and what this model owes them is [readouts]: the five strings a cell asserts
     * on, computed here so that the table and the screen cannot disagree about what `count=`
     * means.
     */
    private fun pagedModel(
        spec: Spec,
        flow: FlowDefinition,
        screen: ScreenDefinition,
        bundle: Bundle,
        inputs: Inputs
    ): String = buildString {
        val list = requireNotNull(screen.list);
        val row = Names.kotlinType(list.itemType);
        append(
            modelPreamble(
                flow,
                screen,
                inputs,
                listOf("Busy", "Loadable", "Paged"),
                values = listOf(row)
            )
        );
        appendLine();
        appendLine("/**");
        appendLine(" * The `${screen.name}` screen's state and rules, with no toolkit in sight.");
        appendLine(" *");
        appendLine(" * Constructor injection, so a test drives this class against a fake service and a");
        appendLine(" * real [Flow] with no device, no composition and no server.");
        appendLine(" *");
        appendLine(" * The cursor is held here and nowhere else: [Paged] is told what the next one is and");
        appendLine(" * keeps only whether there WAS one, because a screen shows rows and never a cursor.");
        appendLine(" */");
        appendLine("class ${type(screen.name, "Model")}(");
        append(constructorLines(modelParameters(flow, screen, bundle)));
        appendLine(")");
        appendLine("{");
        appendLine("    private val mutableState: MutableStateFlow<Paged<$row>> =");
        appendLine("        MutableStateFlow(Paged.loading);");
        appendLine();
        appendLine("    /** What this screen's paged read has produced so far. */");
        appendLine("    val state: StateFlow<Paged<$row>> = mutableState.asStateFlow();");
        appendLine();
        appendLine("    /** The flow's stack, so the screen can print its depth as a readout. */");
        appendLine("    val stack: StateFlow<List<${route(flow)}>> = flow.stack;");
        appendLine();
        append(generationField());
        appendLine();
        append(cursorField());
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
            append(navigationOnlyAction(spec, flow, screen, action, bundle));
        };
        appendLine();
        append(isCurrent(flow, screen, bundle, clearedBy = null));
        appendLine("}");
    }

    private fun cursorField(): String = buildString {
        appendLine("    /**");
        appendLine("     * Where the next page starts, or null when there is no next page.");
        appendLine("     *");
        appendLine("     * Cleared by [load] rather than only written by it. A reload that kept the cursor");
        appendLine("     * it had would ask the server for page two of a list whose page one it is in the");
        appendLine("     * act of reading again, and append the answer to nothing.");
        appendLine("     */");
        appendLine("    private var cursor: String? = null;");
    }

    /**
     * The five strings a runner reads a paged screen by (E10).
     *
     * On the MODEL and not in a view, because a paged flow's views are a person's: there is no
     * generated view here to put them in, and a readout each implementer spelled for
     * themselves would be a case table asserting on text two apps write differently.
     *
     * The existing `Loadable` and `Busy` models are deliberately left without one. The same
     * property there would be tidier and it would also rewrite every generated model in the
     * repository, which is a diff with no claim behind it in a change whose first gate is that
     * version 1 generates what it generated before.
     */
    private fun pagedReadouts(row: String): String = buildString {
        appendLine("    /**");
        appendLine("     * What a runner reads this screen as: how deep the flow stands, what the first page");
        appendLine("     * did, what a further page is doing, how many rows are on screen, and whether the");
        appendLine("     * server said there are more.");
        appendLine("     */");
        appendLine("    val readouts: List<String> get()");
        appendLine("    {");
        appendLine("        val paged = mutableState.value;");
        appendLine("        return listOf(");
        appendLine("            \"stack=\" + flow.stack.value.size,");
        appendLine("            \"state=\" + pageName(paged.page),");
        appendLine("            \"more=\" + moreName(paged.more),");
        appendLine("            \"count=\" + rowCount(paged.page),");
        appendLine("            \"hasMore=\" + paged.hasMore");
        appendLine("        );");
        appendLine("    }");
        appendLine();
        appendLine("    /** The one word a runner reads the FIRST page's state as. */");
        appendLine("    private fun pageName(page: Loadable<List<$row>>): String = when (page)");
        appendLine("    {");
        appendLine("        is Loadable.Loading -> \"loading\"");
        appendLine("        is Loadable.Ready -> \"ready\"");
        appendLine("        is Loadable.Empty -> \"empty\"");
        appendLine("        is Loadable.Error -> \"error\"");
        appendLine("    };");
        appendLine();
        appendLine("    /** The one word a runner reads a FURTHER page's state as. */");
        appendLine("    private fun moreName(more: Busy): String = when (more)");
        appendLine("    {");
        appendLine("        is Busy.Idle -> \"idle\"");
        appendLine("        is Busy.Busy -> \"busy\"");
        appendLine("        is Busy.Error -> \"error\"");
        appendLine("    };");
        appendLine();
        appendLine("    /** How many rows are on screen, which is none until the first page arrives. */");
        appendLine("    private fun rowCount(page: Loadable<List<$row>>): Int =");
        appendLine("        if (page is Loadable.Ready) page.value.size else 0;");
    }

    private fun loadPage(screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val list = requireNotNull(screen.list);
        appendLine("    /**");
        appendLine("     * Reads the FIRST page. Called once when the screen appears, however it appeared.");
        appendLine("     *");
        appendLine("     * The cursor is dropped before the call and not after it, so this always asks for");
        appendLine("     * the first page — which is what makes [reload] a reload rather than an append.");
        appendLine("     */");
        appendLine("    suspend fun load()");
        appendLine("    {");
        appendLine("        val token = ++generation;");
        appendLine("        cursor = null;");
        appendLine("        mutableState.value = Paged.loading;");
        appendLine("        val page: ${response(requireNotNull(screen.source))} = try");
        appendLine("        {");
        appendLine("            ${pageCall(screen, bundle, "null")};");
        appendLine("        }");
        append(pagedCatch("firstPageFailed"));
        appendLine("        if (!isCurrent(token))");
        appendLine("        {");
        appendLine("            return;");
        appendLine("        }");
        appendLine("        cursor = page.${list.next};");
        appendLine("        mutableState.value = mutableState.value.firstPage(page.${list.items}, page.${list.next});");
        appendLine("    }");
    }

    private fun loadMore(screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val list = requireNotNull(screen.list);
        appendLine("    /**");
        appendLine("     * Reads the page after the rows already on screen.");
        appendLine("     *");
        appendLine("     * Ignored unless [Paged.canLoadMore] — there are rows, the server said there are");
        appendLine("     * more, and no page is already in flight. The state's own `appending` ignores it a");
        appendLine("     * second time, and both guards are wanted: a list asks for its next page when the");
        appendLine("     * end of it comes into view, and the end comes into view whenever the list is laid");
        appendLine("     * out again.");
        appendLine("     */");
        appendLine("    suspend fun loadMore()");
        appendLine("    {");
        appendLine("        if (!mutableState.value.canLoadMore)");
        appendLine("        {");
        appendLine("            return;");
        appendLine("        }");
        appendLine("        val token = ++generation;");
        appendLine("        val from = cursor;");
        appendLine("        mutableState.value = mutableState.value.appending();");
        appendLine("        val page: ${response(requireNotNull(screen.source))} = try");
        appendLine("        {");
        appendLine("            ${pageCall(screen, bundle, "from")};");
        appendLine("        }");
        append(pagedCatch("appendFailed"));
        appendLine("        if (!isCurrent(token))");
        appendLine("        {");
        appendLine("            return;");
        appendLine("        }");
        appendLine("        cursor = page.${list.next};");
        appendLine("        mutableState.value = mutableState.value.appended(page.${list.items}, page.${list.next});");
        appendLine("    }");
    }

    /**
     * The two names the screen's own controls call, and they are aliases on purpose.
     *
     * A footer that failed draws "try again" and a list that was pulled draws a refresh, and
     * neither is a different act from the one it repeats — writing them as two bodies would be
     * two places for the cursor rule to be got wrong.
     */
    private fun pagedRetries(): String = buildString {
        appendLine("    /** Asks again for the page that failed. The footer's own control calls this. */");
        appendLine("    suspend fun retryMore()");
        appendLine("    {");
        appendLine("        loadMore();");
        appendLine("    }");
        appendLine();
        appendLine("    /** Reads the list again from its first page, cursor and all. */");
        appendLine("    suspend fun reload()");
        appendLine("    {");
        appendLine("        load();");
        appendLine("    }");
    }

    /** A page's `try` and the two clauses after it, failing through the transition [named]. */
    private fun pagedCatch(named: String): String = buildString {
        appendLine("        catch (cancelled: CancellationException)");
        appendLine("        {");
        appendLine("            throw cancelled;");
        appendLine("        }");
        appendLine("        catch (failure: Exception)");
        appendLine("        {");
        appendLine("            if (isCurrent(token))");
        appendLine("            {");
        appendLine("                mutableState.value = mutableState.value.$named(ScreenFailure.envelope(failure));");
        appendLine("            }");
        appendLine("            return;");
        appendLine("        };");
    }

    /** The source call a paged model makes, through the use case when the screen asks for one. */
    private fun pageCall(screen: ScreenDefinition, bundle: Bundle, from: String): String
    {
        val source = requireNotNull(screen.source);
        val list = requireNotNull(screen.list);
        if (!screen.usecase)
        {
            return "${source.service}.${source.name}(${pagedRequest(screen, bundle, "${list.limitValue}", from)})";
        }
        val arguments = RouteParameters.of(screen, bundle).map { "${it.name} = ${it.name}" } +
            "${list.limitField} = ${list.limitValue}" + "${list.cursor} = $from";
        return "useCase.${source.name}(" + arguments.joinToString(", ") + ")";
    }

    /**
     * One page's request: what the route carries, the size the spec wrote, and the cursor.
     *
     * Not [requestLiteral], because the cursor is an OPTIONAL field and that one passes the
     * required ones alone. A paged request is the one place where an optional field is the
     * point of the call.
     */
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
                field.name == list.limitField -> "${field.name} = $limit"
                field.name == list.cursor -> "${field.name} = $from"
                !field.optional -> "${field.name} = ${field.name}"
                else -> null
            }
        };
        return "${Names.kotlinType(requestType)}(" + arguments.joinToString(", ") + ")";
    }

    // ---- the form model ----------------------------------------------------

    /**
     * A screen that collects a screenful of input and sends it: its state is a `Form`.
     *
     * Every field is checked at once, because a screen that refused one field at a time makes
     * a person press the control four times to be told four things that were all knowable at
     * the first press. What clears a refusal is [Form.edited] through `edit`, per field —
     * there is no `clearError` here, because a form's refusals are not one refusal.
     *
     * A field the contract declares as an INTEGER is converted after the check and before the
     * call, to 32 bits on both platforms: `Form`'s `Number` kind checks the SHAPE and says
     * nothing about width, precisely because the two languages' integers are not one integer
     * (Form.kt's own header, P9). A value the conversion cannot hold is refused as
     * `FieldError.Kind` and nothing is sent.
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
        append(modelPreamble(flow, screen, inputs, formImports(), components = listOf("FieldKind")));
        appendLine();
        appendLine("/**");
        appendLine(" * The `${screen.name}` screen's state and rules, with no toolkit in sight.");
        appendLine(" *");
        appendLine(" * Constructor injection, so a test drives this class against a fake service and a");
        appendLine(" * real [Flow] with no device, no composition and no server.");
        appendLine(" *");
        appendLine(" * Every field is checked at once and a refusal is stated without anything being sent;");
        appendLine(" * editing one field clears that field's refusal and no other.");
        appendLine(" */");
        appendLine("class ${type(screen.name, "Model")}(");
        append(constructorLines(modelParameters(flow, screen, bundle)));
        appendLine(")");
        appendLine("{");
        appendLine("    private val mutableState: MutableStateFlow<Form> = MutableStateFlow(Form());");
        appendLine();
        appendLine("    /** What this screen's fields and its write are doing. */");
        appendLine("    val state: StateFlow<Form> = mutableState.asStateFlow();");
        appendLine();
        appendLine("    /** The flow's stack, so the screen can print its depth as a readout. */");
        appendLine("    val stack: StateFlow<List<${route(flow)}>> = flow.stack;");
        appendLine();
        append(generationField());
        appendLine();
        append(rulesTable(screen, bundle));
        appendLine();
        append(formReadouts());
        appendLine();
        append(editMethod());
        appendLine();
        append(submitMethod(spec, flow, screen, submit, fields, bundle));
        screen.actions.filter { it != submit }.forEach { action ->
            appendLine();
            append(navigationOnlyAction(spec, flow, screen, action, bundle));
        };
        appendLine();
        append(refusalHelpers());
        appendLine();
        append(isCurrent(flow, screen, bundle, clearedBy = "edit"));
        appendLine("}");
    }

    /**
     * The three strings a runner reads a form by (E10), for the reason [pagedReadouts] gives.
     *
     * `fields=` is sorted by field name, and that is not tidiness: this platform's `Form.fields`
     * holds the rules' own insertion order and Swift's is a `Dictionary`, so a readout that
     * printed the order it found would be two different readouts for one state.
     */
    private fun formReadouts(): String = buildString {
        appendLine("    /**");
        appendLine("     * What a runner reads this screen as: how deep the flow stands, what the write is");
        appendLine("     * doing, and which fields are refused and by which rule.");
        appendLine("     */");
        appendLine("    val readouts: List<String> get()");
        appendLine("    {");
        appendLine("        val form = mutableState.value;");
        appendLine("        return listOf(");
        appendLine("            \"stack=\" + flow.stack.value.size,");
        appendLine("            \"state=\" + submitName(form.submit),");
        appendLine("            \"fields=\" + refusedFields(form)");
        appendLine("        );");
        appendLine("    }");
        appendLine();
        appendLine("    /** The one word a runner reads this form's write as. */");
        appendLine("    private fun submitName(submit: Busy): String = when (submit)");
        appendLine("    {");
        appendLine("        is Busy.Idle -> \"idle\"");
        appendLine("        is Busy.Busy -> \"busy\"");
        appendLine("        is Busy.Error -> \"error\"");
        appendLine("    };");
        appendLine();
        appendLine("    /** Which fields are refused and by which rule, by field name, or `ok`. */");
        appendLine("    private fun refusedFields(form: Form): String");
        appendLine("    {");
        appendLine("        val refused = refusals(form).map { \"${'$'}{it.first}:${'$'}{it.second}\" };");
        appendLine("        return if (refused.isEmpty()) \"ok\" else refused.joinToString(\",\");");
        appendLine("    }");
    }

    private fun editMethod(): String = buildString {
        appendLine("    /**");
        appendLine("     * The field was edited, so its refusal is stale and goes.");
        appendLine("     *");
        appendLine("     * Per field, which is what a form has instead of `clearError`: a person fixing one");
        appendLine("     * field is not telling the screen anything about the other three. A field the last");
        appendLine("     * check did not look at is left alone rather than invented.");
        appendLine("     */");
        appendLine("    fun edit(field: String)");
        appendLine("    {");
        appendLine("        mutableState.value = mutableState.value.edited(field);");
        appendLine("    }");
    }

    private fun submitMethod(
        spec: Spec,
        flow: FlowDefinition,
        screen: ScreenDefinition,
        action: ActionDefinition,
        fields: List<RouteParameters.Parameter>,
        bundle: Bundle
    ): String = buildString {
        val call = requireNotNull(action.call);
        val parameters = fields.joinToString(", ") { "${it.name}: String" };
        appendLine("    /**");
        appendLine("     * ${call.declaration.summary}");
        appendLine("     *");
        appendLine("     * Ignored while a write is already in flight (R2), and refused outright when any");
        appendLine("     * field breaks its rules — every field at once, and nothing is sent.");
        appendLine("     *");
        appendLine("     * Every parameter is text, including the ones the contract types as integers: what");
        appendLine("     * a person typed is a string, and turning it into a number is a step this method");
        appendLine("     * takes after the check and can still refuse.");
        appendLine("     */");
        appendLine("    suspend fun ${action.name}($parameters)");
        appendLine("    {");
        appendLine("        if (!mutableState.value.canSubmit)");
        appendLine("        {");
        appendLine("            return;");
        appendLine("        }");
        appendLine("        val checked = Form.check(");
        appendLine("            ${fieldValues(fields, text = true)},");
        appendLine("            rules,");
        appendLine("            validator");
        appendLine("        );");
        appendLine("        if (!checked.isValid)");
        appendLine("        {");
        appendLine("            mutableState.value = checked;");
        appendLine("            return;");
        appendLine("        }");
        fields.filter { ScreenShape.isInteger(it) }.forEach { field -> append(conversion(field)) };
        appendLine("        val token = ++generation;");
        appendLine("        mutableState.value = checked.submitting();");
        appendLine("        try");
        appendLine("        {");
        appendLine("            ${call.service}.${call.name}(${formRequest(call, fields, bundle)});");
        appendLine("        }");
        append(formCatch());
        appendLine("        if (!isCurrent(token))");
        appendLine("        {");
        appendLine("            return;");
        appendLine("        }");
        appendLine("        mutableState.value = mutableState.value.submitted();");
        if (action.then != null)
        {
            appendLine("        ${navigationCall(spec, flow, action, bundle)};");
        }
        appendLine("    }");
    }

    /**
     * One integer field, turned into the 32 bits both platforms carry.
     *
     * `Form`'s `Number` kind has already said the text is an optional sign and some digits; it
     * says nothing about WIDTH, on purpose, because this platform's `Int` is 32 bits and
     * Swift's is 64 and a check that parsed would accept `3000000000` on one platform and
     * refuse it on the other (Form.kt's header, P9). So the width is decided here, the same
     * way on both, and a value outside it is the field's refusal rather than a request the
     * server answers with something nobody meant.
     */
    private fun conversion(field: RouteParameters.Parameter): String = buildString {
        appendLine("        val ${field.name}Value = ${field.name}.toIntOrNull();");
        appendLine("        if (${field.name}Value == null)");
        appendLine("        {");
        appendLine("            mutableState.value = Form(");
        appendLine("                fields = checked.fields + (\"${field.name}\" to FieldError.Kind(FieldKind.Number)),");
        appendLine("                submit = checked.submit");
        appendLine("            );");
        appendLine("            return;");
        appendLine("        }");
    }

    /** A form's request: text where the contract says text, and the converted value elsewhere. */
    private fun formRequest(
        method: ServiceMethod,
        fields: List<RouteParameters.Parameter>,
        bundle: Bundle
    ): String
    {
        val requestType = method.declaration.requestType ?: return "Unit";
        val converted = fields.filter { ScreenShape.isInteger(it) }.map { it.name }.toSet();
        val arguments = bundle.typeNamed(requestType).fields.filter { !it.optional }.map { field ->
            val value = if (field.name in converted) "${field.name}Value.toLong()" else field.name;
            "${field.name} = $value";
        };
        return "${Names.kotlinType(requestType)}(" + arguments.joinToString(", ") + ")";
    }

    private fun formCatch(): String = buildString {
        appendLine("        catch (cancelled: CancellationException)");
        appendLine("        {");
        appendLine("            throw cancelled;");
        appendLine("        }");
        appendLine("        catch (failure: Exception)");
        appendLine("        {");
        appendLine("            if (isCurrent(token))");
        appendLine("            {");
        appendLine("                mutableState.value = mutableState.value.submitFailed(ScreenFailure.envelope(failure));");
        appendLine("            }");
        appendLine("            return;");
        appendLine("        }");
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
        appendLine("    suspend fun ${source.name}(${useCaseParameters(screen, parameters)}): ${response(source)}");
        appendLine("}");
        appendLine();
        appendLine("/** The pass-through. It adds a seam, not a rule. */");
        appendLine("class Default$name(");
        appendLine("    private val service: ${type(source.service, "Service")}");
        appendLine(") : $name");
        appendLine("{");
        appendLine("    override suspend fun ${source.name}(${useCaseParameters(screen, parameters)}): ${response(source)} =");
        appendLine("        service.${source.name}(${useCaseRequest(screen, bundle, source)})");
        appendLine("}");
    }

    private fun parameterList(parameters: List<RouteParameters.Parameter>): String =
        parameters.joinToString(", ") { "${it.name}: ${kotlinType(it.type)}" }

    /**
     * What a use case is asked for, which on a paged screen is two things more.
     *
     * A page size and a cursor are not route parameters — the route carries what a screen IS,
     * and the page it happens to be reading is not that — so they arrive here as arguments,
     * named after the contract's own request fields so a reader can put the two side by side.
     */
    private fun useCaseParameters(screen: ScreenDefinition, parameters: List<RouteParameters.Parameter>): String
    {
        val list = screen.list ?: return parameterList(parameters);
        return (parameters.map { "${it.name}: ${kotlinType(it.type)}" } +
            "${list.limitField}: Long" + "${list.cursor}: String?").joinToString(", ");
    }

    /** The request a pass-through use case builds, page or whole. */
    private fun useCaseRequest(screen: ScreenDefinition, bundle: Bundle, source: ServiceMethod): String
    {
        val list = screen.list ?: return requestLiteral(source, screen, bundle);
        return pagedRequest(screen, bundle, list.limitField, list.cursor);
    }

    // ---- the failure mapping ----------------------------------------------

    /**
     * Every refusal a screen can show, as one envelope type.
     *
     * `Loadable.Error` and `Busy.Error` carry core's envelope, so a screen's own refusal —
     * a blank required input, which never reached a server — has to be one too. It is
     * given a code of this generator's own rather than borrowing a contract code that
     * would read as something a server said.
     */
    private fun failure(spec: Spec, bundle: Bundle, inputs: Inputs): String = buildString {
        val rules = spec.screens.any { screen -> screen.inputs.any { it.rules != null } };
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
        if (rules)
        {
            append(ruleRefusal());
            appendLine();
        }
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
     * The words themselves are `SpfnStrings`'s. Nothing here reads `envelope.message` except
     * [fieldMessage], whose message field is this generator's own field name and never a
     * server's text (decision C7).
     */
    private fun classification(bundle: Bundle, rules: Boolean): String = buildString {
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
        if (rules)
        {
            appendLine("     *");
            appendLine("     * A field its rules refused carries `<field>:<rule>`, so the field is what is read");
            appendLine("     * off the front of the message and the rule's name is not compared to anything.");
        }
        appendLine("     */");
        appendLine("    fun fieldMessage(envelope: SpfnErrorEnvelope?, field: String): String? =");
        appendLine(
            "        if (envelope != null && envelope.code == VALIDATION && " +
                (if (rules) "envelope.message.substringBefore(':') == field)" else "envelope.message == field)")
        );
        appendLine("        {");
        appendLine("            SpfnStrings.errorValidation");
        appendLine("        }");
        appendLine("        else");
        appendLine("        {");
        appendLine("            null");
        appendLine("        };");
    }

    /**
     * The other refusal a screen makes itself: a field its `rules` turned down, named by the
     * RULE that turned it down.
     *
     * Emitted only where some screen of this spec writes `rules`, and that is not thrift — it
     * is what keeps a version 1 spec generating the file it generated before. The message is
     * `<field>:<rule>`, and `fieldMessage` reads the field off the front of it, so the line
     * still lands under the right field; the rule's name is there for a readout and for a
     * person reading a log. The SENTENCE a person sees is still `SpfnStrings`'s, because a
     * rule name is not a sentence (decision C7).
     */
    private fun ruleRefusal(): String = buildString {
        appendLine("    /** A field its own rules refused. The message is `<field>:<rule>`. */");
        appendLine("    fun validation(field: String, rule: String): SpfnErrorEnvelope =");
        appendLine("        SpfnErrorEnvelope(code = VALIDATION, message = \"\$field:\$rule\", requestId = \"\");");
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
        if (validated(spec, bundle).isNotEmpty())
        {
            appendLine("import xyz.superfunction.spfn.ui.FieldValidator");
        }
        appendLine();
        appendLine("/** The app's one graph: services in, flows and screen models out. */");
        appendLine("class AppContainer(");
        append(constructorLines(containerParameters(spec, bundle)));
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
        spec.screens.filter { takesValidator(it, bundle) && customRule(it) }

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
        if (customRule(screen))
        {
            arguments += "${screen.name}Validator";
        }
        appendLine("    /** A fresh model for one appearance of `${screen.name}`. */");
        appendLine("    fun ${screen.name}Model(${parameterList(parameters)}): ${type(screen.name, "Model")} =");
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
