// State descriptors from an app contract document, one test per rule, both platforms.
//
// docs/architecture/state-client-design.md §2-13-2 is the rule. Each test builds an invented
// state router — a `getStates` snapshot and the read (and write) of each state it names —
// generates both states files from it, and holds them to the rule: the text it promises, or
// the refusal it names. The generated fixture is compiled and run by the state module's own
// tests (Tests/SPFNStateTests/AppStatesFixture, spfn-state appstates).

package xyz.superfunction.spfn.codegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Builds state routers and reads what the two states emitters make of them. */
object StateDocuments
{
    const val STRING = """{"type":"string"}""";

    val ENUM_VALUE = """{"anyOf":[{"const":"pending","type":"string"},{"const":"agreed","type":"string"}]}""";

    val OBJECT_DATA = AppDocuments.object_(""""marketing":{"type":"boolean"}""", "\"marketing\"");

    val KEY_QUERY = """{"query":${AppDocuments.object_(""""workspaceId":$STRING""", "\"workspaceId\"")}}""";

    val SNAPSHOT_QUERY = """{"query":{"type":"object","properties":{"workspaceId":$STRING}}}""";

    /** One state as its router declares it. */
    data class State(
        val id: String,
        val value: String = ENUM_VALUE,
        val data: String? = null,
        val keyed: Boolean = false,
        val writable: Boolean = false
    )
    {
        val pascal: String get() = id.replaceFirstChar { it.uppercaseChar() }

        val response: String get() = AppDocuments.object_(fields(""""at":$STRING"""), names("\"at\""))

        val entry: String get() = AppDocuments.object_(fields(""), names(""))

        private fun fields(first: String): String =
            listOfNotNull(first.ifEmpty { null }, """"value":$value""", data?.let { """"data":$it""" }).joinToString(",")

        private fun names(first: String): String =
            listOfNotNull(first.ifEmpty { null }, "\"value\"", data?.let { "\"data\"" }).joinToString(",")
    }

    fun snapshot(states: List<State>, request: String = SNAPSHOT_QUERY): String
    {
        val manifestEntry = AppDocuments.object_(""""key":{"type":"array","items":$STRING},"write":{"type":"boolean"}""", "\"key\",\"write\"");
        val entries = states.joinToString(",") { "\"${it.id}\":${it.entry}" };
        val manifest = states.joinToString(",") { "\"${it.id}\":$manifestEntry" };
        val response = AppDocuments.object_(
            """"at":$STRING,"states":{"type":"object","properties":{$entries}},"manifest":${AppDocuments.object_(manifest, "")}""",
            "\"at\",\"states\",\"manifest\""
        );
        return AppDocuments.operation(name = "getStates", path = "/state", request = request, response = response, auth = "clientProofV1");
    }

    fun read(state: State, response: String = state.response, method: String = "GET", request: String = if (state.keyed) KEY_QUERY else "{}"): String =
        AppDocuments.operation(
            name = "get${state.pascal}State", path = "/state/${state.id}", method = method, request = request,
            response = response, auth = "clientProofV1"
        )

    fun write(
        state: State,
        response: String = state.response,
        method: String = "PUT",
        body: String? = AppDocuments.object_(""""agreed":{"type":"boolean"}""", "\"agreed\""),
        query: String? = if (state.keyed) AppDocuments.object_(""""workspaceId":$STRING""", "\"workspaceId\"") else null
    ): String
    {
        val request = listOfNotNull(query?.let { "\"query\":$it" }, body?.let { "\"body\":$it" }).joinToString(",", "{", "}");
        return AppDocuments.operation(
            name = "put${state.pascal}State", path = "/state/${state.id}", method = method, request = request,
            response = response, auth = "clientProofV1"
        );
    }

    /** The snapshot, then each state's read and (when writable) write. */
    fun router(vararg states: State): String =
        AppDocuments.document(snapshot(states.toList()), *states.flatMap { state ->
            listOfNotNull(read(state), if (state.writable) write(state) else null)
        }.toTypedArray())

