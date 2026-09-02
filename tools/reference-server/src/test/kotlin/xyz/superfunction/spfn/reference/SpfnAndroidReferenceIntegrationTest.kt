// SPFN Mobile — the Android SDK against a real socket.
//
// Excluded from `./gradlew build` by the `*IntegrationTest` naming rule and run by
// `./gradlew :reference-server:spfnIntegrationTest`, which is what
// `sh tools/reference-server/run-integration.sh` invokes. Every case records a receipt,
// so a run that skipped everything cannot be reported as a run that passed.
//
// The five cases are the ones nothing below a socket can prove:
//   (a) three operations answer their declared types with the values sent
//   (b) an expired session costs exactly one re-handshake and then succeeds
//   (c) a revocation the client cannot fix surfaces as an auth failure
//   (d) a request replayed byte for byte is refused
//   (e) a timeout and a cancellation both work while a real server is holding the call
//
// Contract 0.10.0 adds the device-code flow, which needs two SDKs at once — one waiting
// and one approving — and so is five more:
//   (f) enrollment, a proof, a rotation and the new key's proof
//   (g) a waiting device is approved from a device already signed in, and can then prove
//   (h) a denial ends the wait, over the contract's one bodyless operation
//   (i) an expired code ends the wait locally, before the server is asked
//   (j) a second approval of one code is refused
//   (k) an approval nobody proved is refused by admission
//
// The same five run against a server in another process when the run names one — see
// `SpfnReferenceTarget.kt`. Every state a case arranges goes through the harness's control
// surface, so these bodies are the same code in both modes and record the same receipts.

package xyz.superfunction.spfn.reference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.client.SpfnClient
import xyz.superfunction.spfn.client.SpfnClientError
import xyz.superfunction.spfn.client.SpfnDelaySleeper
import xyz.superfunction.spfn.client.SpfnKeyLifecycle
import xyz.superfunction.spfn.client.SpfnKeyLifecycleException
import xyz.superfunction.spfn.client.SpfnKeyLifecycleState
import xyz.superfunction.spfn.client.SpfnSleeper
import xyz.superfunction.spfn.client.SpfnSocialNonce
import xyz.superfunction.spfn.client.SpfnTransport
import xyz.superfunction.spfn.client.SpfnTransportError
import xyz.superfunction.spfn.client.SpfnTransportRequest
import xyz.superfunction.spfn.client.SpfnTransportResponse
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnDigest
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.core.SpfnNoResponse
import xyz.superfunction.spfn.generated.SpfnApproveDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDenyDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnDeviceAuthInfoRequest
import xyz.superfunction.spfn.generated.SpfnEchoRequest
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnKeyAlgorithm
import xyz.superfunction.spfn.generated.SpfnKeyPlatform
import xyz.superfunction.spfn.generated.SpfnListItemsRequest
import xyz.superfunction.spfn.generated.SpfnPollDeviceAuthRequest
import xyz.superfunction.spfn.generated.SpfnStartDeviceAuthRequest

class SpfnAndroidReferenceIntegrationTest
{
    @Test
    fun `case a - handshake, echo and items list round trip over HTTP`() = runBlocking<Unit>
    {
        SpfnReferenceClientHarness().use { harness ->
            val clockLeadMillis = harness.proofClockLeadMillis();
            assertTrue(
                "the synchronized proof clock leads a later server sample by ${clockLeadMillis}ms",
                clockLeadMillis <= 0
            );
            val echoed = try
            {
                harness.client.execute(SpfnReferenceCalls.echo, SpfnEchoRequest("over the wire", 42));
            }
            catch (failure: SpfnClientError.Auth)
            {
                throw AssertionError(
                    "the refused proof leads a later server sample by ${harness.lastProofLeadMillis()}ms",
                    failure
                );
            }
            assertEquals("over the wire", echoed.message);
            assertEquals(42L, echoed.sequence);
            assertServerTime(harness, echoed.serverTimeMillis);

            val first = harness.client.execute(SpfnReferenceCalls.listItems, SpfnListItemsRequest(limit = 2));
            assertEquals(listOf("item-0001", "item-0002"), first.items.map { it.id });
            assertEquals(listOf("alpha", "bravo"), first.items.map { it.name });
            assertEquals("item-0002", first.nextCursor);

            val rest = harness.client.execute(
                SpfnReferenceCalls.listItems,
                SpfnListItemsRequest(limit = 10, cursor = "item-0002")
            );
            assertEquals(listOf("item-0003", "item-0004", "item-0005"), rest.items.map { it.id });
            assertNull(rest.nextCursor);

            val stats = harness.stats();
            assertEquals(1, stats.handshakeCount);
            assertEquals(1, stats.echoCount);
            assertEquals(2, stats.itemsListCount);
            assertEquals(0, stats.refusalCount);

            SpfnIntegrationReceipt.record("kotlin-a");
        }
    }

