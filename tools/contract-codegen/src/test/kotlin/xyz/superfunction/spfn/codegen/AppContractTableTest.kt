// The app-contract type mapping table, one test per row, both platforms.
//
// docs/architecture/app-contract-codegen.md §2 is the table. Each test builds an invented
// one-operation document, generates Swift and Kotlin from it, and holds both outputs to
// the row — the text a row promises, or the refusal it names. Behaviour on real wire bytes
// is the other half: AppContractFixtureTest.kt (spfn-core) and AppContractFixtureTests.swift.

package xyz.superfunction.spfn.codegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Builds documents and reads what the two emitters make of them. */
object AppDocuments
{
    val AUTH_CLASSES = setOf("none", "clientProofV1");

    fun operation(
        name: String = "getThing",
        response: String = """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}""",
        request: String = "{}",
        method: String = "GET",
        path: String = "/v1/things",
        auth: String = "none",
        requiresSession: Boolean = false
    ): String =
        """{"auth":"$auth","interceptor":{},"method":"$method","name":"$name","path":"$path","request":$request,""" +
            """"requiresSession":$requiresSession,"response":$response,"since":"1.0.0"}"""

    fun document(vararg operations: String): String =
        """{"compatibilityPolicy":"perOperation","documentVersion":1,"operations":[${operations.joinToString(",")}]}"""

    fun selection(vararg names: String): String = """{"operations":[${names.joinToString(",") { "\"$it\"" }}]}"""

    fun object_(properties: String, required: String): String =
        """{"type":"object","properties":{$properties},"required":[$required]}"""

    fun read(document: String, selection: String = selection("getThing")): AppContract =
        AppContractReader.read(document, "0".repeat(64), selection, AUTH_CLASSES)

    /** Every generated file of one platform, joined, so a test can look for a line in any of them. */
    fun swift(document: String, selection: String = selection("getThing")): String =
        AppSwiftEmitter(read(document, selection), "TestAPI").emit().toSortedMap().values.joinToString("\n")

    fun kotlin(document: String, selection: String = selection("getThing")): String =
        AppKotlinEmitter(read(document, selection), "TestAPI", "test.api").emit().toSortedMap().values.joinToString("\n")

    fun response(properties: String, required: String = ""): String =
        document(operation(response = object_(properties, required)))

    fun body(properties: String, required: String = ""): String =
        document(operation(method = "POST", request = """{"body":${object_(properties, required)}}""", response = """{"type":"null"}"""))

    fun refusal(document: String, selection: String = selection("getThing")): String
    {
        try
        {
            read(document, selection);
        }
        catch (refused: JsonException)
        {
            return refused.message ?: "";
        }
        fail("the generator accepted a document the table says it must refuse");
        return "";
    }
}

class AppContractTableTest
{
    private fun assertBoth(document: String, swift: List<String>, kotlin: List<String>)
    {
        val swiftText = AppDocuments.swift(document);
        val kotlinText = AppDocuments.kotlin(document);
        swift.forEach { assertTrue("Swift lacks: $it\n$swiftText", swiftText.contains(it)) };
        kotlin.forEach { assertTrue("Kotlin lacks: $it\n$kotlinText", kotlinText.contains(it)) };
    }

    private fun assertRefused(document: String, row: String)
    {
        val message = AppDocuments.refusal(document);
        assertTrue("the refusal does not name $row: $message", message.contains("($row)"));
    }

    // ---- 2-1 scalars and structure -----------------------------------------

    @Test
    fun `T1 string`()
    {
        assertBoth(
            AppDocuments.response(""""a":{"type":"string","format":"date-time"}""", "\"a\""),
            listOf("public var a: String", "self.a = try SPFNDecoding.string(members[\"a\"], at: \"\\(path).a\")"),
            listOf("val a: String", "a = SpfnDecoding.string(members[\"a\"], \"\$path.a\")")
        );
    }

    @Test
    fun `T2 integer`()
    {
        assertBoth(
            AppDocuments.response(""""a":{"type":"integer"}""", "\"a\""),
            listOf("public var a: Int64", "SPFNDecoding.integer(members[\"a\"]"),
            listOf("val a: Long", "SpfnDecoding.integer(members[\"a\"]")
        );
    }

