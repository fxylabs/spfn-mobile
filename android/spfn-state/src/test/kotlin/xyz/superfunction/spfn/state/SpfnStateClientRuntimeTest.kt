// SPFN Mobile — the runner around the core, wired: the real SpfnStateClient over a scripted
// stream and a scripted `execute`, on the test scheduler.
//
// The case table (SpfnStateClientTest) holds the decisions; this suite holds what only the
// runner does — `current` answering at once from `initial`, a subscription's flow yielding the
// current entry first and a frame's change after, `close()` ending the flow and the wire
// listener, a write's answer typed by the generated decoders, one state client per stream.
// The descriptors are the generated StateFixtureStates: the codegen's output and the client
// compile and run together here.
//
// "A state without a write cannot be written" is a compile-time rule. This repository does
// not run the compiler on code that must fail (there is no compile-fail harness); it checks a
// compiler-enforced rule where the class file shows it, as spfn-ui's JavaVisibilityTest does
// (docs/IMPLEMENTATION-PITFALLS.md P15). `write_takesOnlyAWritableDescriptor` is that check.
// The line it stands for —
//
//     client.write(StateFixtureStates.aiAccounts, input)   // does not compile: not writable
//
// — is a type mismatch because `aiAccounts` is a SpfnStateDescriptor and `write` takes a
// SpfnWritableStateDescriptor. A Java caller is not held by generics, which is why the
// manifest's `write: false` is still refused at run time (I-11).

package xyz.superfunction.spfn.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.client.SpfnEventStream
import xyz.superfunction.spfn.client.SpfnEventStreamConfiguration
import xyz.superfunction.spfn.client.SpfnEventStreamState
import xyz.superfunction.spfn.client.SpfnIdleReason
import xyz.superfunction.spfn.client.SpfnKeyCustody
import xyz.superfunction.spfn.client.SpfnKeyLifecycle
import xyz.superfunction.spfn.client.SpfnKeyMetadataStore
import xyz.superfunction.spfn.client.SpfnKeystoreEngine
import xyz.superfunction.spfn.client.SpfnKeystoreGeneratedKey
import xyz.superfunction.spfn.client.SpfnStoredKeyMetadata
import xyz.superfunction.spfn.client.SpfnStreamConnected
import xyz.superfunction.spfn.client.SpfnTransport
import xyz.superfunction.spfn.client.SpfnTransportRequest
import xyz.superfunction.spfn.client.SpfnTransportResponse
import xyz.superfunction.spfn.client.SpfnWireSignal
import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.state.appstates.GetAiAccountsStateResponseValue
import xyz.superfunction.spfn.state.appstates.GetConsentStateResponseData
import xyz.superfunction.spfn.state.appstates.GetConsentStateResponseValue
import xyz.superfunction.spfn.state.appstates.PutConsentStateBody
import xyz.superfunction.spfn.state.appstates.StateFixtureStates

@OptIn(ExperimentalCoroutinesApi::class)
class SpfnStateClientRuntimeTest
{
    @Test
    fun subscribe_yieldsCurrentFirst_thenAFramesChange() = runTest()
    {
        val wired = Wired(this);
        assertEquals("current answers initial at once, before the engine ran", ready(T1, "pending", 1), wired.client.current(StateFixtureStates.consent));
        val entries = mutableListOf<SpfnStateEntry<GetConsentStateResponseValue, GetConsentStateResponseData>>();
        backgroundScope.launch { wired.client.subscribe(StateFixtureStates.consent).toList(entries) };
        runCurrent();
        assertEquals("the current entry first", listOf(ready(T1, "pending", 1)), entries);
        wired.open();
        wired.stream.send(SpfnWireSignal.Frame("state.consent.changed", consent(T2, "agreed", 2)));
        runCurrent();
        assertEquals("then the frame's change", listOf(ready(T1, "pending", 1), ready(T2, "agreed", 2)), entries);
        assertEquals(ready(T2, "agreed", 2), wired.client.current(StateFixtureStates.consent));
        assertEquals(SpfnStateConnection.Connected, wired.client.connection());
        assertEquals(SpfnStateConnection.Connected, wired.client.subscribeConnection().value);
        assertEquals("every state was served at open: no request", emptyList<String>(), wired.executor.sent);
    }