    @Test
    fun `case b - an expired session is recovered with exactly one re-handshake`() = runBlocking<Unit>
    {
        SpfnReferenceClientHarness().use { harness ->
            harness.client.execute(SpfnReferenceCalls.echo, SpfnEchoRequest("before", 1));
            assertEquals(1, harness.stats().handshakeCount);

            // The server drops the session without touching the expiry it advertised, so
            // the client goes on believing in it and presents it rather than pre-emptively
            // opening a new one — which is the only way to reach the refusal path. The
            // clock is not touched: an external server runs on the wall clock, and a case
            // that moved a test clock would only be runnable against the local one.
            harness.control.expireSessions();

            val after = harness.client.execute(SpfnReferenceCalls.echo, SpfnEchoRequest("after", 2));
            assertEquals("after", after.message);

            val stats = harness.stats();
            assertEquals("exactly one re-handshake", 2, stats.handshakeCount);
            assertEquals("the refused attempt was not applied", 2, stats.echoCount);
            assertEquals(1, stats.refusalCount);

            SpfnIntegrationReceipt.record("kotlin-b");
        }
    }

    @Test
    fun `case c - a revoked key surfaces after the one re-handshake fails too`() = runBlocking<Unit>
    {
        SpfnReferenceClientHarness().use { harness ->
            harness.client.execute(SpfnReferenceCalls.echo, SpfnEchoRequest("before", 1));

            harness.control.revokeKey(SpfnReferenceTestKeys.KEY_ID);

            val failure = runCatching {
                harness.client.execute(SpfnReferenceCalls.echo, SpfnEchoRequest("after", 2))
            }.exceptionOrNull();

            val auth = failure as? SpfnClientError.Auth ?: throw AssertionError("expected an auth failure, got $failure");
            assertEquals(SpfnGeneratedErrorCode.SESSION_REVOKED, auth.failure.code);
            assertEquals(401, auth.failure.httpStatus);

            val stats = harness.stats();
            assertEquals("the operation and the re-handshake were both refused", 2, stats.refusalCount);
            assertEquals("no second session was ever opened", 1, stats.handshakeCount);

            SpfnIntegrationReceipt.record("kotlin-c");
        }
    }

    @Test
    fun `case d - a request replayed byte for byte is refused`() = runBlocking<Unit>
    {
        SpfnReferenceClientHarness().use { harness ->
            val operation = SpfnGeneratedOperations.echoSend;
            val body = SpfnCanonicalJson.encode(SpfnEchoRequest("replay me", 3).canonicalValue());

            // Assembled through the session, so the nonce, the timestamp and the proof are
            // the ones the SDK would really have sent. Replaying is then the SDK's own
            // request, sent twice — not a hand-built approximation of one.
            val headers = harness.session.proofHeaders(operation, body);
            val request = SpfnTransportRequest(
                method = operation.method,
                url = harness.baseUrl + operation.path,
                headers = headers,
                body = body,
                timeoutMillis = 5_000
            );

            assertEquals(200, harness.transport.execute(request).statusCode);

            val replayed = harness.transport.execute(request);
            assertEquals(401, replayed.statusCode);
            assertEquals(
                "PROOF_REPLAYED",
                SpfnErrorEnvelope.decode(SpfnCanonicalJson.parse(replayed.body)).code
            );

            SpfnIntegrationReceipt.record("kotlin-d");
        }
    }

