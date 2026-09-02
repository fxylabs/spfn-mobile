// The screen spec, as the generator understands it.
//
// Reading is strict in both directions: a key the spec must carry and does not is a hard
// failure, and a value that names something the contract or the spec itself does not
// declare is a hard failure too. Nothing is defaulted and nothing is guessed — a spec that
// half-parsed would emit an app whose screens are plausible rather than the ones somebody
// wrote down, which is the P8 failure moved one layer up.
//
// examples/ui-spec/SCHEMA.md is this file in prose, written for whoever authors the next
// spec. The two are meant to be read together; the refusals below are numbered there.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle
import xyz.superfunction.spfn.codegen.FieldType
import xyz.superfunction.spfn.codegen.Json
import xyz.superfunction.spfn.codegen.JsonValue
import xyz.superfunction.spfn.codegen.Names
import xyz.superfunction.spfn.codegen.Operation
import xyz.superfunction.spfn.codegen.bool
import xyz.superfunction.spfn.codegen.obj
import xyz.superfunction.spfn.codegen.required
import xyz.superfunction.spfn.codegen.text

/** What generating this spec refused, with a message that names the field. */
class SpecException(message: String) : IllegalArgumentException(message)

/** One service method: a name a screen calls, and the operation behind it. */
data class ServiceMethod(
    val service: String,
    val name: String,
    /** The descriptor name, e.g. `authDeviceInfo` — never the contract's dotted id. */
    val operation: String,
    /** The operation as the pinned bundle declares it. */
    val declaration: Operation
)
{
    val reference: String get() = "$service.$name";
}

data class ServiceDefinition(val name: String, val methods: List<ServiceMethod>)

/** What an action does to the flow once its call has succeeded. */
sealed interface Navigation
{
    data object Close : Navigation

    data object Pop : Navigation

    data class Push(val screen: String) : Navigation
}

data class ActionDefinition(
    val name: String,
    /** The write this action performs, or null for an action that only navigates. */
    val call: ServiceMethod?,
    val then: Navigation?
)

data class ScreenDefinition(
    val name: String,
    val flow: String,
    /** The read that fills this screen, or null for a screen that reads nothing. */
    val source: ServiceMethod?,
    val usecase: Boolean,
    val actions: List<ActionDefinition>
)
{
    /**
     * Whether this screen's state is a `Loadable` rather than a `Busy`. A screen with a
     * source shows what it read; a screen without one shows only whether its write is in
     * flight (SCHEMA.md, "How a screen's state type is derived").
     */
    val isLoadable: Boolean get() = source != null;
}

data class FlowDefinition(val name: String, val entry: String, val start: String)

