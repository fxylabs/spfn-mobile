// The screen spec, as the generator understands it.
//
// Reading is strict in three directions: a key the spec must carry and does not is a hard
// failure, a key it carries that this file does not read is a hard failure, and a value
// that names something the contract or the spec itself does not declare is a hard failure
// too. Nothing is defaulted and nothing is guessed — a spec that half-parsed would emit an
// app whose screens are plausible rather than the ones somebody wrote down, which is the P8
// failure moved one layer up.
//
// The middle one is the least obvious and the reason it is here: an optional key cannot be
// missed by its absence, so `useCase: true` written beside the `usecase` this file reads is
// a spec whose use-case layer was requested and silently not emitted.
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
    val then: Navigation?,
    /**
     * How the control is drawn: `primary`, `secondary`, `destructive` or `text`.
     *
     * Defaulted rather than required, and `secondary` rather than `primary`, because a
     * default that shouted would make every unconsidered control the loudest thing on its
     * screen. It reaches the emitters as the component name and nothing else — the role
     * decides a fill and a font, never what the action does.
     */
    val role: String
)

/**
 * What the spec says about one of a screen's typed inputs.
 *
 * The input itself is DERIVED — `RouteParameters.inputs` reads it off the contract, because
 * what a screen has to collect is a fact about the request its action sends. This is the
 * decoration on top of it: what keyboard to raise, what to call it, and whether the return
 * key and the first appearance do anything. An entry here that names no derived input is
 * refused, so a renamed request field cannot leave a stale decoration behind (refusal 8).
 */
data class InputDefinition(
    val name: String,
    /** `code`, `text`, `email` or `number`. Decides the keyboard, never the request. */
    val kind: String,
    /** What the field is called on screen. */
    val label: String,
    /** Whether the return key performs the screen's action, and therefore says `go`. */
    val submitOnReturn: Boolean,
    /** Whether the field takes focus when the screen appears. */
    val autofocus: Boolean
)

data class ScreenDefinition(
    val name: String,
    val flow: String,
    /** The read that fills this screen, or null for a screen that reads nothing. */
    val source: ServiceMethod?,
    val usecase: Boolean,
    val actions: List<ActionDefinition>,
    /** The header's title. The screen's own name when the spec does not say. */
    val title: String,
    /** Whether the body scrolls, and therefore gets out of the keyboard's way. */
    val scroll: Boolean,
    /**
     * Whether the header draws a close control.
     *
     * Defaulted from the FLOW rather than fixed: the root of a modal or a sheet is presented
     * over something and has a way out of its own, and a pushed flow's root does not. A spec
     * that says `false` where the default is `true` is a screen that suppresses the way out
     * — a consent step, a screen mid-way through a purchase — and it is the one direction
     * worth being able to say.
     */
    val close: Boolean,
    /**
     * Whether this screen's header suppresses a close the FLOW would otherwise have drawn.
     *
     * Not the same question as [close] and the emitters need this one. `Flow.leading` draws a
     * back on every route above the root, and the root of a pushed flow draws nothing — so
     * `close = false` is the ordinary answer for most screens and means "the flow decides".
     * Only a root that would have had a close and asked not to has anything to pass, and a
     * view that passed an empty leading slot everywhere would erase every back control in the
     * app (which is exactly what the first cut of this emitter did).
     */
    val suppressesClose: Boolean,
    /** What the spec says about this screen's derived inputs, by input name. */
    val inputs: List<InputDefinition>
)
{
    /**
     * Whether this screen's state is a `Loadable` rather than a `Busy`. A screen with a
     * source shows what it read; a screen without one shows only whether its write is in
     * flight (SCHEMA.md, "How a screen's state type is derived").
     */
    val isLoadable: Boolean get() = source != null;

    /**
     * The services this screen's model is given, deduplicated and sorted: the one its
     * source reads through, and the one each of its actions calls.
     *
     * Derived rather than assumed, and a LIST rather than one name, because the spec
     * permits both ends of that. A sourced screen whose actions only navigate calls
     * exactly one service; a screen with `"actions": {}` and no source calls none and
     * takes none; and an action may call a service its screen's source does not read
     * through, which is two constructor parameters and not a choice between them.
     */
    val services: List<String> get() =
        (listOfNotNull(source) + actions.mapNotNull { it.call }).map { it.service }.distinct().sorted();

    /** Whether anything on this screen calls a service, and therefore has an answer to drop. */
    val calls: Boolean get() = source != null || actions.any { it.call != null };

    /** What the spec says about the input called [name], or nothing, which is every default. */
    fun inputNamed(name: String): InputDefinition =
        inputs.firstOrNull { it.name == name }
            ?: InputDefinition(name = name, kind = "text", label = name, submitOnReturn = false, autofocus = false);

    /**
     * The action that re-reads this screen's own source and moves nothing, or null.
     *
     * One place, because three of them ask: both model emitters write it as `await load()`
     * rather than as a write, and both view emitters give it to `LoadableView`'s retry slot
     * rather than drawing a control of its own. A screen that drew both would put two nodes
     * under one id, and a runner asked for that id would refuse to pick.
     */
    val reread: ActionDefinition? get() = source?.let { read ->
        actions.firstOrNull { it.call?.reference == read.reference && it.then == null }
    };
}

