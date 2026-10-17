// T15 against Superself's own contract document: the state reads that carry a query
// generate on both emitters, as published and with their auth class set to the proven one.
//
// superself-current-query.json is a byte copy of Superself's contracts/current.json
// (state-client-design §5-2, P-a and P-c). It is read as published, with one exception
// named in `stripEvents`.

package xyz.superfunction.spfn.codegen

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SuperselfQueryTest
{
    private val selection = AppDocuments.selection("getAiAccountsState", "getStates");

    private lateinit var document: JsonValue.Obj;

    /**
     * Drops the top-level `events` section, which the generator still refuses as an
     * unknown key. Opening it is another unit's work (w-x7b1b, AppContract.kt); until it
     * lands the copy is read without it. The document carries no `deprecatedIn` or
     * `removedIn`, so nothing else is stripped.
     */
    @Before
    fun stripEvents()
    {
        val published = Json.parse(File("src/test/resources/app-contract/superself-current-query.json").readText(), allowFractions = true) as JsonValue.Obj;
        document = JsonValue.Obj(LinkedHashMap(published.members.filterKeys { it != "events" }));
    }

    @Test
    fun `the state reads generate their query arguments as published`()
    {
        assertGenerates(write(document), "none");
    }

    /**
     * Superself publishes both reads as `auth: none`; the state client expects them proven
     * (state-client-design "서명된 키 읽기"). The same document with the two set to
     * `clientProofV1` is what T15 used to refuse.
     */
    @Test
    fun `the state reads generate their query arguments when proven`()
    {
        assertGenerates(write(proven(document, setOf("getAiAccountsState", "getStates"))), "clientProofV1");
    }

    private fun assertGenerates(text: String, auth: String)
    {
        val swift = AppDocuments.swift(text, selection);
        val kotlin = AppDocuments.kotlin(text, selection);
        listOf(
            "public static func getAiAccountsState(workspaceId: String) -> SPFNCall<Void, GetAiAccountsStateResponse>",
            "path: \"/state/aiAccounts\",",
            "query: [(\"workspaceId\", workspaceId)],",
            "public static func getStates(workspaceId: String? = nil) -> SPFNCall<Void, GetStatesResponse>",
            "path: \"/state\",",
            "authProfile: \"$auth\","
        ).forEach { assertTrue("Swift lacks: $it\n$swift", swift.contains(it)) };
        listOf(
            "fun getAiAccountsState(workspaceId: String): SpfnCall<Unit, GetAiAccountsStateResponse>",
            "path = \"/state/aiAccounts\",",
            "query = listOf(\"workspaceId\" to workspaceId),",
            "fun getStates(workspaceId: String? = null): SpfnCall<Unit, GetStatesResponse>",
            "path = \"/state\",",
            "authProfile = \"$auth\","
        ).forEach { assertTrue("Kotlin lacks: $it\n$kotlin", kotlin.contains(it)) };
    }

    private fun proven(document: JsonValue.Obj, names: Set<String>): JsonValue.Obj
    {
        val operations = document.members.getValue("operations").arr().map { operation ->
            val members = (operation as JsonValue.Obj).members;
            if (members.getValue("name").text() !in names) operation
            else JsonValue.Obj(LinkedHashMap(members + ("auth" to JsonValue.Text("clientProofV1"))))
        };
        return JsonValue.Obj(LinkedHashMap(document.members + ("operations" to JsonValue.Arr(operations))));
    }

    private fun write(value: JsonValue): String = when (value)
    {
        is JsonValue.Obj -> value.members.entries.joinToString(",", "{", "}") { quoted(it.key) + ":" + write(it.value) }
        is JsonValue.Arr -> value.elements.joinToString(",", "[", "]") { write(it) }
        is JsonValue.Text -> quoted(value.value)
        is JsonValue.Number -> value.value.toString()
        is JsonValue.Fraction -> value.text
        is JsonValue.Bool -> value.value.toString()
        JsonValue.Null -> "null"
    }

    private fun quoted(text: String): String = text.toCharArray().joinToString("", "\"", "\"") { character ->
        when
        {
            character == '"' || character == '\\' -> "\\$character"
            character < ' ' -> "\\u%04x".format(character.code)
            else -> character.toString()
        }
    }
}