data class Spec(
    val specVersion: Long,
    val manifestSha256: String,
    val services: List<ServiceDefinition>,
    val flows: List<FlowDefinition>,
    val screens: List<ScreenDefinition>
)
{
    fun screenNamed(name: String): ScreenDefinition =
        screens.first { it.name == name }

    fun screensOf(flow: FlowDefinition): List<ScreenDefinition> =
        screens.filter { it.flow == flow.name }

    companion object
    {
        const val SUPPORTED_VERSION: Long = 1;

        fun read(specText: String, bundle: Bundle): Spec
        {
            val root = Json.parse(specText).obj();
            val version = root.required("specVersion").numberOrRefusal();
            if (version != SUPPORTED_VERSION)
            {
                throw SpecException(
                    "specVersion is $version; this generator reads $SUPPORTED_VERSION and refuses to " +
                        "partially read another"
                );
            }

            val services = readServices(root.required("services").obj(), bundle);
            val methods = services.flatMap { it.methods }.associateBy { it.reference };
            val flows = readFlows(root.required("flows").obj());
            val screens = readScreens(root.required("screens").obj(), methods);

            checkReferences(flows, screens);

            return Spec(
                specVersion = version,
                manifestSha256 = root.required("contract").obj().required("manifestSha256").text(),
                services = services,
                flows = flows,
                screens = screens
            );
        }

        /**
         * Refusal 2: an operation name must be one the contract generator emits.
         *
         * The legal set is derived with `Names.lowerCamel`, the same function
         * `SwiftEmitter` and `KotlinEmitter` name their descriptors with, so a name this
         * accepts is a name `SpfnGeneratedCalls` really carries. Re-implementing the
         * rule here would let the two drift and turn a spec typo into a compile error in
         * a file nobody wrote.
         */
        private fun readServices(members: Map<String, JsonValue>, bundle: Bundle): List<ServiceDefinition>
        {
            val declared = bundle.operations.associateBy { Names.lowerCamel(it.id) };
            return members.keys.sorted().map { service ->
                val entries = members.getValue(service).obj();
                ServiceDefinition(
                    name = service,
                    methods = entries.keys.sorted().map { method ->
                        val operation = entries.getValue(method).obj().required("operation").text();
                        val declaration = declared[operation]
                            ?: throw SpecException(
                                "services.$service.$method names operation '$operation', which the pinned " +
                                    "contract does not declare; the generated descriptors are: " +
                                    declared.keys.sorted().joinToString(", ")
                            );
                        ServiceMethod(service, method, operation, declaration);
                    }
                );
            };
        }

        private fun readFlows(members: Map<String, JsonValue>): List<FlowDefinition> =
            members.keys.sorted().map { flow ->
                val entry = members.getValue(flow).obj();
                val style = entry.required("entry").text();
                if (style != "modal" && style != "push")
                {
                    throw SpecException("flows.$flow.entry is '$style'; it must be 'modal' or 'push'");
                }
                FlowDefinition(name = flow, entry = style, start = entry.required("start").text());
            }

        private fun readScreens(
            members: Map<String, JsonValue>,
            methods: Map<String, ServiceMethod>
        ): List<ScreenDefinition> = members.keys.sorted().map { screen ->
            val entry = members.getValue(screen).obj();
            val sourceValue = entry.required("source");
            val source = if (sourceValue is JsonValue.Null) null
            else resolve(sourceValue.text(), methods, "screens.$screen.source");

            if (source != null && !source.declaration.declaresResponse)
            {
                throw SpecException(
                    "screens.$screen.source names '${source.reference}', whose operation declares no " +
                        "response; a screen cannot be filled by a read that answers with nothing"
                );
            }

            ScreenDefinition(
                name = screen,
                flow = entry.required("flow").text(),
                source = source,
                usecase = entry["usecase"]?.bool() ?: false,
                actions = readActions(entry.required("actions").obj(), methods, screen)
            );
        }

        private fun readActions(
            members: Map<String, JsonValue>,
            methods: Map<String, ServiceMethod>,
            screen: String
        ): List<ActionDefinition> = members.keys.sorted().map { action ->
            val entry = members.getValue(action).obj();
            val call = entry["call"]?.let { resolve(it.text(), methods, "screens.$screen.actions.$action.call") };
            val then = entry["then"]?.let { readNavigation(it, "screens.$screen.actions.$action.then") };
            if (call == null && then == null)
            {
                throw SpecException(
                    "screens.$screen.actions.$action declares neither a call nor a then; it is a control " +
                        "that does nothing"
                );
            }
            ActionDefinition(name = action, call = call, then = then);
        }

        private fun readNavigation(value: JsonValue, where: String): Navigation
        {
            if (value is JsonValue.Obj)
            {
                val push = value.members["push"]
                    ?: throw SpecException("$where is an object with no 'push' key");
                if (value.members.size != 1)
                {
                    throw SpecException("$where carries more than 'push'; a then does one thing");
                }
                return Navigation.Push(push.text());
            }
            return when (val word = value.text())
            {
                "close" -> Navigation.Close
                "pop" -> Navigation.Pop
                else -> throw SpecException("$where is '$word'; it must be 'close', 'pop' or {\"push\": …}")
            };
        }

        /** Refusal 5, for both the places a `service.method` can be written. */
        private fun resolve(
            reference: String,
            methods: Map<String, ServiceMethod>,
            where: String
        ): ServiceMethod = methods[reference]
            ?: throw SpecException(
                "$where names '$reference', which no service declares; the declared methods are: " +
                    methods.keys.sorted().joinToString(", ")
            );

        /**
         * Refusals 3 and 4: nothing may name a screen outside its own flow.
         *
         * Two flows' routes on one stack is what `FlowRoute` exists to prevent, and a
         * spec is where it costs nothing to prevent. A push across flows would compile —
         * the route types differ, so it would not — but a `start` naming a foreign screen
         * would open a host on a route it cannot render.
         */
        private fun checkReferences(flows: List<FlowDefinition>, screens: List<ScreenDefinition>)
        {
            val byName = screens.associateBy { it.name };
            val flowNames = flows.map { it.name }.toSet();

            screens.forEach { screen ->
                if (screen.flow !in flowNames)
                {
                    throw SpecException("screens.${screen.name}.flow names '${screen.flow}', which is not a flow");
                }
                screen.actions.forEach { action ->
                    val push = action.then as? Navigation.Push ?: return@forEach;
                    val target = byName[push.screen]
                        ?: throw SpecException(
                            "screens.${screen.name}.actions.${action.name}.then pushes '${push.screen}', " +
                                "which is not a screen"
                        );
                    if (target.flow != screen.flow)
                    {
                        throw SpecException(
                            "screens.${screen.name}.actions.${action.name}.then pushes '${push.screen}', " +
                                "which belongs to flow '${target.flow}' and not to '${screen.flow}'"
                        );
                    }
                };
            };

            flows.forEach { flow ->
                val start = byName[flow.start]
                    ?: throw SpecException("flows.${flow.name}.start names '${flow.start}', which is not a screen");
                if (start.flow != flow.name)
                {
                    throw SpecException(
                        "flows.${flow.name}.start names '${flow.start}', which belongs to flow '${start.flow}'"
                    );
                }
            };
        }

        private fun JsonValue.numberOrRefusal(): Long = when (this)
        {
            is JsonValue.Number -> value
            else -> throw SpecException("specVersion is not a number")
        };
    }
}