    val SNAPSHOT = AppDocuments.selection("getStates");

    fun swift(document: String, selection: String = SNAPSHOT): String =
        AppSwiftEmitter(AppDocuments.read(document, selection), "TestAPI", "TestStates").emit().getValue("TestStates.swift")

    fun kotlin(document: String, selection: String = SNAPSHOT): String =
        AppKotlinEmitter(AppDocuments.read(document, selection), "TestAPI", "test.api", "TestStates").emit().getValue("TestStates.kt")
}

class AppStatesTableTest
{
    private fun assertBoth(document: String, swift: List<String>, kotlin: List<String>)
    {
        val swiftText = StateDocuments.swift(document);
        val kotlinText = StateDocuments.kotlin(document);
        swift.forEach { assertTrue("Swift lacks: $it\n$swiftText", swiftText.contains(it)) };
        kotlin.forEach { assertTrue("Kotlin lacks: $it\n$kotlinText", kotlinText.contains(it)) };
    }

    private fun assertRefused(document: String, vararg fragments: String)
    {
        val message = AppDocuments.refusal(document, StateDocuments.SNAPSHOT);
        fragments.forEach { assertTrue("the refusal does not say '$it': $message", message.contains(it)) };
    }

    private val consent = StateDocuments.State("consent", data = StateDocuments.OBJECT_DATA, writable = true);

    private val aiAccounts = StateDocuments.State(
        "aiAccounts", value = AppDocuments.object_(""""connected":{"type":"boolean"}""", "\"connected\""), keyed = true
    );

    // ---- outputs ------------------------------------------------------------

    @Test
    fun `a keyless state reads with no key and declares no keyFields`()
    {
        val document = StateDocuments.router(StateDocuments.State("theme"));
        assertBoth(
            document,
            listOf(
                "import SPFNCore\nimport SPFNState\n",
                "public enum TestStates\n{\n    public static let theme = SPFNStateDescriptor(\n        id: \"theme\",\n" +
                    "        read: { _ in TestAPI.getThemeState() },\n" +
                    "        frame: { SPFNStateFrame(at: \$0.at, value: \$0.value, data: SPFNNoStateData()) }\n    )\n"
            ),
            listOf(
                "import xyz.superfunction.spfn.core.SpfnCall\nimport xyz.superfunction.spfn.state.SpfnStateDescriptor\n" +
                    "import xyz.superfunction.spfn.state.SpfnStateFrame\nimport xyz.superfunction.spfn.state.SpfnStateKey\n",
                "object TestStates\n{\n    val theme = SpfnStateDescriptor(\n        id = \"theme\",\n" +
                    "        read = { TestAPI.getThemeState() },\n        frame = { SpfnStateFrame(it.at, it.value, Unit) },\n    );\n"
            )
        );
        assertFalse(StateDocuments.swift(document).contains("keyFields"));
        assertFalse(StateDocuments.kotlin(document).contains("keyFields"));
    }

    @Test
    fun `a keyed state passes every key field to its read, in name order`()
    {
        val twoKeys = """{"query":${AppDocuments.object_(""""workspaceId":${StateDocuments.STRING},"projectId":${StateDocuments.STRING}""", "\"workspaceId\",\"projectId\"")}}""";
        val state = StateDocuments.State("board", keyed = true);
        val snapshotQuery = """{"query":{"type":"object","properties":{"projectId":${StateDocuments.STRING},"workspaceId":${StateDocuments.STRING}}}}""";
        assertBoth(
            AppDocuments.document(StateDocuments.snapshot(listOf(state), snapshotQuery), StateDocuments.read(state, request = twoKeys)),
            listOf(
                "        keyFields: [\"projectId\", \"workspaceId\"],\n",
                "        read: { key in TestAPI.getBoardState(projectId: key[\"projectId\"]!, workspaceId: key[\"workspaceId\"]!) },\n"
            ),
            listOf(
                "        keyFields = listOf(\"projectId\", \"workspaceId\"),\n",
                "        read = { key -> TestAPI.getBoardState(projectId = key.getValue(\"projectId\"), workspaceId = key.getValue(\"workspaceId\")) },\n"
            )
        );
    }

