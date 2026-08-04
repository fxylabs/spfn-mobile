// SPFN Mobile — the key lifecycle contract: M1–M7 pinned.
//
// The flows run over the same scripted transport the execute suite uses, with the
// fixture keypairs injected through the engine seam as the "generated" keys — which is
// what lets the wire bytes a flow produces be compared against Contracts/fixtures byte
// for byte instead of against whatever the implementation happened to send (P10).
//
// SPFNKeyLifecycleTests.swift is the counterpart and uses corresponding case names.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.auth.SpfnEcdsa
import xyz.superfunction.spfn.core.SpfnDigest
import xyz.superfunction.spfn.generated.SpfnGeneratedContract
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class SpfnKeyLifecycleTest
{
    private val baseUrl = "https://example.invalid"
    private val ttlMillis: Long = SpfnGeneratedContract.KEY_POLICY_TTL_DAYS * 24 * 60 * 60 * 1_000

    // ---- M1 + M2: enrollment sends the fixture bytes and persists the identity

    @Test
    fun enrollSendsTheExactFixtureBytesAndPersistsTheIdentity() = runBlocking {
        val fixture = enrollmentFixture();
        val oauthNative = fixture.obj("oauthNative");
        val value = oauthNative.obj("value");

        val transport = ScriptedTransport(
            listOf(answer("{\"isNewUser\":true,\"keyId\":\"key-test-0001\",\"userId\":\"user-test-0001\"}"))
        );
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val result = lifecycle.enroll(
            provider = oauthNative.text("provider"),
            idToken = value.text("idToken"),
            nonce = SpfnSocialNonce.of(value.text("nonce"))
        );

        assertEquals(SpfnEnrollmentResult("user-test-0001", "key-test-0001", true), result);

        val sent = transport.received.first();
        assertEquals("POST", sent.method);
        assertEquals(baseUrl + oauthNative.text("path"), sent.url);
        assertEquals(
            "an unproven enrollment carries exactly the fixture's headers",
            oauthNative.headerPairs("headers"),
            sent.headers
        );
        assertEquals(
            "the enrollment body must be the fixture bytes exactly (M1)",
            oauthNative.text("canonical"),
            sent.body?.toString(Charsets.UTF_8)
        );

        // M2: the identity the server issued is what future proofs carry.
        val active = store.load(SpfnKeyLifecycle.ACTIVE_SLOT);
        assertEquals("user-test-0001", active?.clientId);
        assertEquals("key-test-0001", active?.keyId);
        val provider = lifecycle.activeProvider();
        assertEquals("user-test-0001", provider?.clientId);
        assertEquals("key-test-0001", provider?.keyId);
        assertEquals(SpfnKeyLifecycleState.ENROLLED, lifecycle.state());
    }

    /**
     * The fingerprint the flow computes must be the fixture's own derivation of the
     * same rule — the two platforms' byte-level agreement rides on this value (P9).
     */
    @Test
    fun theEnrollmentFingerprintMatchesTheFixtureDerivation()
    {
        val fingerprints = enrollmentFixture().obj("fingerprints");

        assertEquals(
            fingerprints.text("testKeySpkiSha256Hex"),
            SpfnDigest.sha256Hex(Base64.getDecoder().decode(testKeyPair().second))
        );
        assertEquals(
            fingerprints.text("wrongKeySpkiSha256Hex"),
            SpfnDigest.sha256Hex(Base64.getDecoder().decode(wrongKeyPair().second))
        );
    }

    // ---- M3: a failed enrollment leaves no orphan --------------------------

    @Test
    fun aFailedEnrollmentDestroysTheGeneratedKey() = runBlocking {
        val transport = ScriptedTransport(
            listOf(answer(ExecuteFixtures.errorEnvelope("CONTRACT_UNSUPPORTED"), 409))
        );
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val thrown = failureOf {
            lifecycle.enroll(provider = "google", idToken = "idtoken-test", nonce = SpfnSocialNonce.of("nonce-enroll-9999"));
        };

        assertNotNull(thrown);
        assertNull("nothing was persisted for a refused enrollment", store.load(SpfnKeyLifecycle.ACTIVE_SLOT));
        assertFalse("the Keystore entry was deleted, not orphaned", engine.contains("spfn-client-key-key-test-0001"));
        assertEquals(SpfnKeyLifecycleState.UNENROLLED, lifecycle.state());
    }

    @Test
    fun enrollRefusesAProviderThatIsNotAPathSegment() = runBlocking {
        val transport = ScriptedTransport(emptyList());
        val lifecycle = makeLifecycle(transport, InMemoryKeyMetadataStore(), scriptedEngine(), keyIds = emptyList());

        for (provider in listOf("", "Google", "google/../evil", "goo gle", "google{", "구글", "ｇoogle"))
        {
            val thrown = failureOf { lifecycle.enroll(provider = provider, idToken = "t", nonce = SpfnSocialNonce.of("n")) };
            assertTrue("'$provider' was accepted: $thrown", thrown is SpfnKeyLifecycleException.MalformedProviderId);
        }
        assertEquals(0, transport.callCount);
    }

    // ---- M4: rotation swaps on success, with the fixture's exact wire shape

    @Test
    fun rotateSendsTheWireVectorAndSwapsToTheCandidate() = runBlocking {
        val vector = WireFixtures.vector("rotate-key");
        val expected = vector.headerPairs("headers");
        val byName = expected.toMap();
        val issuedAt = byName.getValue(SpfnWireHeaders.ISSUED_AT_MILLIS).toLong();
        val nonce = byName.getValue(SpfnWireHeaders.NONCE);

        val transport = ScriptedTransport(listOf(answer("{\"keyId\":\"key-test-0002\",\"success\":true}")));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair(), wrongKeyPair());
        enrol(store, engine, keyId = "key-test-0001", clientId = "client-test-0001", createdAt = issuedAt);
        val lifecycle = makeLifecycle(
            transport,
            store,
            engine,
            keyIds = listOf("key-test-0002"),
            clock = FakeClock(issuedAt),
            nonces = listOf(nonce)
        );

        val result = lifecycle.rotate();

        assertEquals(SpfnEnrollmentResult("client-test-0001", "key-test-0002", false), result);

        val sent = transport.received.first();
        assertEquals(baseUrl + vector.text("path"), sent.url);
        assertHeadersMatchWireVector(sent.headers, expected, vector);
        assertEquals(vector.text("canonicalBody"), sent.body?.toString(Charsets.UTF_8));

        // Exactly one signable key, and it is the new one; the old entry is gone.
        assertEquals("key-test-0002", store.load(SpfnKeyLifecycle.ACTIVE_SLOT)?.keyId);
        assertNull(store.load(SpfnKeyLifecycle.CANDIDATE_SLOT));
        assertFalse(engine.contains("spfn-client-key-key-test-0001"));
        val provider = lifecycle.activeProvider();
        assertEquals("key-test-0002", provider?.keyId);
        val message = "probe".toByteArray(Charsets.UTF_8);
        assertTrue(verifies(Base64.getDecoder().decode(wrongKeyPair().second), message,
            requireNotNull(provider).sign(message)));
    }

    // ---- M5: every way a rotation fails, exactly one signable key ----------

    /**
     * A refusal in the same call that sent the request: the server did not apply it,
     * so the candidate is destroyed and the old key stays the one signer.
     */
    @Test
    fun aRefusedRotationKeepsTheOldKeyAndDiscardsTheCandidate() = runBlocking {
        val transport = ScriptedTransport(listOf(answer(ExecuteFixtures.errorEnvelope("PROOF_INVALID"), 401)));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair(), wrongKeyPair());
        enrol(store, engine, keyId = "key-test-0001", clientId = "client-test-0001");
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0002"));

        val thrown = failureOf { lifecycle.rotate() };

        assertTrue("got $thrown", thrown is SpfnClientError.Auth);
        assertEquals(SpfnGeneratedErrorCode.PROOF_INVALID, (thrown as SpfnClientError.Auth).failure.code);
        assertEquals("key-test-0001", store.load(SpfnKeyLifecycle.ACTIVE_SLOT)?.keyId);
        assertNull(store.load(SpfnKeyLifecycle.CANDIDATE_SLOT));
        assertFalse("the candidate's Keystore entry is gone", engine.contains("spfn-client-key-key-test-0002"));
        assertEquals(SpfnKeyLifecycleState.ENROLLED, lifecycle.state());
    }

    /**
     * A transport failure is the one outcome where the server's state is unknown:
     * the machine parks in ROTATION_PENDING, and the old key stays the only signer.
     */
    @Test
    fun aTransportFailureParksTheRotationWithTheOldKeyActive() = runBlocking {
        val transport = ScriptedTransport(
            listOf(ScriptedTransport.Outcome.Failure(SpfnTransportError.TimedOut()))
        );
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair(), wrongKeyPair());
        enrol(store, engine, keyId = "key-test-0001", clientId = "client-test-0001");
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0002"));

        val thrown = failureOf { lifecycle.rotate() };

        assertTrue("got $thrown", thrown is SpfnClientError.Transport);
        assertEquals(SpfnKeyLifecycleState.ROTATION_PENDING, lifecycle.state());
        assertEquals("the candidate never becomes signable by existing",
            "key-test-0001", lifecycle.activeProvider()?.keyId);

        // And while unresolved, no second rotation and no enrollment may start.
        val rotateAgain = failureOf { lifecycle.rotate() };
        assertTrue("got $rotateAgain", rotateAgain is SpfnKeyLifecycleException.RotationUnresolved);
    }

    /**
     * Resume, case one: the server never saw the first attempt. The re-send succeeds
     * and the swap completes as if nothing had died.
     */
    @Test
    fun resumeRetriesARotationTheServerNeverApplied() = runBlocking {
        val transport = ScriptedTransport(listOf(answer("{\"keyId\":\"key-test-0002\",\"success\":true}")));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair(), wrongKeyPair());
        enrol(store, engine, keyId = "key-test-0001", clientId = "client-test-0001");
        parkCandidate(store, engine, keyId = "key-test-0002", clientId = "client-test-0001");
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = emptyList());

        val result = lifecycle.resumeRotation();

        assertEquals("key-test-0002", result.keyId);
        assertEquals("key-test-0002", store.load(SpfnKeyLifecycle.ACTIVE_SLOT)?.keyId);
        assertNull(store.load(SpfnKeyLifecycle.CANDIDATE_SLOT));
    }

    /**
     * Resume, case two: PROOF_INVALID against a proof this SDK assembled correctly
     * means the old key is no longer registered — the earlier attempt WAS applied,
     * and the candidate is the key the server now honours.
     */
    @Test
    fun resumePromotesTheCandidateWhenTheOldKeyIsNoLongerRegistered() = runBlocking {
        val transport = ScriptedTransport(listOf(answer(ExecuteFixtures.errorEnvelope("PROOF_INVALID"), 401)));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair(), wrongKeyPair());
        enrol(store, engine, keyId = "key-test-0001", clientId = "client-test-0001");
        parkCandidate(store, engine, keyId = "key-test-0002", clientId = "client-test-0001");
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = emptyList());

        val result = lifecycle.resumeRotation();

        assertEquals("key-test-0002", result.keyId);
        assertEquals("key-test-0002", store.load(SpfnKeyLifecycle.ACTIVE_SLOT)?.keyId);
        assertNull(store.load(SpfnKeyLifecycle.CANDIDATE_SLOT));
    }

    /**
     * Resume, case three: a death between the swap and the candidate cleanup. Both
     * slots name one key, and the resume is only the cleanup — no network at all.
     */
    @Test
    fun resumeAfterADeathBetweenSwapAndCleanupOnlyCleansUp() = runBlocking {
        val transport = ScriptedTransport(emptyList());
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(wrongKeyPair());
        val key = SpfnKeystoreCustodyKey.generate("key-test-0002", engine);
        store.save(SpfnKeyLifecycle.ACTIVE_SLOT, key.metadata("client-test-0001", 1_750_000_000_000));
        store.save(SpfnKeyLifecycle.CANDIDATE_SLOT, key.metadata("client-test-0001", 1_750_000_000_000));
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = emptyList());

        val result = lifecycle.resumeRotation();

        assertEquals("key-test-0002", result.keyId);
        assertNull(store.load(SpfnKeyLifecycle.CANDIDATE_SLOT));
        assertEquals("a settled rotation costs no request", 0, transport.callCount);
    }

    // ---- M6: SESSION_REVOKED wipes -----------------------------------------

    @Test
    fun sessionRevokedDuringRotationWipesEverything() = runBlocking {
        val transport = ScriptedTransport(listOf(answer(ExecuteFixtures.errorEnvelope("SESSION_REVOKED"), 401)));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair(), wrongKeyPair());
        enrol(store, engine, keyId = "key-test-0001", clientId = "client-test-0001");
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0002"));

        failureOf { lifecycle.rotate() };

        assertNull(store.load(SpfnKeyLifecycle.ACTIVE_SLOT));
        assertNull(store.load(SpfnKeyLifecycle.CANDIDATE_SLOT));
        assertFalse(engine.contains("spfn-client-key-key-test-0001"));
        assertFalse(engine.contains("spfn-client-key-key-test-0002"));
        assertEquals(
            "the re-enrollment-required signal a caller reads",
            SpfnKeyLifecycleState.UNENROLLED,
            lifecycle.state()
        );
    }

    @Test
    fun noteSessionRevokedIsTheSameWipe() = runBlocking {
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        enrol(store, engine, keyId = "key-test-0001", clientId = "client-test-0001");
        val lifecycle = makeLifecycle(ScriptedTransport(emptyList()), store, engine, keyIds = emptyList());

        lifecycle.noteSessionRevoked();

        assertNull(store.load(SpfnKeyLifecycle.ACTIVE_SLOT));
        assertFalse(engine.contains("spfn-client-key-key-test-0001"));
        assertEquals(SpfnKeyLifecycleState.UNENROLLED, lifecycle.state());
    }

    // ---- M7: the TTL judgment ----------------------------------------------

    @Test
    fun rotationDueFollowsTheKeyPolicyTtl() = runBlocking {
        val createdAt = 1_750_000_000_000L;
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        enrol(store, engine, keyId = "key-test-0001", clientId = "client-test-0001", createdAt = createdAt);

        // One millisecond inside the TTL: not due, and the remainder says how close.
        val clock = FakeClock(createdAt + ttlMillis - 1);
        val lifecycle = makeLifecycle(ScriptedTransport(emptyList()), store, engine, keyIds = emptyList(), clock = clock);
        assertEquals(1L, lifecycle.keyRemainingMillis());
        assertFalse(lifecycle.rotationDue());

        // With a lead time, the same moment is already due — the foreground trigger.
        assertTrue(lifecycle.rotationDue(leadTimeMillis = 24 * 60 * 60 * 1_000));

        // At the boundary the key has reached its TTL.
        clock.set(createdAt + ttlMillis);
        assertTrue(lifecycle.rotationDue());

        // No key, nothing due.
        store.delete(SpfnKeyLifecycle.ACTIVE_SLOT);
        assertNull(lifecycle.keyRemainingMillis());
        assertFalse(lifecycle.rotationDue());
    }

    // ---- assembly ----------------------------------------------------------

    private fun answer(text: String, statusCode: Int = 200): ScriptedTransport.Outcome =
        ScriptedTransport.Outcome.Answer(jsonResponse(statusCode, text))

    private fun enrollmentFixture(): Map<String, xyz.superfunction.spfn.core.SpfnCanonicalValue> =
        WireFixtures.load("Contracts/fixtures/enrollment/enrollment.json").members()

    private fun verifies(publicKeySpkiDer: ByteArray, message: ByteArray, rawSignature: ByteArray): Boolean
    {
        val verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(SpfnEcdsa.publicKeyFromSpki(publicKeySpkiDer));
        verifier.update(message);
        return verifier.verify(SpfnEcdsa.rawToDer(rawSignature));
    }

    /** (privateKeyPkcs8Base64, publicKeySpkiBase64) for the primary fixture keypair. */
    private fun testKeyPair(): Pair<String, String>
    {
        val keyPair = WireFixtures.wire().obj("testKeyPair");
        return keyPair.text("privateKeyPkcs8Base64") to keyPair.text("publicKeySpkiBase64");
    }

    /** The second fixture keypair, standing in for a freshly generated rotation key. */
    private fun wrongKeyPair(): Pair<String, String>
    {
        val keyPair = WireFixtures.load("Contracts/fixtures/proof/proof-input.json").members().obj("wrongKeyPair");
        return keyPair.text("privateKeyPkcs8Base64") to keyPair.text("publicKeySpkiBase64");
    }

    private fun scriptedEngine(vararg pairs: Pair<String, String>): ScriptedKeystoreEngine =
        ScriptedKeystoreEngine(pairs.toMutableList())

    private fun enrol(
        store: SpfnKeyMetadataStore,
        engine: ScriptedKeystoreEngine,
        keyId: String,
        clientId: String,
        createdAt: Long = 1_750_000_000_000
    )
    {
        val key = SpfnKeystoreCustodyKey.generate(keyId, engine);
        store.save(SpfnKeyLifecycle.ACTIVE_SLOT, key.metadata(clientId = clientId, createdAtMillis = createdAt));
    }

    private fun parkCandidate(
        store: SpfnKeyMetadataStore,
        engine: ScriptedKeystoreEngine,
        keyId: String,
        clientId: String
    )
    {
        val key = SpfnKeystoreCustodyKey.generate(keyId, engine);
        store.save(SpfnKeyLifecycle.CANDIDATE_SLOT, key.metadata(clientId = clientId, createdAtMillis = 1_750_000_000_000));
    }

    private fun makeLifecycle(
        transport: SpfnTransport,
        store: SpfnKeyMetadataStore,
        engine: SpfnKeystoreEngine,
        keyIds: List<String>,
        clock: FakeClock = FakeClock(1_750_000_000_000),
        nonces: List<String> = emptyList()
    ): SpfnKeyLifecycle
    {
        val remaining = keyIds.toMutableList();
        return SpfnKeyLifecycle(
            transport = transport,
            store = store,
            engine = engine,
            baseUrl = baseUrl,
            clock = clock,
            nonceGenerator = ScriptedNonceGenerator(nonces),
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
}

/**
 * An engine over scripted fixture keypairs: each generate() installs the next fixed
 * pair instead of a random one, which is what lets a flow's wire bytes be pinned.
 */
class ScriptedKeystoreEngine(
    private val scripted: MutableList<Pair<String, String>>
) : SpfnKeystoreEngine
{
    private val keys = mutableMapOf<String, java.security.KeyPair>()

    override fun generate(alias: String, preferStrongBox: Boolean): SpfnKeystoreGeneratedKey
    {
        check(scripted.isNotEmpty()) { "the suite scripted no further keypairs" };
        val (privateB64, publicB64) = scripted.removeAt(0);
        val private = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateB64)));
        val public = SpfnEcdsa.publicKeyFromSpki(Base64.getDecoder().decode(publicB64));
        keys[alias] = java.security.KeyPair(public, private);
        return SpfnKeystoreGeneratedKey(SpfnKeyCustody.TRUSTED_ENVIRONMENT, public.encoded);
    }

    override fun publicKeySpkiDer(alias: String): ByteArray? = keys[alias]?.public?.encoded

    override fun signDer(alias: String, message: ByteArray): ByteArray
    {
        val key = keys[alias]?.private ?: throw IllegalStateException("no signing key under this alias");
        val signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(message);
        return signer.sign();
    }

    override fun contains(alias: String): Boolean = keys.containsKey(alias)

    override fun delete(alias: String)
    {
        keys.remove(alias);
    }
}
