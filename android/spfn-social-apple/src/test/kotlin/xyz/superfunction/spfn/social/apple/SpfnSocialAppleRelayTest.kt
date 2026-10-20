// SPFN Mobile — the relay link, case table rows R1–R8.
//
// The relay is classified from three plain strings, so these rows run on a host JVM
// without android.net.Uri, whose host stubs answer null for every query parameter.

package xyz.superfunction.spfn.social.apple

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpfnSocialAppleRelayTest
{
    /** R1: the call's own state and a token — the token. */
    @Test
    fun r1_aMatchingRelayWithATokenAnswersTheToken()
    {
        val answer = classifyRelay(SpfnSocialAppleRelay(TOKEN, STATE, null), STATE);

        assertEquals(TOKEN, (answer as SpfnSocialAppleAnswer.Token).value);
    }

    /** R2: the call's own state and neither a token nor an error. */
    @Test
    fun r2_aMatchingRelayWithoutATokenIsIdentityTokenMissing()
    {
        for (token in listOf(null, ""))
        {
            val answer = classifyRelay(SpfnSocialAppleRelay(token, STATE, null), STATE);

            assertTrue(refusalOf(answer) is SpfnSocialAppleException.IdentityTokenMissing);
        }
    }

    /** R3: Apple's own cancellation code is the user closing the flow, not a failure. */
    @Test
    fun r3_aUserCancelledErrorIsCancelled()
    {
        val answer = classifyRelay(SpfnSocialAppleRelay(null, STATE, "user_cancelled_authorize"), STATE);

        assertTrue(refusalOf(answer) is SpfnSocialAppleException.Cancelled);
    }

    /** R4: an error shaped as a code is kept as the reason. */
    @Test
    fun r4_anErrorShapedAsACodeIsTheReason()
    {
        for (code in listOf("invalid_request", "invalid_client", "a", "_".repeat(64)))
        {
            val answer = classifyRelay(SpfnSocialAppleRelay(null, STATE, code), STATE);

            assertEquals(code, (refusalOf(answer) as SpfnSocialAppleException.Failed).reason);
        }
    }

    /** R5: anything else in `error` is not a code, and its text is dropped. */
    @Test
    fun r5_anyOtherErrorIsUnrecognizedAndItsTextIsDropped()
    {
        val leaked = "Invalid request: id_token=apple-token-leak-0001";
        for (error in listOf("", "Invalid_Request", "invalid-request", "a".repeat(65), leaked))
        {
            val failure = refusalOf(classifyRelay(SpfnSocialAppleRelay(null, STATE, error), STATE));

            assertEquals("error '$error'", SpfnSocialApple.UNRECOGNIZED_REASON, (failure as SpfnSocialAppleException.Failed).reason);
            assertTrue(failure.message?.contains("apple-token-leak-0001") != true);
        }
    }

    /**
     * R6: a relay without this call's exact state is not this call's — the guard that
     * makes an exported receiver safe. A forged link changes nothing, and the call still
     * takes its own relay afterwards.
     */
    @Test
    fun r6_aRelayWithoutTheCallsStateIsNotDelivered() = runBlocking {
        for (state in listOf(null, "", OTHER_STATE, STATE.uppercase(), STATE.dropLast(1), "$STATE "))
        {
            assertNull("state '$state'", classifyRelay(SpfnSocialAppleRelay(TOKEN, state, null), STATE));
        }

        val relays = SpfnSocialAppleRelays();
        val answer = relays.begin(STATE)!!;
        assertFalse(relays.deliver(SpfnSocialAppleRelay("forged-token", OTHER_STATE, null)));
        assertFalse(relays.deliver(SpfnSocialAppleRelay("forged-token", null, null)));
        assertTrue("the pending call is untouched", relays.isWaiting(STATE));
        assertFalse(answer.isCompleted);

        assertTrue(relays.deliver(SpfnSocialAppleRelay(TOKEN, STATE, null)));
        assertEquals(TOKEN, (answer.await() as SpfnSocialAppleAnswer.Token).value);
    }

    /**
     * R7: a relay with no call waiting — a stale link, or the process was restarted while
     * the tab was open — is dropped without a crash.
     */
    @Test
    fun r7_aRelayWithNoPendingCallIsNotDelivered()
    {
        val relays = SpfnSocialAppleRelays();

        assertFalse(relays.deliver(SpfnSocialAppleRelay(TOKEN, STATE, null)));
        assertFalse(relays.isWaiting(STATE));
    }

    /** R8: a call answers once; a second relay finds nothing. */
    @Test
    fun r8_aSecondRelayAfterTheCallFinishedIsNotDelivered() = runBlocking {
        val relays = SpfnSocialAppleRelays();
        val answer = relays.begin(STATE)!!;

        assertTrue(relays.deliver(SpfnSocialAppleRelay(TOKEN, STATE, null)));
        assertFalse(relays.deliver(SpfnSocialAppleRelay("second-token", STATE, null)));
        assertFalse(relays.deliver(SpfnSocialAppleRelay(null, STATE, "invalid_request")));
        assertEquals(TOKEN, (answer.await() as SpfnSocialAppleAnswer.Token).value);
    }

    /** Defect 1: nothing a relay renders carries the token. */
    @Test
    fun aRelayAndItsAnswerRenderWithoutTheToken()
    {
        val relay = SpfnSocialAppleRelay(TOKEN, STATE, "invalid_request");
        val answer = classifyRelay(relay, STATE);

        assertFalse(relay.toString().contains(TOKEN));
        assertFalse(answer.toString().contains(TOKEN));
    }

    /** The state is 32 random bytes as lowercase hex, and a fresh one per call. */
    @Test
    fun theStateIsThirtyTwoRandomBytesInLowercaseHex()
    {
        val first = mintState();
        val second = mintState();

        assertTrue(first, Regex("[0-9a-f]{64}").matches(first));
        assertNotEquals(first, second);
    }

    private fun refusalOf(answer: SpfnSocialAppleAnswer?): SpfnSocialAppleException =
        (answer as SpfnSocialAppleAnswer.Refused).failure

    private companion object
    {
        const val TOKEN = "apple-token-0001"
        const val STATE = "5f0c1d2e3a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9012a3b4c5d6"
        const val OTHER_STATE = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
