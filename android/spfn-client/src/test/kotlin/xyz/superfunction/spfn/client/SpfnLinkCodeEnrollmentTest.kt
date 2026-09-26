// SPFN Mobile — link-code enrollment (M9), one test per cell of the L table.
//
// The flow is device-code enrollment turned round, so most of the table is the D table's
// rules asked again of the new entry point: the claim, the key's life, the wait, the
// deadline and the long poll. What only this flow has is the code it reads — refused
// locally when it cannot be one — and the three refusals the contract's `deviceLink`
// section names, which an app shows as three different things.
//
// The `redeem` body is compared against the `deviceStart` fixture that a third
// implementation derived from the contract text (P10): the contract gives
// RedeemDeviceLinkRequest StartDeviceAuthRequest's fields plus `userCode`, so the same
// key material must produce the same bytes plus that one member.
//
// SPFNLinkCodeEnrollmentTests.swift is the counterpart and uses corresponding names.

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
import xyz.superfunction.spfn.generated.SpfnGeneratedContract
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import kotlin.coroutines.cancellation.CancellationException

class SpfnLinkCodeEnrollmentTest
{
    private val baseUrl = "https://example.invalid"

    /** The instant every case starts from, and the expiry the `redeem` answer names. */
    private val startedAtMillis = 1_750_000_000_000L
    private val expiresAtMillis = startedAtMillis + 300_000
    private val intervalMillis = 5_000L
    private val matchNumber = 37L

    // ---- L1–L3: the flow is refused before anything is sent ----------------

    /**
     * L1: a code that is not eight characters of the alphabet once folded is refused
     * before a key exists — no request, no key generated, no claim left behind.
     */
    @Test
    fun l1_aMalformedCodeIsRefusedBeforeAKeyOrARequest() = runBlocking {
        val malformed = listOf(
            "", "WDJB-MJH", "WDJB-MJHTX", "WDJB-MJH0", "WDJB-MJHI", "WDJB-MJHL", "WDJB-MJHO",
            "WDJB_MJHT", "WDJB–MJHT", "https://example.invalid/WDJB-MJHT", "WDJB-MJHß"
        );
        for (code in malformed)
        {
            var minted = 0;
            val transport = ScriptedTransport(emptyList());
            val lifecycle = SpfnKeyLifecycle(
                transport = transport,
                store = InMemoryKeyMetadataStore(),
                engine = scriptedEngine(testKeyPair()),
                baseUrl = baseUrl,
                clock = FakeClock(startedAtMillis),
                proofClock = FakeClock(startedAtMillis),
                nonceGenerator = ScriptedNonceGenerator(emptyList()),
                sleeper = ScriptedSleeper(),
                newKeyId = { minted += 1; "key-test-0001" }
            );

            val thrown = failureOf { lifecycle.enrollByLinkCode(code) { _, _ -> } };

            assertTrue("'$code' was accepted, got $thrown", thrown is SpfnKeyLifecycleException.MalformedLinkCode);
            assertEquals("nothing is sent for '$code'", 0, transport.callCount);
            assertEquals("no key is generated for '$code'", 0, minted);
        }
    }