    @Test
    fun close_endsTheFlow_andDetachesFromTheStream() = runTest()
    {
        val wired = Wired(this);
        val collection = backgroundScope.launch { wired.client.subscribe(StateFixtureStates.consent).collect() };
        runCurrent();
        assertEquals(1, wired.stream.listeners);
        wired.client.close();
        runCurrent();
        assertTrue("the subscription's flow completed", collection.isCompleted);
        assertFalse("completed, not cancelled", collection.isCancelled);
        assertEquals("the wire listener is detached", 0, wired.stream.listeners);
        assertEquals(SpfnStateConnection.Reconnecting(0), wired.client.connection());
    }

    @Test
    fun write_appliesTheAnswer_typedByTheGeneratedDecoders() = runTest()
    {
        val wired = Wired(this);
        wired.executor.answers["putConsentState"] = consent(T2, "agreed", 2);
        val frame = wired.client.write(StateFixtureStates.consent, PutConsentStateBody(agreed = true, marketing = true));
        assertEquals(SpfnStateFrame(T2, GetConsentStateResponseValue.Agreed, GetConsentStateResponseData(marketing = true, version = 2)), frame);
        assertEquals("the answer is applied at once", SpfnStateEntry.Ready(frame), wired.client.current(StateFixtureStates.consent));
        assertEquals(listOf("putConsentState"), wired.executor.sent);
        assertEquals(PutConsentStateBody(agreed = true, marketing = true), wired.executor.requests.single());
    }

    @Test
    fun write_takesOnlyAWritableDescriptor()
    {
        val writable: SpfnWritableStateDescriptor<GetConsentStateResponseValue, GetConsentStateResponseData, PutConsentStateBody> = StateFixtureStates.consent;
        val readOnly: SpfnStateDescriptor<GetAiAccountsStateResponseValue, Unit> = StateFixtureStates.aiAccounts;
        assertEquals("consent", writable.id);
        assertEquals(listOf("workspaceId"), readOnly.keyFields);
        val write = SpfnStateClient::class.java.declaredMethods.single { it.name == "write" };
        assertSame("write's descriptor parameter is the writable type", SpfnWritableStateDescriptor::class.java, write.parameterTypes[0]);
        val states = StateFixtureStates::class.java;
        assertSame("a state without a write is generated as the read-only type", SpfnStateDescriptor::class.java, states.getMethod("getAiAccounts").returnType);
        assertSame("a state with a write as the writable one", SpfnWritableStateDescriptor::class.java, states.getMethod("getConsent").returnType);
    }

    @Test
    fun construction_refusesASecondClientOrAMissingEvent() = runTest()
    {
        val wired = Wired(this);
        val second = assertThrows(IllegalArgumentException::class.java) { wired.client(wired.stream) };
        assertTrue(second.message.orEmpty().contains("already has a state client"));
        val narrow = FakeStateStream(SpfnEventStreamConfiguration(events = listOf("state.consent.changed")));
        val missing = assertThrows(IllegalArgumentException::class.java) { wired.client(narrow) };
        assertTrue(missing.message.orEmpty().contains("state.aiAccounts.changed"));
    }

    /**
     * W-20 (state-client-design.md §2-14, D5): the key lifecycle's observer has moved the
     * cache to B while `Subject(B)` still waits in the inbox. `current` answers `Loading` at
     * once, and an entry the core still makes for A reaches no subscriber and not `current`.
     */
    @Test
    fun w20_observerAhead_previousAccountsEntriesHandedToNobody() = runTest()
    {
        val wired = Wired(this);
        val stream = FakeStateStream(SpfnEventStreamConfiguration(events = StateFixtureStates.events));
        val configuration = wired.client.configuration;
        val core = SpfnStateCore(
            SpfnStateCore.Configuration(configuration.accesses(stream.configuration), configuration.snapshot, 0, 0),
            configuration.initial
        );
        val shared = SpfnStateShared();
        val services = SpfnStateServices(stream, emptyList(), ScriptedExecutor(), { awaitCancellation() }, {}, {}, backgroundScope);
        val engine = SpfnStateEngine(core, services, shared, Channel(Channel.UNLIMITED));
        val heard = mutableListOf<SpfnStateCell?>();
        shared.signedIn("client-a");
        engine.process(SpfnStateEngineInput.Core(SpfnStateInput.Subject("client-a")));
        engine.process(SpfnStateEngineInput.Subscribe(1, CONSENT, SpfnStateSink(deliver = { heard.add(it) }, finish = {})));
        val opened = SpfnStreamConnected(1, emptyMap(), StateFixtureStates.events, emptyList(), StateFixtureStates.events, "client-a");
        engine.process(SpfnStateEngineInput.Core(SpfnStateInput.Wire(SpfnWireSignal.Opened(opened))));
        assertEquals(listOf(ready(T1, "pending", 1)), heard.map { SpfnStateShared.entry<GetConsentStateResponseValue, GetConsentStateResponseData>(it) });

        shared.signedIn("client-b");
        assertNull("current answers Loading as soon as the store holds B", shared.cell(CONSENT));
        engine.process(SpfnStateEngineInput.Core(SpfnStateInput.Wire(SpfnWireSignal.Frame("state.consent.changed", consent(T2, "agreed", 2)))));
        assertEquals("A's frame, applied by a core still behind, reaches no subscriber", 1, heard.size);
        assertNull("nor current", shared.cell(CONSENT));

        engine.process(SpfnStateEngineInput.Core(SpfnStateInput.Subject("client-b")));
        assertEquals("the core caught up: the subscription is told Loading", listOf(ready(T1, "pending", 1), SpfnStateEntry.Loading), heard.map { SpfnStateShared.entry<GetConsentStateResponseValue, GetConsentStateResponseData>(it) });
        assertNull(shared.cell(CONSENT));
    }