    @Test
    fun `case e - a timeout and a cancellation both work against a server that is waiting`()
    {
        SpfnReferenceClientHarness(timeoutMillis = 400).use { harness ->
            runBlocking {
                harness.client.execute(SpfnReferenceCalls.echo, SpfnEchoRequest("warm up", 1));

                harness.control.hold(SpfnGeneratedOperations.echoSend.path, HOLD_MILLIS, 1);
                val timedOut = runCatching {
                    harness.client.execute(SpfnReferenceCalls.echo, SpfnEchoRequest("too slow", 2))
                }.exceptionOrNull();

                val transportFailure = timedOut as? SpfnClientError.Transport
                    ?: throw AssertionError("expected a transport failure, got $timedOut");
                assertTrue(
                    "expected a timeout, got ${transportFailure.error}",
                    transportFailure.error is SpfnTransportError.TimedOut
                );
            }

            runBlocking {
                harness.control.hold(SpfnGeneratedOperations.echoSend.path, HOLD_MILLIS, 1);

                val startedAt = System.nanoTime();
                val call = async {
                    harness.client.execute(SpfnReferenceCalls.echo, SpfnEchoRequest("give up", 3))
                };
                delay(200);
                call.cancel();

                val cancelled = runCatching { call.await() }.exceptionOrNull();
                val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

                assertTrue("expected cancellation, got $cancelled", cancelled is CancellationException);
                assertTrue(
                    "cancellation waited for the server instead of stopping the call ($elapsedMillis ms)",
                    elapsedMillis < HOLD_MILLIS
                );
            }

            SpfnIntegrationReceipt.record("kotlin-e");
        }
    }

    /**
     * The REST enrollment surface end to end (case f): enrollment, a proof round trip
     * with the enrolled key, a rotation proved by it, and a proof round trip with the
     * new key — while the replaced key is refused at the revocation step with
     * SESSION_REVOKED.
     *
     * In process the surface always exists — the server is this repository's own. An
     * external target carries it only when the run says so (`spfn.integrationRestOps`),
     * because the primitives dev server has the three dev operations and no `/_auth`
     * surface; against it this case is out of scope and its receipt is not expected.
     */
    @Test
    fun `case f - enrollment, proof, rotation and the new key's proof`() = runBlocking<Unit>
    {
        val restOps = SpfnIntegrationTarget.resolve() == null ||
            System.getProperty("spfn.integrationRestOps") == "1";
        org.junit.Assume.assumeTrue(
            "case f SKIPPED: the named target is assumed to carry only the dev three-operation surface",
            restOps
        );

        SpfnReferenceClientHarness().use { harness ->
            // Software custody on purpose: the suite runs on a JVM with no Keystore,
            // and hardware custody is the COMPATIBILITY axis.
            val engine = SpfnIntegrationSoftwareEngine();
            val store = SpfnIntegrationMetadataStore();
            val lifecycle = xyz.superfunction.spfn.client.SpfnKeyLifecycle(
                transport = harness.transport,
                store = store,
                engine = engine,
                baseUrl = harness.baseUrl,
                clock = harness.clientClock
            );

            // The token is minted inside the sign-in closure because it has to carry the
            // nonce, and the nonce is the fingerprint of a key that does not exist until
            // the enrollment generates it. That ordering is the whole reason the entry
            // point takes a closure. Google echoes the raw value, so `requestValue` here
            // is the fingerprint the reference server compares the body against.
            val userId = "user-kotlin-f-0001";
            val enrolled = lifecycle.enroll(provider = "google")
            { nonce ->
                "spfn-test-idtoken.google.$userId.${nonce.requestValue}"
            };
            assertEquals(userId, enrolled.clientId);
            assertTrue(enrolled.isNewUser);

            // A proven round trip under the enrolled key: handshake, echo, exact values.
            val firstProvider = requireNotNull(lifecycle.activeProvider());
            val echoed = harness.client(firstProvider).execute(
                SpfnReferenceCalls.echo,
                SpfnEchoRequest("enrolled key proves", 61)
            );
            assertEquals("enrolled key proves", echoed.message);

            // Rotate under the old key's proof; the lifecycle swaps to the new key.
            val rotated = lifecycle.rotate();
            assertEquals(userId, rotated.clientId);
            assertTrue(rotated.keyId != enrolled.keyId);

            val newProvider = requireNotNull(lifecycle.activeProvider());
            assertEquals(rotated.keyId, newProvider.keyId);
            val again = harness.client(newProvider).execute(
                SpfnReferenceCalls.echo,
                SpfnEchoRequest("rotated key proves", 62)
            );
            assertEquals("rotated key proves", again.message);

            // The replaced key cannot prove anything. On this platform the swap also
            // deleted the Keystore entry, so the stale provider fails before a byte is
            // sent — the server-side half of the same rule (SESSION_REVOKED at the
            // revocation step for a rotated-away key, per revocationRule) is pinned by
            // SpfnReferenceRestOpsTest, where the key material is test-owned.
            try
            {
                harness.client(firstProvider).execute(
                    SpfnReferenceCalls.echo,
                    SpfnEchoRequest("stale key", 63)
                );
                org.junit.Assert.fail("the replaced key must not prove anything");
            }
            catch (refused: SpfnClientError.Auth)
            {
                assertEquals(SpfnGeneratedErrorCode.SESSION_REVOKED, refused.failure.code);
            }
            catch (destroyed: IllegalStateException)
            {
                assertTrue(
                    "got $destroyed",
                    destroyed.message == "no signing key under this alias"
                );
            }

            SpfnIntegrationReceipt.record("kotlin-f");
        }
    }