    @Test
    fun l2_anEnrolledInstallIsRefusedAndSendsNothing() = runBlocking {
        val transport = ScriptedTransport(emptyList());
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair(), testKeyPair());
        val existing = SpfnKeystoreCustodyKey.generate("key-test-0009", engine);
        store.save(SpfnKeyLifecycle.ACTIVE_SLOT, existing.metadata(clientId = "client-test-0001", createdAtMillis = startedAtMillis));
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val thrown = failureOf { lifecycle.enrollByLinkCode(TYPED_CODE) { _, _ -> } };

        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.AlreadyEnrolled);
        assertEquals(0, transport.callCount);
    }

    /**
     * L3, first direction: a device-code wait is running, so the link-code call is
     * refused. One claim covers every enrollment entry point.
     */
    @Test
    fun l3_aDeviceCodeWaitInFlightRefusesTheLinkCodeCall() = runBlocking {
        val waiting = CompletableDeferred<Unit>();
        val release = CompletableDeferred<Unit>();
        val transport = ScriptedTransport(
            listOf(
                answer(
                    "{\"deviceCode\":\"device-code-test-0001\",\"expiresAtMillis\":$expiresAtMillis," +
                        "\"intervalMillis\":$intervalMillis,\"userCode\":\"WDJB-MJHT\"}"
                ),
                approvedAnswer()
            )
        );
        val engine = scriptedEngine(testKeyPair(), testKeyPair());
        val sleeper = ScriptedSleeper { waiting.complete(Unit); release.await() };
        val lifecycle = makeLifecycle(
            transport, InMemoryKeyMetadataStore(), engine,
            keyIds = listOf("key-test-0001", "key-test-0002"),
            sleeper = sleeper
        );

        val device = async { lifecycle.enrollByDeviceCode { _, _ -> } };
        waiting.await();

        val thrown = failureOf { lifecycle.enrollByLinkCode(TYPED_CODE) { _, _ -> } };
        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.EnrollmentInFlight);

        release.complete(Unit);
        assertEquals("key-test-0001", device.await().keyId);
        assertEquals("the link-code call sent nothing", 2, transport.callCount);
    }

    /** L3, the other direction: a link-code wait is running, so a device-code call is refused. */
    @Test
    fun l3_aLinkCodeWaitInFlightRefusesTheDeviceCodeCall() = runBlocking {
        val waiting = CompletableDeferred<Unit>();
        val release = CompletableDeferred<Unit>();
        val transport = ScriptedTransport(listOf(redeemAnswer(), approvedAnswer()));
        val engine = scriptedEngine(testKeyPair(), testKeyPair());
        val sleeper = ScriptedSleeper { waiting.complete(Unit); release.await() };
        val lifecycle = makeLifecycle(
            transport, InMemoryKeyMetadataStore(), engine,
            keyIds = listOf("key-test-0001", "key-test-0002"),
            sleeper = sleeper
        );

        val link = async { lifecycle.enrollByLinkCode(TYPED_CODE) { _, _ -> } };
        waiting.await();

        val thrown = failureOf { lifecycle.enrollByDeviceCode { _, _ -> } };
        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.EnrollmentInFlight);

        release.complete(Unit);
        assertEquals("key-test-0001", link.await().keyId);
        assertEquals("the device-code call sent nothing", 2, transport.callCount);
    }

    // ---- L4: what `redeem` puts on the wire --------------------------------

    /**
     * L4: the body is the `deviceStart` fixture's, member for member, plus the folded
     * code; the match is shown once, before anything is saved.
     */
    @Test
    fun l4_redeemSendsTheStartFieldsPlusTheFoldedCodeAndShowsTheMatchOnce() = runBlocking {
        val fixture = deviceStartFixture();
        val startBody = fixture.obj("byPlatform").obj(SpfnClientIdentity.KIND).text("canonical");
        val expected = SpfnCanonicalJson.parse(startBody.toByteArray(Charsets.UTF_8)).members() +
            ("userCode" to SpfnCanonicalValue.Text(STORED_CODE));
        val transport = ScriptedTransport(listOf(redeemAnswer(), approvedAnswer()));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val shown = mutableListOf<Pair<Long, Long>>();
        val recordAtShowTime = mutableListOf<SpfnStoredKeyMetadata?>();
        lifecycle.enrollByLinkCode(TYPED_CODE, deviceName = fixture.text("deviceName"))
        { match, expiresAt ->
            shown.add(match to expiresAt);
            recordAtShowTime.add(store.load(SpfnKeyLifecycle.ACTIVE_SLOT));
        };

        val sent = transport.received.first();
        assertEquals("POST", sent.method);
        assertEquals(baseUrl + SpfnGeneratedOperations.authDeviceLinkRedeem.path, sent.url);
        assertEquals(expected, SpfnCanonicalJson.parse(requireNotNull(sent.body)).members());
        val kind = sent.headers.first { it.first == SpfnWireHeaders.CLIENT_KIND }.second;
        assertEquals("the parked key's platform is this build's kind", SpfnCanonicalValue.Text(kind), expected["platform"]);

        assertEquals("the match is shown exactly once", listOf(matchNumber to expiresAtMillis), shown);
        assertNull("nothing is saved before the approval", recordAtShowTime.first());
    }

    // ---- L5–L7: `redeem` refused or unreadable -----------------------------

    /** L5: never issued, or used by another device — "code not found". */
    @Test
    fun l5_aRedeemNotFoundIsItsOwnOutcomeAndDestroysTheKey() = runBlocking {
        assertRedeemRefusal("DeviceLinkNotFoundError", 404, SpfnGeneratedErrorCode.DeviceLinkNotFoundError);
    }

    /** L6: the code died — "code expired". Distinct from L5 by code and by status. */
    @Test
    fun l6_aRedeemExpiredIsItsOwnOutcomeAndDestroysTheKey() = runBlocking {
        assertRedeemRefusal("DeviceLinkExpiredError", 400, SpfnGeneratedErrorCode.DeviceLinkExpiredError);
    }

    /**
     * L7: a match number outside the contract's 10–99 is one the signed-in device will
     * never offer. It is refused as an answer this client cannot read, and not shown.
     */
    @Test
    fun l7_aMatchNumberOutsideTheContractRangeIsRefusedAndNotShown() = runBlocking {
        for (match in listOf(9L, 100L, 0L, -37L))
        {
            val transport = ScriptedTransport(listOf(redeemAnswer(match), approvedAnswer()));
            val store = InMemoryKeyMetadataStore();
            val engine = scriptedEngine(testKeyPair());
            val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

            var shown = 0;
            val thrown = failureOf { lifecycle.enrollByLinkCode(TYPED_CODE) { _, _ -> shown += 1 } };

            val decoding = thrown as? SpfnClientError.Decoding ?: throw AssertionError("$match was accepted, got $thrown");
            assertEquals("$match", SpfnDecodingFailure.NOT_THE_DECLARED_RESPONSE, decoding.failure);
            assertEquals("$match was shown", 0, shown);
            assertEquals("no poll follows $match", 1, transport.callCount);
            assertNoKeySurvived(store, engine, lifecycle);
        }
    }

    // ---- L8: approval ------------------------------------------------------

    @Test
    fun l8_anApprovedPollSavesTheParkedKeyExactlyAsEnrollmentDoes() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                redeemAnswer(),
                pendingAnswer(intervalMillis),
                approvedAnswer(userId = "user-test-0007", passwordChangeRequired = true)
            )
        );
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val result = lifecycle.enrollByLinkCode(TYPED_CODE) { _, _ -> };

        assertEquals(
            SpfnDeviceCodeEnrollmentResult(clientId = "user-test-0007", keyId = "key-test-0001", passwordChangeRequired = true),
            result
        );
        assertEquals(SpfnKeyLifecycleState.ENROLLED, lifecycle.state());
        val active = requireNotNull(store.load(SpfnKeyLifecycle.ACTIVE_SLOT));
        assertEquals("user-test-0007", active.clientId);
        assertEquals("key-test-0001", active.keyId);
        assertEquals("user-test-0007", lifecycle.activeProvider()?.clientId);
        assertEquals(listOf(DEVICE_CODE, DEVICE_CODE), polledDeviceCodes(transport));
    }

    // ---- L9–L11: the poll refusals that end the wait ------------------------

    /** L9: the signed-in device refused, or picked another number — "not approved". */
    @Test
    fun l9_aDeniedPollDestroysTheKeyAndCarriesTheCode() = runBlocking {
        assertPollRefusalEndsTheWait("DeviceLinkDeniedError", 403, SpfnGeneratedErrorCode.DeviceLinkDeniedError);
    }

    @Test
    fun l10_anExpiredPollDestroysTheKeyAndCarriesTheCode() = runBlocking {
        assertPollRefusalEndsTheWait("DeviceLinkExpiredError", 400, SpfnGeneratedErrorCode.DeviceLinkExpiredError);
    }

    /**
     * L11: the approval was collected by another poll. Ended as D11 ends a device-code
     * wait: the key this call parked is not the one that poll registered for anyone.
     */
    @Test
    fun l11_aNotFoundPollDestroysTheKeyAndCarriesTheCode() = runBlocking {
        assertPollRefusalEndsTheWait("DeviceLinkNotFoundError", 404, SpfnGeneratedErrorCode.DeviceLinkNotFoundError);
    }

    // ---- L12: the deadline, judged on the proof clock ----------------------

    @Test
    fun l12_theProofClockDeadlineEndsTheWaitWithoutAnotherPoll() = runBlocking {
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
        val transport = ScriptedTransport(listOf(redeemAnswer(), pendingAnswer(intervalMillis)));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(
            transport, store, engine,
            keyIds = listOf("key-test-0001"),
            clock = wallClock,
            proofClock = proofClock,
            sleeper = sleeper
        );

        val thrown = failureOf { lifecycle.enrollByLinkCode(TYPED_CODE) { _, _ -> } };

        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.LinkCodeExpired);
        assertEquals("a wall clock past the expiry does not end the wait; the proof clock does",
            2, transport.callCount);
        assertNoKeySurvived(store, engine, lifecycle);
    }

    // ---- L13: the caller withdraws -----------------------------------------

    /**
     * L13: cancelled between `redeem` and the approval. The key goes, the install stays
     * unenrolled, and the claim is released — the next enrollment is not refused as one
     * still in flight.
     */
    @Test
    fun l13_cancellationAfterRedeemDestroysTheKeyAndReleasesTheClaim() = runBlocking {
        val waiting = CompletableDeferred<Unit>();
        val transport = ScriptedTransport(listOf(redeemAnswer(), redeemAnswer(), approvedAnswer()));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair(), testKeyPair());
        val sleeper = ScriptedSleeper { wait ->
            if (wait == 1)
            {
                waiting.complete(Unit);
                awaitCancellation();
            }
        };
        val lifecycle = makeLifecycle(
            transport, store, engine,
            keyIds = listOf("key-test-0001", "key-test-0002"),
            sleeper = sleeper
        );

        val call = async { lifecycle.enrollByLinkCode(TYPED_CODE) { _, _ -> } };
        waiting.await();
        call.cancel();

        val thrown = runCatching { call.await() }.exceptionOrNull();
        assertTrue("expected the platform's cancellation, got $thrown", thrown is CancellationException);
        assertEquals("a cancelled wait sends no poll", 1, transport.callCount);
        assertNoKeySurvived(store, engine, lifecycle);

        lifecycle.enrollByLinkCode(TYPED_CODE) { _, _ -> };
        assertEquals("the claim was released with the cancelled call", SpfnKeyLifecycleState.ENROLLED, lifecycle.state());
    }

    // ---- L14, L15: the long poll and the version check ---------------------

    /**
     * L14: every link poll asks to be held, its deadline outlasts the hold, and a held
     * `pending` is asked again at once.
     */
    @Test
    fun l14_everyPollAsksToBeHeldAndAHeldPendingPollsAgainAtOnce() = runBlocking {
        val transport = ScriptedTransport(listOf(redeemAnswer(), pendingAnswer(0), approvedAnswer()));
        val sleeper = ScriptedSleeper();
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"), sleeper = sleeper);

        lifecycle.enrollByLinkCode(TYPED_CODE) { _, _ -> };

        assertEquals("only the redeem answer's interval was slept", listOf(intervalMillis), sleeper.waits);
        val polls = transport.received.filter { it.url.endsWith(SpfnGeneratedOperations.authDeviceLinkPoll.path) };
        assertEquals(2, polls.size);
        for (poll in polls)
        {
            val body = SpfnCanonicalJson.parse(requireNotNull(poll.body)).members();
            assertEquals(SpfnCanonicalValue.Integer(20_000), body["waitMillis"]);
            assertEquals(15_000L + 20_000L, poll.timeoutMillis);
        }
        assertEquals("redeem is not held", 15_000L, transport.received.first().timeoutMillis);
    }

    /**
     * L15: the new operations ride the execute path, so a server announcing a contract
     * this SDK does not admit is refused on `redeem` like any other call — here 0.13.1,
     * which predates the device link — and the key goes with it.
     */
    @Test
    fun l15_aRedeemFromAServerOutsideTheAdmittedRangeIsAContractRefusal() = runBlocking {
        val transport = ScriptedTransport(
            listOf(
                ScriptedTransport.Outcome.Answer(
                    SpfnTransportResponse(
                        statusCode = 200,
                        headers = listOf("content-type" to "application/json") + announcementHeaders(version = "0.13.1"),
                        body = redeemBody().toByteArray(Charsets.UTF_8)
                    )
                )
            )
        );
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val thrown = failureOf { lifecycle.enrollByLinkCode(TYPED_CODE) { _, _ -> } };

        val refused = thrown as? SpfnClientError.Contract ?: throw AssertionError("expected a contract refusal, got $thrown");
        assertEquals(
            SpfnContractMismatch(
                SpfnContractMismatch.Reason.OUTSIDE_ADMITTED_RANGE,
                "0.13.1",
                SpfnGeneratedContract.BINDING.admittedRange
            ),
            refused.mismatch
        );
        assertNoKeySurvived(store, engine, lifecycle);
    }

    // ---- assembly ----------------------------------------------------------

    /** L5 and L6 differ only in the code. */
    private suspend fun assertRedeemRefusal(wireCode: String, httpStatus: Int, expected: SpfnGeneratedErrorCode)
    {
        val transport = ScriptedTransport(listOf(answer(ExecuteFixtures.errorEnvelope(wireCode), httpStatus)));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        var shown = 0;
        val thrown = failureOf { lifecycle.enrollByLinkCode(TYPED_CODE) { _, _ -> shown += 1 } };

        val refused = thrown as? SpfnClientError.Server ?: throw AssertionError("expected a refusal, got $thrown");
        assertEquals(expected, refused.failure.code);
        assertEquals(httpStatus, refused.failure.httpStatus);
        assertEquals("a match nobody was given must not be shown", 0, shown);
        assertNoKeySurvived(store, engine, lifecycle);
    }

    /** L9–L11 differ only in the code. */
    private suspend fun assertPollRefusalEndsTheWait(wireCode: String, httpStatus: Int, expected: SpfnGeneratedErrorCode)
    {
        val transport = ScriptedTransport(
            listOf(redeemAnswer(), answer(ExecuteFixtures.errorEnvelope(wireCode), httpStatus))
        );
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val thrown = failureOf { lifecycle.enrollByLinkCode(TYPED_CODE) { _, _ -> } };

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

    /** The `deviceCode` every link poll carried, in order. */
    private fun polledDeviceCodes(transport: ScriptedTransport): List<String> =
        transport.received
            .filter { it.url.endsWith(SpfnGeneratedOperations.authDeviceLinkPoll.path) }
            .map { SpfnCanonicalJson.parse(requireNotNull(it.body)).members().text("deviceCode") }

    private fun redeemBody(match: Long = matchNumber): String =
        "{\"deviceCode\":\"$DEVICE_CODE\",\"expiresAtMillis\":$expiresAtMillis," +
            "\"intervalMillis\":$intervalMillis,\"matchNumber\":$match}"

    private fun redeemAnswer(match: Long = matchNumber): ScriptedTransport.Outcome = answer(redeemBody(match))

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
        /** A synthetic test value; not a credential of anything. */
        const val DEVICE_CODE = "device-code-test-0002"

        /** As a person might type it, and as the server stores it. */
        const val TYPED_CODE = " wdjb-mjht "
        const val STORED_CODE = "WDJBMJHT"
    }
}