    @Test
    fun `T3 number is refused`()
    {
        assertRefused(AppDocuments.response(""""a":{"type":"number"}""", "\"a\""), "T3");
    }

    @Test
    fun `T4 boolean`()
    {
        assertBoth(
            AppDocuments.response(""""a":{"type":"boolean"}""", "\"a\""),
            listOf("public var a: Bool", "SPFNDecoding.boolean(members[\"a\"]"),
            listOf("val a: Boolean", "SpfnDecoding.boolean(members[\"a\"]")
        );
    }

    @Test
    fun `T5 object properties are emitted in name order`()
    {
        val document = AppDocuments.response(""""b":{"type":"string"},"a":{"type":"string"}""", "\"b\",\"a\"");
        val swift = AppDocuments.swift(document);
        val kotlin = AppDocuments.kotlin(document);
        assertTrue(swift.contains("public struct GetThingResponse: Equatable, Sendable"));
        assertTrue(swift.indexOf("public var a: String") < swift.indexOf("public var b: String"));
        assertTrue(kotlin.contains("data class GetThingResponse("));
        assertTrue(kotlin.indexOf("val a: String") < kotlin.indexOf("val b: String"));
    }

    @Test
    fun `T6 array of scalars`()
    {
        assertBoth(
            AppDocuments.response(""""a":{"type":"array","items":{"type":"string"}}""", "\"a\""),
            listOf("public var a: [String]", "SPFNDecoding.array(members[\"a\"], at: \"\\(path).a\").map { try SPFNDecoding.string(\$0"),
            listOf("val a: List<String>", "SpfnDecoding.array(members[\"a\"], \"\$path.a\").map { value0 -> SpfnDecoding.string(value0")
        );
    }

    @Test
    fun `T7 array of objects names its element Item`()
    {
        val element = AppDocuments.object_(""""id":{"type":"string"}""", "\"id\"");
        assertBoth(
            AppDocuments.response(""""rows":{"type":"array","items":$element}""", "\"rows\""),
            listOf("public var rows: [GetThingResponseRowsItem]", "public struct GetThingResponseRowsItem"),
            listOf("val rows: List<GetThingResponseRowsItem>", "data class GetThingResponseRowsItem(")
        );
    }

    @Test
    fun `T8 required nullable decodes through present and encodes an explicit null`()
    {
        val nullable = """"a":{"anyOf":[{"type":"string"},{"type":"null"}]}""";
        assertBoth(
            AppDocuments.response(nullable, "\"a\""),
            listOf("public var a: String?", "TestAPI.nonNull(TestAPI.present(members, \"a\", at: \"\\(path).a\"))"),
            listOf("val a: String?\n", "TestAPISupport.nonNull(TestAPISupport.present(members, \"a\", \"\$path.a\"))")
        );
        assertBoth(
            AppDocuments.body(nullable, "\"a\""),
            listOf("            a: String?\n", "members[\"a\"] = .null"),
            listOf("    val a: String?\n", "?: SpfnCanonicalValue.Null;")
        );
    }

    @Test
    fun `T9 optional decodes absent as nil and omits the key in a body`()
    {
        val optional = """"a":{"type":"string"}""";
        assertBoth(
            AppDocuments.response(optional),
            listOf("a: String? = nil", "TestAPI.nonNull(members[\"a\"])"),
            listOf("val a: String? = null", "TestAPISupport.nonNull(members[\"a\"])")
        );
        val swift = AppDocuments.swift(AppDocuments.body(optional));
        assertTrue(swift.contains("if let value = self.a"));
        assertFalse(swift.contains(".null"));
        assertTrue(AppDocuments.kotlin(AppDocuments.body(optional)).contains("this.a?.let { value0 -> members[\"a\"] = "));
    }