    // ---- the device-code flow: two SDKs, one code -------------------------
    //
    // Every case here runs SDK A (waiting, with a fresh store) and SDK B (already signed
    // in, the approver) against one server. A's entry point blocks until somebody
    // answers, so it runs in its own coroutine and the case does B's half in between —
    // which is exactly the shape the flow has in life.

    /**
     * Case g, the happy path: A shows a code, B recognises the device by its fingerprint
     * prefix and approves, A's next poll lands the approval, and A can then prove with
     * the key that approval registered — and rotate it, because a key enrolled this way
     * has to be indistinguishable from one `enroll()` produced.
     */
    @Test
    fun `case g - a waiting device is approved and can then prove and rotate`() = runBlocking<Unit>
    {
        assumeRestOps("g");
        SpfnReferenceClientHarness().use { harness ->
            val approver = enrolApprover(harness, "user-kotlin-g-0001");
            val waiting = waitingDevice(harness, "key-kotlin-g-0001");
            val shown = CompletableDeferred<String>();

            val signIn = async {
                waiting.lifecycle.enrollByDeviceCode(deviceName = DEVICE_NAME)
                { userCode, _ -> shown.complete(userCode) };
            };
            val userCode = shown.await();

            // B looks before it decides: the answer names the device that is waiting, and
            // the prefix is over A's real public key rather than over anything B chose.
            val described = approver.client.execute(
                SpfnReferenceCalls.deviceInfo,
                SpfnDeviceAuthInfoRequest(userCode)
            );
            assertEquals(DEVICE_NAME, described.deviceName);
            assertEquals(
                SpfnDigest.sha256Hex(waiting.engine.onlyPublicKeySpkiDer()).take(FINGERPRINT_PREFIX_LENGTH),
                described.fingerprintPrefix
            );

            val approved = approver.client.execute(
                SpfnReferenceCalls.deviceApprove,
                SpfnApproveDeviceAuthRequest(userCode)
            );
            assertEquals("approve answers with the device it just let in", DEVICE_NAME, approved.deviceName);

            val settled = signIn.await();
            assertEquals("the approver's account is the one A joined", approver.clientId, settled.clientId);
            assertEquals("key-kotlin-g-0001", settled.keyId);
            assertEquals(false, settled.passwordChangeRequired);
            assertEquals(SpfnKeyLifecycleState.ENROLLED, waiting.lifecycle.state());

            val provider = requireNotNull(waiting.lifecycle.activeProvider());
            val echoed = harness.client(provider).execute(
                SpfnReferenceCalls.echo,
                SpfnEchoRequest("approved key proves", 71)
            );
            assertEquals("approved key proves", echoed.message);

            // A record this flow saved must be one every other lifecycle path accepts.
            val rotated = waiting.lifecycle.rotate();
            assertEquals(approver.clientId, rotated.clientId);
            assertTrue(rotated.keyId != settled.keyId);
            val rotatedEcho = harness.client(requireNotNull(waiting.lifecycle.activeProvider())).execute(
                SpfnReferenceCalls.echo,
                SpfnEchoRequest("rotated after device sign-in", 72)
            );
            assertEquals("rotated after device sign-in", rotatedEcho.message);

            SpfnIntegrationReceipt.record("kotlin-g");
        }
    }