    /**
     * The write takes no frame of its own: `put<Id>State` answers its own generated type, and the
     * read's decoder reads the answer — the recognizer proved the two response schemas equal.
     */
    @Test
    fun `a writable state adds its write with the same key`()
    {
        val keyedWritable = aiAccounts.copy(writable = true);
        assertBoth(
            StateDocuments.router(consent, keyedWritable),
            listOf(
                "        frame: { SPFNStateFrame(at: \$0.at, value: \$0.value, data: \$0.data) }\n" +
                    "    ).writable(write: { _ in TestAPI.putConsentState() })\n",
                "    ).writable(write: { key in TestAPI.putAiAccountsState(workspaceId: key[\"workspaceId\"]!) })\n"
            ),
            listOf(
                "        frame = { SpfnStateFrame(it.at, it.value, it.data) },\n" +
                    "    ).writable(write = { TestAPI.putConsentState() });\n",
                "    ).writable(write = { key -> TestAPI.putAiAccountsState(workspaceId = key.getValue(\"workspaceId\")) });\n"
            )
        );
    }

    @Test
    fun `a state with data frames its data, a state without frames the no-data value`()
    {
        assertBoth(
            StateDocuments.router(consent.copy(writable = false), aiAccounts),
            listOf(
                "        read: { _ in TestAPI.getConsentState() },\n        frame: { SPFNStateFrame(at: \$0.at, value: \$0.value, data: \$0.data) }\n",
                "        frame: { SPFNStateFrame(at: \$0.at, value: \$0.value, data: SPFNNoStateData()) }\n"
            ),
            listOf(
                "        read = { TestAPI.getConsentState() },\n        frame = { SpfnStateFrame(it.at, it.value, it.data) },\n",
                "        frame = { SpfnStateFrame(it.at, it.value, Unit) },\n"
            )
        );
    }

    @Test
    fun `all lists every state in id order, not document order`()
    {
        val document = StateDocuments.router(consent, aiAccounts);
        assertBoth(
            document,
            listOf("    public static let all: [any SPFNAnyStateDescriptor] = [aiAccounts, consent]\n"),
            listOf("    val all: List<SpfnStateDescriptor<*, *>> = listOf(aiAccounts, consent);\n")
        );
        val swift = StateDocuments.swift(document);
        assertTrue(swift.indexOf("static let aiAccounts") < swift.indexOf("static let consent"));
    }

    /** Only `state.<id>.changed`, in id order: the document's top-level `events` section stays unread. */
    @Test
    fun `events names each state's changed event only, in id order`()
    {
        val plain = StateDocuments.router(consent, aiAccounts);
        val withEvents = plain.replace(
            "{\"compat", "{\"events\":{\"auth\":\"tokenExchange\",\"items\":[{\"name\":\"item.created\",\"payload\":{}}]},\"compat"
        );
        assertBoth(
            withEvents,
            listOf("    public static let events: [String] = [\"state.aiAccounts.changed\", \"state.consent.changed\"]\n"),
            listOf("    val events: List<String> = listOf(\"state.aiAccounts.changed\", \"state.consent.changed\");\n")
        );
        assertEquals(StateDocuments.swift(plain), StateDocuments.swift(withEvents));
        assertEquals(StateDocuments.kotlin(plain), StateDocuments.kotlin(withEvents));
    }