data class FlowDefinition(
    val name: String,
    /** `push`, `modal` or `sheet`. */
    val entry: String,
    /**
     * How tall the sheet stands: `fit`, `half` or `full`. Null for a flow that is not one.
     *
     * Required when `entry` is `sheet` and refused otherwise, because a detent on a modal is
     * a value nothing reads — the shape `FlowEntry` took when it stopped being an enum, said
     * once more one layer up.
     */
    val detent: String?,
    val start: String
)
{
    /** Whether this flow is presented over something and therefore has a way out of its own. */
    val presentedOver: Boolean get() = entry != "push";
}

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
            checkKeys(root, setOf("specVersion", "contract", "services", "flows", "screens"), "");
            val version = root.required("specVersion").numberOrRefusal();
            if (version != SUPPORTED_VERSION)
            {
                throw SpecException(
                    "specVersion is $version; this generator reads $SUPPORTED_VERSION and refuses to " +
                        "partially read another"
                );
            }

            val contract = root.required("contract").obj();
            checkKeys(contract, setOf("manifestSha256"), "contract.");

            val services = readServices(root.required("services").obj(), bundle);
            val methods = services.flatMap { it.methods }.associateBy { it.reference };
            val flows = readFlows(root.required("flows").obj());
            val screens = readScreens(root.required("screens").obj(), methods, flows);

            checkReferences(flows, screens);
            checkInputs(screens, bundle);

            return Spec(
                specVersion = version,
                manifestSha256 = contract.required("manifestSha256").text(),
                services = services,
                flows = flows,
                screens = screens
            );
        }

        /**
         * Refusal 6: every key of every object is one this generator reads.
         *
         * SCHEMA.md promises a spec the generator does not fully understand is refused
         * rather than partially read, and an OPTIONAL key is where that promise is spent.
         * A required key misspelled is already a missing-key refusal; `useCase: true`
         * beside the `usecase` this file reads is not — it falls through the `?: false`
         * and emits a screen with no use-case layer, which is P8 one layer up: nothing
         * failed, and the app is the one nobody wrote.
         *
         * [where] is the path prefix of the object, empty at the top level, so the
         * message names the key by the path an author can search the spec for.
         */
        private fun checkKeys(members: Map<String, JsonValue>, known: Set<String>, where: String)
        {
            val unknown = members.keys.filter { it !in known }.sorted();
            if (unknown.isEmpty())
            {
                return;
            }
            throw SpecException(
                unknown.joinToString(", ") { "$where$it" } +
                    " is not a key this generator reads; the keys it reads here are: " +
                    known.sorted().joinToString(", ")
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
                        val entry = entries.getValue(method).obj();
                        checkKeys(entry, setOf("operation"), "services.$service.$method.");
                        val operation = entry.required("operation").text();
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
                checkKeys(entry, setOf("entry", "sheet", "start"), "flows.$flow.");
                val style = entry.required("entry").text();
                if (style !in ENTRIES)
                {
                    throw SpecException(
                        "flows.$flow.entry is '$style'; it must be one of ${ENTRIES.joinToString(", ")}"
                    );
                }
                FlowDefinition(
                    name = flow,
                    entry = style,
                    detent = readDetent(entry["sheet"], style, flow),
                    start = entry.required("start").text()
                );
            }

        /**
         * The height a sheet stands at, required for a sheet and refused for anything else.
         *
         * Both directions are refusals. A sheet with no detent has no height to resolve, and
         * a modal with one carries a number nothing reads — which is exactly the state
         * `FlowEntry` stopped being an enum to avoid, said one layer up in the spec.
         */
        private fun readDetent(value: JsonValue?, entry: String, flow: String): String?
        {
            if (entry != "sheet")
            {
                if (value != null)
                {
                    throw SpecException(
                        "flows.$flow.sheet is written on a flow entered as '$entry'; a detent is a " +
                            "height only a sheet stands at"
                    );
                }
                return null;
            }
            val sheet = (value ?: throw SpecException(
                "flows.$flow.entry is 'sheet' but flows.$flow.sheet is absent; a sheet stands at a " +
                    "detent and there is no default height"
            )).obj();
            checkKeys(sheet, setOf("detent"), "flows.$flow.sheet.");
            val detent = sheet.required("detent").text();
            if (detent !in DETENTS)
            {
                throw SpecException(
                    "flows.$flow.sheet.detent is '$detent'; it must be one of ${DETENTS.joinToString(", ")}"
                );
            }
            return detent;
        }

        private fun readScreens(
            members: Map<String, JsonValue>,
            methods: Map<String, ServiceMethod>,
            flows: List<FlowDefinition>
        ): List<ScreenDefinition> = members.keys.sorted().map { screen ->
            val entry = members.getValue(screen).obj();
            checkKeys(
                entry,
                setOf("flow", "source", "usecase", "actions", "title", "scroll", "header", "inputs"),
                "screens.$screen."
            );
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

            val flowName = entry.required("flow").text();
            val flow = flows.firstOrNull { it.name == flowName };
            ScreenDefinition(
                name = screen,
                flow = flowName,
                source = source,
                usecase = entry["usecase"]?.bool() ?: false,
                actions = readActions(entry.required("actions").obj(), methods, screen),
                // Not promoted to required. A screen with no title is a screen somebody has
                // not named yet, and a header reading `enterCode` says exactly that — where a
                // refusal would stop a spec being writable in the order people write one.
                title = entry["title"]?.text() ?: screen,
                scroll = entry["scroll"]?.bool() ?: true,
                close = readClose(entry["header"], screen, flow),
                suppressesClose = isRoot(screen, flow) && !readClose(entry["header"], screen, flow),
                inputs = readInputs(entry["inputs"], screen)
            );
        }

        /**
         * Whether this screen's header draws a close, defaulted from the flow it belongs to.
         *
         * The default is the runtime's own rule stated at generation time: `Flow.leading`
         * gives the root of a modal or a sheet a close and gives a pushed flow's root
         * nothing, because a pushed flow's way out is the host app's back. A screen that is
         * not its flow's root never has one — it has a back — so the key only means anything
         * on a root, and it is read the same way everywhere rather than refused where it is
         * moot.
         */
        /** Whether this screen is the root of a flow that was presented over something. */
        private fun isRoot(screen: String, flow: FlowDefinition?): Boolean =
            flow != null && flow.start == screen && flow.presentedOver

        private fun readClose(value: JsonValue?, screen: String, flow: FlowDefinition?): Boolean
        {
            val fromFlow = isRoot(screen, flow);
            if (value == null)
            {
                return fromFlow;
            }
            val header = value.obj();
            checkKeys(header, setOf("close"), "screens.$screen.header.");
            return header["close"]?.bool() ?: fromFlow;
        }

        /**
         * What the spec says about this screen's inputs, defaulted key by key.
         *
         * Every one of the four is optional, and every default is the quiet answer: ordinary
         * text, the field's own name as its label, a return key that only dismisses, and no
         * focus stolen on appearance. A screen collects what its request needs whether or not
         * this object exists at all.
         */
        private fun readInputs(value: JsonValue?, screen: String): List<InputDefinition>
        {
            if (value == null)
            {
                return emptyList();
            }
            val members = value.obj();
            return members.keys.sorted().map { input ->
                val entry = members.getValue(input).obj();
                checkKeys(
                    entry,
                    setOf("kind", "label", "submitOnReturn", "autofocus"),
                    "screens.$screen.inputs.$input."
                );
                val kind = entry["kind"]?.text() ?: "text";
                if (kind !in FIELD_KINDS)
                {
                    throw SpecException(
                        "screens.$screen.inputs.$input.kind is '$kind'; it must be one of " +
                            FIELD_KINDS.joinToString(", ")
                    );
                }
                InputDefinition(
                    name = input,
                    kind = kind,
                    label = entry["label"]?.text() ?: input,
                    submitOnReturn = entry["submitOnReturn"]?.bool() ?: false,
                    autofocus = entry["autofocus"]?.bool() ?: false
                );
            };
        }

        private fun readActions(
            members: Map<String, JsonValue>,
            methods: Map<String, ServiceMethod>,
            screen: String
        ): List<ActionDefinition> = members.keys.sorted().map { action ->
            val entry = members.getValue(action).obj();
            checkKeys(entry, setOf("call", "then", "role"), "screens.$screen.actions.$action.");
            val role = entry["role"]?.text() ?: "secondary";
            if (role !in CONTROL_ROLES)
            {
                throw SpecException(
                    "screens.$screen.actions.$action.role is '$role'; it must be one of " +
                        CONTROL_ROLES.joinToString(", ")
                );
            }
            val call = entry["call"]?.let { resolve(it.text(), methods, "screens.$screen.actions.$action.call") };
            val then = entry["then"]?.let { readNavigation(it, "screens.$screen.actions.$action.then") };
            if (call == null && then == null)
            {
                throw SpecException(
                    "screens.$screen.actions.$action declares neither a call nor a then; it is a control " +
                        "that does nothing"
                );
            }
            ActionDefinition(name = action, call = call, then = then, role = role);
        }

        private fun readNavigation(value: JsonValue, where: String): Navigation
        {
            if (value is JsonValue.Obj)
            {
                checkKeys(value.members, setOf("push"), "$where.");
                val push = value.members["push"]
                    ?: throw SpecException("$where is an object with no 'push' key");
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

        /**
         * Refusal 8: an `inputs` entry has to decorate an input this screen really collects.
         *
         * The inputs themselves are derived from the contract, so a request field renamed
         * upstream silently orphans whatever the spec said about it — the field keeps being
         * collected, and it keeps being collected as plain text with no label and no return
         * key, which is the P8 family: nothing failed and the screen is not the one somebody
         * wrote.
         */
        private fun checkInputs(screens: List<ScreenDefinition>, bundle: Bundle)
        {
            screens.forEach { screen ->
                val derived = screen.actions
                    .flatMap { RouteParameters.inputs(screen, it, bundle) }
                    .map { it.name }
                    .toSet();
                screen.inputs.forEach { input ->
                    if (input.name !in derived)
                    {
                        throw SpecException(
                            "screens.${screen.name}.inputs.${input.name} decorates an input this screen " +
                                "does not collect; the inputs its actions need are: " +
                                (derived.sorted().joinToString(", ").ifEmpty { "none" })
                        );
                    }
                };
            };
        }

        private val ENTRIES: List<String> = listOf("modal", "push", "sheet");

        private val DETENTS: List<String> = listOf("fit", "half", "full");

        private val FIELD_KINDS: List<String> = listOf("code", "text", "email", "number");

        private val CONTROL_ROLES: List<String> = listOf("primary", "secondary", "destructive", "text");

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
