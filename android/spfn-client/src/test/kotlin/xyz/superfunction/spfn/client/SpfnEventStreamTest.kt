// SPFN Mobile — the whole event stream over the fakes (docs/architecture/event-stream-design.md §9-2).
//
// The token call goes through the real `execute` and a real session; the stream goes
// through the fake stream transport; time is the test scheduler's. What is asserted is
// what reaches each boundary — signed headers on the token call, the exact stream URL —
// and what a listener receives. SPFNEventStreamTests.swift carries the same names.

package xyz.superfunction.spfn.client

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class SpfnEventStreamTest
{
    private val attached = SpfnEventSignal.Reread(SpfnRereadCause.Attached);

    private class Fixture(scope: TestScope, keyIds: List<String> = emptyList())
    {
        val tokens = TokenServer();
        val streams = SpfnFakeStreamTransport();
        val keyLifecycle = signedInLifecycle(tokens, keyIds);
        val events = SpfnEventStream(
            keyLifecycle = keyLifecycle,
            configuration = SpfnEventStreamConfiguration(events = listOf("sessionUnread", "sessionActivity")),
            transport = streams,
            scope = scope.backgroundScope,
            jitter = { 1.0 }
        );
    }

    private fun decodeText(value: SpfnCanonicalValue): String =
        ((value as SpfnCanonicalValue.Obj).members["sessionId"] as SpfnCanonicalValue.Text).value;

    @Test
    fun endToEnd_fakeTransport_opensDeliversReconnectsAndSignsOut() = runTest {
        val fixture = Fixture(this);
        val first = fixture.streams.enqueue();
        val signals = mutableListOf<SpfnEventSignal<String>>();
        backgroundScope.launch { fixture.events.listen("sessionActivity", ::decodeText).collect { signals.add(it) } };

        fixture.events.setSignedIn("client-test-0001");
        fixture.events.setForeground(true);
        runCurrent();

        val tokenRequest = fixture.tokens.tokenRequests.single();
        assertEquals("POST", tokenRequest.method);
        assertEquals("https://example.invalid/events/token", tokenRequest.url);
        assertTrue("the token call is signed", tokenRequest.headers.any { it.first == SpfnWireHeaders.SESSION });
        assertTrue(tokenRequest.headers.containsAll(SpfnClientIdentity.headers));

        val streamRequest = fixture.streams.requests.single();
        assertEquals("GET", streamRequest.method);
        assertEquals("https://example.invalid/events/stream?token=token-1&events=sessionActivity,sessionUnread", streamRequest.url);
        assertTrue(streamRequest.headers.contains("accept" to "text/event-stream"));
        assertFalse("the stream GET is not signed", streamRequest.headers.any { it.first == SpfnWireHeaders.SESSION });
        assertEquals(SpfnEventStreamState.Connecting(1), fixture.events.state.value);

        first.send(ServerFrames.CONNECTED);
        runCurrent();
        assertEquals(SpfnEventStreamState.Open(1), fixture.events.state.value);
        assertEquals(listOf(attached, SpfnEventSignal.Reread(SpfnRereadCause.Opened(1))), signals);

        first.send(ServerFrames.PING + ServerFrames.activity(1, "s-1", "w-1"));
        runCurrent();
        assertEquals(SpfnEventSignal.Frame("s-1"), signals.last());

        val second = fixture.streams.enqueue();
        first.fail();
        runCurrent();
        assertEquals(SpfnEventStreamState.Retrying(2, 1_000, SpfnRetryReason.Network), fixture.events.state.value);
        advanceTimeBy(1_001);
        runCurrent();
        assertTrue(fixture.streams.requests.last().url.contains("token=token-2&"));
        second.send(ServerFrames.CONNECTED);
        runCurrent();
        assertEquals(SpfnEventStreamState.Open(2), fixture.events.state.value);
        assertEquals(SpfnEventSignal.Reread(SpfnRereadCause.Opened(2)), signals.last());

        fixture.events.setSignedIn(null);
        runCurrent();
        assertTrue(second.cancelled);
        assertEquals(SpfnEventStreamState.Idle(SpfnIdleReason.SIGNED_OUT), fixture.events.state.value);
    }

    @Test
    fun endToEnd_silenceWatchdog_reconnects() = runTest {
        val fixture = Fixture(this);
        val quiet = fixture.streams.enqueue();
        fixture.events.setSignedIn("client-test-0001");
        fixture.events.setForeground(true);
        runCurrent();
        quiet.send(ServerFrames.CONNECTED);
        runCurrent();
        advanceTimeBy(24_000);
        quiet.send(ServerFrames.PING);
        runCurrent();
        advanceTimeBy(24_000);
        runCurrent();
        assertEquals(SpfnEventStreamState.Open(1), fixture.events.state.value);
        advanceTimeBy(1_001);
        runCurrent();
        // Open for 49 s, past the stable interval, so the retry starts over at attempt 1.
        assertEquals(SpfnEventStreamState.Retrying(1, 1_000, SpfnRetryReason.Silence), fixture.events.state.value);
        assertTrue(quiet.cancelled);
    }

    @Test
    fun endToEnd_token429_honoursRetryAfter() = runTest {
        val fixture = Fixture(this);
        fixture.tokens.tokenAnswers.addLast(
            SpfnTransportResponse(
                statusCode = 429,
                headers = listOf("content-type" to "application/json", "Retry-After" to "12") + announcementHeaders(),
                body = ExecuteFixtures.errorEnvelope("TooManyRequestsError").toByteArray()
            )
        );
        fixture.events.setSignedIn("client-test-0001");
        fixture.events.setForeground(true);
        runCurrent();
        assertEquals(SpfnEventStreamState.Retrying(2, 12_000, SpfnRetryReason.ServerError(429)), fixture.events.state.value);
        advanceTimeBy(11_000);
        runCurrent();
        assertEquals(1, fixture.tokens.tokenRequests.size);
        advanceTimeBy(1_001);
        runCurrent();
        assertEquals(2, fixture.tokens.tokenRequests.size);
    }

    /**
     * Defect anticipation: `StateFlow` conflates, so a collector can miss `open(1)` when
     * `open(2)` follows quickly. The reread a listener needs is in its own queue, not in
     * the state, so the listener still reads for the epoch it ends on.
     */
    @Test
    fun stateConflation_doesNotLoseReread() = runTest {
        val fixture = Fixture(this);
        val first = fixture.streams.enqueue();
        val second = fixture.streams.enqueue();
        val signals = mutableListOf<SpfnEventSignal<String>>();
        val collector = backgroundScope.launch { fixture.events.listen("sessionActivity", ::decodeText).collect { signals.add(it) } };
        runCurrent();
        fixture.events.setSignedIn("client-test-0001");
        fixture.events.setForeground(true);
        runCurrent();
        first.send(ServerFrames.CONNECTED);
        first.finish();
        runCurrent();
        advanceTimeBy(1_001);
        runCurrent();
        second.send(ServerFrames.CONNECTED);
        runCurrent();
        assertEquals(SpfnEventStreamState.Open(2), fixture.events.state.value);
        assertEquals(SpfnEventSignal.Reread(SpfnRereadCause.Opened(2)), signals.last());
        collector.cancel();
    }

    @Test
    fun listeners_neverReachMachine() = runTest {
        val fixture = Fixture(this);
        val connection = fixture.streams.enqueue();
        fixture.events.setSignedIn("client-test-0001");
        fixture.events.setForeground(true);
        runCurrent();
        connection.send(ServerFrames.CONNECTED);
        runCurrent();

        val random = Random(20_260_927);
        val live = mutableListOf<kotlinx.coroutines.Job>();
        repeat(1_000) { step ->
            if (live.isNotEmpty() && random.nextBoolean())
            {
                live.removeAt(random.nextInt(live.size)).cancel();
            }
            else
            {
                val condition: (String) -> Boolean = when (random.nextInt(3))
                {
                    0 -> { _ -> true }
                    1 -> { _ -> false }
                    else -> { _ -> throw IllegalStateException("condition bug") }
                };
                live.add(backgroundScope.launch { runCatching { fixture.events.listen("sessionActivity", ::decodeText, condition).collect { } } });
            }
            if (step % 50 == 0)
            {
                connection.send(ServerFrames.activity(step, "s-$step", "w-1"));
            }
            runCurrent();
        };
        assertEquals(1, fixture.tokens.tokenRequests.size);
        assertEquals(1, fixture.streams.requests.size);
        assertTrue(fixture.streams.requests.single().url.endsWith("&events=sessionActivity,sessionUnread"));
        assertEquals(SpfnEventStreamState.Open(1), fixture.events.state.value);
    }

    // ---- the token call signs with the key signed in at the moment of the call ----

    /**
     * E-41: a rotation leaves the open stream alone, and the next token call — here the
     * reconnect after a drop — is signed by the new key, for the same account.
     */
    @Test
    fun tokenCall_afterRotate_signsWithTheNewKey() = runTest {
        val fixture = Fixture(this, keyIds = listOf("key-test-0002"));
        fixture.tokens.routes[SpfnGeneratedOperations.authKeysRotate.path] = jsonResponse(200, "{\"keyId\":\"key-test-0002\",\"success\":true}");
        val first = fixture.streams.enqueue();
        open(fixture, first);
        assertEquals(listOf("client-test-0001", "key-test-0001"), signer(fixture.tokens.tokenRequests.last()));

        fixture.keyLifecycle.rotate();
        runCurrent();
        assertEquals("a rotation does not touch the connection", SpfnEventStreamState.Open(1), fixture.events.state.value);
        fixture.streams.enqueue();
        first.fail();
        runCurrent();
        advanceTimeBy(1_001);
        runCurrent();
        assertEquals(2, fixture.streams.requests.size);
        assertEquals(2, fixture.tokens.tokenRequests.size);
        assertEquals(listOf("client-test-0001", "key-test-0002"), signer(fixture.tokens.tokenRequests.last()));
    }

    /**
     * E-43: another account's key is enrolled while the stream is open for the first; the
     * token call that follows `setSignedIn(other)` signs as the other account.
     */
    @Test
    fun tokenCall_afterSignedInAsOther_signsAsTheOtherAccount() = runTest {
        val fixture = Fixture(this, keyIds = listOf("key-test-0002"));
        fixture.tokens.routes["/_auth/oauth/google/native"] =
            jsonResponse(200, "{\"mfaRequired\":false,\"isNewUser\":false,\"keyId\":\"key-test-0002\",\"userId\":\"client-test-0002\"}");
        val first = fixture.streams.enqueue();
        open(fixture, first);

        fixture.keyLifecycle.wipe();
        fixture.keyLifecycle.enroll(provider = "google") { "id-token-test" };
        fixture.streams.enqueue();
        fixture.events.setSignedIn("client-test-0002");
        runCurrent();
        assertTrue(first.cancelled);
        assertEquals(2, fixture.streams.requests.size);
        assertEquals(2, fixture.tokens.tokenRequests.size);
        assertEquals(listOf("client-test-0002", "key-test-0002"), signer(fixture.tokens.tokenRequests.last()));
    }

    /**
     * E-40: after a wipe nothing is sent. The sign-out the host passes on leaves the stream
     * idle; a sign-in input with no key behind it sends nothing either.
     */
    @Test
    fun afterWipe_noTokenCall_idleSignedOut() = runTest {
        val fixture = Fixture(this);
        val first = fixture.streams.enqueue();
        open(fixture, first);
        val sentBefore = fixture.tokens.requests.size;

        fixture.keyLifecycle.wipe();
        fixture.events.setSignedIn(fixture.keyLifecycle.signedInClientId.value);
        runCurrent();
        assertTrue(first.cancelled);
        assertEquals(SpfnEventStreamState.Idle(SpfnIdleReason.SIGNED_OUT), fixture.events.state.value);

        fixture.events.setSignedIn("client-test-0001");
        runCurrent();
        assertEquals(SpfnEventStreamState.Closed(SpfnCloseReason.Unauthorized), fixture.events.state.value);
        assertEquals("no handshake and no token call without a key", sentBefore, fixture.tokens.requests.size);
        assertEquals(1, fixture.streams.requests.size);
    }

    private fun TestScope.open(fixture: Fixture, connection: SpfnFakeStreamTransport.FakeStream)
    {
        fixture.events.setSignedIn("client-test-0001");
        fixture.events.setForeground(true);
        runCurrent();
        connection.send(ServerFrames.CONNECTED);
        runCurrent();
        assertEquals(SpfnEventStreamState.Open(1), fixture.events.state.value);
    }

    /** The client id and key id a signed request names. */
    private fun signer(request: SpfnTransportRequest): List<String?> =
        listOf(SpfnWireHeaders.CLIENT_ID, SpfnWireHeaders.KEY_ID).map { name -> request.headers.firstOrNull { it.first == name }?.second };

    @Test
    fun token_neverPrinted() = runTest {
        val token = SpfnEventStreamToken("f".repeat(64));
        assertEquals("SpfnEventStreamToken(redacted)", token.toString());
        assertFalse(SpfnEventEffect.OpenStream(1, listOf("a"), token).toString().contains("ffff"));
        assertFalse(SpfnEventInput.TokenMinted(1, token).toString().contains("ffff"));

        val fixture = Fixture(this);
        fixture.streams.enqueue();
        fixture.events.setSignedIn("client-test-0001");
        fixture.events.setForeground(true);
        runCurrent();
        assertFalse(fixture.streams.requests.single().toString().contains("token-1"));
        assertFalse(fixture.events.state.value.toString().contains("token"));
    }
}
