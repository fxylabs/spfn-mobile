// The Kotlin half of the scaffold: the Compose example app's generated sources.
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

object KotlinEmitter
{
    const val ROOT: String = "examples/android-compose/src/main/kotlin/xyz/superfunction/spfn/example/generated";
    private const val PACKAGE: String = "xyz.superfunction.spfn.example.generated";

    fun emit(spec: Spec, bundle: Bundle, inputs: Inputs): Map<String, String>
    {
        val files = mutableMapOf<String, String>();
        spec.services.forEach { service ->
            files["$ROOT/services/${type(service.name, "Service")}.kt"] = service(service, inputs);
        };
        spec.flows.forEach { flow ->
            files["$ROOT/flows/${type(flow.name, "Flow")}.kt"] = flow(spec, flow, bundle, inputs);
        };
        files["$ROOT/screens/ScreenFailure.kt"] = failure(inputs);
        spec.screens.forEach { screen ->
            files["$ROOT/screens/${type(screen.name, "Model")}.kt"] = model(spec, screen, bundle, inputs);
            if (screen.usecase)
            {
                files["$ROOT/screens/${type(screen.name, "UseCase")}.kt"] = useCase(screen, bundle, inputs);
            }
            files["$ROOT/views/${type(screen.name, "Screen")}.kt"] = view(screen, bundle, inputs);
        };
        files["$ROOT/AppContainer.kt"] = container(spec, bundle, inputs);
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
        appendLine("package $PACKAGE.services");
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
        appendLine("package $PACKAGE.flows");
        appendLine();
        appendLine("import androidx.compose.runtime.Composable");
        appendLine("import androidx.compose.runtime.remember");
        appendLine("import xyz.superfunction.spfn.example.generated.AppContainer");
        screens.sortedBy { it.name }.forEach {
            appendLine("import $PACKAGE.views.${type(it.name, "Screen")}");
        };
        appendLine("import xyz.superfunction.spfn.ui.Flow");
        appendLine("import xyz.superfunction.spfn.ui.FlowEntry");
        appendLine("import xyz.superfunction.spfn.ui.FlowHost");
        appendLine("import xyz.superfunction.spfn.ui.FlowRoute");
        appendLine();
        append(routeType(flow, screens, bundle));
        appendLine();
        append(flowFactory(flow));
        appendLine();
        append(flowHost(flow, screens, bundle));
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
        val entry = UiNames.pascal(flow.entry);
        appendLine("/** How this flow is presented, and therefore what a back on its last route means. */");
        appendLine("val ${UiNames.pascal(flow.name)}Entry: FlowEntry = FlowEntry.$entry;");
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
        appendLine("package $PACKAGE.screens");
        appendLine();
        appendLine("import kotlinx.coroutines.flow.MutableStateFlow");
        appendLine("import kotlinx.coroutines.flow.StateFlow");
        appendLine("import kotlinx.coroutines.flow.asStateFlow");
        appendLine("import xyz.superfunction.spfn.client.SpfnClientError");
        requestImports(screen).forEach { appendLine("import xyz.superfunction.spfn.generated.$it") };
        appendLine("import $PACKAGE.flows.${route(flow)}");
        appendLine("import $PACKAGE.services.${type(screen.source?.service ?: callService(screen), "Service")}");
        appendLine("import xyz.superfunction.spfn.ui.$stateImport");
        appendLine("import xyz.superfunction.spfn.ui.Flow");
    }

    private fun callService(screen: ScreenDefinition): String =
        screen.actions.firstNotNullOf { it.call }.service

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
        appendLine("    private val service: ${type(callService(screen), "Service")},");
        appendLine("    private val flow: Flow<${route(flow)}>");
        appendLine(")");
        appendLine("{");
        appendLine("    private val mutableState: MutableStateFlow<Busy> = MutableStateFlow(Busy.Idle);");
        appendLine();
        appendLine("    /** What this screen's write is doing. */");
        appendLine("    val state: StateFlow<Busy> = mutableState.asStateFlow();");
        appendLine();
        appendLine("    /** The flow's stack, so the screen can print its depth as a readout. */");
        appendLine("    val stack: StateFlow<List<${route(flow)}>> = flow.stack;");
        appendLine();
        append(generationField());
        screen.actions.forEach { action -> append(busyAction(spec, flow, screen, action, bundle)) };
        appendLine();
        append(isCurrent());
        appendLine("}");
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

