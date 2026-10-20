// SPFN Mobile — the Custom Tab driver's lifecycle, case table rows T1–T7.
//
// The activities are thin shells over three things a host JVM can run: the pending-call
// holder, the resume decision and the destroy decision. These rows drive the real driver
// against a scripted tab and make the calls each activity makes at the moment it makes
// them, so what is proven is the waiting, not a browser.

package xyz.superfunction.spfn.social.apple

import android.app.Activity
import android.content.ActivityNotFoundException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
import xyz.superfunction.spfn.client.SpfnInternalNonceAccess
import xyz.superfunction.spfn.client.SpfnSocialNonce

@OptIn(SpfnInternalNonceAccess::class)
class SpfnSocialAppleCustomTabDriverTest
{
    private val relays = SpfnSocialAppleRelays()
    private val tab = ScriptedTab()
    private val adapter = SpfnSocialApple(SpfnSocialAppleCustomTabDriver(tab, relays))

    /**
     * T1: the management activity is resumed with the call still waiting — the user
     * closed the tab. A relay arriving after that finds nothing (defect 5).
     */
    @Test
    fun t1_aClosedTabIsCancelled() = runBlocking {
        val call = async { failureOf { adapter.idToken(appleNonce()) } };
        val state = tab.opened.await();

        val step = tabStep(launched = true, answered = false, waiting = relays.isWaiting(state));
        assertEquals(SpfnSocialAppleTabStep.CANCEL, step);
        relays.refuse(state, SpfnSocialAppleException.Cancelled());

        assertTrue(call.await() is SpfnSocialAppleException.Cancelled);
        assertFalse("a late relay is not delivered", relays.deliver(SpfnSocialAppleRelay(TOKEN, state, null)));
    }

    /**
     * T2: the receiver delivers the relay, the call resumes with the token, and the
     * management activity brought forward finishes. A closed tab after that changes
     * nothing (defect 5).
     */
    @Test
    fun t2_aRelayResumesTheCallAndTheTabFinishes() = runBlocking {
        val call = async { adapter.idToken(appleNonce()) };
        val state = tab.opened.await();

        assertTrue(relays.deliver(SpfnSocialAppleRelay(TOKEN, state, null)));

        assertEquals(TOKEN, call.await());
        assertEquals(
            SpfnSocialAppleTabStep.FINISH,
            tabStep(launched = true, answered = true, waiting = relays.isWaiting(state))
        );
        assertEquals(
            "a return without the answered flag still only finishes",
            SpfnSocialAppleTabStep.FINISH,
            tabStep(launched = true, answered = false, waiting = relays.isWaiting(state))
        );
        assertFalse(relays.refuse(state, SpfnSocialAppleException.Cancelled()));
        assertEquals("an answered call leaves the tab to the relay's return", 0, tab.dismissed);
    }

    /**
     * T3: the caller's coroutine is cancelled while the tab is open. The cancellation
     * propagates as itself (P16), the call is forgotten, the tab is taken down, and a
     * relay arriving later is a stale one (R7).
     */
    @Test
    fun t3_aCancelledCallerClearsThePendingCall() = runBlocking {
        val call = async { adapter.idToken(appleNonce()) };
        val state = tab.opened.await();

        call.cancel();
        val thrown = failureOf { call.await() };

        assertTrue("$thrown", thrown is CancellationException);
        assertFalse(relays.isWaiting(state));
        assertEquals(1, tab.dismissed);
        assertFalse(relays.deliver(SpfnSocialAppleRelay(TOKEN, state, null)));
        assertEquals(
            SpfnSocialAppleTabStep.FINISH,
            tabStep(launched = true, answered = true, waiting = relays.isWaiting(state))
        );
    }

    /** T4: one sign-in at a time; the second fails and the first is untouched. */
    @Test
    fun t4_aSecondSignInWhileOneIsPendingFails() = runBlocking {
        val first = async { adapter.idToken(appleNonce()) };
        val state = tab.opened.await();

        val second = failureOf { adapter.idToken(appleNonce()) };

        assertEquals(
            SpfnSocialApple.SIGN_IN_IN_PROGRESS_REASON,
            (second as SpfnSocialAppleException.Failed).reason
        );
        assertEquals("the second call must not open a tab", 1, tab.openings);
        assertTrue(relays.isWaiting(state));
        assertTrue(relays.deliver(SpfnSocialAppleRelay(TOKEN, state, null)));
        assertEquals(TOKEN, first.await());
    }