    @Test
    fun `snapshot passes the getStates query fields as optional named arguments`()
    {
        assertBoth(
            StateDocuments.router(consent, aiAccounts),
            listOf(
                "    public static let snapshot: @Sendable (SPFNStateKey) -> SPFNCall<Void, TestAPI.GetStatesResponse> =\n" +
                    "        { query in TestAPI.getStates(workspaceId: query[\"workspaceId\"]) }\n}\n"
            ),
            listOf(
                "    val snapshot: (SpfnStateKey) -> SpfnCall<Unit, GetStatesResponse> =\n" +
                    "        { query -> TestAPI.getStates(workspaceId = query[\"workspaceId\"]) };\n}\n"
            )
        );
        val keyless = AppDocuments.document(StateDocuments.snapshot(listOf(consent), "{}"), StateDocuments.read(consent), StateDocuments.write(consent));
        assertBoth(keyless, listOf("        { _ in TestAPI.getStates() }\n"), listOf("        { TestAPI.getStates() };\n"));
    }

    @Test
    fun `selecting getStates pulls in each state's read and write, and listing them too is no error`()
    {
        val document = StateDocuments.router(consent, aiAccounts);
        val pulled = AppDocuments.read(document, StateDocuments.SNAPSHOT);
        val listed = AppDocuments.read(document, AppDocuments.selection("putConsentState", "getStates", "getConsentState"));
        val expected = listOf("getAiAccountsState", "getConsentState", "getStates", "putConsentState");
        assertEquals(expected, pulled.operationNames);
        assertEquals(expected, listed.operationNames);
        assertTrue(AppHeader.lines(pulled).contains("operations:      ${expected.joinToString(", ")}"));
        assertEquals(StateDocuments.swift(document), StateDocuments.swift(document, AppDocuments.selection("getConsentState", "getStates")));
    }

    /** No anchor, nothing recognised: the reads are ordinary calls, and the calls file says why. */
    @Test
    fun `without getStates nothing is a state and the calls file says so`()
    {
        val document = StateDocuments.router(consent, aiAccounts);
        val selection = AppDocuments.selection("getConsentState", "putConsentState");
        val contract = AppDocuments.read(document, selection);
        assertEquals(null, contract.states);
        val swift = AppSwiftEmitter(contract, "TestAPI", "TestStates").emit();
        val kotlin = AppKotlinEmitter(contract, "TestAPI", "test.api", "TestStates").emit();
        assertEquals(setOf("TestAPI.swift", "TestAPICalls.swift", "TestAPITypes.swift"), swift.keys);
        assertEquals(setOf("TestAPICalls.kt", "TestAPISupport.kt", "TestAPITypes.kt"), kotlin.keys);
        val comment = "// No state descriptors are generated: the selection names getConsentState, putConsentState\n" +
            "// but not getStates, the snapshot states are recognised from. They are ordinary calls.\n";
        assertTrue(swift.getValue("TestAPICalls.swift"), swift.getValue("TestAPICalls.swift").contains("import SPFNCore\n\n$comment\nextension TestAPI"));
        assertTrue(kotlin.getValue("TestAPICalls.kt"), kotlin.getValue("TestAPICalls.kt").contains("\n\n$comment\n/**"));
        val unrelated = AppDocuments.swift(AppDocuments.document(AppDocuments.operation()));
        assertFalse(unrelated.contains("No state descriptors"));
    }

    @Test
    fun `the states namespace is required with getStates and refused without it`()
    {
        fun refused(fragment: String, contract: AppContract, statesNamespace: String?)
        {
            try
            {
                AppContractGeneration.checkStatesNamespace(contract, "TestAPI", statesNamespace);
                fail("accepted a states namespace the rule refuses");
            }
            catch (refusal: JsonException)
            {
                assertTrue(refusal.message, refusal.message!!.contains(fragment));
            }
        }
        val document = StateDocuments.router(consent);
        val anchored = AppDocuments.read(document, StateDocuments.SNAPSHOT);
        val plain = AppDocuments.read(document, AppDocuments.selection("getConsentState"));
        refused("name them with -Pspfn.appContract.statesNamespace", anchored, null);
        refused("-Pspfn.appContract.statesNamespace (--states-namespace) 'TestStates' is given", plain, "TestStates");
        refused("collides with a generated file or type", anchored, "TestAPICalls");
        refused("collides with a generated file or type", anchored, "GetStatesResponse");
        AppContractGeneration.checkStatesNamespace(anchored, "TestAPI", "TestStates");
        AppContractGeneration.checkStatesNamespace(plain, "TestAPI", null);
        val arguments = arrayOf(
            "--mode", "verify", "--document", "/d", "--operations", "/o", "--swift-out", "/s", "--swift-namespace", "API",
            "--kotlin-out", "/k", "--kotlin-package", "a.b", "--sdk-root", "/r"
        );
        assertEquals(null, AppContractOptions.parse(arguments).statesNamespace);
        assertEquals("AppStates", AppContractOptions.parse(arguments + arrayOf("--states-namespace", "AppStates")).statesNamespace);
    }

