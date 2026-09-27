// JSON Schema → Shape, one row of the type mapping table at a time.
//
// docs/architecture/app-contract-codegen.md §2 is the table; every branch below names the
// row it implements. Anything the table does not list is refused with the position it was
// found at, because a guess here compiles on both platforms and lies on both (P8).

package xyz.superfunction.spfn.codegen

/** A resolved schema plus whether its value may be `null` (`anyOf [T, {type: null}]`). */
data class Resolved(val shape: Shape, val nullable: Boolean)

/** One object member before resolution: its name, its schema, and whether it is required. */
data class Member(val name: String, val schema: JsonValue, val required: Boolean)

class SchemaResolver(private val types: TypeRegistry, private val role: Role)
{
    fun resolve(node: JsonValue, typeName: String, where: String, depth: Int = 0): Resolved
    {
        if (depth > MAX_DEPTH)
        {
            throw JsonException("$where nests deeper than $MAX_DEPTH levels; recursion is not generated (R4)");
        }
        val schema = node as? JsonValue.Obj
            ?: throw JsonException("$where is not a schema object (R1)");
        checkKeywords(schema.members, where);
        return when
        {
            "anyOf" in schema.members -> resolveAnyOf(schema.members, typeName, where, depth)
            "const" in schema.members -> Resolved(constant(schema.members, typeName, where), false)
            else -> Resolved(typed(schema.members, typeName, where, depth), false)
        };
    }

    private fun typed(schema: Map<String, JsonValue>, typeName: String, where: String, depth: Int): Shape
    {
        val type = schema["type"]
            ?: throw JsonException("$where has no 'type' (Unknown or Any); there is nothing to generate (R1)");
        if (type is JsonValue.Arr)
        {
            throw JsonException("$where declares 'type' as an array; declare a nullable value with anyOf (R5)");
        }
        return when (val name = type.text())
        {
            "string" -> Shape.Text
            "integer" -> Shape.Integer
            "boolean" -> Shape.Bool
            "number" -> throw JsonException(
                "$where is 'number'; the SDK's wire reader carries integers only, and one fraction fails the " +
                    "whole response (T3)"
            )
            "array" -> Shape.ListOf(element(schema, typeName, where, depth))
            "object" -> record(schema, typeName, where, depth)
            "null" -> throw JsonException("$where is always null; only a whole response may be {type: null} (R6)")
            else -> throw JsonException("$where has type '$name', which JSON Schema does not define")
        };
    }

    /** T6, T7: the element is named `<array>Item`; a nullable element is outside the table. */
    private fun element(schema: Map<String, JsonValue>, typeName: String, where: String, depth: Int): Shape
    {
        val items = schema["items"] ?: throw JsonException("$where is an array without 'items' (R1)");
        if (items is JsonValue.Arr)
        {
            throw JsonException("$where declares tuple 'items' (R5)");
        }
        val element = resolve(items, "${typeName}Item", "$where[]", depth + 1);
        if (element.nullable)
        {
            throw JsonException("$where[] is nullable; a nullable array element is not generated (R6)");
        }
        return element.shape;
    }

    /** T5, T13: properties in name order, each child named from its property path. */
    private fun record(schema: Map<String, JsonValue>, typeName: String, where: String, depth: Int): Shape
    {
        types.claim(typeName, where);
        val members = objectMembers(schema, where);
        if (members.isEmpty())
        {
            throw JsonException("$where is an object with no properties; declare {type: null} for no body (R6)");
        }
        checkPascalCollisions(members, typeName, where);
        val properties = members.map { member ->
            val child = resolve(member.schema, typeName + Naming.pascal(member.name), "$where.${member.name}", depth + 1);
            Property(member.name, child.shape, member.required, child.nullable)
        };
        val record = Shape.Record(typeName, properties, role);
        types.add(record);
        return record;
    }

    /** T11 and T8: string constants make an open enumeration; one other member makes it nullable. */
    private fun resolveAnyOf(schema: Map<String, JsonValue>, typeName: String, where: String, depth: Int): Resolved
    {
        val beside = schema.keys.filter { it in STRUCTURAL && it != "anyOf" };
        if (beside.isNotEmpty())
        {
            throw JsonException("$where has anyOf beside ${beside.sorted().joinToString(", ")} (R3)");
        }
        val options = schema.required("anyOf").arr();
        val nullable = options.any { isNull(it) };
        val rest = options.filter { !isNull(it) };
        return when
        {
            rest.isEmpty() -> throw JsonException("$where is anyOf of null alone (R6)")
            rest.all { isStringConstant(it) } ->
                Resolved(enumeration(rest.map { it.obj().required("const").text() }, typeName, where), nullable)
            rest.size == 1 ->
            {
                val single = resolve(rest[0], typeName, where, depth + 1);
                Resolved(single.shape, nullable || single.nullable)
            }
            else -> throw JsonException("$where is a union of ${rest.size} non-constant schemas; unions are not generated (R3)")
        };
    }