    /**
     * Case h, the denial — and the end-to-end proof of the contract's one bodyless
     * operation: `deny` answers 204 with an empty body, the SDK decodes that as the unit
     * value, and A ends holding no key at all.
     */
    @Test
    fun `case h - a denial ends the wait and leaves no key`() = runBlocking<Unit>
    {
        assumeRestOps("h");
        SpfnReferenceClientHarness().use { harness ->
            val approver = enrolApprover(harness, "user-kotlin-h-0001");
            val waiting = waitingDevice(harness, "key-kotlin-h-0001");
            val shown = CompletableDeferred<String>();

            val signIn = async {
                runCatching {
                    waiting.lifecycle.enrollByDeviceCode(deviceName = DEVICE_NAME)
                    { userCode, _ -> shown.complete(userCode) };
                };
            };
            val userCode = shown.await();

            val denied = approver.client.execute(SpfnReferenceCalls.deviceDeny, SpfnDenyDeviceAuthRequest(userCode));
            assertSame("a bodyless operation answers with the unit value", SpfnNoResponse, denied);

            val failure = signIn.await().exceptionOrNull();
            val refused = failure as? SpfnClientError.Server
                ?: throw AssertionError("expected the denial, got $failure");
            assertEquals(SpfnGeneratedErrorCode.DeviceAuthDeniedError, refused.failure.code);
            assertEquals(SpfnKeyLifecycleState.UNENROLLED, waiting.lifecycle.state());
            assertNull("a refused device keeps no key", waiting.lifecycle.activeProvider());

            SpfnIntegrationReceipt.record("kotlin-h");
        }
    }

    /**
     * Case i, expiry: the wait ends on A's own deadline before the server is asked, and a
     * poll sent by hand afterwards is what shows the server would have refused it too.
     *
     * A's sleeper is injected here and nowhere else in this file. The case has to advance
     * the server's clock while A is between two polls, and racing a real 200ms wait would
     * make the assertion "no poll was sent" true or false by scheduling.
     */
    @Test
    fun `case i - an expired code ends the wait before the server is asked`() = runBlocking<Unit>
    {
        assumeRestOps("i");
        SpfnReferenceClientHarness().use { harness ->
            val clockMoved = CompletableDeferred<Unit>();
            val waiting = waitingDevice(harness, "key-kotlin-i-0001", sleeper = { clockMoved.await() });
            val shown = CompletableDeferred<Long>();

            val signIn = async {
                runCatching {
                    waiting.lifecycle.enrollByDeviceCode(deviceName = DEVICE_NAME)
                    { _, expiresAtMillis -> shown.complete(expiresAtMillis) };
                };
            };
            shown.await();

            if (!harness.control.advanceClock(EXPIRY_ADVANCE_MILLIS))
            {
                signIn.cancel();
                throw AssertionError(
                    "case i was expected to run but the target's clock cannot be moved; " +
                        "run without -Pspfn.integrationTestClock=1 when the target is on the wall clock"
                );
            }
            clockMoved.complete(Unit);

            val failure = signIn.await().exceptionOrNull();
            assertTrue(
                "the expiry is A's own judgment, got $failure",
                failure is SpfnKeyLifecycleException.DeviceCodeExpired
            );
            assertEquals("no poll was sent for a code A knows is dead", 0, waiting.polls());
            assertEquals(SpfnKeyLifecycleState.UNENROLLED, waiting.lifecycle.state());

            // And the server's own answer, on a code this case parks by hand: the two
            // ends agree that an expired record is refused rather than kept waiting on.
            val parked = harness.client.execute(
                SpfnReferenceCalls.deviceStart,
                startRequest("key-kotlin-i-0002", freshKeySpkiDer())
            );
            harness.control.advanceClock(EXPIRY_ADVANCE_MILLIS);
            val refused = runCatching {
                harness.client.execute(SpfnReferenceCalls.devicePoll, SpfnPollDeviceAuthRequest(parked.deviceCode))
            }.exceptionOrNull() as? SpfnClientError.Server
                ?: throw AssertionError("expected the server to refuse an expired code");
            assertEquals(SpfnGeneratedErrorCode.DeviceAuthExpiredError, refused.failure.code);

            SpfnIntegrationReceipt.record("kotlin-i");
        }
    }

