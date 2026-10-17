// The state router in an app's contract document, recognised by name and shape.
//
// docs/architecture/state-client-design.md §2-13-2 is the rule. The anchor is a selected
// `getStates`: its response is the schema spfn's state router derives from its own
// declarations, so the property names of `states` are the server's list of state ids. Each
// id must then have its read, `get<Id>State`, and may have its write, `put<Id>State`. Every
// check reads the document as written, before any operation is resolved, so a refusal names
// the state rather than wherever the generic reader would have stopped. Nothing is guessed: a
// mismatch names the state and stops (P8).

package xyz.superfunction.spfn.codegen

/** One recognised state: its id, the key fields its read takes (name order), and its calls. */
data class AppState(val id: String, val keyFields: List<String>, val read: AppOperation, val write: AppOperation?)
{
    /** Whether the read answers `data` beside `value` (spfn `.data()`). */
    val hasData: Boolean get() = read.response?.properties.orEmpty().any { it.name == "data" }
}

/** The snapshot operation and every state it names, in id order. */
data class AppStateRouter(val snapshot: AppOperation, val states: List<AppState>)

/** A state as the document declares it, before its operations are resolved. */
data class StatePlan(val id: String, val keyFields: List<String>, val read: String, val write: String?)

object AppStateRecognizer
{
    const val ANCHOR = "getStates";

    /** spfn `defineState`'s rule for an id. */
    private val ID = Regex("[a-z][a-zA-Z0-9]*");

    /** The members every generated states namespace has beside its states (R9). */
    private val RESERVED = setOf("all", "events", "snapshot");

    private val STATE_SHAPED = Regex("(get|put)[A-Z][A-Za-z0-9]*State");

    /** The selected operations shaped like a state router's while `getStates` is not selected. */
    fun unanchored(selected: List<String>): List<String> =
        if (ANCHOR in selected) emptyList() else selected.filter { STATE_SHAPED.matches(it) }.sorted()

    /** Null when the selection has no anchor: then nothing is a state. */
    fun plans(byName: Map<String, Map<String, JsonValue>>, selected: List<String>): List<StatePlan>?
    {
        if (ANCHOR !in selected)
        {
            return null;
        }
        val anchor = byName.getValue(ANCHOR);
        val entries = anchorEntries(anchor);
        val snapshotQuery = snapshotQuery(anchor);
        return entries.keys.sorted().map { id -> plan(id, entries.getValue(id), byName, snapshotQuery) };
    }

    fun router(plans: List<StatePlan>, operations: List<AppOperation>): AppStateRouter
    {
        val byName = operations.associateBy { it.name };
        return AppStateRouter(
            byName.getValue(ANCHOR),
            plans.map { AppState(it.id, it.keyFields, byName.getValue(it.read), it.write?.let(byName::getValue)) }
        );
    }

    // ---- the anchor -------------------------------------------------------

    /** Each state id with its `states` entry schema. */
    private fun anchorEntries(anchor: Map<String, JsonValue>): Map<String, JsonValue>
    {
        val response = anchor.required("response");
        val properties = Schemas.properties(response);
        val shaped = anchor["method"] == JsonValue.Text("GET") && "body" !in anchor.required("request").obj() &&
            Schemas.isString(properties["at"]) && Schemas.isObject(properties["states"]) &&
            Schemas.isObject(properties["manifest"]) && Schemas.required(response).containsAll(listOf("at", "states", "manifest"));
        if (!shaped)
        {
            throw JsonException(
                "operation '$ANCHOR' is not a state snapshot: a GET without a body whose response requires " +
                    "at: string, states: object and manifest: object (state-client-design §2-13-2)"
            );
        }
        val states = Schemas.properties(properties.getValue("states"));
        val manifest = Schemas.properties(properties.getValue("manifest"));
        if (states.keys != manifest.keys)
        {
            throw JsonException(
                "operation '$ANCHOR' names states ${states.keys.sorted()} but a manifest of ${manifest.keys.sorted()}; " +
                    "a state router's snapshot names the same ids in both"
            );
        }
        return states;
    }

    /** The snapshot's query: optional strings, each passed through from the client's query. */
    private fun snapshotQuery(anchor: Map<String, JsonValue>): Set<String>
    {
        val query = anchor.required("request").obj()["query"] ?: return emptySet();
        val required = Schemas.required(query);
        Schemas.properties(query).forEach { (name, schema) ->
            if (!Schemas.isString(schema) || name in required)
            {
                throw JsonException(
                    "operation '$ANCHOR' query field '$name' is not an optional string; the generated snapshot " +
                        "passes the fields of a query through as optional strings"
                );
            }
        };
        return Schemas.properties(query).keys;
    }

    // ---- one state --------------------------------------------------------

    private fun plan(id: String, entry: JsonValue, byName: Map<String, Map<String, JsonValue>>, snapshotQuery: Set<String>): StatePlan
    {
        checkId(id);
        val readName = "get${Naming.pascal(id)}State";
        val read = byName[readName]
            ?: throw JsonException(
                "state '$id': getStates names it, but the document declares no $readName; a state router's states " +
                    "are all contracted or none is"
            );
        checkRead(id, readName, read, entry);
        val keyFields = keyFields(id, readName, read);
        val unreadable = keyFields.filter { it !in snapshotQuery };
        if (unreadable.isNotEmpty())
        {
            throw JsonException(
                "state '$id': key field(s) ${unreadable.joinToString(", ")} are not in getStates' query, so the " +
                    "snapshot cannot be read with that key"
            );
        }
        val writeName = "put${Naming.pascal(id)}State";
        byName[writeName]?.let { checkWrite(id, writeName, it, readName, read) };
        return StatePlan(id, keyFields, readName, writeName.takeIf { it in byName });
    }

