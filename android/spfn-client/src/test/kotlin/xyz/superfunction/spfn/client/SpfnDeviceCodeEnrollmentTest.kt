// SPFN Mobile — device-code enrollment (M8), one test per cell of the D table.
//
// The table is closed: D1–D20 are every state and every answer the waiting side of the
// contract's `deviceAuthorization` flow can meet, and each test is named after its cell.
// What the flow sends is compared against Contracts/fixtures/enrollment/enrollment.json,
// which a third implementation derived from the contract text (P10) — never against what
// the SDK happened to send.
//
// The wait is a value here, not elapsed time: the sleeper and both clocks are injected,
// so "obeys the server's interval" and "ends at the server's expiry" are assertions
// rather than stopwatch readings.
//
// SPFNDeviceCodeEnrollmentTests.swift is the counterpart and uses corresponding names.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import kotlin.coroutines.cancellation.CancellationException

class SpfnDeviceCodeEnrollmentTest
{
    private val baseUrl = "https://example.invalid"

    /** The instant every case starts from, and the expiry the `start` answer names. */
    private val startedAtMillis = 1_750_000_000_000L
    private val expiresAtMillis = startedAtMillis + 600_000
    private val intervalMillis = 5_000L

    // ---- D1–D3: the flow is refused before anything is sent ----------------

