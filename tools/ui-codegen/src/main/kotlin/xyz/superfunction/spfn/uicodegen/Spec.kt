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
// What is read is JSON, and from a contract document that means its `json spfn-ui` block and
// only that: THE PROSE IS NOT AN INPUT TO THE GENERATOR; THE BLOCK IS. `SpecInput` hands the
// block over, and digests the same bytes it hands over, so a document whose prose is reworded
// parses to the spec it already was and every generated header stays where it stood.
//
// examples/ui-spec/SCHEMA.md is this file in prose, written for whoever authors the next
// spec. The two are meant to be read together; the refusals below are numbered there.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle
import xyz.superfunction.spfn.codegen.Field
import xyz.superfunction.spfn.codegen.FieldType
import xyz.superfunction.spfn.codegen.Json
import xyz.superfunction.spfn.codegen.JsonValue
import xyz.superfunction.spfn.codegen.Names
import xyz.superfunction.spfn.codegen.Operation
import xyz.superfunction.spfn.codegen.TypeDefinition
import xyz.superfunction.spfn.codegen.bool
import xyz.superfunction.spfn.codegen.number
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
    val autofocus: Boolean,

    /**
     * What this field is checked against before anything is sent, or null.
     *
     * Null and an all-defaults object are NOT the same thing, which is why this is nullable
     * rather than defaulted. A screen whose spec says nothing about a field keeps the
     * behaviour it had before `rules` existed — the emitted model refuses a blank required
     * input and sends everything else — and a screen that writes `rules` opts that field
     * into `Form.check`. Defaulting would have turned every v1 screen into a checked one,
     * which is a generated-output change wearing a schema addition's clothes.
     */
    val rules: RulesDefinition?
)

/**
 * What one of a screen's fields is checked against, as the spec declares it.
 *
 * The shape of `FieldRules` minus its `kind`, which is deliberately not repeated here: a
 * field's kind is already `inputs.<name>.kind`, where it decides the keyboard, and a second
 * spelling of it under `rules` would be two answers to one question. The generator reads the
 * one key and writes it into both places.
 */
data class RulesDefinition(
    /** Whether an empty field is a refusal. Default `true`. */
    val required: Boolean,

    /** The fewest UTF-16 code units the field accepts, or null. */
    val minLength: Long?,

    /** The most UTF-16 code units the field accepts, or null. */
    val maxLength: Long?,

    /**
     * The spec's name for the extra rule this field carries, or null for none.
     *
     * Nothing here interprets it: it is what makes a field opt in to the screen's
     * `FieldValidator`, and one field carrying it is what makes that validator a required
     * constructor argument rather than an optional one.
     */
    val custom: String?
)

/**
 * How a screen reads its source one page at a time.
 *
 * Every name below is the CONTRACT's, resolved against the pinned bundle when the spec is
 * read. The emitted model reaches for these fields by name, so a field renamed upstream has
 * to be a refusal here: a `next` that is no longer an optional string would otherwise reach
 * a compiler as a type error in a file nobody wrote, and an `items` that is no longer an
 * array would reach it as rows of a type nobody asked for.
 *
 * [limitField] is a NAME and not the fixed word `limit`, because a contract is free to call
 * a page size `pageSize`; [limitValue] is how many rows this screen asks for, which is the
 * spec's own decision and not something a contract can state.
 */