    /** T12: a lone string constant is an open enumeration of one value. */
    private fun constant(schema: Map<String, JsonValue>, typeName: String, where: String): Shape
    {
        val value = schema.required("const");
        val type = schema["type"];
        if (value !is JsonValue.Text || (type != null && type != JsonValue.Text("string")))
        {
            throw JsonException("$where is a constant that is not a string; only string constants are generated (T12)");
        }
        return enumeration(listOf(value.value), typeName, where);
    }

    private fun enumeration(values: List<String>, typeName: String, where: String): Shape
    {
        if (values.size != values.toSet().size)
        {
            throw JsonException("$where repeats a constant value");
        }
        types.claim(typeName, where);
        Naming.checkEnumCases(values, where);
        val enumeration = Shape.Enumeration(typeName, values);
        types.add(enumeration);
        return enumeration;
    }

    /** `property` and `Property` in one object would both become `…Property` (R9). */
    private fun checkPascalCollisions(members: List<Member>, typeName: String, where: String)
    {
        members.groupBy { Naming.pascal(it.name) }.forEach { (pascal, group) ->
            if (group.size > 1)
            {
                throw JsonException(
                    "$where properties ${group.joinToString(", ") { it.name }} all name the type $typeName$pascal (R9)"
                );
            }
        };
    }

    private fun isNull(node: JsonValue): Boolean =
        node is JsonValue.Obj && node.members["type"] == JsonValue.Text("null") &&
            node.members.keys.all { it == "type" || it in IGNORED }

    private fun isStringConstant(node: JsonValue): Boolean
    {
        val members = (node as? JsonValue.Obj)?.members ?: return false;
        val type = members["type"];
        return members["const"] is JsonValue.Text && (type == null || type == JsonValue.Text("string")) &&
            members.keys.all { it == "const" || it == "type" || it in IGNORED };
    }

    companion object
    {
        const val MAX_DEPTH = 64;

        /** Annotation and validation keywords: the server validates, the client does not. */
        val IGNORED = setOf(
            "description", "title", "default", "examples", "format", "pattern", "minLength", "maxLength",
            "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minItems",
            "maxItems", "uniqueItems"
        );

        val STRUCTURAL = setOf("type", "properties", "required", "items", "anyOf", "const", "additionalProperties");

        private val REFUSED = mapOf(
            "\$ref" to "a reference; recursion is not generated (R4)",
            "\$id" to "a schema id, the mark of a recursive or referenced schema (R4)",
            "patternProperties" to "a map; maps are not generated (R2)",
            "oneOf" to "a union; unions are not generated (R3)",
            "allOf" to "an intersection; intersections are not generated (R3)",
            "not" to "a negation (Type.Never or Type.Not) (R3)",
            "enum" to "an enum keyword; declare anyOf of string constants (Type.Union of Type.Literal) (R3)"
        );

        fun checkKeywords(schema: Map<String, JsonValue>, where: String)
        {
            schema.keys.sorted().forEach { key ->
                REFUSED[key]?.let { throw JsonException("$where uses '$key': $it") };
                if (key !in STRUCTURAL && key !in IGNORED)
                {
                    throw JsonException("$where uses '$key', a keyword this generator does not read (R7)");
                }
            };
        }

        /** An object's members in name order, `required` read as a set. */
        fun objectMembers(schema: Map<String, JsonValue>, where: String): List<Member>
        {
            checkAdditionalProperties(schema["additionalProperties"], where);
            val properties = schema["properties"]?.obj() ?: emptyMap();
            val required = schema["required"]?.arr()?.map { it.text() }?.toSet() ?: emptySet();
            val undeclared = required.filter { it !in properties };
            if (undeclared.isNotEmpty())
            {
                throw JsonException("$where requires ${undeclared.sorted().joinToString(", ")}, which it does not declare");
            }
            return properties.keys.sorted().map { name ->
                AppContractReader.checkIdentifier(name, "$where property '$name'");
                Member(name, properties.getValue(name), name in required)
            };
        }

        private fun checkAdditionalProperties(value: JsonValue?, where: String)
        {
            if (value != null && value !is JsonValue.Bool)
            {
                throw JsonException("$where declares additionalProperties as a schema: a map; maps are not generated (R2)");
            }
        }
    }
}