    // ---- refusals -------------------------------------------------------------

    @Test
    fun `R9 a state id that is a generated member is refused`()
    {
        listOf("all", "events", "snapshot").forEach { id ->
            assertRefused(StateDocuments.router(StateDocuments.State(id)), "state id '$id' collides", "(R9)");
        };
    }

    @Test
    fun `a state id outside spfn's rule is refused`()
    {
        assertRefused(StateDocuments.router(StateDocuments.State("Consent")), "state id 'Consent' is not ^[a-z][a-zA-Z0-9]*$");
        assertRefused(StateDocuments.router(StateDocuments.State("my_state")), "state id 'my_state' is not");
    }

    @Test
    fun `R1 a state value or data the table cannot hold is refused naming the state`()
    {
        assertRefused(StateDocuments.router(StateDocuments.State("consent", value = "{}")), "state 'consent': getConsentState.response.value", "(R1)");
        assertRefused(StateDocuments.router(StateDocuments.State("consent", data = "{}")), "state 'consent': getConsentState.response.data", "(R1)");
    }

    @Test
    fun `T3 a fractional state value is refused naming the state`()
    {
        assertRefused(StateDocuments.router(StateDocuments.State("score", value = """{"type":"number"}""")), "state 'score': getScoreState.response.value", "(T3)");
    }

    @Test
    fun `R3 an object-union state value is refused naming the state`()
    {
        val union = """{"anyOf":[${AppDocuments.object_(""""a":{"type":"string"}""", "\"a\"")},${AppDocuments.object_(""""b":{"type":"string"}""", "\"b\"")}]}""";
        assertRefused(StateDocuments.router(StateDocuments.State("shape", value = union)), "state 'shape': getShapeState.response.value", "(R3)");
    }

    @Test
    fun `a state without its read is refused`()
    {
        assertRefused(
            AppDocuments.document(StateDocuments.snapshot(listOf(consent, aiAccounts)), StateDocuments.read(consent), StateDocuments.write(consent)),
            "state 'aiAccounts': getStates names it, but the document declares no getAiAccountsState"
        );
    }

    @Test
    fun `a read that is not a GET is refused`()
    {
        val state = StateDocuments.State("theme");
        assertRefused(
            AppDocuments.document(StateDocuments.snapshot(listOf(state)), StateDocuments.read(state, method = "POST")),
            "state 'theme': getThemeState is not a GET without params or body"
        );
    }

    @Test
    fun `a read response other than at, value and data is refused`()
    {
        val state = StateDocuments.State("theme");
        fun withResponse(response: String) = AppDocuments.document(StateDocuments.snapshot(listOf(state)), StateDocuments.read(state, response = response));
        val shape = "state 'theme': getThemeState response is not exactly { at: string, value, data? }";
        val value = """"value":${StateDocuments.ENUM_VALUE}""";
        assertRefused(withResponse(AppDocuments.object_(value, "\"value\"")), shape);
        assertRefused(withResponse(AppDocuments.object_(""""at":{"type":"integer"},$value""", "\"at\",\"value\"")), shape);
        assertRefused(withResponse(AppDocuments.object_(""""at":${StateDocuments.STRING},$value,"extra":${StateDocuments.STRING}""", "\"at\",\"value\",\"extra\"")), shape);
        assertRefused(withResponse(AppDocuments.object_(""""at":${StateDocuments.STRING},$value""", "\"at\"")), shape);
    }

