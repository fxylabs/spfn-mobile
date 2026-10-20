// SPFN Mobile — the relay link and the one sign-in waiting for it.
//
// Everything that decides an outcome lives here, apart from the activities, so it runs on
// a host JVM: the relay is read off the link into three plain strings by the receiver,
// because android.net.Uri's host stubs answer defaults and a classifier written against
// them would be tested against nothing.
//
// The redirect receiver is exported, so any app on the device can fire a link at it. The
// guard is the state: 32 random bytes this module mints per call and hands to the app's
// URL seam, and the server echoes back. A relay without that exact value is not this
// call's and changes nothing. A forged relay that did carry it would still carry a token
// for some other key — the server binds the token's nonce to the enrolling key's
// fingerprint and refuses it — which is why the token itself is safe to carry in a link.

package xyz.superfunction.spfn.social.apple

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * What a relay link carried. Not a data class: its rendering would print the token, and
 * a relay is exactly the value somebody logs while debugging a sign-in.
 */
internal class SpfnSocialAppleRelay(
    val idToken: String?,
    val state: String?,
    val error: String?
)
{
    override fun toString(): String = "SpfnSocialAppleRelay"
}

/** How a call that was answered ended: a token, or a refusal to throw. */
internal sealed interface SpfnSocialAppleAnswer
{
    class Token(val value: String) : SpfnSocialAppleAnswer
    {
        override fun toString(): String = "Token"
    }

    class Refused(val failure: SpfnSocialAppleException) : SpfnSocialAppleAnswer
}

/**
 * The relay as an answer to the call that expects [expectedState], or null when it is
 * not that call's relay at all (R6). The token wins over an error: a relay carrying one
 * is a completed authorization whatever else rides with it.
 */
internal fun classifyRelay(relay: SpfnSocialAppleRelay, expectedState: String): SpfnSocialAppleAnswer?
{
    if (!sameState(relay.state, expectedState))
    {
        return null;
    }
    val token = relay.idToken;
    if (!token.isNullOrEmpty())
    {
        return SpfnSocialAppleAnswer.Token(token);
    }
    val error = relay.error;
    if (error == null)
    {
        return SpfnSocialAppleAnswer.Refused(SpfnSocialAppleException.IdentityTokenMissing());
    }
    return SpfnSocialAppleAnswer.Refused(classifyAppleError(error));
}

/**
 * Apple's `error` as a refusal. Its code is kept when it has the shape of one; anything
 * else is dropped, because a value an attacker or a proxy wrote is not a code.
 */
internal fun classifyAppleError(error: String): SpfnSocialAppleException = when
{
    error == APPLE_USER_CANCELLED -> SpfnSocialAppleException.Cancelled()
    APPLE_ERROR_CODE.matches(error) -> SpfnSocialAppleException.Failed(error)
    else -> SpfnSocialAppleException.Failed(SpfnSocialApple.UNRECOGNIZED_REASON)
}

/** Compared in constant time, so the state is not guessed a character at a time. */
private fun sameState(received: String?, expected: String): Boolean =
    received != null && MessageDigest.isEqual(received.toByteArray(), expected.toByteArray())

/** What Apple's web flow reports when the user cancels on its page. */
private const val APPLE_USER_CANCELLED = "user_cancelled_authorize"

private val APPLE_ERROR_CODE = Regex("[a-z_]{1,64}")

/**
 * The state for one call: 32 bytes from `SecureRandom`, lowercase hex. Written out by
 * hand rather than through `HexFormat`, which Android only has from API 34 (P14).
 */
internal fun mintState(random: SecureRandom = SecureRandom()): String
{
    val bytes = ByteArray(STATE_BYTES);
    random.nextBytes(bytes);
    return bytes.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') };
}

private const val STATE_BYTES = 32

/**
 * The one sign-in waiting for its relay, process-wide. One, because the tab is one
 * screen and two calls racing for one relay would hand one of them the other's token.
 *
 * Every answer goes through a [CompletableDeferred], whose `complete` succeeds once, and
 * the call is forgotten in the same step — so a late relay after a closed tab, or a closed
 * tab after a relay, finds nothing and changes nothing. Nothing survives process death on
 * purpose: a relay arriving in a fresh process has no call to answer, and is dropped.
 */
internal class SpfnSocialAppleRelays
{
    private class Call(val state: String, val answer: CompletableDeferred<SpfnSocialAppleAnswer>)

    private var call: Call? = null

    /** Starts waiting under [state], or null when another call is already waiting (T4). */
    @Synchronized
    fun begin(state: String): Deferred<SpfnSocialAppleAnswer>?
    {
        if (call != null)
        {
            return null;
        }
        val answer = CompletableDeferred<SpfnSocialAppleAnswer>();
        call = Call(state, answer);
        return answer;
    }

    /** Answers the waiting call with [relay] when it is that call's relay. */
    @Synchronized
    fun deliver(relay: SpfnSocialAppleRelay): Boolean
    {
        val running = call ?: return false;
        val answer = classifyRelay(relay, running.state) ?: return false;
        return finish(running, answer);
    }

    /** Ends the call waiting under [state] with [failure]: a closed tab, no browser. */
    @Synchronized
    fun refuse(state: String, failure: SpfnSocialAppleException): Boolean
    {
        val running = call?.takeIf { it.state == state } ?: return false;
        return finish(running, SpfnSocialAppleAnswer.Refused(failure));
    }

    /**
     * Forgets the call waiting under [state] without answering it, because its caller has
     * stopped waiting. True when there was still one, so the tab is left to take down.
     */
    @Synchronized
    fun abandon(state: String): Boolean
    {
        if (call?.state != state)
        {
            return false;
        }
        call = null;
        return true;
    }

    @Synchronized
    fun isWaiting(state: String?): Boolean = state != null && call?.state == state

    private fun finish(running: Call, answer: SpfnSocialAppleAnswer): Boolean
    {
        call = null;
        return running.answer.complete(answer);
    }

    companion object
    {
        /** The holder the activities and the Custom Tab driver share. */
        val shared = SpfnSocialAppleRelays()
    }
}
