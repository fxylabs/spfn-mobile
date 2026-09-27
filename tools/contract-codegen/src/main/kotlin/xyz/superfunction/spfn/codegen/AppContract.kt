// An app's contract document, as the generator understands it.
//
// The document is `@spfn/core:contract`, `documentVersion: 1`: every operation a separately
// shipped client calls, with its request and response as JSON Schema produced by TypeBox.
// Only the operations the app selects are read deeply, and every construct outside the
// type mapping table (docs/architecture/app-contract-codegen.md §2) is refused by name —
// a schema this reader does not understand never falls through into a plausible type (P8).

package xyz.superfunction.spfn.codegen

/** A resolved schema. Records and enumerations carry the name they are emitted under. */
sealed interface Shape
{
    data object Text : Shape

    data object Integer : Shape

    data object Bool : Shape

    /** An open string set: a value this build does not know decodes as `unknown`. */
    data class Enumeration(val typeName: String, val values: List<String>) : Shape

    data class Record(val typeName: String, val properties: List<Property>, val role: Role) : Shape

    data class ListOf(val element: Shape) : Shape
}

/** Whether a record is sent (encoded) or received (decoded). A record is never both. */
enum class Role
{
    BODY,
    RESPONSE
}

/**
 * One member of an object. `required` and `nullable` are separate axes: required means the
 * key is always present, nullable means its value may be `null`.
 */
data class Property(
    val name: String,
    val shape: Shape,
    val required: Boolean,
    val nullable: Boolean
)

/** A path piece: literal text, or the name of a parameter substituted into it. */
sealed interface PathPiece
{
    data class Literal(val text: String) : PathPiece

    data class Parameter(val name: String) : PathPiece
}

data class AppOperation(
    val name: String,
    val method: String,
    val path: String,
    val pathPieces: List<PathPiece>,
    val auth: String,
    val requiresSession: Boolean,
    val since: String?,
    /** In path order. Every one is required, and each is `Text` or `Integer`. */
    val pathParameters: List<Property>,
    /** In name order. Scalars and enumerations only; never nullable. */
    val query: List<Property>,
    val body: Shape.Record?,
    /** Null when the document declares `{type: null}`: the operation answers no body. */
    val response: Shape.Record?
)

data class AppContract(
    val sha256: String,
    val documentVersion: Long,
    /** In name order, whatever order the selection listed them in. */
    val operations: List<AppOperation>,
    /** Every emitted record and enumeration, in name order. */
    val types: List<Shape>
)
{
    val operationNames: List<String> get() = operations.map { it.name }
}

object AppContractReader
{
    private val TOP_LEVEL_KEYS = setOf("compatibilityPolicy", "documentVersion", "operations");

    private val OPERATION_KEYS = setOf(
        "auth", "interceptor", "method", "name", "path", "request", "requiresSession", "response", "since"
    );

    private val REQUEST_KEYS = setOf("params", "query", "body");

    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE");

    /**
     * Reads the document and resolves the selected operations.
     *
     * `authClasses` is the set the SDK's pinned contract declares: the execute path refuses
     * any other class, so an operation naming one is refused here instead.
     */
    fun read(documentText: String, sha256: String, selectionText: String, authClasses: Set<String>): AppContract
    {
        val root = Json.parse(documentText, allowFractions = true).obj();
        checkKeys(root, TOP_LEVEL_KEYS, "the document");
        val documentVersion = root.required("documentVersion");
        if (documentVersion != JsonValue.Number(1))
        {
            val shown = (documentVersion as? JsonValue.Number)?.value?.toString() ?: documentVersion.toString();
            throw JsonException("documentVersion $shown is not supported; this generator reads documentVersion 1");
        }
        val byName = operationsByName(root.required("operations").arr());
        val names = AppSelection.read(selectionText, byName.keys);
        val types = TypeRegistry();
        val operations = names.sorted().map { readOperation(byName.getValue(it), authClasses, types) };
        return AppContract(sha256, 1, operations, types.all());
    }

    private fun operationsByName(operations: List<JsonValue>): Map<String, Map<String, JsonValue>>
    {
        val byName = LinkedHashMap<String, Map<String, JsonValue>>();
        operations.forEach { entry ->
            val members = entry.obj();
            val name = members.required("name").text();
            if (byName.put(name, members) != null)
            {
                throw JsonException("the document declares operation '$name' twice");
            }
        };
        return byName;
    }

    private fun readOperation(members: Map<String, JsonValue>, authClasses: Set<String>, types: TypeRegistry): AppOperation
    {
        val name = members.required("name").text();
        checkKeys(members, OPERATION_KEYS, "operation '$name'");
        checkIdentifier(name, "operation name '$name'");
        val method = members.required("method").text();
        if (method !in METHODS)
        {
            throw JsonException("operation '$name' has method '$method'; expected one of ${METHODS.sorted()}");
        }
        val path = members.required("path").text();
        val auth = members.required("auth").text();
        val requiresSession = members.required("requiresSession").bool();
        checkAuth(name, auth, requiresSession, authClasses);
        val request = members.required("request").obj();
        checkKeys(request, REQUEST_KEYS, "operation '$name' request");
        val schemas = OperationSchemas(name, types);
        val pieces = PathTemplate.parse(path, "operation '$name'");
        val pathParameters = schemas.pathParameters(request["params"], pieces);
        val query = schemas.query(request["query"], auth);
        val body = request["body"]?.let { schemas.body(it, method) };
        return AppOperation(
            name = name,
            method = method,
            path = path,
            pathPieces = pieces,
            auth = auth,
            requiresSession = requiresSession,
            since = members["since"]?.text(),
            pathParameters = pathParameters,
            query = query,
            body = body,
            response = schemas.response(members.required("response"))
        );
    }

