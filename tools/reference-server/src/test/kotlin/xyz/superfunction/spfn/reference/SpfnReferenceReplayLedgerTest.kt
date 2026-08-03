// SPFN Mobile — the replay ledger stays bounded without ever forgetting too early.
//
// A ledger that only grows is fine for the length of a test run and wrong as a statement
// of how the rule works, so entries are dropped once the window has passed them. The
// dangerous version of that is dropping one moment too soon: the nonce becomes spendable
// again while a proof carrying it would still be accepted. These tests sit on both sides
// of that boundary.

package xyz.superfunction.spfn.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.superfunction.spfn.auth.SpfnProofInput
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations

class SpfnReferenceReplayLedgerTest
{
    @Test
    fun `a nonce inside the window is refused as a replay`()
    {
        val clock = SpfnReferenceTestClock();
        val state = SpfnReferenceState(clock);
        val start = clock.nowMillis();

        assertNull(admit(state, NONCE, start));
        clock.advance(WINDOW);

        assertEquals("PROOF_REPLAYED", admit(state, NONCE, start)?.code?.wireCode);
        assertEquals(1, state.stats().spentNonceCount);
    }

    @Test
    fun `an entry past the window is dropped, and the proof that carried it is expired anyway`()
    {
        val clock = SpfnReferenceTestClock();
        val state = SpfnReferenceState(clock);
        val start = clock.nowMillis();

        assertNull(admit(state, NONCE, start));
        clock.advance(WINDOW + 1);

        // Expired, not replayed: the entry is gone, and the window check refuses the
        // presentation before the ledger is ever consulted.
        assertEquals("PROOF_EXPIRED", admit(state, NONCE, start)?.code?.wireCode);
        assertEquals(0, state.stats().spentNonceCount);
    }

    @Test
    fun `a nonce becomes spendable again only once the window has passed it`()
    {
        val clock = SpfnReferenceTestClock();
        val state = SpfnReferenceState(clock);
        val start = clock.nowMillis();

        assertNull(admit(state, NONCE, start));
        clock.advance(WINDOW + 1);

        // The contract admits one (clientId, nonce) per window, not one for all time.
        assertNull(admit(state, NONCE, clock.nowMillis()));
    }

    @Test
    fun `expired sessions do not accumulate`()
    {
        val clock = SpfnReferenceTestClock();
        val state = SpfnReferenceState(clock, sessionTtlMillis = 1_000);

        repeat(5) { state.openSession(SpfnReferenceTestKeys.CLIENT_ID, SpfnReferenceTestKeys.KEY_ID) };
        assertEquals(5, state.stats().liveSessionCount);

        clock.advance(1_001);
        assertEquals(0, state.stats().liveSessionCount);
    }

    private fun admit(state: SpfnReferenceState, nonce: String, issuedAtMillis: Long): SpfnReferenceRefusal?
    {
        val operation = SpfnGeneratedOperations.echoSend;
        val input = SpfnProofInput.forRequest(
            method = operation.method,
            path = operation.path,
            clientId = SpfnReferenceTestKeys.CLIENT_ID,
            keyId = SpfnReferenceTestKeys.KEY_ID,
            nonce = nonce,
            issuedAtMillis = issuedAtMillis,
            canonicalBody = BODY
        );
        return state.admit(
            clientId = SpfnReferenceTestKeys.CLIENT_ID,
            keyId = SpfnReferenceTestKeys.KEY_ID,
            presentedSessionId = null,
            requiresSession = false,
            proofInput = input,
            presentedProof = SpfnReferenceTestKeys.proofFor(input)
        );
    }

    private companion object
    {
        const val NONCE = "nonce-ledger-000001"
        const val WINDOW = SpfnReferenceState.DEFAULT_REPLAY_WINDOW_MILLIS
        val BODY: ByteArray = "{\"message\":\"ledger\",\"sequence\":1}".toByteArray(Charsets.UTF_8)
    }
}
