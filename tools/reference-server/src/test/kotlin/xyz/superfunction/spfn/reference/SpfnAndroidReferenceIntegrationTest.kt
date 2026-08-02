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
            assertEquals(SpfnReferenceTestClock.DEFAULT_START_MILLIS, echoed.serverTimeMillis);

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
        SpfnReferenceClientHarness(sessionTtlMillis = 1_000).use { harness ->
            harness.client.execute(SpfnReferenceCalls.echo, SpfnEchoRequest("before", 1));
            assertEquals(1, harness.stats().handshakeCount);

            // The server moves past the expiry it advertised. The client's clock does not,
            // so it presents the session it still believes in rather than pre-emptively
            // opening a new one — which is the only way to reach the refusal path.
            harness.server.clock.advance(1_500);

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

            harness.server.server.state.revokeKey(SpfnReferenceTestKeys.KEY_ID);

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
                url = harness.server.baseUrl + operation.path,
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

                harness.server.server.state.holdPath(SpfnGeneratedOperations.echoSend.path, HOLD_MILLIS, 1);
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
                harness.server.server.state.holdPath(SpfnGeneratedOperations.echoSend.path, HOLD_MILLIS, 1);

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

    private companion object
    {
        /** Long enough that neither the timeout nor the cancellation can be a coincidence. */
        const val HOLD_MILLIS = 3_000L
    }
}
