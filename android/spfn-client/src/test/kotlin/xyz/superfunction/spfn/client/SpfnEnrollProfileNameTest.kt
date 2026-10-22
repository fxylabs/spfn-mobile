// SPFN Mobile — `enroll` sends the person's name as contract 0.13.5's `profile.name`.
//
// One test per row of the E1–E8 table in the w-20mw8 brief. Every body is compared
// against Contracts/fixtures/enrollment/enrollment.json, whose `oauthNativeProfile`
// vectors derive-expected-values.py wrote by hand from the contract, so the two
// platforms agreeing means both agree with a third implementation (P10).
//
// SPFNEnrollProfileNameTests.swift is the counterpart and uses the same row names.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnDigest

class SpfnEnrollProfileNameTest
{
    private val baseUrl = "https://example.invalid"
    private val personName = "김민수"
    private val enrolled = "{\"mfaRequired\":false,\"isNewUser\":true,\"keyId\":\"key-test-0001\",\"userId\":\"user-test-0001\"}"

    // ---- E1–E4: what the body carries --------------------------------------

    @Test
    fun test_profileName_E1_theTokenOnlyFormSendsTodaysBytes() = runBlocking {
        val token = fixtureToken();
        val sent = enrolledBody { lifecycle -> lifecycle.enroll(provider = "google") { token } };

        assertEquals("the token-only body is the pre-0.13.5 fixture, byte for byte", oauthNative().text("canonical"), sent);
        assertFalse(sent.contains("\"profile\""));
    }

    @Test
    fun test_profileName_E2_aNameRidesAsProfileName() = runBlocking {
        val token = fixtureToken();
        val sent = enrolledBody { lifecycle ->
            lifecycle.enrollWithSignIn(provider = "google") { SpfnSocialSignIn(idToken = token, displayName = personName) }
        };

        assertEquals(namedVector(personName).text("canonical"), sent);
        assertTrue(sent.contains("\"profile\":{\"name\":\"김민수\"}"));
    }

    @Test
    fun test_profileName_E3_noNameSendsNoProfileKey() = runBlocking {
        val token = fixtureToken();
        val sent = enrolledBody { lifecycle ->
            lifecycle.enrollWithSignIn(provider = "google") { SpfnSocialSignIn(idToken = token, displayName = null) }
        };

        assertEquals("never profile: {} and never name: null", oauthNative().text("canonical"), sent);
    }

    @Test
    fun test_profileName_E4_aBlankNameSendsNoProfileKey() = runBlocking {
        val token = fixtureToken();
        val blanks = profileVectors().list("blank").map { it.text() };
        assertFalse(blanks.isEmpty());

        for (blank in blanks)
        {
            val sent = enrolledBody { lifecycle ->
                lifecycle.enrollWithSignIn(provider = "google") { SpfnSocialSignIn(idToken = token, displayName = blank) }
            };
            assertEquals("blank name ${blank.map { it.code }}", oauthNative().text("canonical"), sent);
        }
    }

    // ---- E5: the contract states no length, so none is invented -----------

    /**
     * 0.13.5 declares `name` as an optional string and nothing more, and upstream's
     * export says "No length is declared because the route enforces none". So a long
     * name is sent, and a refusal of it is the server's, read exactly as the same
     * refusal of a body without a name.
     */
    @Test
    fun test_profileName_E5_noLengthIsInventedAndARefusalIsClassifiedAsToday() = runBlocking {
        val long = "가".repeat(4_096);
        val refusal = answer(ExecuteFixtures.errorEnvelope("ValidationError"), 400);

        val withName = refusedEnrollment(refusal, displayName = long);
        val withoutName = refusedEnrollment(refusal, displayName = null);

        assertTrue("the name is sent whole", withName.sent.contains(long));
        assertTrue("${withName.thrown}", withName.thrown is SpfnClientError.Server);
        assertTrue("${withoutName.thrown}", withoutName.thrown is SpfnClientError.Server);
        val failure = (withName.thrown as SpfnClientError.Server).failure;
        val failureWithoutName = (withoutName.thrown as SpfnClientError.Server).failure;
        assertEquals("ValidationError", failure.code.wireCode);
        assertEquals(failureWithoutName.code, failure.code);
        assertEquals(failureWithoutName.httpStatus, failure.httpStatus);
    }