/** The request and response of one operation, each resolved under its own names and role. */
class OperationSchemas(private val operation: String, private val types: TypeRegistry)
{
    private val prefix = Naming.pascal(operation);

    /** T14: exactly the path's `:name` segments, each a required string or integer. */
    fun pathParameters(params: JsonValue?, pieces: List<PathPiece>): List<Property>
    {
        val inPath = pieces.filterIsInstance<PathPiece.Parameter>().map { it.name };
        val declared = params?.let { container(it, "params") } ?: emptyList();
        if (declared.map { it.name }.toSet() != inPath.toSet() || inPath.size != inPath.toSet().size)
        {
            throw JsonException(
                "operation '$operation' path parameters $inPath do not match its params ${declared.map { it.name }} (T14)"
            );
        }
        val byName = declared.associateBy { it.name };
        return inPath.map { name -> scalar(byName.getValue(name), "params", pathShapes = true) };
    }

    /** T15: optional scalars allowed, nullable not; only on an unproven operation. */
    fun query(query: JsonValue?, auth: String): List<Property>
    {
        val members = query?.let { container(it, "query") } ?: return emptyList();
        if (members.isNotEmpty() && auth != "none")
        {
            throw JsonException(
                "operation '$operation' is $auth and has a query; the SDK signs the path it sends and the server " +
                    "verifies the path without its query, so a query is not generated on a proven operation (T15)"
            );
        }
        return members.map { scalar(it, "query", pathShapes = false) };
    }

    /** T16: an object named `<Op>Body`; optional-and-nullable has no representation (T10). */
    fun body(body: JsonValue, method: String): Shape.Record
    {
        if (method == "GET")
        {
            throw JsonException("operation '$operation' is a GET with a body (T16)");
        }
        val resolved = SchemaResolver(types, Role.BODY).resolve(body, "${prefix}Body", "$operation.body");
        val record = resolved.shape as? Shape.Record
            ?: throw JsonException("operation '$operation' body is not an object (T16)");
        if (resolved.nullable)
        {
            throw JsonException("operation '$operation' body is nullable (T16)");
        }
        checkNoTriState(record, "$operation.body");
        return record;
    }

    /** T18, T19: an object named `<Op>Response`, or `{type: null}` for no body. */
    fun response(response: JsonValue): Shape.Record?
    {
        val members = response.obj();
        if (members["type"] == JsonValue.Text("null") && members.keys.all { it == "type" || it in SchemaResolver.IGNORED })
        {
            return null;
        }
        val resolved = SchemaResolver(types, Role.RESPONSE).resolve(response, "${prefix}Response", "$operation.response");
        val record = resolved.shape as? Shape.Record;
        if (record == null || resolved.nullable)
        {
            throw JsonException("operation '$operation' response is neither an object nor {type: null} (R6)");
        }
        return record;
    }

    private fun container(node: JsonValue, slot: String): List<Member>
    {
        val schema = node.obj();
        val where = "$operation.$slot";
        SchemaResolver.checkKeywords(schema, where);
        if (schema["type"] != JsonValue.Text("object"))
        {
            throw JsonException("$where is not an object");
        }
        return SchemaResolver.objectMembers(schema, where);
    }

    private fun scalar(member: Member, slot: String, pathShapes: Boolean): Property
    {
        val where = "$operation.$slot.${member.name}";
        val typeName = prefix + Naming.pascal(slot) + Naming.pascal(member.name);
        val resolved = SchemaResolver(types, Role.BODY).resolve(member.schema, typeName, where);
        val allowed = if (pathShapes) resolved.shape is Shape.Text || resolved.shape is Shape.Integer
        else resolved.shape !is Shape.Record && resolved.shape !is Shape.ListOf;
        if (!allowed || resolved.nullable || (pathShapes && !member.required))
        {
            throw JsonException(
                "$where is not a ${if (pathShapes) "required string or integer" else "non-null scalar"} (${if (pathShapes) "T14" else "T15"})"
            );
        }
        return Property(member.name, resolved.shape, member.required, false);
    }

    private fun checkNoTriState(record: Shape.Record, where: String)
    {
        record.properties.forEach { property ->
            if (!property.required && property.nullable)
            {
                throw JsonException(
                    "$where.${property.name} is optional and nullable; a request cannot tell 'not sent' from 'null' " +
                        "in a generated type (T10)"
                );
            }
            nestedRecords(property.shape).forEach { checkNoTriState(it, "$where.${property.name}") };
        };
    }

    private fun nestedRecords(shape: Shape): List<Shape.Record> = when (shape)
    {
        is Shape.Record -> listOf(shape)
        is Shape.ListOf -> nestedRecords(shape.element)
        else -> emptyList()
    }
}