    @Test
    fun `a read value that differs from the snapshot's is refused`()
    {
        val state = StateDocuments.State("theme");
        val other = state.copy(value = """{"anyOf":[{"const":"light","type":"string"},{"const":"dark","type":"string"}]}""");
        assertRefused(
            AppDocuments.document(StateDocuments.snapshot(listOf(state)), StateDocuments.read(other)),
            "state 'theme': the value schema of getThemeState differs from getStates' states.theme.value"
        );
    }

    @Test
    fun `a read key field that is not a required string is refused`()
    {
        val state = StateDocuments.State("board", keyed = true);
        val optional = """{"query":{"type":"object","properties":{"workspaceId":${StateDocuments.STRING}}}}""";
        assertRefused(
            AppDocuments.document(StateDocuments.snapshot(listOf(state)), StateDocuments.read(state, request = optional)),
            "state 'board': getBoardState query field 'workspaceId' is not a required string"
        );
    }

    @Test
    fun `a key field missing from the snapshot's query is refused`()
    {
        assertRefused(
            AppDocuments.document(StateDocuments.snapshot(listOf(aiAccounts), "{}"), StateDocuments.read(aiAccounts)),
            "state 'aiAccounts': key field(s) workspaceId are not in getStates' query"
        );
    }

    @Test
    fun `a write that is not a PUT is refused`()
    {
        assertRefused(
            AppDocuments.document(StateDocuments.snapshot(listOf(consent)), StateDocuments.read(consent), StateDocuments.write(consent, method = "POST")),
            "state 'consent': putConsentState is not a PUT"
        );
    }

    @Test
    fun `a write query that differs from the read's is refused`()
    {
        assertRefused(
            AppDocuments.document(StateDocuments.snapshot(listOf(aiAccounts)), StateDocuments.read(aiAccounts), StateDocuments.write(aiAccounts, query = null)),
            "state 'aiAccounts': putAiAccountsState query differs from getAiAccountsState's"
        );
    }

    @Test
    fun `a write response that differs from the read's is refused`()
    {
        val other = consent.copy(data = null).response;
        assertRefused(
            AppDocuments.document(StateDocuments.snapshot(listOf(consent)), StateDocuments.read(consent), StateDocuments.write(consent, response = other)),
            "state 'consent': putConsentState response differs from getConsentState's"
        );
    }

    @Test
    fun `T16 a write body that is not an object is refused`()
    {
        assertRefused(
            AppDocuments.document(StateDocuments.snapshot(listOf(consent)), StateDocuments.read(consent), StateDocuments.write(consent, body = null)),
            "state 'consent': putConsentState body is not an object (T16)"
        );
        assertRefused(
            AppDocuments.document(StateDocuments.snapshot(listOf(consent)), StateDocuments.read(consent), StateDocuments.write(consent, body = StateDocuments.STRING)),
            "state 'consent': putConsentState body is not an object (T16)"
        );
    }

    @Test
    fun `a getStates that is not a state snapshot is refused`()
    {
        assertRefused(
            AppDocuments.document(AppDocuments.operation(name = "getStates", path = "/state")),
            "operation 'getStates' is not a state snapshot"
        );
        val mismatched = StateDocuments.router(consent).replace(
            "\"manifest\":{\"type\":\"object\",\"properties\":{\"consent\"", "\"manifest\":{\"type\":\"object\",\"properties\":{\"other\""
        );
        assertRefused(mismatched, "operation 'getStates' names states [consent] but a manifest of [other]");
    }