/**
 * The route parameters a screen carries, derived rather than declared.
 *
 * A screen's route has to carry whatever its own requests need. Every request the screen
 * sends is built from these by field name, so the rule is: the route carries the required
 * fields of the screen's SOURCE request, and an action whose request needs a field the
 * route does not carry takes it as a method parameter instead. That is what makes
 * `reviewDevice(userCode:)` a route with a payload and `enterCode` one without, from the
 * contract alone.
 */
object RouteParameters
{
    data class Parameter(val name: String, val type: FieldType)

    /** The fields the screen's route carries. Empty for a screen with no source. */
    fun of(screen: ScreenDefinition, bundle: Bundle): List<Parameter>
    {
        val source = screen.source ?: return emptyList();
        return required(source, bundle, "screens.${screen.name}.source");
    }

    /** The fields an action's own request needs that its screen's route does not carry. */
    fun inputs(screen: ScreenDefinition, action: ActionDefinition, bundle: Bundle): List<Parameter>
    {
        val call = action.call ?: return emptyList();
        val carried = of(screen, bundle).map { it.name }.toSet();
        return required(call, bundle, "screens.${screen.name}.actions.${action.name}.call")
            .filter { it.name !in carried };
    }

    private fun required(method: ServiceMethod, bundle: Bundle, where: String): List<Parameter>
    {
        val requestType = method.declaration.requestType ?: return emptyList();
        return bundle.typeNamed(requestType).fields.filter { !it.optional }.map { field ->
            val type = bundle.fieldType(field);
            if (type !is FieldType.StringType && type !is FieldType.IntegerType)
            {
                throw SpecException(
                    "$where needs $requestType.${field.name}, whose type '${field.type}' this generator " +
                        "cannot carry on a route; only a required string or integer can be one"
                );
            }
            Parameter(field.name, type);
        };
    }
}

/** The one place a spec name becomes a type name, so both emitters spell them alike. */
object UiNames
{
    fun pascal(name: String): String = name.replaceFirstChar { it.uppercase() }

    fun swiftType(name: String, kind: String): String = pascal(name) + kind

    fun kotlinType(name: String, kind: String): String = pascal(name) + kind
}