    // ---- E6: nothing on the enroll path carries the name ------------------

    @Test
    fun test_profileName_E6_noErrorOrDescriptionCarriesTheName() = runBlocking {
        val answers = listOf(
            answer(ExecuteFixtures.errorEnvelope("ValidationError"), 400),
            answer(ExecuteFixtures.errorEnvelope("Error"), 500),
            ScriptedTransport.Outcome.Failure(SpfnTransportError.TimedOut()),
            answer("{}"),
            answer("not-json"),
            answer("{\"mfaRequired\":true}", 202),
            answer("{\"mfaRequired\":false,\"keyId\":\"key-other-9999\",\"userId\":\"user-test-0001\"}")
        );
        for (outcome in answers)
        {
            assertNamesNoOne(refusedEnrollment(outcome, displayName = personName).thrown);
        }

        val lifecycle = makeLifecycle(ScriptedTransport(emptyList()), InMemoryKeyMetadataStore());
        val missing = failureOf {
            lifecycle.enrollWithSignIn(provider = "google") { SpfnSocialSignIn(idToken = "", displayName = personName) };
        };
        assertTrue("$missing", missing is SpfnKeyLifecycleException.IdTokenMissing);
        assertNamesNoOne(missing);

        val signIn = SpfnSocialSignIn(idToken = "idtoken-test", displayName = personName);
        for (text in listOf(signIn.toString(), "$signIn"))
        {
            assertFalse(text, text.contains(personName));
            assertFalse(text, text.contains("idtoken-test"));
        }
    }

    @Test
    fun test_profileName_E6_theStoredRecordDoesNotCarryTheName() = runBlocking {
        val store = InMemoryKeyMetadataStore();
        val lifecycle = makeLifecycle(ScriptedTransport(listOf(answer(enrolled))), store);
        lifecycle.enrollWithSignIn(provider = "google") { SpfnSocialSignIn(idToken = "idtoken-test", displayName = personName) };

        val active = store.load(SpfnKeyLifecycle.ACTIVE_SLOT);
        assertEquals("key-test-0001", active?.keyId);
        assertFalse(active.toString().contains(personName));
    }

    // ---- E7: a second factor ends the same way with a name as without -----