    @Test
    fun `a getStates query field that is not an optional string is refused`()
    {
        val required = """{"query":${AppDocuments.object_(""""workspaceId":${StateDocuments.STRING}""", "\"workspaceId\"")}}""";
        assertRefused(
            AppDocuments.document(StateDocuments.snapshot(listOf(aiAccounts), required), StateDocuments.read(aiAccounts)),
            "operation 'getStates' query field 'workspaceId' is not an optional string"
        );
    }

    // ---- Superself ------------------------------------------------------------

    /**
     * Superself's `contracts/current.json` with its state routes proven (`clientProofV1`) and
     * carrying their key as a query (P-a, P-c), copied verbatim. Selecting `getStates` alone
     * generates all four states; selecting all 37 operations would stop at R1 on
     * `deleteHarnessSetting`, which is that app's to change.
     */
    @Test
    fun `the Superself document generates its four states from getStates alone`()
    {
        val document = File("src/test/resources/app-contract/superself-current-states.json").readText();
        val contract = AppDocuments.read(document, StateDocuments.SNAPSHOT);
        assertEquals(
            listOf("getAiAccountsState", "getConsentState", "getMemberState", "getStates", "getWorkspacesState", "putConsentState"),
            contract.operationNames
        );
        val swift = AppSwiftEmitter(contract, "AppAPI", "AppStates").emit().getValue("AppStates.swift");
        val kotlin = AppKotlinEmitter(contract, "AppAPI", "com.example.api", "AppStates").emit().getValue("AppStates.kt");
        listOf(
            "public enum AppStates\n",
            "    public static let consent = SPFNStateDescriptor(\n        id: \"consent\",\n        read: { _ in AppAPI.getConsentState() },\n",
            "    ).writable(write: { _ in AppAPI.putConsentState() })\n",
            "        id: \"aiAccounts\",\n        keyFields: [\"workspaceId\"],\n        read: { key in AppAPI.getAiAccountsState(workspaceId: key[\"workspaceId\"]!) },\n",
            "        id: \"member\",\n        keyFields: [\"workspaceId\"],\n",
            "        id: \"workspaces\",\n        read: { _ in AppAPI.getWorkspacesState() },\n",
            "    public static let all: [any SPFNAnyStateDescriptor] = [aiAccounts, consent, member, workspaces]\n",
            "    public static let events: [String] = [\"state.aiAccounts.changed\", \"state.consent.changed\", \"state.member.changed\", \"state.workspaces.changed\"]\n",
            "    public static let snapshot: @Sendable (SPFNStateKey) -> SPFNCall<Void, AppAPI.GetStatesResponse> =\n" +
                "        { query in AppAPI.getStates(workspaceId: query[\"workspaceId\"]) }\n"
        ).forEach { assertTrue("Swift lacks: $it\n$swift", swift.contains(it)) };
        listOf(
            "object AppStates\n",
            "    val consent = SpfnStateDescriptor(\n        id = \"consent\",\n        read = { AppAPI.getConsentState() },\n",
            "    ).writable(write = { AppAPI.putConsentState() });\n",
            "        read = { key -> AppAPI.getAiAccountsState(workspaceId = key.getValue(\"workspaceId\")) },\n",
            "        read = { key -> AppAPI.getMemberState(workspaceId = key.getValue(\"workspaceId\")) },\n",
            "        read = { AppAPI.getWorkspacesState() },\n",
            "    val all: List<SpfnStateDescriptor<*, *>> = listOf(aiAccounts, consent, member, workspaces);\n",
            "    val events: List<String> = listOf(\"state.aiAccounts.changed\", \"state.consent.changed\", \"state.member.changed\", \"state.workspaces.changed\");\n",
            "    val snapshot: (SpfnStateKey) -> SpfnCall<Unit, GetStatesResponse> =\n" +
                "        { query -> AppAPI.getStates(workspaceId = query[\"workspaceId\"]) };\n"
        ).forEach { assertTrue("Kotlin lacks: $it\n$kotlin", kotlin.contains(it)) };
    }
}