    /**
     * T5: a recreated management activity restores `launched`, so it never opens a second
     * tab; and its destruction for the recreation does not end the call.
     */
    @Test
    fun t5_aRecreationNeitherRelaunchesTheTabNorCancelsTheCall() = runBlocking {
        val call = async { adapter.idToken(appleNonce()) };
        val state = tab.opened.await();

        for (answered in listOf(false, true))
        {
            for (waiting in listOf(false, true))
            {
                assertNotEquals(
                    SpfnSocialAppleTabStep.LAUNCH,
                    tabStep(launched = true, answered = answered, waiting = waiting)
                );
            }
        }
        assertFalse(endsCallOnDestroy(finishing = false, changingConfigurations = true));
        assertFalse(endsCallOnDestroy(finishing = true, changingConfigurations = true));
        assertTrue(relays.isWaiting(state));

        // Only the first resume of a fresh instance opens it, and only for a waiting call.
        assertEquals(SpfnSocialAppleTabStep.LAUNCH, tabStep(launched = false, answered = false, waiting = true));
        assertEquals(SpfnSocialAppleTabStep.FINISH, tabStep(launched = false, answered = false, waiting = false));
        assertTrue("a removed task does end it", endsCallOnDestroy(finishing = true, changingConfigurations = false));

        relays.deliver(SpfnSocialAppleRelay(TOKEN, state, null));
        assertEquals(TOKEN, call.await());
    }

    /** T6: no app on the device can open the URL. */
    @Test
    fun t6_noBrowserFailsWithItsOwnReason() = runBlocking {
        val call = async { failureOf { adapter.idToken(appleNonce()) } };
        val state = tab.opened.await();

        relays.openOrRefuse(state) { throw ActivityNotFoundException() };

        assertEquals(
            SpfnSocialApple.NO_BROWSER_REASON,
            (call.await() as SpfnSocialAppleException.Failed).reason
        );
        assertFalse(relays.isWaiting(state));
    }

    /**
     * T7: the app's URL seam throws. It runs through the production driver — the seam is
     * the first thing its tab calls, so no activity is started — and is classified as any
     * driver failure is (A5–A7). The call is forgotten, so the next sign-in is not refused
     * as one in progress.
     */
    @Test
    fun t7_aThrowingUrlSeamIsClassified() = runBlocking {
        val cancellation = CancellationException("the caller's scope was cancelled");
        val ownRefusal = SpfnSocialAppleException.Failed("invalid_client");
        val failures = listOf(cancellation, ownRefusal, IllegalStateException("https://leak?id_token=apple-token-leak"));
        val asked = mutableListOf<Pair<String, String>>();

        val thrown = failures.map { failure ->
            val seam = SpfnSocialAppleAuthorizationUrl { requestNonce, state ->
                asked += requestNonce to state;
                throw failure;
            };
            failureOf { SpfnSocialApple(SpfnSocialAppleCustomTabDriver(Activity(), seam)).idToken(appleNonce()) };
        };

        assertSame(cancellation, thrown[0]);
        assertSame(ownRefusal, thrown[1]);
        val unclassified = thrown[2] as SpfnSocialAppleException.Failed;
        assertEquals(SpfnSocialApple.UNCLASSIFIED_REASON, unclassified.reason);
        assertTrue(unclassified.message?.contains("apple-token-leak") != true);

        // The seam was handed the hash and a fresh state each time.
        assertTrue(asked.all { (requestNonce, _) -> requestNonce == appleNonce().requestValue });
        assertTrue(asked.all { (_, state) -> Regex("[0-9a-f]{64}").matches(state) });
        assertEquals(3, asked.map { (_, state) -> state }.toSet().size);
        assertTrue(asked.none { (_, state) -> SpfnSocialAppleRelays.shared.isWaiting(state) });
    }

    /** A nonce minted the way an enrollment for this adapter mints one. */
    private fun appleNonce(): SpfnSocialNonce =
        SpfnSocialNonce.forProvider(FINGERPRINT, SpfnSocialApple.PROVIDER)

    private suspend fun failureOf(body: suspend () -> Unit): Throwable?
    {
        return try
        {
            body();
            null;
        }
        catch (failure: Throwable)
        {
            failure;
        }
    }

    private companion object
    {
        const val TOKEN = "apple-token-0001"

        /** A fingerprint shaped as one taken over a real key: 64 lowercase hex characters. */
        const val FINGERPRINT = "aa919f16ced3a7bae097e8fde574681a9184cbc53ba1dd9ab43fa716774b690a"
    }
}

/**
 * The tab, scripted: it reports the state it was opened under and counts how often it
 * was opened and taken down.
 */
internal class ScriptedTab : SpfnSocialAppleTab
{
    val opened = CompletableDeferred<String>()
    var openings = 0
        private set
    var dismissed = 0
        private set

    override suspend fun open(requestNonce: String, state: String)
    {
        openings += 1;
        opened.complete(state);
    }

    override fun dismiss()
    {
        dismissed += 1;
    }
}