data class ListDefinition(
    /** The response field carrying the rows, declared `array<T>`. */
    val items: String,

    /** The response field carrying the next page's cursor, an optional string. */
    val next: String,

    /** The request field the cursor is handed back on, an optional string. */
    val cursor: String,

    /** The request field the page size is handed over on, an integer. */
    val limitField: String,

    /** How many rows one page asks for. */
    val limitValue: Long,

    /** What one row is: the `T` of the response field's `array<T>`. */
    val itemType: String
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
     * Not the same question as [close] and the emitters need this one. `Flow.wayOut` draws a
     * back on every route above the root and on a pushed flow's root — so `close = false` is
     * the ordinary answer for most screens and means "the flow decides". Only a root that
     * would have had a close and asked not to has anything to pass, and a view that passed an
     * empty slot everywhere would erase every way out in the app (which is exactly what the
     * first cut of this emitter did).
     *
     * What gets passed is the TRAILING slot, because that is where the X is drawn
     * (decision N3). It was the leading slot while the close lived there, and a suppression
     * aimed at the slot the control has left is a suppression that suppresses nothing.
     */
    val suppressesClose: Boolean,
    /** What the spec says about this screen's derived inputs, by input name. */
    val inputs: List<InputDefinition>,
    /**
     * The static body this screen draws, by key, or null for a screen that draws none.
     *
     * A KEY into `BodyText` rather than the words themselves. A spec says what a screen is,
     * and a paragraph of body copy is not that — a spec carrying its own prose is one nobody
     * can read the structure out of. Refused on a screen with a `source`, because that
     * screen's body is what it read: a static one under it would be a second answer to the
     * same question, and the read's would be the one nobody could see.
     */
    val bodyKey: String?,

    /**
     * How this screen reads its source a page at a time, or null for a screen that reads it
     * whole.
     *
     * Refused on a screen with no `source` for the reason `body` is refused on a screen with
     * one: a page is a page OF a read, and a screen that performs none has nothing to page.
     */
    val list: ListDefinition?
)
{
    /**
     * Whether this screen's state is a `Loadable` rather than a `Busy`. A screen with a
     * source shows what it read; a screen without one shows only whether its write is in
     * flight (SCHEMA.md, "How a screen's state type is derived").
     *
     * A PAGED screen is excluded rather than included, even though it reads: its state is a
     * `Paged<T>`, which carries a `Loadable` inside it and is not one. Every `list` is a
     * specVersion 2 key, so on a v1 spec this is still exactly `source != null`.
     */
    val isLoadable: Boolean get() = source != null && list == null;

    /** Whether this screen's state is a `Paged<T>`, which is what a `list` declares. */
    val isPaged: Boolean get() = list != null;

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

    /** The paragraphs this screen draws under its header. Empty when it names no body. */
    val body: List<String> get() = bodyKey?.let { BodyText.paragraphs(it) } ?: emptyList<String>();

    /** Whether this screen's body is long enough to put a control below the fold. */
    val bodyScrolls: Boolean get() = bodyKey?.let { BodyText.scrolls(it) } ?: false;

    /** What the spec says about the input called [name], or nothing, which is every default. */
    fun inputNamed(name: String): InputDefinition =
        inputs.firstOrNull { it.name == name }
            ?: InputDefinition(
                name = name,
                kind = "text",
                label = name,
                submitOnReturn = false,
                autofocus = false,
                rules = null
            );

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
    val start: String,

    /**
     * Who writes this flow's views: `reference` — the generator — or `authored` — a person.
     *
     * A generated view is a skeleton built out of SPFNUI's components, and it is the right
     * answer for a flow nobody has drawn yet. A flow whose screens have been written from a
     * contract document by hand is the other case, and for it the generator has to keep its
     * hands off entirely: it neither writes those files nor deletes them as stale, because
     * "a generated directory holds only generated files" would otherwise eat the work.
     */
    val views: String
)
{
    /** Whether this flow is presented over something and therefore has a way out of its own. */
    val presentedOver: Boolean get() = entry != "push";

    /** Whether this flow's views are a person's, and therefore not this generator's to touch. */
    val authored: Boolean get() = views == AUTHORED;

    companion object
    {
        const val AUTHORED: String = "authored";

        const val REFERENCE: String = "reference";
    }
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

    /**
     * Whether [screen]'s view is written by hand rather than generated.
     *
     * A fact about the FLOW and never about the screen: a flow whose screens were written
     * from a contract document was written whole, and half a flow drawn by a person under
     * half a flow drawn from a grammar is two vocabularies inside one stack.
     */
    fun viewIsAuthored(screen: ScreenDefinition): Boolean =
        flows.first { it.name == screen.flow }.authored

    /**
     * This spec with only [wanted] left of it, or this spec when [wanted] is null.
     *
     * The screens of the flows that stay come with them, and so do the services those
     * screens reach: a service nothing kept calls would be emitted as a protocol and a
     * default implementation with no caller. Everything the reader already refused stays
     * refused — narrowing happens after the whole spec has been read, so a flow nobody
     * asked for is still checked before it is dropped, and a target cannot hide a broken
     * flow by not asking for it.
     *
     * A name that is not a flow is refused rather than ignored: a misspelled `--flows`
     * that fell through would emit an app with no screens at all and report success.
     */
    fun narrowedTo(wanted: Set<String>?): Spec
    {
        if (wanted == null)
        {
            return this;
        }
        val unknown = wanted.filter { name -> flows.none { it.name == name } }.sorted();
        if (unknown.isNotEmpty())
        {
            throw SpecException(
                "--flows names " + unknown.joinToString(", ") + ", which this spec does not " +
                    "declare; its flows are: " + flows.map { it.name }.sorted().joinToString(", ")
            );
        }
        val keptFlows = flows.filter { it.name in wanted };
        val keptScreens = screens.filter { it.flow in wanted };
        val reached = keptScreens.flatMap { it.services }.toSet();
        return copy(
            services = services.filter { it.name in reached },
            flows = keptFlows,
            screens = keptScreens
        );
    }

    companion object
    {
        /**
         * The versions this generator reads, and what the second one adds.
         *
         * Both are read by ONE reader rather than by two, because the two specs are the same
         * spec: version 2 adds `screens.<s>.list` and `screens.<s>.inputs.<i>.rules` and
         * changes nothing else, so a version 1 file generates exactly the files it generated
         * before. What the version buys is the refusal in the other direction — a v2 key
         * written into a v1 file is a spec whose author expected a screen this generator
         * would not have emitted, and it is refused by name rather than ignored.
         */
        const val SUPPORTED_VERSION: Long = 1;

        /** The version that added `list` and `inputs.<i>.rules`. */
        const val PAGED_VERSION: Long = 2;

        private val SUPPORTED_VERSIONS: List<Long> = listOf(SUPPORTED_VERSION, PAGED_VERSION);

        fun read(specText: String, bundle: Bundle): Spec
        {
            val root = Json.parse(specText).obj();
            checkKeys(root, setOf("specVersion", "contract", "services", "flows", "screens"), "");
            val version = root.required("specVersion").numberOrRefusal();
            if (version !in SUPPORTED_VERSIONS)
            {
                throw SpecException(
                    "specVersion is $version; this generator reads " +
                        SUPPORTED_VERSIONS.joinToString(" and ") + ", and refuses to partially read another"
                );
            }

            val contract = root.required("contract").obj();
            checkKeys(contract, setOf("manifestSha256"), "contract.");

            val services = readServices(root.required("services").obj(), bundle);
            val methods = services.flatMap { it.methods }.associateBy { it.reference };
            val flows = readFlows(root.required("flows").obj());
            val screens = readScreens(root.required("screens").obj(), methods, flows, bundle, version);

            checkReferences(flows, screens);
            checkInputs(screens, bundle);
            checkShapes(screens, bundle);
            checkAuthoredViews(flows, screens, bundle);

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
                checkKeys(entry, setOf("entry", "sheet", "start", "views"), "flows.$flow.");
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
                    start = entry.required("start").text(),
                    views = readViews(entry["views"], flow)
                );
            }

        /**
         * Who writes this flow's views, defaulting to the generator.
         *
         * The default is `reference` because that is the state every flow starts in: a
         * screen nobody has drawn yet is a skeleton, and a spec that had to say so on every
         * flow would make the common case the noisy one. A word outside the pair is refused
         * for refusal 7's reason — `views: "manual"` that fell through to the default would
         * generate over the very files it was written to protect.
         */
        private fun readViews(value: JsonValue?, flow: String): String
        {
            val views = (value ?: return FlowDefinition.REFERENCE).text();
            if (views !in VIEW_SOURCES)
            {
                throw SpecException(
                    "flows.$flow.views is '$views'; it must be one of ${VIEW_SOURCES.joinToString(", ")}"
                );
            }
            return views;
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
            flows: List<FlowDefinition>,
            bundle: Bundle,
            version: Long
        ): List<ScreenDefinition> = members.keys.sorted().map { screen ->
            val entry = members.getValue(screen).obj();
            checkKeys(
                entry,
                setOf(
                    "flow", "source", "usecase", "actions", "title", "scroll", "header", "inputs",
                    "body", "list"
                ),
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
                inputs = readInputs(entry["inputs"], screen, version),
                bodyKey = readBody(entry["body"], screen, source),
                list = readList(entry["list"], screen, source, bundle, version)
            );
        }

        /**
         * Whether this screen's header draws a close, defaulted from the flow it belongs to.
         *
         * The default is the runtime's own rule stated at generation time: `Flow.wayOut`
         * gives the root of a modal or a sheet a close and gives a pushed flow's root a back,
         * because a pushed flow stands on the host's own stack and what is under its root is
         * the host's screen. A screen that is not its flow's root never has a close — it has
         * a back — so the key only means anything on a root, and it is read the same way
         * everywhere rather than refused where it is moot.
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
         * The paragraphs this screen draws under its header, or null.
         *
         * Required to be a key `BodyText` carries, and refused outright on a screen with a
         * `source`. Both directions are refusals for the reason `sheet.detent` is: a screen
         * that reads has a body already, and a static one written beside it is words nothing
         * would draw — or, worse, words drawn over the read the screen exists for.
         */
        private fun readBody(value: JsonValue?, screen: String, source: ServiceMethod?): String?
        {
            if (value == null)
            {
                return null;
            }
            if (source != null)
            {
                throw SpecException(
                    "screens.$screen.body is written on a screen whose source is " +
                        "'${source.reference}'; a screen that reads shows what it read"
                );
            }
            val key = value.text();
            if (BodyText.paragraphs(key) == null)
            {
                throw SpecException(
                    "screens.$screen.body is '$key', which is not a body this generator " +
                        "carries; the bodies it carries are: " + BodyText.keys().joinToString(", ")
                );
            }
            return key;
        }

        /**
         * What the spec says about this screen's inputs, defaulted key by key.
         *
         * Every one of the four is optional, and every default is the quiet answer: ordinary
         * text, the field's own name as its label, a return key that only dismisses, and no
         * focus stolen on appearance. A screen collects what its request needs whether or not
         * this object exists at all.
         */
        private fun readInputs(value: JsonValue?, screen: String, version: Long): List<InputDefinition>
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
                    setOf("kind", "label", "submitOnReturn", "autofocus", "rules"),
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
                    autofocus = entry["autofocus"]?.bool() ?: false,
                    rules = readRules(entry["rules"], "screens.$screen.inputs.$input.rules", version)
                );
            };
        }

        /**
         * Refusal 12, half of it: what one field is checked against, or nothing.
         *
         * `required` defaults to TRUE, which is the one default here that is not "nothing".
         * A field a spec bothered to write rules for and said nothing about is a field
         * somebody expects to be filled in, and the opposite default would make
         * `"rules": { "minLength": 2 }` a rule that accepts an empty value.
         */
        private fun readRules(value: JsonValue?, where: String, version: Long): RulesDefinition?
        {
            if (value == null)
            {
                return null;
            }
            if (version < PAGED_VERSION)
            {
                throw SpecException(
                    "$where is a specVersion $PAGED_VERSION key and this spec says $version; a spec " +
                        "whose fields are checked says which generator it was written for"
                );
            }
            val entry = value.obj();
            checkKeys(entry, setOf("required", "minLength", "maxLength", "custom"), "$where.");
            return RulesDefinition(
                required = entry["required"]?.bool() ?: true,
                minLength = entry["minLength"]?.number(),
                maxLength = entry["maxLength"]?.number(),
                custom = entry["custom"]?.text()
            );
        }

        /**
         * Refusals 10 and 11: a `list` describes a read this screen really performs, in the
         * contract's own field names.
         *
         * Both directions are refusals, and the second is the one worth the code. The names
         * reach the emitted model as field accesses and request arguments, so `next` naming a
         * field the response does not declare, or one that is not an optional string, would
         * be a compile error in a file nobody wrote — and `items` naming something that is
         * not an `array<T>` would be a screen paging over rows of a type nobody asked for,
         * which is P8 one layer up.
         */
        private fun readList(
            value: JsonValue?,
            screen: String,
            source: ServiceMethod?,
            bundle: Bundle,
            version: Long
        ): ListDefinition?
        {
            if (value == null)
            {
                return null;
            }
            if (version < PAGED_VERSION)
            {
                throw SpecException(
                    "screens.$screen.list is a specVersion $PAGED_VERSION key and this spec says " +
                        "$version; a spec that reads a page at a time says which generator it was " +
                        "written for"
                );
            }
            if (source == null)
            {
                throw SpecException(
                    "screens.$screen.list is written on a screen whose source is null; a page is a " +
                        "page OF a read, and this screen performs none"
                );
            }
            val entry = value.obj();
            checkKeys(entry, setOf("items", "next", "cursor", "limit"), "screens.$screen.list.");
            val limit = entry.required("limit").obj();
            checkKeys(limit, setOf("field", "value"), "screens.$screen.list.limit.");
            val response = bundle.typeNamed(requireNotNull(source.declaration.responseType));
            val request = bundle.typeNamed(
                source.declaration.requestType
                    ?: throw SpecException(
                        "screens.$screen.list pages '${source.reference}', whose operation declares no " +
                            "request; a page is asked for with a cursor and a size, and there is nowhere " +
                            "to put either"
                    )
            );
            val itemsField = entry.required("items").text();
            val cursorField = entry.required("cursor").text();
            val limitName = limit.required("field").text();
            return ListDefinition(
                items = itemsField,
                next = optionalString(response, entry.required("next").text(), "screens.$screen.list.next"),
                cursor = optionalString(request, cursorField, "screens.$screen.list.cursor"),
                limitField = integerField(request, limitName, "screens.$screen.list.limit.field"),
                limitValue = limit.required("value").number(),
                itemType = rowType(response, itemsField, bundle, "screens.$screen.list.items")
            );
        }

        /** The type one row is, out of the response field the spec named. */
        private fun rowType(type: TypeDefinition, field: String, bundle: Bundle, where: String): String
        {
            val declared = fieldOf(type, field, where);
            val rows = bundle.fieldType(declared);
            if (rows !is FieldType.ArrayOf)
            {
                throw SpecException(
                    "$where names ${type.name}.$field, whose type is '${declared.type}'; the rows of a " +
                        "paged read are an array<T>"
                );
            }
            val element = rows.element;
            if (element !is FieldType.Named)
            {
                throw SpecException(
                    "$where names ${type.name}.$field, whose rows are '${declared.type}'; a row is a type " +
                        "the contract declares, because it is what the screen's `Paged` is of"
                );
            }
            return element.name;
        }

        /** The named field, required to be an optional string, which is what a cursor is. */
        private fun optionalString(type: TypeDefinition, field: String, where: String): String
        {
            val declared = fieldOf(type, field, where);
            if (!declared.optional || declared.type != "string")
            {
                throw SpecException(
                    "$where names ${type.name}.$field, which is '${declared.type}'" +
                        (if (declared.optional) "" else " and required") +
                        "; a cursor is an optional string, because the page after the last one has none"
                );
            }
            return field;
        }

        /** The named field, required to be an integer, which is what a page size is. */
        private fun integerField(type: TypeDefinition, field: String, where: String): String
        {
            val declared = fieldOf(type, field, where);
            if (declared.type != "integer")
            {
                throw SpecException(
                    "$where names ${type.name}.$field, which is '${declared.type}'; a page size is an integer"
                );
            }
            return field;
        }

        private fun fieldOf(type: TypeDefinition, field: String, where: String): Field =
            type.fields.firstOrNull { it.name == field }
                ?: throw SpecException(
                    "$where names '$field', which ${type.name} does not declare; its fields are: " +
                        type.fields.joinToString(", ") { it.name }
                );

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

        /**
         * Refusal 12's other half: the two new screens have one write each, or none.
         *
         * A paged screen's calls are its own — load, loadMore, retryMore and reload — and a
         * write beside them would be a second thing changing the rows under a person's scroll
         * with no state to say so. A form's calls are the one that sends its fields; a second
         * one would be two forms drawn on top of each other, and `Form.check` has one answer
         * to give. Both are refused here rather than emitted as something plausible.
         */
        private fun checkShapes(screens: List<ScreenDefinition>, bundle: Bundle)
        {
            screens.forEach { screen ->
                if (screen.isPaged)
                {
                    screen.actions.firstOrNull { it.call != null }?.let { action ->
                        throw SpecException(
                            "screens.${screen.name}.actions.${action.name} calls " +
                                "'${action.call?.reference}' on a screen that reads a page at a time; " +
                                "a paged screen's own calls are its load, loadMore, retryMore and reload"
                        );
                    };
                    return@forEach;
                }
                if (!ScreenShape.isForm(screen, bundle))
                {
                    return@forEach;
                }
                val submit = ScreenShape.submitAction(screen, bundle);
                screen.actions.firstOrNull { it.call != null && it != submit }?.let { action ->
                    throw SpecException(
                        "screens.${screen.name}.actions.${action.name} calls " +
                            "'${action.call?.reference}' beside the write that sends this form's " +
                            "fields; a form is one screenful of input and one write that sends it"
                    );
                };
            };
        }

        /**
         * Refusal 13: a flow with a list or a form screen writes its own views.
         *
         * The two screens version 2 adds are the two nobody can draw from a grammar. A list
         * is rows of something — what a row shows is the whole design of the screen — and a
         * form is fields, labels and the order a person fills them in. A generated skeleton
         * for either would be a screen shaped like the spec rather than like the thing, which
         * is what decision D1/D2 of 2026-09-09 took the views back for.
         *
         * So the flow says `views: authored` or it is refused. And `authored` is only
         * writable in a contract document (`SpecInput.checkViewSource`), which means the same
         * sentence twice from two directions: a flow that reads a page at a time, or collects
         * a screenful of input, lives in a document that states what its screens must do.
         */
        private fun checkAuthoredViews(
            flows: List<FlowDefinition>,
            screens: List<ScreenDefinition>,
            bundle: Bundle
        )
        {
            flows.filterNot { it.authored }.forEach { flow ->
                val drawn = screens.filter { it.flow == flow.name }
                    .firstOrNull { it.isPaged || ScreenShape.isForm(it, bundle) }
                    ?: return@forEach;
                throw SpecException(
                    "flows.${flow.name}.views is '${flow.views}' and '${drawn.name}' is a " +
                        (if (drawn.isPaged) "list" else "form") +
                        " screen; a list or a form screen has no reference view, so the flow's views " +
                        "are authored from its contract document"
                );
            };
        }

        private val ENTRIES: List<String> = listOf("modal", "push", "sheet");

        private val DETENTS: List<String> = listOf("fit", "half", "full");

        private val VIEW_SOURCES: List<String> =
            listOf(FlowDefinition.AUTHORED, FlowDefinition.REFERENCE);

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

    /**
     * The fields the screen's route carries. Empty for a screen with no source.
     *
     * A paged screen's page SIZE is not one of them, however required the contract makes it.
     * It is a number the spec wrote down — `list.limit.value` — so a route that carried it
     * would ask every caller of `push` to say how many rows the next screen reads, and two
     * pushes could then disagree about what one screen is.
     */
    fun of(screen: ScreenDefinition, bundle: Bundle): List<Parameter>
    {
        val source = screen.source ?: return emptyList();
        val carried = required(source, bundle, "screens.${screen.name}.source");
        val list = screen.list ?: return carried;
        return carried.filter { it.name != list.limitField };
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

/**
 * Which of the four models a screen gets, asked in one place.
 *
 * Three of the four follow from the spec alone and are `ScreenDefinition`'s own properties.
 * The fourth does not: whether a screen is a FORM is a fact about how many fields its
 * requests need, and that is the contract's to say. Both emitters and the rule table ask
 * here rather than each re-deriving it, because a screen the emitters called a form and the
 * table called a write would be a table asserting cells against a model that does not exist.
 */
object ScreenShape
{
    /**
     * The typed inputs a screen collects, deduplicated, in the order the contract declares
     * them.
     *
     * Contract order rather than alphabetical, because it is the order the request type
     * itself is written in, and it is what decides the parameter order of the emitted
     * `submit` and the order a `fields=` readout would list rules in if it were not sorted.
     */
    fun inputs(screen: ScreenDefinition, bundle: Bundle): List<RouteParameters.Parameter> =
        screen.actions.flatMap { RouteParameters.inputs(screen, it, bundle) }.distinctBy { it.name }

    /**
     * Whether this screen's state is a `Form`: it writes, it does not read, and it collects
     * more than one field.
     *
     * Two fields is the line because one field is where the `Busy` model already says
     * everything there is to say — a screen with a single input has no second refusal to
     * report beside the first, and `Form`'s whole reason for existing is that a person
     * pressing submit should be told every wrong thing at once. A screen that READS is never
     * a form whatever it collects: its state is what it read, and a write over it is the
     * `Loadable` model's write.
     */
    fun isForm(screen: ScreenDefinition, bundle: Bundle): Boolean =
        screen.source == null && inputs(screen, bundle).size >= 2

    /**
     * The action a form's fields are collected for.
     *
     * The one action whose call needs them, and a refusal when two actions split them: a
     * screen whose fields are the arguments of two different writes is two forms drawn on top
     * of one another, and `Form.check` has one answer to give.
     */
    fun submitAction(screen: ScreenDefinition, bundle: Bundle): ActionDefinition
    {
        val collecting = screen.actions.filter { RouteParameters.inputs(screen, it, bundle).isNotEmpty() };
        if (collecting.size != 1)
        {
            throw SpecException(
                "screens.${screen.name} collects ${inputs(screen, bundle).size} fields across " +
                    "${collecting.size} actions; a form is one screenful of input and one write that " +
                    "sends it"
            );
        }
        return collecting.single();
    }

    /** Whether this input reaches its request as an integer, and is therefore converted. */
    fun isInteger(input: RouteParameters.Parameter): Boolean = input.type is FieldType.IntegerType
}

/** The one place a spec name becomes a type name, so both emitters spell them alike. */
object UiNames
{
    fun pascal(name: String): String = name.replaceFirstChar { it.uppercase() }

    fun swiftType(name: String, kind: String): String = pascal(name) + kind

    fun kotlinType(name: String, kind: String): String = pascal(name) + kind
}