    @Test
    fun test_profileName_E7_aSecondFactorAnswerIsTheSameRefusal() = runBlocking {
        val challenge = """{"mfaRequired":true,"challenge":{"secret":"private-challenge","expiresAtMillis":1750000060000}}""";
        val store = InMemoryKeyMetadataStore();
        val engine = ScriptedKeystoreEngine(mutableListOf(testKeyPair()));
        val transport = ScriptedTransport(listOf(answer(challenge, 202)));
        val lifecycle = makeLifecycle(transport, store, engine);

        val thrown = failureOf {
            lifecycle.enrollWithSignIn(provider = "google") { SpfnSocialSignIn(idToken = "idtoken-test", displayName = personName) };
        };

        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.SecondFactorRequired);
        assertEquals("this SDK version does not finish a second-factor sign-in", thrown?.message);
        assertTrue("the name was sent", transport.received.first().body!!.toString(Charsets.UTF_8).contains("\"profile\""));
        assertNull(store.load(SpfnKeyLifecycle.ACTIVE_SLOT));
        assertNull(store.load(SpfnKeyLifecycle.CANDIDATE_SLOT));
        assertFalse("the Keystore entry was deleted, not orphaned", engine.contains("spfn-client-key-key-test-0001"));
        assertEquals(SpfnKeyLifecycleState.UNENROLLED, lifecycle.state());
    }

    // ---- E8: the same names make the same bytes on both platforms ---------

    /**
     * Every sendable name in the shared table, the Korean one, a supplementary-plane
     * emoji with escapes around it, and the three characters one platform's own
     * whitespace test calls blank and the server does not.
     */
    @Test
    fun test_profileName_E8_everySharedVectorIsReproducedByteForByte() = runBlocking {
        val token = fixtureToken();
        val vectors = profileVectors().list("named").map { it.members() };
        assertEquals(5, vectors.size);

        for (vector in vectors)
        {
            val displayName = vector.text("displayName");
            val sent = enrolledBody { lifecycle ->
                lifecycle.enrollWithSignIn(provider = "google") { SpfnSocialSignIn(idToken = token, displayName = displayName) }
            };
            assertEquals("name ${displayName.map { it.code }}", vector.text("canonical"), sent);
            assertEquals(vector.text("sha256"), SpfnDigest.sha256Hex(sent.toByteArray(Charsets.UTF_8)));
        }
    }

    // ---- assembly ----------------------------------------------------------

    private class Refused(val thrown: Throwable?, val sent: String)

    /** Runs one enrollment the server accepts and answers the body it sent. */
    private suspend fun enrolledBody(enroll: suspend (SpfnKeyLifecycle) -> SpfnEnrollmentResult): String
    {
        val transport = ScriptedTransport(listOf(answer(enrolled)));
        enroll(makeLifecycle(transport, InMemoryKeyMetadataStore()));
        assertEquals(1, transport.received.size);
        return transport.received.first().body!!.toString(Charsets.UTF_8);
    }

    /** Runs one enrollment that ends in [outcome] and answers what it threw and sent. */
    private suspend fun refusedEnrollment(outcome: ScriptedTransport.Outcome, displayName: String?): Refused
    {
        val transport = ScriptedTransport(listOf(outcome));
        val lifecycle = makeLifecycle(transport, InMemoryKeyMetadataStore());
        val thrown = failureOf {
            lifecycle.enrollWithSignIn(provider = "google") { SpfnSocialSignIn(idToken = "idtoken-test", displayName = displayName) };
        };
        return Refused(thrown, transport.received.firstOrNull()?.body?.toString(Charsets.UTF_8) ?: "");
    }

    private fun assertNamesNoOne(thrown: Throwable?)
    {
        val texts = listOf(
            thrown.toString(),
            thrown?.message ?: "",
            thrown?.localizedMessage ?: "",
            thrown?.stackTraceToString() ?: ""
        );
        for (text in texts)
        {
            assertFalse(text, text.contains(personName));
        }
    }

    private fun makeLifecycle(
        transport: SpfnTransport,
        store: SpfnKeyMetadataStore,
        engine: ScriptedKeystoreEngine = ScriptedKeystoreEngine(mutableListOf(testKeyPair()))
    ): SpfnKeyLifecycle
    {
        val clock = FakeClock(1_750_000_000_000);
        return SpfnKeyLifecycle(
            transport = transport,
            store = store,
            engine = engine,
            baseUrl = baseUrl,
            clock = clock,
            proofClock = clock,
            nonceGenerator = ScriptedNonceGenerator(emptyList()),
            newKeyId = { "key-test-0001" }
        );
    }

    /** (privateKeyPkcs8Base64, publicKeySpkiBase64) for the primary fixture keypair. */
    private fun testKeyPair(): Pair<String, String>
    {
        val keyPair = WireFixtures.wire().obj("testKeyPair");
        return keyPair.text("privateKeyPkcs8Base64") to keyPair.text("publicKeySpkiBase64");
    }

    private fun answer(text: String, statusCode: Int = 200): ScriptedTransport.Outcome =
        ScriptedTransport.Outcome.Answer(jsonResponse(statusCode, text))

    private fun enrollment(): Map<String, SpfnCanonicalValue> =
        WireFixtures.load("Contracts/fixtures/enrollment/enrollment.json").members()

    private fun oauthNative(): Map<String, SpfnCanonicalValue> = enrollment().obj("oauthNative")

    private fun profileVectors(): Map<String, SpfnCanonicalValue> = enrollment().obj("oauthNativeProfile")

    private fun namedVector(displayName: String): Map<String, SpfnCanonicalValue> =
        profileVectors().list("named").map { it.members() }.first { it.text("displayName") == displayName }

    private fun fixtureToken(): String = oauthNative().obj("value").text("idToken")

    private suspend fun failureOf(body: suspend () -> Unit): Throwable?
    {
        try
        {
            body();
        }
        catch (failure: Throwable)
        {
            return failure;
        }
        throw AssertionError("expected a failure");
    }
}
