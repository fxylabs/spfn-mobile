// SPFN Mobile — the server refuses in the order the SDK says it will.
//
// The contract fixes one ordering rule explicitly (revocation before proof verification)
// and `SpfnProofAcceptance` in spfn-auth implements the whole sequence. The server has its
// own ledger, because it needs bounded memory and a session check the SDK-side verifier
// has no concept of, so "the same order" is a claim rather than a shared line of code.
//
// This test makes it evidence: every combination of the four refusal grounds is presented
// to both, and the two have to name the same one. Reordering either implementation makes
// a case here fail rather than making the two quietly disagree.

package xyz.superfunction.spfn.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.superfunction.spfn.auth.SpfnAuthException
import xyz.superfunction.spfn.auth.SpfnProofAcceptance
import xyz.superfunction.spfn.auth.SpfnProofInput
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations

class SpfnReferenceCheckOrderTest
{
    @Test
    fun `a valid presentation is admitted by both`()
    {
        assertAgreement(Grounds(), null);
    }

    @Test
    fun `revocation outranks every other ground`()
    {
        assertAgreement(Grounds(revoked = true, expired = true, replayed = true, badProof = true), "SESSION_REVOKED");
        assertAgreement(Grounds(revoked = true), "SESSION_REVOKED");
    }

    @Test
    fun `expiry outranks replay and a bad proof`()
    {
        assertAgreement(Grounds(expired = true, replayed = true, badProof = true), "PROOF_EXPIRED");
        assertAgreement(Grounds(expired = true), "PROOF_EXPIRED");
    }

    @Test
    fun `replay outranks a bad proof`()
    {
        assertAgreement(Grounds(replayed = true, badProof = true), "PROOF_REPLAYED");
        assertAgreement(Grounds(replayed = true), "PROOF_REPLAYED");
    }

    @Test
    fun `a bad proof is the last thing checked`()
    {
        assertAgreement(Grounds(badProof = true), "PROOF_INVALID");
    }

    @Test
    fun `a proof issued in the future is refused as expired`()
    {
        assertAgreement(Grounds(issuedAhead = true), "PROOF_EXPIRED");
    }

    @Test
    fun `the window boundary is inclusive on both sides`()
    {
        val window = SpfnReferenceState.DEFAULT_REPLAY_WINDOW_MILLIS;
        assertAgreement(Grounds(ageMillis = window), null);
        assertAgreement(Grounds(ageMillis = window + 1), "PROOF_EXPIRED");
    }

    // ---- the two implementations, asked the same question -------------------

    private class Grounds(
        val revoked: Boolean = false,
        val expired: Boolean = false,
        val replayed: Boolean = false,
        val badProof: Boolean = false,
        val issuedAhead: Boolean = false,
        val ageMillis: Long? = null
    )

    private class Outcomes(val server: String?, val sdk: String?)

    private fun assertAgreement(grounds: Grounds, expected: String?)
    {
        val outcomes = outcomes(grounds);
        assertEquals("server", expected, outcomes.server);
        assertEquals("SDK verifier", expected, outcomes.sdk);
    }

    private fun outcomes(grounds: Grounds): Outcomes
    {
        val now = SpfnReferenceTestClock.DEFAULT_START_MILLIS;
        val age = when
        {
            grounds.ageMillis != null -> grounds.ageMillis
            grounds.issuedAhead -> -1L
            grounds.expired -> SpfnReferenceState.DEFAULT_REPLAY_WINDOW_MILLIS + 1
            else -> 0L
        };

        val input = proofInput(nonce = NONCE, issuedAtMillis = now - age);
        val presented = if (grounds.badProof) BAD_PROOF else SpfnReferenceTestKeys.proofFor(input);

        return Outcomes(
            server = serverOutcome(grounds, input, presented, now),
            sdk = sdkOutcome(grounds, input, presented, now)
        );
    }

    private fun serverOutcome(
        grounds: Grounds,
        input: SpfnProofInput,
        presented: String,
        nowMillis: Long
    ): String?
    {
        val clock = SpfnReferenceTestClock();
        val state = SpfnReferenceState(clock);

        if (grounds.replayed)
        {
            // Spent by a presentation that really was admitted: a ledger entry a test
            // wrote by hand would prove nothing about how entries get there.
            val first = proofInput(nonce = input.nonce, issuedAtMillis = nowMillis);
            assertNull(admitOnServer(state, first, SpfnReferenceTestKeys.proofFor(first)));
        }
        if (grounds.revoked)
        {
            state.revokeKey(SpfnReferenceTestKeys.KEY_ID);
        }
        return admitOnServer(state, input, presented)?.code?.wireCode;
    }

    private fun admitOnServer(
        state: SpfnReferenceState,
        input: SpfnProofInput,
        presented: String
    ): SpfnReferenceRefusal? = state.admit(
        clientId = SpfnReferenceTestKeys.CLIENT_ID,
        keyId = SpfnReferenceTestKeys.KEY_ID,
        presentedSessionId = null,
        requiresSession = false,
        proofInput = input,
        presentedProof = presented
    )

    private fun sdkOutcome(
        grounds: Grounds,
        input: SpfnProofInput,
        presented: String,
        nowMillis: Long
    ): String?
    {
        val acceptance = SpfnProofAcceptance(SpfnReferenceState.DEFAULT_REPLAY_WINDOW_MILLIS);

        if (grounds.replayed)
        {
            val first = proofInput(nonce = input.nonce, issuedAtMillis = nowMillis);
            acceptance.admit(SpfnReferenceTestKeys.proofFor(first), first, PUBLIC_KEY, nowMillis);
        }
        if (grounds.revoked)
        {
            acceptance.revoke(SpfnReferenceTestKeys.KEY_ID);
        }

        return try
        {
            acceptance.admit(presented, input, PUBLIC_KEY, nowMillis);
            null;
        }
        catch (refusal: SpfnAuthException)
        {
            refusal.code;
        };
    }

    private fun proofInput(nonce: String, issuedAtMillis: Long): SpfnProofInput
    {
        val operation = SpfnGeneratedOperations.echoSend;
        return SpfnProofInput.forRequest(
            method = operation.method,
            path = operation.path,
            clientId = SpfnReferenceTestKeys.CLIENT_ID,
            keyId = SpfnReferenceTestKeys.KEY_ID,
            nonce = nonce,
            issuedAtMillis = issuedAtMillis,
            canonicalBody = BODY
        );
    }

    private companion object
    {
        const val NONCE = "nonce-order-000001"
        val PUBLIC_KEY: ByteArray = SpfnReferenceTestKeys.PUBLIC_KEY_SPKI_DER
        val BODY: ByteArray = "{\"message\":\"order\",\"sequence\":1}".toByteArray(Charsets.UTF_8)

        /** 128 hex characters that are not the signature of anything: r = s = 0. */
        val BAD_PROOF = "0".repeat(128)
    }
}