    /** Case j: a decision on a device is made once, and the second approval is told so. */
    @Test
    fun `case j - a second approval of one code is refused`() = runBlocking<Unit>
    {
        assumeRestOps("j");
        SpfnReferenceClientHarness().use { harness ->
            val approver = enrolApprover(harness, "user-kotlin-j-0001");
            val waiting = waitingDevice(harness, "key-kotlin-j-0001");
            val shown = CompletableDeferred<String>();

            val signIn = async {
                waiting.lifecycle.enrollByDeviceCode(deviceName = DEVICE_NAME)
                { userCode, _ -> shown.complete(userCode) };
            };
            val userCode = shown.await();

            approver.client.execute(SpfnReferenceCalls.deviceApprove, SpfnApproveDeviceAuthRequest(userCode));
            val second = runCatching {
                approver.client.execute(SpfnReferenceCalls.deviceApprove, SpfnApproveDeviceAuthRequest(userCode))
            }.exceptionOrNull() as? SpfnClientError.Server
                ?: throw AssertionError("a second approval must be refused");
            assertEquals(SpfnGeneratedErrorCode.DeviceAuthAlreadyHandledError, second.failure.code);

            assertEquals("the first approval still stands", approver.clientId, signIn.await().clientId);

            SpfnIntegrationReceipt.record("kotlin-j");
        }
    }

    /**
     * Case k: `approve` is the one call that binds an account, so it is the one that has
     * to be proved. Sent through the transport rather than the client, because the SDK
     * cannot be talked into sending a proven operation unproven — which is itself the
     * point, and is why this is asserted against the server instead.
     */
    @Test
    fun `case k - an approval nobody proved is refused by admission`() = runBlocking<Unit>
    {
        assumeRestOps("k");
        SpfnReferenceClientHarness().use { harness ->
            val approver = enrolApprover(harness, "user-kotlin-k-0001");
            val waiting = waitingDevice(harness, "key-kotlin-k-0001");
            val shown = CompletableDeferred<String>();

            val signIn = async {
                waiting.lifecycle.enrollByDeviceCode(deviceName = DEVICE_NAME)
                { userCode, _ -> shown.complete(userCode) };
            };
            val userCode = shown.await();

            val operation = SpfnGeneratedOperations.authDeviceApprove;
            val unproven = harness.transport.execute(
                SpfnTransportRequest(
                    method = operation.method,
                    url = harness.baseUrl + operation.path,
                    headers = listOf("content-type" to "application/json"),
                    body = SpfnCanonicalJson.encode(SpfnApproveDeviceAuthRequest(userCode).canonicalValue()),
                    timeoutMillis = 5_000
                )
            );
            assertTrue("an unproven approval must not be applied", unproven.statusCode >= 400);
            assertEquals(
                "CONTRACT_UNSUPPORTED",
                SpfnErrorEnvelope.decode(SpfnCanonicalJson.parse(unproven.body)).code
            );

            // The record was not touched, which the approval that still works proves.
            approver.client.execute(SpfnReferenceCalls.deviceApprove, SpfnApproveDeviceAuthRequest(userCode));
            assertEquals(approver.clientId, signIn.await().clientId);

            SpfnIntegrationReceipt.record("kotlin-k");
        }
    }

    // ---- what the five device cases are built out of -----------------------

    /** SDK B: a device already signed in, which is who approves. */
    private class Approver(val clientId: String, val client: SpfnClient)

    /** SDK A: the waiting device, with its own store, engine and counted transport. */
    private class WaitingDevice(
        val lifecycle: SpfnKeyLifecycle,
        val engine: SpfnIntegrationSoftwareEngine,
        private val transport: SpfnCountingTransport
    )
    {
        /** How many polls this device has sent. Case i's whole assertion is that it is zero. */
        fun polls(): Int = transport.countEndingWith(SpfnGeneratedOperations.authDevicePoll.path)
    }

    private suspend fun enrolApprover(harness: SpfnReferenceClientHarness, userId: String): Approver
    {
        val lifecycle = SpfnKeyLifecycle(
            transport = harness.transport,
            store = SpfnIntegrationMetadataStore(),
            engine = SpfnIntegrationSoftwareEngine(),
            baseUrl = harness.baseUrl,
            clock = harness.clientClock,
            proofClock = harness.proofClock
        );
        val enrolled = lifecycle.enroll(provider = "google")
        { nonce -> "spfn-test-idtoken.google.$userId.${nonce.requestValue}" };
        return Approver(enrolled.clientId, harness.client(requireNotNull(lifecycle.activeProvider())));
    }

