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
// The same five run against a server in another process when the run names one — see
// `SpfnReferenceTarget.kt`. Every state a case arranges goes through the harness's control
// surface, so these bodies are the same code in both modes and record the same receipts.

package xyz.superfunction.spfn.reference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.client.SpfnClientError
import xyz.superfunction.spfn.client.SpfnTransportError
import xyz.superfunction.spfn.client.SpfnTransportRequest
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.generated.SpfnEchoRequest
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnListItemsRequest

class SpfnAndroidReferenceIntegrationTest
{
    @Test
    fun `case a - handshake, echo and items list round trip over HTTP`() = runBlocking<Unit>
    {
        SpfnReferenceClientHarness().use { harness ->
            val echoed = harness.client.execute(SpfnReferenceCalls.echo, SpfnEchoRequest("over the wire", 42));
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
     * new key — while the replaced key is refused with the non-disclosing PROOF_INVALID.
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

            val userId = "user-kotlin-f-0001";
            val nonce = "nonce-kotlin-f-0001";
            val enrolled = lifecycle.enroll(
                provider = "google",
                idToken = "spfn-test-idtoken.google.$userId.$nonce",
                nonce = nonce
            );
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
            // sent — the server-side half of the same rule (the non-disclosing
            // PROOF_INVALID for a rotated-away key) is pinned by
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
                assertEquals(SpfnGeneratedErrorCode.PROOF_INVALID, refused.failure.code);
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
    }
}
