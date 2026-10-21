// SPFN Mobile — Apple's web authorization in a Custom Tab, answered by a relay link.
//
// The round trip, end to end:
//
//   1. The driver mints a state and asks the app's [SpfnSocialAppleAuthorizationUrl] for
//      the URL to open. The app owns that URL because its server and its API client own
//      the call behind it — the native start, `/_auth/oauth/apple/native/start` — and
//      this module names no endpoint.
//   2. SpfnSocialAppleAuthorizationActivity opens it in a Custom Tab. Apple posts the
//      authorization to the server's `/_auth/oauth/apple/callback`, and the server
//      redirects (303) to the app's link with `id_token` and `state` — and `user` on a
//      first authorization, or `error`, which is `expired` when the start has lapsed — in
//      its query.
//   3. SpfnSocialAppleRedirectActivity receives that link, hands it to the waiting call
//      and brings the management activity forward, which clears the tab and finishes.
//
// The shape is AppAuth-Android's management and redirect-receiver pair, without AppAuth.
// One difference is deliberate: the receiver classifies the link itself instead of
// forwarding it. A relay that is not this call's then never touches the tab, and the
// token never rides a second intent.

package xyz.superfunction.spfn.social.apple

import android.app.Activity
import android.net.Uri
import kotlinx.coroutines.Deferred

/**
 * Where the authorization starts. The app answers with the Apple authorization URL its
 * SPFN server issues for this sign-in: it posts `{ requestNonce, state, relayUrl }` to
 * `/_auth/oauth/apple/native/start` and returns the `authUrl` the server answers with.
 * [requestNonce] is the 64-character lowercase SHA-256 hex Apple puts in the token as its
 * nonce; [state] is this call's, and the server echoes it back in the relay link.
 *
 * The relay link is the app's too: the server redirects to `relayUrl`, and the app routes
 * it to [SpfnSocialAppleRedirectActivity] by declaring that activity again in its own
 * manifest with an intent filter; the manifest merger joins the declarations. The link
 * is an https App Link, verified, on the host listed in the server's
 * `SPFN_AUTH_APPLE_RELAY_URLS` — the server refuses any other kind, because an
 * unverified link is one any app on the device could claim:
 * ```
 * <activity
 *     android:name="xyz.superfunction.spfn.social.apple.SpfnSocialAppleRedirectActivity"
 *     android:exported="true">
 *     <intent-filter android:autoVerify="true">
 *         <action android:name="android.intent.action.VIEW" />
 *         <category android:name="android.intent.category.DEFAULT" />
 *         <category android:name="android.intent.category.BROWSABLE" />
 *         <data android:scheme="https" android:host="app.example.com"
 *             android:path="/_auth/oauth/apple/android" />
 *     </intent-filter>
 * </activity>
 * ```
 *
 * The state is still what makes a relay this call's, so a link another app fires at the
 * receiver changes nothing.
 */
fun interface SpfnSocialAppleAuthorizationUrl
{
    suspend fun url(requestNonce: String, state: String): Uri
}

/**
 * The tab, behind a seam of its own, so the waiting is testable without a browser and
 * without android.net.Uri — whose host stubs answer null.
 */
internal interface SpfnSocialAppleTab
{
    /** Puts the authorization for [state] on the screen. */
    suspend fun open(requestNonce: String, state: String)

    /** Takes the tab down after its caller stopped waiting. */
    fun dismiss()
}

/**
 * The flow itself: one tab, one relay, one token — and the name, when Apple sent one.
 *
 * The parameter is an [Activity] rather than a `Context` for the reason the Google
 * driver's is (P15): the tab is started on top of an activity's task, and an application
 * context compiles here and then refuses to start an activity outside a new task — at the
 * moment the user taps the sign-in button.
 *
 * One sign-in at a time: a second call while one is waiting fails with
 * [SpfnSocialApple.SIGN_IN_IN_PROGRESS_REASON] and leaves the first alone. The call
 * belongs to the coroutine that made it, so cancelling that coroutine ends the sign-in
 * and takes the tab down — run it in a scope that outlives a rotation, such as a
 * ViewModel's, when a rotation should not end it.
 */
class SpfnSocialAppleCustomTabDriver internal constructor(
    private val tab: SpfnSocialAppleTab,
    private val relays: SpfnSocialAppleRelays
) : SpfnSocialAppleDriver
{
    constructor(activity: Activity, authorizationUrl: SpfnSocialAppleAuthorizationUrl) :
        this(ActivityTab(activity, authorizationUrl), SpfnSocialAppleRelays.shared)

    override suspend fun credential(requestNonce: String): SpfnSocialAppleCredential?
    {
        val state = mintState();
        val answer = relays.begin(state)
            ?: throw SpfnSocialAppleException.Failed(SpfnSocialApple.SIGN_IN_IN_PROGRESS_REASON);
        return when (val answered = awaitRelay(requestNonce, state, answer))
        {
            is SpfnSocialAppleAnswer.Signed -> answered.credential
            is SpfnSocialAppleAnswer.Refused -> throw answered.failure
        }
    }

    /**
     * Opens the tab and waits for whatever answers the call. However the wait ends
     * without an answer — the URL seam threw, the caller was cancelled — the call is
     * forgotten here, so a relay arriving afterwards finds nothing (R7), and a tab that
     * was opened is taken down.
     */
    private suspend fun awaitRelay(
        requestNonce: String,
        state: String,
        answer: Deferred<SpfnSocialAppleAnswer>
    ): SpfnSocialAppleAnswer
    {
        var opened = false;
        try
        {
            tab.open(requestNonce, state);
            opened = true;
            return answer.await();
        }
        finally
        {
            if (relays.abandon(state) && opened)
            {
                tab.dismiss();
            }
        }
    }
}

/** The production tab: the app's URL, opened by the management activity. */
private class ActivityTab(
    private val activity: Activity,
    private val authorizationUrl: SpfnSocialAppleAuthorizationUrl
) : SpfnSocialAppleTab
{
    override suspend fun open(requestNonce: String, state: String)
    {
        val url = authorizationUrl.url(requestNonce, state);
        activity.startActivity(SpfnSocialAppleAuthorizationActivity.launching(activity, url, state));
    }

    override fun dismiss()
    {
        activity.startActivity(SpfnSocialAppleAuthorizationActivity.answered(activity));
    }
}