    /**
     * W-21 (§2-14, D5): the client observes the signed-in id from construction — the id then
     * is the baseline and clears nothing; a wipe right after clears before it returns — and
     * stops when its scope ends.
     */
    @Test
    fun w21_client_observesSignedInFromConstructionUntilReleased() = runTest()
    {
        val lifecycle = signedInLifecycle("client-a");
        val wired = Wired(this, lifecycle);
        assertEquals("the id at construction is the baseline", ready(T1, "pending", 1), wired.client.current(StateFixtureStates.consent));
        lifecycle.wipe();
        assertEquals("observed since construction, cleared inside wipe()", SpfnStateEntry.Loading, wired.client.current(StateFixtureStates.consent));

        val other = signedInLifecycle("client-a");
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job());
        val shared = Wired(this, other, scope).client.shared;
        runCurrent();
        scope.cancel();
        runCurrent();
        other.wipe();
        assertEquals("a released client's observer is gone", 0, shared.epoch);
        assertNotNull(shared.cell(CONSENT));
    }

    /** One client over a scripted stream and `execute`, started from a snapshot with `consent`. */
    private class Wired(
        private val test: TestScope,
        lifecycle: SpfnKeyLifecycle = signedOutLifecycle(),
        private val scope: CoroutineScope = test.backgroundScope
    )
    {
        val stream = FakeStateStream(SpfnEventStreamConfiguration(events = StateFixtureStates.events));
        val executor = ScriptedExecutor();
        val errors = mutableListOf<Throwable>();
        private val configuration = SpfnStateClientConfiguration(
            stream = SpfnEventStream(signedOutLifecycle(), SpfnEventStreamConfiguration(events = StateFixtureStates.events), scope = test.backgroundScope),
            keyLifecycle = lifecycle,
            states = StateFixtureStates.all,
            snapshot = StateFixtureStates.snapshot,
            onError = { errors.add(it) },
            initial = SpfnStateInitial(SpfnStatesSnapshot.decode(snapshot(T1, mapOf("consent" to consent(T1, "pending", 1)))))
        );
        val client = client(stream);

        fun client(on: SpfnStateStream): SpfnStateClient =
            SpfnStateClient(configuration, scope, on, executor, { awaitCancellation() }, { work -> scope.launch { work() } });

        /** Connection 1 opens and serves every state in its initial frames. */
        fun open()
        {
            stream.state.value = SpfnEventStreamState.Open(1);
            stream.send(SpfnWireSignal.Opened(SpfnStreamConnected(1, emptyMap(), StateFixtureStates.events, emptyList(), StateFixtureStates.events)));
        }
    }

    private class FakeStateStream(override val configuration: SpfnEventStreamConfiguration) : SpfnStateStream
    {
        val state = MutableStateFlow<SpfnEventStreamState>(SpfnEventStreamState.Idle(SpfnIdleReason.SIGNED_OUT));
        private val wire = MutableSharedFlow<SpfnWireSignal>(extraBufferCapacity = 64);
        var listeners = 0;
        val queries = mutableListOf<Map<String, String>>();

        override val owner: Any
            get() = this;

        override val states: Flow<SpfnEventStreamState>
            get() = state;

        override fun listenWire(names: List<String>): Flow<SpfnWireSignal> = flow()
        {
            listeners += 1;
            try
            {
                emitAll(wire);
            }
            finally
            {
                listeners -= 1;
            }
        };

        fun send(signal: SpfnWireSignal)
        {
            check(wire.tryEmit(signal));
        }

        override fun setQuery(fields: Map<String, String>)
        {
            queries.add(fields);
        }

        override fun reconnectNow()
        {
        }
    }

    /** Answers each operation id with its scripted canonical value; nothing scripted is a network failure. */
    private class ScriptedExecutor : SpfnStateExecutor
    {
        val answers = mutableMapOf<String, SpfnCanonicalValue>();
        val sent = mutableListOf<String>();
        val requests = mutableListOf<Any?>();

        override suspend fun <Req, Resp> execute(call: SpfnCall<Req, Resp>, request: Req): Resp
        {
            sent.add(call.operation.id);
            requests.add(request);
            return call.decode(answers[call.operation.id] ?: throw StateTestNetworkError());
        }
    }

    private companion object
    {
        const val T1 = "2026-10-17T09:00:00.001Z";
        const val T2 = "2026-10-17T09:00:00.002Z";
        val CONSENT = SpfnStateSlot("consent", emptyMap());

        fun ready(at: String, value: String, version: Long): SpfnStateEntry<GetConsentStateResponseValue, GetConsentStateResponseData> =
            SpfnStateEntry.Ready(SpfnStateFrame(at, GetConsentStateResponseValue.of(value), GetConsentStateResponseData(marketing = version > 1, version = version)));

        fun consent(at: String, value: String, version: Long): SpfnCanonicalValue =
            SpfnCanonicalValue.Obj(
                mapOf(
                    "at" to text(at),
                    "value" to text(value),
                    "data" to SpfnCanonicalValue.Obj(mapOf("marketing" to SpfnCanonicalValue.Bool(version > 1), "version" to SpfnCanonicalValue.Integer(version)))
                )
            );

        fun snapshot(at: String, states: Map<String, SpfnCanonicalValue>): SpfnCanonicalValue =
            SpfnCanonicalValue.Obj(
                mapOf(
                    "at" to text(at),
                    "states" to SpfnCanonicalValue.Obj(states),
                    "manifest" to SpfnCanonicalValue.Obj(
                        mapOf(
                            "aiAccounts" to SpfnCanonicalValue.Obj(mapOf("key" to texts(listOf("workspaceId")), "write" to SpfnCanonicalValue.Bool(false))),
                            "consent" to SpfnCanonicalValue.Obj(mapOf("key" to texts(emptyList()), "write" to SpfnCanonicalValue.Bool(true)))
                        )
                    )
                )
            );

        /** Nobody signed in, nothing reachable: the configuration's stream is never put in the foreground. */
        fun signedOutLifecycle(): SpfnKeyLifecycle = lifecycle(null);

        /** One active key for [clientId], until a wipe deletes it; nothing reachable. */
        fun signedInLifecycle(clientId: String): SpfnKeyLifecycle = lifecycle(clientId);

        private fun lifecycle(clientId: String?): SpfnKeyLifecycle = SpfnKeyLifecycle(
            transport = object : SpfnTransport
            {
                override suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse = error("not in this suite");
            },
            store = object : SpfnKeyMetadataStore
            {
                private var active = clientId?.let { SpfnStoredKeyMetadata("key-test-0001", it, SpfnKeyCustody.TRUSTED_ENVIRONMENT, 0, "alias-test-0001") };

                override fun load(slot: String): SpfnStoredKeyMetadata? = if (slot == SpfnKeyLifecycle.ACTIVE_SLOT) active else null;

                override fun save(slot: String, metadata: SpfnStoredKeyMetadata) = error("not in this suite");

                override fun delete(slot: String)
                {
                    if (slot == SpfnKeyLifecycle.ACTIVE_SLOT)
                    {
                        active = null;
                    }
                }
            },
            engine = object : SpfnKeystoreEngine
            {
                override fun generate(alias: String, preferStrongBox: Boolean): SpfnKeystoreGeneratedKey = error("not in this suite");

                override fun publicKeySpkiDer(alias: String): ByteArray? = null;

                override fun signDer(alias: String, message: ByteArray): ByteArray = error("not in this suite");

                override fun contains(alias: String): Boolean = false;

                override fun delete(alias: String)
                {
                }
            },
            baseUrl = "https://example.test"
        );
    }
}