    private fun checkId(id: String)
    {
        if (!ID.matches(id))
        {
            throw JsonException("state id '$id' is not ^[a-z][a-zA-Z0-9]*\$, the rule spfn defineState applies");
        }
        if (id in RESERVED)
        {
            throw JsonException("state id '$id' collides with the generated member of the same name (all, events, snapshot) (R9)");
        }
    }

    /** GET, no params or body, response exactly `{ at: string, value, data? }`, all required. */
    private fun checkRead(id: String, readName: String, read: Map<String, JsonValue>, entry: JsonValue)
    {
        if (read["method"] != JsonValue.Text("GET") || read.required("request").obj().keys.any { it != "query" })
        {
            throw JsonException("state '$id': $readName is not a GET without params or body");
        }
        val response = read.required("response");
        val fields = Schemas.properties(response);
        val shaped = Schemas.isObject(response) && Schemas.isString(fields["at"]) && "value" in fields &&
            fields.keys.all { it in setOf("at", "value", "data") } && Schemas.required(response) == fields.keys;
        if (!shaped)
        {
            throw JsonException("state '$id': $readName response is not exactly { at: string, value, data? }, every member required");
        }
        checkSnapshotEntry(id, readName, fields, entry);
        fields.keys.filter { it != "at" }.sorted().forEach { field ->
            SchemaResolver(TypeRegistry(), Role.RESPONSE)
                .resolve(fields.getValue(field), "${Naming.pascal(readName)}Response${Naming.pascal(field)}", "state '$id': $readName.response.$field");
        };
    }

    /** The snapshot entry carries the read's `value` and `data` with the same schemas. */
    private fun checkSnapshotEntry(id: String, readName: String, fields: Map<String, JsonValue>, entry: JsonValue)
    {
        val members = Schemas.properties(entry);
        val expected = fields.keys - "at";
        if (!Schemas.isObject(entry) || members.keys != expected || Schemas.required(entry) != expected)
        {
            throw JsonException(
                "state '$id': getStates' states.$id is not { ${expected.sorted().joinToString(", ")} }, every member " +
                    "required, as $readName answers"
            );
        }
        val differing = expected.sorted().firstOrNull { !Schemas.same(members.getValue(it), fields.getValue(it)) };
        if (differing != null)
        {
            throw JsonException("state '$id': the $differing schema of $readName differs from getStates' states.$id.$differing");
        }
    }

    /** spfn `.key()` allows required string fields only; they arrive as the read's query. */
    private fun keyFields(id: String, readName: String, read: Map<String, JsonValue>): List<String>
    {
        val query = read.required("request").obj()["query"] ?: return emptyList();
        val required = Schemas.required(query);
        val fields = Schemas.properties(query);
        fields.forEach { (name, schema) ->
            if (!Schemas.isString(schema) || name !in required)
            {
                throw JsonException("state '$id': $readName query field '$name' is not a required string, which a state key is");
            }
        };
        return fields.keys.sorted();
    }

    /** PUT, the read's query, an object body (T16), the read's response. */
    private fun checkWrite(id: String, writeName: String, write: Map<String, JsonValue>, readName: String, read: Map<String, JsonValue>)
    {
        val request = write.required("request").obj();
        val readQuery = read.required("request").obj()["query"] ?: JsonValue.Null;
        when
        {
            write["method"] != JsonValue.Text("PUT") || "params" in request ->
                throw JsonException("state '$id': $writeName is not a PUT without params")
            !Schemas.same(request["query"] ?: JsonValue.Null, readQuery) ->
                throw JsonException("state '$id': $writeName query differs from $readName's; a write takes the read's key")
            !Schemas.isObject(request["body"]) ->
                throw JsonException("state '$id': $writeName body is not an object (T16)")
            !Schemas.same(write.required("response"), read.required("response")) ->
                throw JsonException("state '$id': $writeName response differs from $readName's")
        }
    }
}

/** Reading schemas as written, for the recognition rule's shape checks. */
private object Schemas
{
    fun properties(schema: JsonValue): Map<String, JsonValue> =
        (schema as? JsonValue.Obj)?.members?.get("properties")?.let { it as? JsonValue.Obj }?.members ?: emptyMap()

    fun required(schema: JsonValue): Set<String> =
        (schema as? JsonValue.Obj)?.members?.get("required")?.arr()?.map { it.text() }?.toSet() ?: emptySet()

    fun isString(schema: JsonValue?): Boolean = typed(schema, "string")

    fun isObject(schema: JsonValue?): Boolean = typed(schema, "object")

    /** A plain `{type: …}`: a constant or a union is not a plain string. */
    private fun typed(schema: JsonValue?, type: String): Boolean
    {
        val members = (schema as? JsonValue.Obj)?.members ?: return false;
        return members["type"] == JsonValue.Text(type) && "const" !in members && "anyOf" !in members;
    }

    /** Equal schemas, `required` read as a set (app-contract-codegen §1-3). */
    fun same(a: JsonValue, b: JsonValue): Boolean = normalised(a) == normalised(b)

    private fun normalised(node: JsonValue): JsonValue = when (node)
    {
        is JsonValue.Obj -> JsonValue.Obj(LinkedHashMap(node.members.mapValues { (key, value) ->
            if (key == "required" && value is JsonValue.Arr) JsonValue.Arr(value.elements.sortedBy { it.toString() })
            else normalised(value)
        }))
        is JsonValue.Arr -> JsonValue.Arr(node.elements.map { normalised(it) })
        else -> node
    }
}