    @Test
    fun d1_anEnrolledInstallIsRefusedAndSendsNothing() = runBlocking {
        val transport = ScriptedTransport(emptyList());
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        enrol(store, engine, "key-test-0009", "client-test-0001");
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val thrown = failureOf { lifecycle.enrollByDeviceCode { _, _ -> } };

        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.AlreadyEnrolled);
        assertEquals("nothing is sent for a refusal the state already knows", 0, transport.callCount);
    }

    @Test
    fun d2_anUnresolvedRotationIsRefusedAndSendsNothing() = runBlocking {
        val transport = ScriptedTransport(emptyList());
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        parkCandidate(store, engine, "key-test-0009", "client-test-0001");
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val thrown = failureOf { lifecycle.enrollByDeviceCode { _, _ -> } };

        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.RotationUnresolved);
        assertEquals(0, transport.callCount);
    }

    /**
     * D3, first direction: a social enrollment is waiting on its sign-in, so the device
     * code call is refused. One flag guards both entry points — two would let each flow
     * read UNENROLLED and register a key the other does not know about.
     */
    @Test
    fun d3_aSocialEnrollmentInFlightRefusesTheDeviceCodeCall() = runBlocking {
        val transport = ScriptedTransport(
            listOf(answer("{\"isNewUser\":true,\"keyId\":\"key-test-0001\",\"userId\":\"user-test-0001\"}"))
        );
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair(), testKeyPair());
        val lifecycle = makeLifecycle(
            transport, store, engine,
            keyIds = listOf("key-test-0001", "key-test-0002")
        );
        val arrived = CompletableDeferred<Unit>();
        val release = CompletableDeferred<Unit>();

        val social = async {
            lifecycle.enroll(provider = "apple")
            {
                arrived.complete(Unit);
                release.await();
                "idtoken-first";
            }
        };
        arrived.await();

        val thrown = failureOf { lifecycle.enrollByDeviceCode { _, _ -> } };
        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.EnrollmentInFlight);

        release.complete(Unit);
        assertEquals("the social enrollment is the one that settled", "key-test-0001", social.await().keyId);
        assertEquals("exactly one registration reached the server", 1, transport.callCount);
    }

    /**
     * D3, the other direction: a device-code call is waiting for an approval, so a
     * social enrollment is refused. Both directions are needed — a flag claimed by one
     * entry point and read by neither would pass the test above.
     */
    @Test
    fun d3_aDeviceCodeCallInFlightRefusesASocialEnrollment() = runBlocking {
        val waiting = CompletableDeferred<Unit>();
        val release = CompletableDeferred<Unit>();
        val transport = ScriptedTransport(listOf(startAnswer(), approvedAnswer()));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair(), testKeyPair());
        val sleeper = ScriptedSleeper { waiting.complete(Unit); release.await() };
        val lifecycle = makeLifecycle(
            transport, store, engine,
            keyIds = listOf("key-test-0001", "key-test-0002"),
            sleeper = sleeper
        );

        val device = async { lifecycle.enrollByDeviceCode { _, _ -> } };
        waiting.await();

        val thrown = failureOf { lifecycle.enroll(provider = "apple") { "idtoken-second" } };
        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.EnrollmentInFlight);

        release.complete(Unit);
        assertEquals("key-test-0001", device.await().keyId);
        assertEquals("the social enrollment sent nothing", 2, transport.callCount);
    }

    // ---- D4, D18: what `start` puts on the wire ----------------------------

    @Test
    fun d4_startSendsTheFixtureBytesAndShowsTheCodeOnce() = runBlocking {
        val fixture = deviceStartFixture();
        val expected = fixture.obj("byPlatform").obj(SpfnClientIdentity.KIND);
        val transport = ScriptedTransport(listOf(startAnswer(), approvedAnswer()));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val shown = mutableListOf<Pair<String, Long>>();
        val recordAtShowTime = mutableListOf<SpfnStoredKeyMetadata?>();
        lifecycle.enrollByDeviceCode(deviceName = fixture.text("deviceName"))
        { userCode, expiresAt ->
            shown.add(userCode to expiresAt);
            recordAtShowTime.add(store.load(SpfnKeyLifecycle.ACTIVE_SLOT));
        };

        val sent = transport.received.first();
        assertEquals("POST", sent.method);
        assertEquals(baseUrl + fixture.text("path"), sent.url);
        assertEquals(
            "an unproven start carries the fixture's headers and then the identity",
            fixture.headerPairs("headers") + SpfnClientIdentity.headers,
            sent.headers
        );
        assertEquals(
            "the start body must be the fixture bytes exactly",
            expected.text("canonical"),
            sent.body?.toString(Charsets.UTF_8)
        );

        assertEquals("the code is shown exactly once", 1, shown.size);
        assertEquals(USER_CODE to expiresAtMillis, shown.first());
        assertNull("nothing is saved before the approval", recordAtShowTime.first());
    }

    /**
     * D18: the `platform` the body registers the key under is the same value the
     * identity header announces. Read off one captured request, so the two cannot be
     * kept in step by the fixture the previous case reads.
     */
    @Test
    fun d18_theStartBodyPlatformIsTheClientKindHeaderValue() = runBlocking {
        val transport = ScriptedTransport(listOf(startAnswer(), approvedAnswer()));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        lifecycle.enrollByDeviceCode { _, _ -> };

        val sent = transport.received.first();
        val kind = sent.headers.first { it.first == SpfnWireHeaders.CLIENT_KIND }.second;
        val body = SpfnCanonicalJson.parse(requireNotNull(sent.body)).members();
        assertEquals("the parked key's platform is what this build announces itself as", kind, body.text("platform"));
    }

    // ---- D5, D6: `start` never answers ------------------------------------

    @Test
    fun d5_aStartTransportFailureDestroysTheKeyAndShowsNoCode() = runBlocking {
        val transport = ScriptedTransport(listOf(ScriptedTransport.Outcome.Failure(SpfnTransportError.TimedOut())));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        var shown = 0;
        val thrown = failureOf { lifecycle.enrollByDeviceCode { _, _ -> shown += 1 } };

        assertTrue("$thrown", thrown is SpfnClientError.Transport);
        assertEquals("a code nobody was given must not be shown", 0, shown);
        assertNoKeySurvived(store, engine, lifecycle);
    }

    @Test
    fun d6_aStartRefusalDestroysTheKeyAndSurfacesTheRefusal() = runBlocking {
        val transport = ScriptedTransport(listOf(answer(ExecuteFixtures.errorEnvelope("ValidationError"), 400)));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        var shown = 0;
        val thrown = failureOf { lifecycle.enrollByDeviceCode { _, _ -> shown += 1 } };

        val refused = thrown as? SpfnClientError.Server ?: throw AssertionError("expected a refusal, got $thrown");
        assertEquals(SpfnGeneratedErrorCode.ValidationError, refused.failure.code);
        assertEquals(0, shown);
        assertNoKeySurvived(store, engine, lifecycle);
    }

    // ---- D7, D8: the two answers the poll is written for --------------------

    @Test
    fun d7_aPendingAnswerWaitsItsIntervalAndPollsTheSameCodeAgain() = runBlocking {
        val secondInterval = 7_000L;
        val transport = ScriptedTransport(
            listOf(startAnswer(), pendingAnswer(secondInterval), approvedAnswer())
        );
        val sleeper = ScriptedSleeper();
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"), sleeper = sleeper);

        lifecycle.enrollByDeviceCode { _, _ -> };

        assertEquals(
            "the first wait is the start answer's interval and the second is the pending answer's",
            listOf(intervalMillis, secondInterval),
            sleeper.waits
        );
        assertEquals(listOf(DEVICE_CODE, DEVICE_CODE), polledDeviceCodes(transport));
    }

    @Test
    fun d8_anApprovedAnswerSavesTheParkedKeyAndAnswersTheLogin() = runBlocking {
        val transport = ScriptedTransport(
            listOf(startAnswer(), approvedAnswer(userId = "user-test-0007", passwordChangeRequired = true))
        );
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val result = lifecycle.enrollByDeviceCode { _, _ -> };

        assertEquals(
            SpfnDeviceCodeEnrollmentResult("user-test-0007", "key-test-0001", true),
            result
        );
        assertEquals(SpfnKeyLifecycleState.ENROLLED, lifecycle.state());
        val active = store.load(SpfnKeyLifecycle.ACTIVE_SLOT);
        assertEquals("the approved poll's userId is the clientId every proof carries", "user-test-0007", active?.clientId);
        assertEquals("key-test-0001", active?.keyId);
        assertEquals("user-test-0007", lifecycle.activeProvider()?.clientId);
    }

    // ---- D9–D13: every refusal the poll can meet ---------------------------

    @Test
    fun d9_aDeniedPollDestroysTheKeyAndCarriesTheCode() = runBlocking {
        assertPollRefusalEndsTheWait("DeviceAuthDeniedError", 403, SpfnGeneratedErrorCode.DeviceAuthDeniedError);
    }

    @Test
    fun d10_anExpiredPollDestroysTheKeyAndCarriesTheCode() = runBlocking {
        assertPollRefusalEndsTheWait("DeviceAuthExpiredError", 400, SpfnGeneratedErrorCode.DeviceAuthExpiredError);
    }

    @Test
    fun d11_aNotFoundPollDestroysTheKeyAndCarriesTheCode() = runBlocking {
        assertPollRefusalEndsTheWait("DeviceAuthNotFoundError", 404, SpfnGeneratedErrorCode.DeviceAuthNotFoundError);
    }

    /**
     * D12: the one refusal the contract marks retryable. The code is still live and this
     * device only asked too fast, so the wait resumes on the interval it already had.
     */
    @Test
    fun d12_aRateLimitKeepsPollingAndKeepsTheKey() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                startAnswer(),
                answer(ExecuteFixtures.errorEnvelope("TooManyRequestsError"), 429),
                approvedAnswer()
            )
        );
        val sleeper = ScriptedSleeper();
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"), sleeper = sleeper);

        val result = lifecycle.enrollByDeviceCode { _, _ -> };

        assertEquals("key-test-0001", result.keyId);
        assertEquals("the rate limit does not change the interval the server asked for",
            listOf(intervalMillis, intervalMillis), sleeper.waits);
        assertEquals(3, transport.callCount);
        assertTrue("the parked key survives a rate limit", engine.contains("spfn-client-key-key-test-0001"));
    }

    /**
     * D13: a code this contract does not declare. The client refuses it as an unknown
     * code rather than rounding it to a neighbour, and a wait it cannot interpret is a
     * wait it ends — polling on would wait out a code that may never move.
     */
    @Test
    fun d13_anUnlistedErrorCodeEndsTheWait() = runBlocking {
        val transport = ScriptedTransport(
            listOf(startAnswer(), answer(ExecuteFixtures.errorEnvelope("SOMETHING_ELSE_V2"), 400))
        );
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val thrown = failureOf { lifecycle.enrollByDeviceCode { _, _ -> } };

        val decoding = thrown as? SpfnClientError.Decoding ?: throw AssertionError("expected a decoding refusal, got $thrown");
        assertEquals(SpfnDecodingFailure.UNKNOWN_ERROR_CODE, decoding.failure);
        assertEquals("no further poll is sent", 2, transport.callCount);
        assertNoKeySurvived(store, engine, lifecycle);
    }

    // ---- D14: a poll whose answer was lost ---------------------------------

    @Test
    fun d14_aPollTransportFailureIsAskedAgainAfterTheInterval() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                startAnswer(),
                ScriptedTransport.Outcome.Failure(SpfnTransportError.Connectivity("the network went away")),
                approvedAnswer()
            )
        );
        val sleeper = ScriptedSleeper();
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"), sleeper = sleeper);

        val result = lifecycle.enrollByDeviceCode { _, _ -> };

        assertEquals("key-test-0001", result.keyId);
        assertEquals("a lost answer costs the same wait, not a new one",
            listOf(intervalMillis, intervalMillis), sleeper.waits);
        assertEquals(listOf(DEVICE_CODE, DEVICE_CODE), polledDeviceCodes(transport));
    }

    // ---- D15: the deadline, judged on the proof clock ----------------------

    /**
     * D15: the wait ends locally at the expiry `start` named, and it is the proof clock
     * — the one `core.time` synchronised — that says so. The device's own wall clock is
     * moved past the expiry first and changes nothing, which is the point: a device with
     * a wrong clock must not give up early or poll a code it was told is dead.
     */
    @Test
    fun d15_theProofClockDeadlineEndsTheWaitWithoutAnotherPoll() = runBlocking {
        val wallClock = FakeClock(startedAtMillis);
        val proofClock = FakeClock(startedAtMillis);
        val sleeper = ScriptedSleeper { wait ->
            if (wait == 1)
            {
                wallClock.set(expiresAtMillis + 1);
            }
            else
            {
                proofClock.set(expiresAtMillis);
            }
        };
        val transport = ScriptedTransport(listOf(startAnswer(), pendingAnswer(intervalMillis)));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(
            transport, store, engine,
            keyIds = listOf("key-test-0001"),
            clock = wallClock,
            proofClock = proofClock,
            sleeper = sleeper
        );

        val thrown = failureOf { lifecycle.enrollByDeviceCode { _, _ -> } };

        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.DeviceCodeExpired);
        assertEquals("a wall clock past the expiry does not end the wait; the proof clock does",
            2, transport.callCount);
        assertNoKeySurvived(store, engine, lifecycle);
    }

    // ---- D16: the caller withdraws -----------------------------------------

    @Test
    fun d16_cancellationDuringTheWaitDestroysTheKeyAndSendsNoPoll() = runBlocking {
        val waiting = CompletableDeferred<Unit>();
        val transport = ScriptedTransport(listOf(startAnswer(), approvedAnswer()));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val sleeper = ScriptedSleeper { waiting.complete(Unit); awaitCancellation() };
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"), sleeper = sleeper);

        val call = async { lifecycle.enrollByDeviceCode { _, _ -> } };
        waiting.await();
        call.cancel();

        val thrown = runCatching { call.await() }.exceptionOrNull();
        assertTrue("expected the platform's cancellation, got $thrown", thrown is CancellationException);
        assertEquals("a cancelled wait sends no poll", 1, transport.callCount);
        assertNoKeySurvived(store, engine, lifecycle);
    }

    // ---- D17: an answer that names a branch it does not carry --------------

    /**
     * D17: the branch is read from `status`, and the fields that branch requires are
     * then required. A default would turn a server that answered half a login into a
     * login — `passwordChangeRequired` absent read as `false` is a rule the account may
     * not have.
     */
    @Test
    fun d17_aBranchMissingItsOwnFieldsIsADecodingRefusal() = runBlocking {
        val incomplete = listOf(
            "{\"status\":\"pending\"}",
            "{\"passwordChangeRequired\":false,\"status\":\"approved\"}",
            "{\"status\":\"approved\",\"userId\":\"user-test-0001\"}",
            "{\"intervalMillis\":0,\"status\":\"pending\"}",
            "{\"intervalMillis\":-1,\"status\":\"pending\"}"
        );
        for (body in incomplete)
        {
            val transport = ScriptedTransport(listOf(startAnswer(), answer(body)));
            val store = InMemoryKeyMetadataStore();
            val engine = scriptedEngine(testKeyPair());
            val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

            val thrown = failureOf { lifecycle.enrollByDeviceCode { _, _ -> } };

            val decoding = thrown as? SpfnClientError.Decoding
                ?: throw AssertionError("'$body' was accepted, got $thrown");
            assertEquals(body, SpfnDecodingFailure.NOT_THE_DECLARED_RESPONSE, decoding.failure);
            assertEquals("no further poll is sent for $body", 2, transport.callCount);
            assertNoKeySurvived(store, engine, lifecycle);
        }
    }

    // ---- D19, D20: the clock read the wait depends on ----------------------

    /**
     * D19: on a fresh install the first iteration's proof-clock read is a real `core.time`
     * request, and a network that dropped it says nothing about the device code — exactly
     * as much as a network that drops the poll (D14). So it costs the same interval and is
     * asked again, and the deadline is judged when the clock finally answers. Ending the
     * wait here would delete the key for a dropped packet the very next line retries.
     */
    @Test
    fun d19_aLostClockFetchCostsTheIntervalAndTheWaitGoesOn() = runBlocking {
        val proofClock = ScriptedProofClock(
            startedAtMillis,
            listOf(SpfnClockSynchronizationException.RequestFailed())
        );
        val sleeper = ScriptedSleeper();
        val transport = ScriptedTransport(listOf(startAnswer(), approvedAnswer()));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(
            transport, store, engine,
            keyIds = listOf("key-test-0001"),
            proofClock = proofClock,
            sleeper = sleeper
        );

        val result = lifecycle.enrollByDeviceCode { _, _ -> };

        assertEquals("key-test-0001", result.keyId);
        assertEquals("the clock was asked again rather than given up on", 2, proofClock.reads);
        assertEquals("a lost clock fetch costs the same wait, not a new one",
            listOf(intervalMillis, intervalMillis), sleeper.waits);
        assertEquals("the lost fetch cost no poll, and one poll approved",
            listOf(DEVICE_CODE), polledDeviceCodes(transport));
    }

    /**
     * D20: the other half. A clock that refuses to synchronize at all is not a lost fetch
     * — an untrusted base URL and a contract with no usable clock operation answer the
     * same on every retry — so retrying would poll until the code expired against a
     * deadline this device can never read. It ends the wait and deletes the key.
     */
    @Test
    fun d20_aClockSynchronizationRefusalEndsTheWaitWithoutAPoll() = runBlocking {
        val refusals = listOf(
            SpfnClockSynchronizationException.UntrustedBaseUrl(),
            SpfnClockSynchronizationException.ContractIncompatible()
        );
        for (refusal in refusals)
        {
            val proofClock = ScriptedProofClock(startedAtMillis, listOf(refusal));
            val transport = ScriptedTransport(listOf(startAnswer(), approvedAnswer()));
            val store = InMemoryKeyMetadataStore();
            val engine = scriptedEngine(testKeyPair());
            val lifecycle = makeLifecycle(
                transport, store, engine,
                keyIds = listOf("key-test-0001"),
                proofClock = proofClock
            );

            val thrown = failureOf { lifecycle.enrollByDeviceCode { _, _ -> } };

            assertEquals(refusal, thrown);
            assertEquals("$refusal was retried", 1, proofClock.reads);
            assertEquals("no poll is sent after $refusal", 1, transport.callCount);
            assertNoKeySurvived(store, engine, lifecycle);
        }
    }

    // ---- assembly ----------------------------------------------------------

    /** D9–D11 differ only in the code, so the shared body is written once. */
    private suspend fun assertPollRefusalEndsTheWait(
        wireCode: String,
        httpStatus: Int,
        expected: SpfnGeneratedErrorCode
    )
    {
        val transport = ScriptedTransport(
            listOf(startAnswer(), answer(ExecuteFixtures.errorEnvelope(wireCode), httpStatus))
        );
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val thrown = failureOf { lifecycle.enrollByDeviceCode { _, _ -> } };

        val refused = thrown as? SpfnClientError.Server ?: throw AssertionError("expected a refusal, got $thrown");
        assertEquals(expected, refused.failure.code);
        assertEquals(httpStatus, refused.failure.httpStatus);
        assertEquals("no further poll is sent", 2, transport.callCount);
        assertNoKeySurvived(store, engine, lifecycle);
    }

    /** What every non-approved exit owes: no record, no Keystore entry, no state. */
    private fun assertNoKeySurvived(
        store: SpfnKeyMetadataStore,
        engine: ScriptedKeystoreEngine,
        lifecycle: SpfnKeyLifecycle
    )
    {
        assertNull("nothing was persisted", store.load(SpfnKeyLifecycle.ACTIVE_SLOT));
        assertFalse(
            "the Keystore entry was deleted, not orphaned",
            engine.contains("spfn-client-key-key-test-0001")
        );
        assertEquals(SpfnKeyLifecycleState.UNENROLLED, lifecycle.state());
    }

    /** The `deviceCode` every poll request carried, in order. */
    private fun polledDeviceCodes(transport: ScriptedTransport): List<String> =
        transport.received
            .filter { it.url.endsWith(SpfnGeneratedOperations.authDevicePoll.path) }
            .map { SpfnCanonicalJson.parse(requireNotNull(it.body)).members().text("deviceCode") }

    private fun startAnswer(): ScriptedTransport.Outcome = answer(
        "{\"deviceCode\":\"$DEVICE_CODE\",\"expiresAtMillis\":$expiresAtMillis," +
            "\"intervalMillis\":$intervalMillis,\"userCode\":\"$USER_CODE\"}"
    )

    private fun pendingAnswer(interval: Long): ScriptedTransport.Outcome =
        answer("{\"intervalMillis\":$interval,\"status\":\"pending\"}")

    private fun approvedAnswer(
        userId: String = "user-test-0001",
        passwordChangeRequired: Boolean = false
    ): ScriptedTransport.Outcome = answer(
        "{\"passwordChangeRequired\":$passwordChangeRequired,\"publicId\":\"public-test-0001\"," +
            "\"status\":\"approved\",\"userId\":\"$userId\"}"
    )

    private fun answer(text: String, statusCode: Int = 200): ScriptedTransport.Outcome =
        ScriptedTransport.Outcome.Answer(jsonResponse(statusCode, text))

    private fun deviceStartFixture(): Map<String, SpfnCanonicalValue> =
        WireFixtures.load("Contracts/fixtures/enrollment/enrollment.json").members().obj("deviceStart")

    /** The fixture test keypair (TEST ONLY — published on purpose). */
    private fun testKeyPair(): Pair<String, String>
    {
        val keyPair = WireFixtures.wire().obj("testKeyPair");
        return keyPair.text("privateKeyPkcs8Base64") to keyPair.text("publicKeySpkiBase64");
    }

    private fun scriptedEngine(vararg pairs: Pair<String, String>): ScriptedKeystoreEngine =
        ScriptedKeystoreEngine(pairs.toMutableList())

    private fun enrol(
        store: SpfnKeyMetadataStore,
        engine: ScriptedKeystoreEngine,
        keyId: String,
        clientId: String
    )
    {
        val key = SpfnKeystoreCustodyKey.generate(keyId, engine);
        store.save(SpfnKeyLifecycle.ACTIVE_SLOT, key.metadata(clientId = clientId, createdAtMillis = startedAtMillis));
    }

    private fun parkCandidate(
        store: SpfnKeyMetadataStore,
        engine: ScriptedKeystoreEngine,
        keyId: String,
        clientId: String
    )
    {
        val key = SpfnKeystoreCustodyKey.generate(keyId, engine);
        store.save(
            SpfnKeyLifecycle.CANDIDATE_SLOT,
            key.metadata(clientId = clientId, createdAtMillis = startedAtMillis)
        );
    }

    private fun makeLifecycle(
        transport: SpfnTransport,
        store: SpfnKeyMetadataStore,
        engine: SpfnKeystoreEngine,
        keyIds: List<String>,
        clock: FakeClock = FakeClock(startedAtMillis),
        proofClock: SpfnProofClock = FakeClock(startedAtMillis),
        sleeper: SpfnSleeper = ScriptedSleeper()
    ): SpfnKeyLifecycle
    {
        val remaining = keyIds.toMutableList();
        return SpfnKeyLifecycle(
            transport = transport,
            store = store,
            engine = engine,
            baseUrl = baseUrl,
            clock = clock,
            proofClock = proofClock,
            nonceGenerator = ScriptedNonceGenerator(emptyList()),
            sleeper = sleeper,
            newKeyId = { if (remaining.isEmpty()) "key-unexpected" else remaining.removeAt(0) }
        );
    }

    private suspend fun failureOf(body: suspend () -> Unit): Throwable?
    {
        try
        {
            body();
        }
        catch (thrown: Throwable)
        {
            return thrown;
        }
        org.junit.Assert.fail("expected a throw");
        return null;
    }

    private companion object
    {
        /** Synthetic test values; neither is a credential of anything. */
        const val DEVICE_CODE = "device-code-test-0001"

        /** As the server spells it — the client passes it through untouched. */
        const val USER_CODE = "WDJB-MJHT"
    }
}