    private fun isCurrent(): String = buildString {
        appendLine("    /** Whether an answer bearing [token] still belongs to a screen that is on show. */");
        appendLine("    private fun isCurrent(token: Int): Boolean = token == generation && flow.isPresented.value");
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
            appendLine("    /** ${navigationSentence(action)} */");
            appendLine("    fun ${action.name}()");
            appendLine("    {");
            appendLine("        generation++;");
            appendLine("        ${navigationCall(spec, flow, action, bundle)};");
            appendLine("    }");
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
        appendLine("            service.${action.call.name}(${requestLiteral(action.call, screen, bundle)});");
        appendLine("        }");
        // SpfnClientError and not Exception: CancellationException is not one of these, so
        // a cancelled coroutine propagates instead of being recorded as a server refusal.
        appendLine("        catch (failure: SpfnClientError)");
        appendLine("        {");
        appendLine("            if (isCurrent(token))");
        appendLine("            {");
        appendLine("                mutableState.value = Busy.Error(ScreenFailure.envelope(failure));");
        appendLine("            }");
        appendLine("            return;");
        appendLine("        }");
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
            val target = RouteParameters.of(spec.screenNamed(then.screen), bundle);
            val arguments = target.joinToString(", ") { "${it.name} = ${it.name}" };
            "flow.push(${route(flow)}.${UiNames.pascal(then.screen)}($arguments))"
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
        if (screen.usecase)
        {
            appendLine("    private val useCase: ${type(screen.name, "UseCase")},");
        }
        appendLine("    private val service: ${type(callService(screen), "Service")},");
        appendLine("    private val flow: Flow<${route(flow)}>,");
        RouteParameters.of(screen, bundle).forEach { appendLine("    private val ${it.name}: ${kotlinType(it.type)},") };
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
        appendLine("    /** Whether one of this screen's writes is in flight. */");
        appendLine("    private var writing: Boolean = false;");
        appendLine();
        append(readMethod(screen, bundle));
        screen.actions.forEach { action -> append(loadableAction(spec, flow, screen, action, bundle)) };
        appendLine();
        append(isCurrent());
        appendLine("}");
    }