    @Test
    fun `T10 optional and nullable collapses in a response and is refused in a body`()
    {
        val both = """"a":{"anyOf":[{"type":"null"},{"type":"string"}]}""";
        assertBoth(
            AppDocuments.response(both),
            listOf("a: String? = nil", "TestAPI.nonNull(members[\"a\"])"),
            listOf("val a: String? = null", "TestAPISupport.nonNull(members[\"a\"])")
        );
        assertRefused(AppDocuments.body(both), "T10");
    }

    @Test
    fun `T11 string constants make an open enumeration`()
    {
        val status = """"status":{"anyOf":[{"const":"in_review","type":"string"},{"const":"done","type":"string"}]}""";
        assertBoth(
            AppDocuments.response(status, "\"status\""),
            listOf(
                "public enum GetThingResponseStatus: Hashable, Sendable", "case inReview", "case unknown(String)",
                "self = .unknown(wireValue)", "try GetThingResponseStatus(wireValue: SPFNDecoding.string(members[\"status\"]"
            ),
            listOf(
                "sealed interface GetThingResponseStatus", "data object InReview : GetThingResponseStatus",
                "data class Unknown(override val wireValue: String) : GetThingResponseStatus", "else -> Unknown(wireValue)",
                "GetThingResponseStatus.of(SpfnDecoding.string(members[\"status\"]"
            )
        );
    }

    @Test
    fun `T12 a lone string constant is an enumeration of one, any other constant is refused`()
    {
        assertBoth(
            AppDocuments.response(""""v":{"const":"v1","type":"string"}""", "\"v\""),
            listOf("public enum GetThingResponseV", "case v1"),
            listOf("sealed interface GetThingResponseV", "data object V1 : GetThingResponseV")
        );
        assertRefused(AppDocuments.response(""""v":{"const":1,"type":"integer"}""", "\"v\""), "T12");
    }

    @Test
    fun `T13 a nested object is named from its property path`()
    {
        val owner = AppDocuments.object_(""""name":{"type":"string"}""", "\"name\"");
        assertBoth(
            AppDocuments.response(""""owner":$owner""", "\"owner\""),
            listOf("public var owner: GetThingResponseOwner", "try GetThingResponseOwner(canonical: members[\"owner\"] ?? .null"),
            listOf("val owner: GetThingResponseOwner", "GetThingResponseOwner.decode(members[\"owner\"] ?: SpfnCanonicalValue.Null")
        );
    }

    // ---- 2-2 request and response positions --------------------------------

    private val params = """{"params":{"type":"object","properties":{"thingId":{"type":"string"},"n":{"type":"integer"}},"required":["thingId","n"]}}""";

    @Test
    fun `T14 path parameters become arguments in path order, substituted percent-encoded`()
    {
        val document = AppDocuments.document(AppDocuments.operation(path = "/v1/things/:thingId/parts/:n", request = params));
        assertBoth(
            document,
            listOf(
                "getThing(thingId: String, n: Int64)",
                "path: \"/v1/things/\" + TestAPI.pathSegment(thingId) + \"/parts/\" + TestAPI.pathSegment(String(n))"
            ),
            listOf(
                "getThing(thingId: String, n: Long)",
                "path = \"/v1/things/\" + TestAPISupport.pathSegment(thingId) + \"/parts/\" + TestAPISupport.pathSegment(n.toString())"
            )
        );
        assertRefused(AppDocuments.document(AppDocuments.operation(path = "/v1/things/:other/parts/:n", request = params)), "T14");
    }

    @Test
    fun `T15 query scalars become arguments on an unproven operation and are refused on a proven one`()
    {
        val query = """{"query":{"type":"object","properties":{"limit":{"type":"integer"},"q":{"type":"string"}},"required":["q"]}}""";
        assertBoth(
            AppDocuments.document(AppDocuments.operation(request = query)),
            listOf("getThing(limit: Int64? = nil, q: String)", "TestAPI.queryString([(\"limit\", limit.map { String(\$0) }), (\"q\", q)])"),
            listOf("getThing(limit: Long? = null, q: String)", "TestAPISupport.queryString(listOf(\"limit\" to limit?.toString(), \"q\" to q))")
        );
        assertRefused(AppDocuments.document(AppDocuments.operation(request = query, auth = "clientProofV1")), "T15");
    }