    private fun checkAuth(name: String, auth: String, requiresSession: Boolean, authClasses: Set<String>)
    {
        if (auth !in authClasses)
        {
            throw JsonException(
                "operation '$name' names auth '$auth', which the SDK's pinned contract does not declare " +
                    "(it declares ${authClasses.sorted()})"
            );
        }
        if (auth == "none" && requiresSession)
        {
            throw JsonException("operation '$name' is auth 'none' yet requiresSession; an unproven call presents no session");
        }
    }

    fun checkKeys(members: Map<String, JsonValue>, allowed: Set<String>, where: String)
    {
        val unknown = members.keys.filter { it !in allowed };
        if (unknown.isNotEmpty())
        {
            throw JsonException("$where has key(s) this generator does not read: ${unknown.sorted().joinToString(", ")}");
        }
    }

    private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*");

    /** ASCII only, on purpose: the two languages classify non-ASCII letters differently (P9). */
    fun checkIdentifier(name: String, what: String)
    {
        if (!IDENTIFIER.matches(name))
        {
            throw JsonException("$what is not an ASCII identifier ([A-Za-z_][A-Za-z0-9_]*)");
        }
    }
}

/** The operation selection: `{ "operations": [names] }`, nothing else. */
object AppSelection
{
    fun read(selectionText: String, declared: Set<String>): List<String>
    {
        val root = Json.parse(selectionText).obj();
        AppContractReader.checkKeys(root, setOf("operations"), "the operation selection");
        val names = root.required("operations").arr().map { it.text() };
        if (names.isEmpty())
        {
            throw JsonException("the operation selection names no operation");
        }
        val repeated = names.groupBy { it }.filterValues { it.size > 1 }.keys;
        if (repeated.isNotEmpty())
        {
            throw JsonException("the operation selection names ${repeated.sorted().joinToString(", ")} more than once");
        }
        val missing = names.filter { it !in declared };
        if (missing.isNotEmpty())
        {
            throw JsonException("the operation selection names ${missing.sorted().joinToString(", ")}, which the document does not declare");
        }
        return names;
    }
}

/** `/v1/items/:itemId/notes` → literal and parameter pieces. */
object PathTemplate
{
    fun parse(path: String, where: String): List<PathPiece>
    {
        if (!path.startsWith("/") || path.contains("//") || path.contains('?') || path.contains('#') ||
            (path.length > 1 && path.endsWith("/")))
        {
            throw JsonException(
                "$where has path '$path'; a path starts with '/' and carries no '//', query, fragment or trailing '/'"
            );
        }
        val pieces = mutableListOf<PathPiece>();
        path.substring(1).split('/').forEach { segment ->
            pieces += PathPiece.Literal("/");
            pieces += if (segment.startsWith(":")) parameter(segment, where) else PathPiece.Literal(segment);
        };
        return merged(pieces);
    }

    private fun parameter(segment: String, where: String): PathPiece
    {
        val name = segment.substring(1);
        AppContractReader.checkIdentifier(name, "$where path parameter '$segment'");
        return PathPiece.Parameter(name);
    }

    /** Adjacent literals joined, so the emitted concatenation reads as the path does. */
    private fun merged(pieces: List<PathPiece>): List<PathPiece>
    {
        val out = mutableListOf<PathPiece>();
        pieces.forEach { piece ->
            val last = out.lastOrNull();
            if (piece is PathPiece.Literal && last is PathPiece.Literal)
            {
                out[out.size - 1] = PathPiece.Literal(last.text + piece.text);
            }
            else
            {
                out += piece;
            }
        };
        return out;
    }
}

/** Every generated type name, with where it came from, so a collision names both origins. */
class TypeRegistry
{
    private val origins = LinkedHashMap<String, String>();
    private val types = mutableListOf<Shape>();

    fun claim(typeName: String, where: String)
    {
        val earlier = origins.putIfAbsent(typeName, where);
        if (earlier != null)
        {
            throw JsonException("type name '$typeName' is generated twice: from $earlier and from $where");
        }
    }

    fun add(type: Shape)
    {
        types += type;
    }

    fun claimed(): Set<String> = origins.keys

    fun all(): List<Shape> = types.sortedBy { typeNameOf(it) }

    private fun typeNameOf(type: Shape): String = when (type)
    {
        is Shape.Record -> type.typeName
        is Shape.Enumeration -> type.typeName
        else -> throw JsonException("only records and enumerations are registered types")
    }
}