    private fun readMethod(screen: ScreenDefinition, bundle: Bundle): String = buildString {
        val source = requireNotNull(screen.source);
        val call = if (screen.usecase) "useCase.${source.name}(${sourceArguments(screen, bundle)})"
        else "service.${source.name}(${requestLiteral(source, screen, bundle)})";
        appendLine("    /** Reads this screen's source. Called once when the screen appears, however it appeared. */");
        appendLine("    suspend fun load()");
        appendLine("    {");
        appendLine("        val token = ++generation;");
        appendLine("        mutableState.value = Loadable.Loading;");
        appendLine("        val value = try");
        appendLine("        {");
        appendLine("            $call;");
        appendLine("        }");
        appendLine("        catch (failure: SpfnClientError)");
        appendLine("        {");
        appendLine("            if (isCurrent(token))");
        appendLine("            {");
        appendLine("                mutableState.value = Loadable.Error(ScreenFailure.envelope(failure));");
        appendLine("            }");
        appendLine("            return;");
        appendLine("        };");
        appendLine("        if (isCurrent(token))");
        appendLine("        {");
        appendLine("            mutableState.value = Loadable.Ready(value);");
        appendLine("        }");
        appendLine("    }");
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
            appendLine("    /** ${navigationSentence(action)} */");
            appendLine("    fun ${action.name}()");
            appendLine("    {");
            appendLine("        generation++;");
            appendLine("        ${navigationCall(spec, flow, action, bundle)};");
            appendLine("    }");
            return@buildString;
        }
        if (action.call.reference == screen.source?.reference && action.then == null)
        {
            appendLine("    /** Reads the source again. Ignored while a write of this screen's is in flight. */");
            appendLine("    suspend fun ${action.name}()");
            appendLine("    {");
            appendLine("        if (writing)");
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
        appendLine("        if (writing || mutableState.value !is Loadable.Ready)");
        appendLine("        {");
        appendLine("            return;");
        appendLine("        }");
        appendLine("        val token = ++generation;");
        appendLine("        writing = true;");
        appendLine("        try");
        appendLine("        {");
        appendLine("            service.${call.name}(${requestLiteral(call, screen, bundle)});");
        appendLine("        }");
        appendLine("        catch (failure: SpfnClientError)");
        appendLine("        {");
        appendLine("            writing = false;");
        appendLine("            if (isCurrent(token))");
        appendLine("            {");
        appendLine("                mutableState.value = Loadable.Error(ScreenFailure.envelope(failure));");
        appendLine("            }");
        appendLine("            return;");
        appendLine("        }");
        appendLine("        writing = false;");
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
        appendLine("package $PACKAGE.screens");
        appendLine();
        requestImports(screen).forEach { appendLine("import xyz.superfunction.spfn.generated.$it") };
        appendLine("import $PACKAGE.services.${type(source.service, "Service")}");
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
    private fun failure(inputs: Inputs): String = buildString {
        appendLine(header(inputs));
        appendLine();
        appendLine("package $PACKAGE.screens");
        appendLine();
        appendLine("import xyz.superfunction.spfn.client.SpfnClientError");
        appendLine("import xyz.superfunction.spfn.core.SpfnErrorEnvelope");
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
        appendLine("     * not. The message carries the SDK's class name and never any server text.");
        appendLine("     */");
        appendLine("    fun envelope(failure: SpfnClientError): SpfnErrorEnvelope = when (failure)");
        appendLine("    {");
        appendLine("        is SpfnClientError.Auth -> failure.failure.envelope");
        appendLine("        is SpfnClientError.Server -> failure.failure.envelope");
        appendLine("        else -> SpfnErrorEnvelope(");
        appendLine("            code = CALL_FAILED,");
        appendLine("            message = failure::class.simpleName ?: CALL_FAILED,");
        appendLine("            requestId = \"\"");
        appendLine("        )");
        appendLine("    };");
        appendLine("}");
    }

    // ---- the views ---------------------------------------------------------

    /**
     * The skeleton, and it is a skeleton on purpose.
     *
     * Every element here exists because a runner has to reach it or read it: one control
     * per action, one field per typed input, and the two readouts. Layout is the human's,
     * outside `generated/` — a generator that produced a design would produce one nobody
     * asked for and one that regeneration would throw away.
     *
     * Selectors follow the harness's rule (`tools/harness/ios/Sources/HarnessView.swift`):
     * a control is found by the id `<screen>.<action>`, a readout by its text. The ids
     * reach Maestro as resource ids because the app's root turns test tags into them.
     */
    private fun view(screen: ScreenDefinition, bundle: Bundle, inputs: Inputs): String = buildString {
        val typed = screen.actions.flatMap { RouteParameters.inputs(screen, it, bundle) }.distinctBy { it.name };
        appendLine(header(inputs));
        appendLine();
        appendLine("package $PACKAGE.views");
        appendLine();
        viewImports(screen, typed).forEach { appendLine("import $it") };
        appendLine();
        appendLine("/** The `${screen.name}` screen: one control per action, and the two readouts. */");
        appendLine("@Composable");
        appendLine("fun ${type(screen.name, "Screen")}(model: ${type(screen.name, "Model")})");
        appendLine("{");
        appendLine("    val state = model.state.collectAsState().value;");
        appendLine("    val stack = model.stack.collectAsState().value;");
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
        appendLine("    Column(modifier = Modifier.fillMaxWidth())");
        appendLine("    {");
        appendLine("        BasicText(text = \"state=\" + stateName(state));");
        appendLine("        BasicText(text = \"stack=\" + stack.size);");
        typed.forEach { input -> append(field(screen, input)) };
        screen.actions.forEach { action -> append(control(screen, action, bundle)) };
        appendLine("    }");
        appendLine("}");
        appendLine();
        if (screen.actions.isNotEmpty() || typed.isNotEmpty())
        {
            append(touchTarget());
            appendLine();
        }
        append(stateName(screen));
    }

    /**
     * The minimum touch target every control and field is given, and why it is not decoration.
     *
     * A `BasicText` is one line tall — well under the platform minimum — and Compose makes
     * up the difference by expanding the node's TOUCH bounds past its layout bounds. In a
     * Column of one-line controls those expansions overlap, and the bounds Compose then
     * reports to accessibility for one control can sit on top of a neighbour's real bounds.
     * A runner taps the reported centre, so it taps the neighbour: `enterCode.cancel`
     * reported a rectangle centred inside `enterCode.userCode`, and cell u5's tap opened the
     * keyboard instead of closing the flow (docs/IMPLEMENTATION-PITFALLS.md P21).
     *
     * Sizing each interactive element to the minimum removes the expansion, so the reported
     * bounds are the real ones and no two of them overlap.
     */
    private fun touchTarget(): String = buildString {
        appendLine("/**");
        appendLine(" * The platform's minimum touch target, given to every control and field.");
        appendLine(" *");
        appendLine(" * Compose expands a control smaller than this past its layout bounds for touch, and");
        appendLine(" * in a column of one-line controls those expansions overlap: the bounds reported for");
        appendLine(" * one control then sit on a neighbour's, and a runner tapping the reported centre taps");
        appendLine(" * the neighbour (docs/IMPLEMENTATION-PITFALLS.md P21). Sized here, nothing is expanded.");
        appendLine(" */");
        appendLine("private val TouchTarget: Dp = 48.dp;");
    }

    private fun viewImports(screen: ScreenDefinition, typed: List<RouteParameters.Parameter>): List<String>
    {
        val imports = mutableListOf(
            "androidx.compose.foundation.layout.Column",
            "androidx.compose.foundation.layout.fillMaxWidth",
            "androidx.compose.foundation.text.BasicText",
            "androidx.compose.runtime.Composable",
            "androidx.compose.runtime.collectAsState",
            "androidx.compose.ui.Modifier",
            "androidx.compose.ui.platform.testTag",
            "$PACKAGE.screens.${type(screen.name, "Model")}",
            "xyz.superfunction.spfn.ui.${if (screen.isLoadable) "Loadable" else "Busy"}"
        );
        if (screen.actions.any { it.call != null })
        {
            imports += "androidx.compose.runtime.rememberCoroutineScope";
            imports += "kotlinx.coroutines.launch";
        }
        if (screen.source != null)
        {
            imports += "androidx.compose.runtime.LaunchedEffect";
        }
        if (screen.actions.isNotEmpty())
        {
            imports += "androidx.compose.foundation.clickable";
        }
        if (typed.isNotEmpty())
        {
            imports += "androidx.compose.foundation.text.BasicTextField";
            imports += "androidx.compose.runtime.getValue";
            imports += "androidx.compose.runtime.mutableStateOf";
            imports += "androidx.compose.runtime.remember";
            imports += "androidx.compose.runtime.setValue";
        }
        if (screen.actions.isNotEmpty() || typed.isNotEmpty())
        {
            imports += "androidx.compose.foundation.layout.heightIn";
            imports += "androidx.compose.ui.unit.Dp";
            imports += "androidx.compose.ui.unit.dp";
        }
        return imports.sorted();
    }

    private fun field(screen: ScreenDefinition, input: RouteParameters.Parameter): String = buildString {
        appendLine("        BasicTextField(");
        appendLine("            value = ${input.name},");
        appendLine("            onValueChange = { ${input.name} = it },");
        appendLine("            modifier = Modifier");
        appendLine("                .testTag(\"${screen.name}.${input.name}\")");
        appendLine("                .heightIn(min = TouchTarget)");
        appendLine("        );");
    }

    private fun control(screen: ScreenDefinition, action: ActionDefinition, bundle: Bundle): String = buildString {
        val id = "${screen.name}.${action.name}";
        val arguments = RouteParameters.inputs(screen, action, bundle).joinToString(", ") { it.name };
        val invoke = if (action.call == null) "model.${action.name}()"
        else "scope.launch { model.${action.name}($arguments) }";
        appendLine("        BasicText(");
        appendLine("            text = \"${action.name}\",");
        appendLine("            modifier = Modifier");
        appendLine("                .testTag(\"$id\")");
        appendLine("                .heightIn(min = TouchTarget)");
        appendLine("                .clickable { $invoke }");
        appendLine("        );");
    }

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
        appendLine("package $PACKAGE");
        appendLine();
        appendLine("import xyz.superfunction.spfn.client.SpfnClient");
        appendLine("import xyz.superfunction.spfn.client.SpfnKeyProvider");
        appendLine("import xyz.superfunction.spfn.client.SpfnSession");
        appendLine("import xyz.superfunction.spfn.client.SpfnTransport");
        spec.flows.forEach { appendLine("import $PACKAGE.flows.${type(it.name, "Flow")}") };
        spec.flows.forEach { appendLine("import $PACKAGE.flows.${route(it)}") };
        spec.screens.forEach { screen ->
            appendLine("import $PACKAGE.screens.${type(screen.name, "Model")}");
            if (screen.usecase)
            {
                appendLine("import $PACKAGE.screens.Default${type(screen.name, "UseCase")}");
            }
        };
        spec.services.forEach {
            appendLine("import $PACKAGE.services.Default${type(it.name, "Service")}");
            appendLine("import $PACKAGE.services.${type(it.name, "Service")}");
        };
        appendLine("import xyz.superfunction.spfn.ui.Flow");
        appendLine();
        appendLine("/** The app's one graph: services in, flows and screen models out. */");
        appendLine("class AppContainer(");
        spec.services.forEach { appendLine("    private val ${it.name}: ${type(it.name, "Service")}") };
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
        val service = screen.source?.service ?: callService(screen);
        val arguments = mutableListOf<String>();
        if (screen.usecase)
        {
            arguments += "Default${type(screen.name, "UseCase")}($service)";
        }
        arguments += service;
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