    @Test
    fun `T16 a body is the call's request type, and a GET body is refused`()
    {
        assertBoth(
            AppDocuments.body(""""text":{"type":"string"}""", "\"text\""),
            listOf("-> SPFNCall<GetThingBody, SPFNNoResponse>", "encode: { \$0.canonicalValue() }", "members[\"text\"] = .string(self.text)"),
            listOf(": SpfnCall<GetThingBody, SpfnNoResponse>", "encode = { body -> body.canonicalValue() }", "members[\"text\"] = SpfnCanonicalValue.Text(this.text);")
        );
        val getBody = """{"body":${AppDocuments.object_(""""a":{"type":"string"}""", "")}}""";
        assertRefused(AppDocuments.document(AppDocuments.operation(request = getBody)), "T16");
    }

    @Test
    fun `T17 no request is Void or Unit and encodes an empty object`()
    {
        assertBoth(
            AppDocuments.document(AppDocuments.operation()),
            listOf("-> SPFNCall<Void, GetThingResponse>", "encode: { _ in SPFNCanonicalValue.object([:]) }"),
            listOf(": SpfnCall<Unit, GetThingResponse>", "encode = { _ -> SpfnCanonicalValue.Obj(emptyMap()) }")
        );
    }

    @Test
    fun `T18 an object response is decoded into Op Response`()
    {
        assertBoth(
            AppDocuments.document(AppDocuments.operation()),
            listOf("decode: { try GetThingResponse(canonical: \$0) }", "declaresResponse: true"),
            listOf("decode = { value -> GetThingResponse.decode(value) }", "declaresResponse = true")
        );
    }

    @Test
    fun `T19 a null response is the SDK's no-response call`()
    {
        assertBoth(
            AppDocuments.document(AppDocuments.operation(response = """{"type":"null"}""")),
            listOf("SPFNCall<Void, SPFNNoResponse>.noResponse(", "declaresResponse: false"),
            listOf("SpfnCall<Unit, SpfnNoResponse> = SpfnCall.noResponse(", "declaresResponse = false")
        );
    }

    @Test
    fun `G5 auth and requiresSession are carried into the descriptor as the document states them`()
    {
        assertBoth(
            AppDocuments.document(AppDocuments.operation(auth = "clientProofV1", requiresSession = true)),
            listOf("authProfile: \"clientProofV1\"", "requiresSession: true", "id: \"getThing\"", "method: \"GET\""),
            listOf("authProfile = \"clientProofV1\"", "requiresSession = true", "id = \"getThing\"", "method = \"GET\"")
        );
    }

    // ---- 2-3 refusals -------------------------------------------------------

    @Test
    fun `R1 a schema without a type is refused`()
    {
        assertRefused(AppDocuments.response(""""a":{}""", "\"a\""), "R1");
    }

    @Test
    fun `R2 maps are refused`()
    {
        assertRefused(AppDocuments.response(""""a":{"type":"object","additionalProperties":{"type":"string"}}""", "\"a\""), "R2");
        assertRefused(AppDocuments.response(""""a":{"type":"object","patternProperties":{"^x":{"type":"string"}}}""", "\"a\""), "R2");
    }

    @Test
    fun `R3 unions are refused`()
    {
        val one = AppDocuments.object_(""""x":{"type":"string"}""", "\"x\"");
        val two = AppDocuments.object_(""""y":{"type":"string"}""", "\"y\"");
        assertRefused(AppDocuments.response(""""a":{"anyOf":[$one,$two]}""", "\"a\""), "R3");
        assertRefused(AppDocuments.response(""""a":{"oneOf":[{"type":"string"}]}""", "\"a\""), "R3");
        assertRefused(AppDocuments.response(""""a":{"not":{}}""", "\"a\""), "R3");
    }

    @Test
    fun `R4 recursion and references are refused`()
    {
        assertRefused(AppDocuments.response(""""a":{"${'$'}ref":"Node"}""", "\"a\""), "R4");
        assertRefused(AppDocuments.response(""""a":{"${'$'}id":"Node","type":"object","properties":{}}""", "\"a\""), "R4");
    }