    private fun waitingDevice(
        harness: SpfnReferenceClientHarness,
        keyId: String,
        sleeper: SpfnSleeper = SpfnDelaySleeper()
    ): WaitingDevice
    {
        val engine = SpfnIntegrationSoftwareEngine();
        val transport = SpfnCountingTransport(harness.transport);
        val minted = ArrayDeque(listOf(keyId));
        return WaitingDevice(
            SpfnKeyLifecycle(
                transport = transport,
                store = SpfnIntegrationMetadataStore(),
                engine = engine,
                baseUrl = harness.baseUrl,
                clock = harness.clientClock,
                proofClock = harness.proofClock,
                sleeper = sleeper,
                // The first key is named, so a case can talk about the key it parked;
                // every later one is fresh, because a rotation that reused the id would
                // generate its candidate over the old key's own alias and destroy the
                // signer it is supposed to rotate away from.
                newKeyId = { minted.removeFirstOrNull() ?: java.util.UUID.randomUUID().toString().lowercase() }
            ),
            engine,
            transport
        );
    }

    /** A `start` body over a key this case generated, for the polls it sends by hand. */
    private fun startRequest(keyId: String, spkiDer: ByteArray): SpfnStartDeviceAuthRequest =
        SpfnStartDeviceAuthRequest(
            publicKey = java.util.Base64.getEncoder().encodeToString(spkiDer),
            keyId = keyId,
            fingerprint = SpfnDigest.sha256Hex(spkiDer),
            algorithm = SpfnKeyAlgorithm.ES256,
            deviceName = DEVICE_NAME,
            platform = SpfnKeyPlatform.ANDROID
        )

    private fun freshKeySpkiDer(): ByteArray
    {
        val generator = java.security.KeyPairGenerator.getInstance("EC");
        generator.initialize(java.security.spec.ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair().public.encoded;
    }

    /**
     * The device cases need the `/_auth` surface, and case i needs a clock a test can
     * move. In process both are always true. Against a named target neither is, so the
     * run says which it has and a case that cannot be arranged skips loudly rather than
     * asserting something weaker.
     */
    private fun assumeRestOps(case: String)
    {
        val external = SpfnIntegrationTarget.resolve() != null;
        val restOps = !external || System.getProperty("spfn.integrationRestOps") == "1";
        org.junit.Assume.assumeTrue(
            "case $case SKIPPED: the named target is assumed to carry only the dev three-operation surface",
            restOps
        );
        if (case == "i")
        {
            val testClock = !external || System.getProperty("spfn.integrationTestClock") == "1";
            org.junit.Assume.assumeTrue(
                "case i SKIPPED: the named target runs on the wall clock, so an expired code cannot be arranged",
                testClock
            );
        }
    }

    /**
     * Checks the instant `echo.send` answered with, as closely as this run can.
     *
     * In process the server runs on a test clock this suite starts, so the value is exact.
     * An external server runs on its own wall clock, and the only thing that stays checkable
     * is that it answered with a real instant rather than a zero or an absent field decoded
     * as one.
     */
    private fun assertServerTime(harness: SpfnReferenceClientHarness, serverTimeMillis: Long)
    {
        val expected = harness.expectedServerTimeMillis;
        if (expected != null)
        {
            assertEquals(expected, serverTimeMillis);
        }
        else
        {
            assertTrue("serverTimeMillis was $serverTimeMillis", serverTimeMillis > 0);
        }
    }

    private companion object
    {
        /** Long enough that neither the timeout nor the cancellation can be a coincidence. */
        const val HOLD_MILLIS = 3_000L

        /** The label the waiting device gives itself; display only, nothing is authorized by it. */
        const val DEVICE_NAME = "Kotlin waiting device"

        /** Past any device code's TTL, so the record is expired whatever state it is in. */
        const val EXPIRY_ADVANCE_MILLIS = 900_000L

        /** Upstream `KEY_FINGERPRINT_PREFIX_LENGTH`, which is what the approver is shown. */
        const val FINGERPRINT_PREFIX_LENGTH = 8
    }
}

/**
 * Counts what one SDK sent, by path.
 *
 * Case i asserts that a device whose code expired sent no poll at all, and "no request"
 * is only assertable per client: the harness transport is shared with the approver, which
 * is busy sending its own.
 */
private class SpfnCountingTransport(private val delegate: SpfnTransport) : SpfnTransport
{
    private val urls = mutableListOf<String>()

    fun countEndingWith(path: String): Int = synchronized(urls) { urls.count { it.endsWith(path) } }

    override suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse
    {
        synchronized(urls) { urls.add(request.url) };
        return delegate.execute(request);
    }
}
