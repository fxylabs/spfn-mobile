// SPFN Mobile — the key lifecycle contract: M1–M7 pinned.
//
// The flows run over the same scripted transport the execute suite uses, with the
// fixture keypairs injected through the engine seam as the "generated" keys — which is
// what lets the wire bytes a flow produces be compared against Contracts/fixtures byte
// for byte instead of against whatever the implementation happened to send (P10).
//
// SPFNKeyLifecycleTests.swift is the counterpart and uses corresponding case names.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import kotlin.coroutines.cancellation.CancellationException

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

        // The fixture's token is answered as the sign-in's result. The nonce is not
        // passed at all any more: the lifecycle derives it from the key it generated,
        // and the body assertion below is what proves it derived the fixture's value.
        val fixtureToken = value.text("idToken");
        val result = lifecycle.enroll(provider = oauthNative.text("provider")) { fixtureToken };

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
            lifecycle.enroll(provider = "google") { "idtoken-test" };
        };

        assertNotNull(thrown);
        assertNull("nothing was persisted for a refused enrollment", store.load(SpfnKeyLifecycle.ACTIVE_SLOT));
        assertFalse("the Keystore entry was deleted, not orphaned", engine.contains("spfn-client-key-key-test-0001"));
        assertEquals(SpfnKeyLifecycleState.UNENROLLED, lifecycle.state());
    }

    /**
     * The same M3 promise on the other side of the request. A server that accepted the
     * enrollment and a store that then refuses to write leaves the one arrangement M3
     * exists to forbid: a Keystore alias with nothing naming it, which the retry cannot
     * find and cannot reuse.
     *
     * This is the boundary Swift does not have — there a failed enrollment drops an
     * in-memory value, while here the alias is already in the Keystore before the
     * request is sent.
     */
    @Test
    fun anEnrollmentThatCannotPersistDestroysTheGeneratedKey() = runBlocking {
        val refusal = IllegalStateException("the keystore metadata file is unwritable");
        val transport = ScriptedTransport(
            listOf(answer("{\"isNewUser\":true,\"keyId\":\"key-test-0001\",\"userId\":\"user-test-0001\"}"))
        );
        val store = RefusingKeyMetadataStore(refusal);
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val thrown = failureOf {
            lifecycle.enroll(provider = "google") { "idtoken-test" };
        };

        assertEquals("the store's own refusal reaches the caller", refusal, thrown);
        assertFalse(
            "a save that threw must not leave the Keystore alias behind",
            engine.contains("spfn-client-key-key-test-0001")
        );
        assertEquals(SpfnKeyLifecycleState.UNENROLLED, lifecycle.state());
    }

    @Test
    fun enrollRefusesAProviderThatIsNotAPathSegment() = runBlocking {
        val transport = ScriptedTransport(emptyList());
        val lifecycle = makeLifecycle(transport, InMemoryKeyMetadataStore(), scriptedEngine(), keyIds = emptyList());

        for (provider in listOf("", "Google", "google/../evil", "goo gle", "google{", "구글", "ｇoogle"))
        {
            val thrown = failureOf { lifecycle.enroll(provider = provider) { "t" } };
            assertTrue("'$provider' was accepted: $thrown", thrown is SpfnKeyLifecycleException.MalformedProviderId);
        }
        assertEquals(0, transport.callCount);
    }

    // ---- the enroll cells the closure entry point added -------------------

    /** Cell 2: a key already exists — refused, and the sign-in never runs. */
    @Test
    fun c2_enrolledRefusesAndRunsNoSignIn() = runBlocking {
        val transport = ScriptedTransport(emptyList());
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-existing-0001"));
        // An install that already holds a key: the metadata is what `state()` reads, and
        // writing it directly is how the M-series rows set this up too.
        store.save(
            SpfnKeyLifecycle.ACTIVE_SLOT,
            SpfnStoredKeyMetadata(
                keyId = "key-existing-0001",
                clientId = "user-existing-0001",
                custody = SpfnKeyCustody.TRUSTED_ENVIRONMENT,
                createdAtMillis = 1_750_000_000_000,
                alias = "spfn-client-key-key-existing-0001"
            )
        );

        var signInRan = 0;
        val thrown = failureOf { lifecycle.enroll(provider = "apple") { signInRan++; "t" } };

        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.AlreadyEnrolled);
        assertEquals("an already-enrolled install must not put a sign-in sheet up", 0, signInRan);
    }

    /** Cell 3: a rotation is unresolved — refused, and the sign-in never runs. */
    @Test
    fun c3_rotationPendingRefusesAndRunsNoSignIn() = runBlocking {
        val transport = ScriptedTransport(emptyList());
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));
        store.save(
            SpfnKeyLifecycle.CANDIDATE_SLOT,
            SpfnStoredKeyMetadata(
                keyId = "key-candidate-0001",
                clientId = "user-existing-0001",
                custody = SpfnKeyCustody.TRUSTED_ENVIRONMENT,
                createdAtMillis = 1_750_000_000_000,
                alias = "spfn-client-key-key-candidate-0001"
            )
        );

        var signInRan = 0;
        val thrown = failureOf { lifecycle.enroll(provider = "apple") { signInRan++; "t" } };

        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.RotationUnresolved);
        assertEquals("an unresolved rotation must not put a sign-in sheet up", 0, signInRan);
        assertEquals("an unresolved rotation must not reach the network", 0, transport.callCount);
    }

    /**
     * Cell 4: a second enrollment during the first one's sign-in is refused.
     *
     * The state checks cannot catch this on their own — an enrollment in progress has
     * saved nothing, so both calls read UNENROLLED. Without the in-flight claim both
     * would generate a Keystore entry and register it, and the second save would bury the
     * first registration while the server kept honouring it.
     */
    @Test
    fun c4_aSecondEnrollmentDuringTheSignInIsRefused() = runBlocking {
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

        val first = async {
            lifecycle.enroll(provider = "apple")
            {
                arrived.complete(Unit);
                release.await();
                "idtoken-first";
            }
        };
        arrived.await();

        val thrown = failureOf { lifecycle.enroll(provider = "apple") { "idtoken-second" } };
        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.EnrollmentInFlight);

        release.complete(Unit);
        val result = first.await();

        assertEquals("the first enrollment is the one that settled", "key-test-0001", result.keyId);
        assertEquals("exactly one registration reached the server", 1, transport.callCount);
    }

    /**
     * Cell 4: the claim is released however the call leaves, so a failed enrollment does
     * not lock the install out of enrolling again. The release is deliberately not a
     * suspending one — a cancelled coroutine could not run that, and a claim outliving its
     * call would be permanent.
     */
    @Test
    fun c4_aFailedEnrollmentReleasesTheClaim() = runBlocking {
        val transport = ScriptedTransport(
            listOf(answer("{\"isNewUser\":true,\"keyId\":\"key-test-0002\",\"userId\":\"user-test-0001\"}"))
        );
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair(), testKeyPair());
        val lifecycle = makeLifecycle(
            transport, store, engine,
            keyIds = listOf("key-test-0001", "key-test-0002")
        );

        val thrown = failureOf { lifecycle.enroll(provider = "apple") { throw SignInRefused() } };
        assertTrue("$thrown", thrown is SignInRefused);

        val result = lifecycle.enroll(provider = "apple") { "idtoken-retry" };
        assertEquals("key-test-0002", result.keyId);
    }

    /**
     * Cell 20: a rotation started during a sign-in answers NotEnrolled, because at that
     * moment the install genuinely holds no key. This is also what proves the mutex is
     * not held across the sign-in: a held mutex would hang here instead of answering.
     */
    @Test
    fun c20_rotateDuringTheSignInAnswersNotEnrolled() = runBlocking {
        val transport = ScriptedTransport(
            listOf(answer("{\"isNewUser\":true,\"keyId\":\"key-test-0001\",\"userId\":\"user-test-0001\"}"))
        );
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));
        val arrived = CompletableDeferred<Unit>();
        val release = CompletableDeferred<Unit>();

        val first = async {
            lifecycle.enroll(provider = "apple")
            {
                arrived.complete(Unit);
                release.await();
                "idtoken-first";
            }
        };
        arrived.await();

        val thrown = failureOf { lifecycle.rotate() };
        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.NotEnrolled);

        release.complete(Unit);
        first.await();
        Unit;
    }

    /**
     * Cell 5: a cancelled sign-in reaches the caller as the cancellation it was, and the
     * Keystore entry the enrollment generated is deleted. This is the case the whole
     * closure shape exists for: the key has to be made before the provider is asked, so
     * abandoning the sheet must not strand it.
     */
    @Test
    fun c5_aCancelledSignInPropagatesUnchangedAndDestroysTheKey() = runBlocking {
        val transport = ScriptedTransport(emptyList());
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val thrown = failureOf { lifecycle.enroll(provider = "apple") { throw CancellationException("dismissed") } };

        assertTrue("the cancellation was reshaped into $thrown", thrown is CancellationException);
        assertEquals("a cancelled sign-in must not reach the network", 0, transport.callCount);
        assertFalse("the Keystore entry was deleted, not orphaned", engine.contains("spfn-client-key-key-test-0001"));
        assertEquals(SpfnKeyLifecycleState.UNENROLLED, lifecycle.state());
    }

    /** Cell 6: any other refusal from the sign-in reaches the caller unchanged. */
    @Test
    fun c6_aRefusedSignInPropagatesUnchangedAndDestroysTheKey() = runBlocking {
        val transport = ScriptedTransport(emptyList());
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));
        val refusal = SignInRefused();

        val thrown = failureOf { lifecycle.enroll(provider = "apple") { throw refusal } };

        assertSame("the provider's own error is the app's to read", refusal, thrown);
        assertEquals(0, transport.callCount);
        assertFalse(engine.contains("spfn-client-key-key-test-0001"));
    }

    /**
     * Cell 7: an empty token is refused here rather than sent. The server can only refuse
     * it, and its refusal for this is outside the contract's error codes — so the app
     * would read an unknown code naming nothing.
     */
    @Test
    fun c7_anEmptyTokenIsRefusedBeforeTheNetwork() = runBlocking {
        val transport = ScriptedTransport(emptyList());
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val thrown = failureOf { lifecycle.enroll(provider = "apple") { "" } };

        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.IdTokenMissing);
        assertEquals(0, transport.callCount);
        assertFalse(engine.contains("spfn-client-key-key-test-0001"));
    }

    /**
     * Cell 9: a success naming a different key is refused and stores nothing. A server
     * that confirms another key has not registered the one this device holds.
     */
    @Test
    fun c9_successNamingAnotherKeyIsRefusedAndStoresNothing() = runBlocking {
        val transport = ScriptedTransport(
            listOf(answer("{\"isNewUser\":true,\"keyId\":\"key-other-9999\",\"userId\":\"user-test-0001\"}"))
        );
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val thrown = failureOf { lifecycle.enroll(provider = "apple") { "idtoken-apple" } };

        assertTrue("$thrown", thrown is SpfnKeyLifecycleException.ServerNamedAnotherKey);
        assertNull(store.load(SpfnKeyLifecycle.ACTIVE_SLOT));
        assertFalse(engine.contains("spfn-client-key-key-test-0001"));
    }

    /**
     * Cell 11: a transport failure destroys the key and hands the error on. Enrollment is
     * the one flow where a lost answer needs no resume: nothing was persisted, so the next
     * attempt starts from the state this one started from.
     */
    @Test
    fun c11_aTransportFailureDestroysTheKeyAndPropagates() = runBlocking {
        val transport = ScriptedTransport(listOf(ScriptedTransport.Outcome.Failure(SpfnTransportError.Connectivity("offline"))));
        val store = InMemoryKeyMetadataStore();
        val engine = scriptedEngine(testKeyPair());
        val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

        val thrown = failureOf { lifecycle.enroll(provider = "apple") { "idtoken-apple" } };

        assertNotNull(thrown);
        assertNull(store.load(SpfnKeyLifecycle.ACTIVE_SLOT));
        assertFalse(engine.contains("spfn-client-key-key-test-0001"));
    }

    /**
     * Cell 15: the body's nonce is the fingerprint, and the sign-in was handed a nonce
     * minted for the provider the call named. The apple case is the one that matters:
     * its request value is a different string from the one the body carries.
     */
    @Test
    fun c15_theBodyCarriesTheFingerprintAndTheSignInGetsTheProvidersShape() = runBlocking {
        for (provider in listOf("apple", "google", "kakao", "naver"))
        {
            val transport = ScriptedTransport(
                listOf(answer("{\"isNewUser\":true,\"keyId\":\"key-test-0001\",\"userId\":\"user-test-0001\"}"))
            );
            val store = InMemoryKeyMetadataStore();
            val engine = scriptedEngine(testKeyPair());
            val lifecycle = makeLifecycle(transport, store, engine, keyIds = listOf("key-test-0001"));

            var handed: SpfnSocialNonce? = null;
            lifecycle.enroll(provider = provider) { nonce -> handed = nonce; "idtoken-$provider" };

            val nonce = requireNotNull(handed);
            assertEquals(provider, nonce.provider);

            val body = String(requireNotNull(transport.received.first().body));
            assertTrue(
                "$provider: the body's nonce must be the fingerprint",
                body.contains("\"nonce\":\"${nonce.fingerprint}\"")
            );
            assertTrue(
                "$provider: the body's fingerprint must be the same value",
                body.contains("\"fingerprint\":\"${nonce.fingerprint}\"")
            );
            if (provider == "apple")
            {
                assertFalse(
                    "apple: the body must not carry the value the authorization request took",
                    body.contains(nonce.requestValue)
                );
            }
        }
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

    /** What a sign-in that refuses looks like: an error of the app's own making. */
    private class SignInRefused : IllegalStateException("the app's sign-in refused")

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

/**
 * A store that reads like any other and refuses every write. It stands for the one
 * failure the enrollment path cannot retry its way out of: the server has already
 * accepted the key, so nothing but destroying the alias leaves the device consistent.
 */
class RefusingKeyMetadataStore(private val failure: Throwable) : SpfnKeyMetadataStore
{
    private val records = mutableMapOf<String, SpfnStoredKeyMetadata>()

    override fun load(slot: String): SpfnStoredKeyMetadata? = records[slot]

    override fun save(slot: String, metadata: SpfnStoredKeyMetadata)
    {
        throw failure;
    }

    override fun delete(slot: String)
    {
        records.remove(slot);
    }
}