    @Test
    fun `R5 a type array and tuple items are refused`()
    {
        assertRefused(AppDocuments.response(""""a":{"type":["string","null"]}""", "\"a\""), "R5");
        assertRefused(AppDocuments.response(""""a":{"type":"array","items":[{"type":"string"}]}""", "\"a\""), "R5");
    }

    @Test
    fun `R6 a nullable element, an empty object and a non-object response are refused`()
    {
        assertRefused(AppDocuments.response(""""a":{"type":"array","items":{"anyOf":[{"type":"string"},{"type":"null"}]}}""", "\"a\""), "R6");
        assertRefused(AppDocuments.response(""""a":{"type":"object","properties":{}}""", "\"a\""), "R6");
        assertRefused(AppDocuments.document(AppDocuments.operation(response = """{"type":"array","items":{"type":"string"}}""")), "R6");
    }

    @Test
    fun `R7 an unknown keyword is refused, annotations are ignored`()
    {
        assertRefused(AppDocuments.response(""""a":{"type":"string","contentEncoding":"base64"}""", "\"a\""), "R7");
        AppDocuments.read(AppDocuments.response(""""a":{"type":"string","minLength":1,"description":"x","default":"y"}""", "\"a\""));
    }

    @Test
    fun `R8 a non-identifier property name or constant is refused`()
    {
        val message = AppDocuments.refusal(AppDocuments.response(""""a-b":{"type":"string"}""", "\"a-b\""));
        assertTrue(message, message.contains("not an ASCII identifier"));
        assertRefused(AppDocuments.response(""""s":{"anyOf":[{"const":"ok","type":"string"},{"const":"3d","type":"string"}]}""", "\"s\""), "R8");
        assertRefused(AppDocuments.response(""""s":{"anyOf":[{"const":"ok","type":"string"},{"const":"é","type":"string"}]}""", "\"s\""), "R8");
    }

    @Test
    fun `R9 collisions are refused by name`()
    {
        assertRefused(AppDocuments.response(""""s":{"anyOf":[{"const":"unknown","type":"string"},{"const":"ok","type":"string"}]}""", "\"s\""), "R9");
        assertRefused(AppDocuments.response(""""s":{"anyOf":[{"const":"in-review","type":"string"},{"const":"in_review","type":"string"}]}""", "\"s\""), "R9");
        val inner = AppDocuments.object_(""""x":{"type":"string"}""", "\"x\"");
        assertRefused(AppDocuments.response(""""a_b":$inner,"aB":$inner""", "\"a_b\",\"aB\""), "R9");
        val clash = AppDocuments.refusal(
            AppDocuments.document(
                AppDocuments.operation(response = AppDocuments.object_(""""response":$inner""", "\"response\"")),
                AppDocuments.operation(name = "getThingResponse", path = "/v1/other")
            ),
            AppDocuments.selection("getThing", "getThingResponse")
        );
        assertTrue(clash, clash.contains("type name 'GetThingResponseResponse' is generated twice"));
    }

    @Test
    fun `R10 an operation the execute path cannot carry is refused`()
    {
        fun refusedSaying(document: String, fragment: String)
        {
            val message = AppDocuments.refusal(document);
            assertTrue(message, message.contains(fragment));
        }
        refusedSaying(AppDocuments.document(AppDocuments.operation(auth = "bearer")), "names auth 'bearer'");
        refusedSaying(AppDocuments.document(AppDocuments.operation(requiresSession = true)), "presents no session");
        refusedSaying(AppDocuments.document(AppDocuments.operation(path = "/v1//x")), "has path '/v1//x'");
        refusedSaying(AppDocuments.document(AppDocuments.operation(path = "/v1/x/")), "has path '/v1/x/'");
        refusedSaying(AppDocuments.document(AppDocuments.operation(request = """{"headers":{}}""")), "headers");
        refusedSaying(AppDocuments.document(AppDocuments.operation().replace("\"since\"", "\"deprecatedIn\":\"2.0.0\",\"since\"")), "deprecatedIn");
    }
}
