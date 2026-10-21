// SPFN Mobile — the activity the relay link lands on.
//
// Exported, because a browser has to be able to open it, and declared here without an
// intent filter, because the link is the app's: the app declares this activity again in
// its own manifest with its verified https App Link (SpfnSocialAppleAuthorizationUrl
// carries the declaration).
//
// It reads three of the query parameters a relay carries — `id_token`, `state` and
// `error`; never `user`, the person's name on a first authorization — hands them to the
// waiting call, and finishes. Being exported means any app can fire anything at it,
// so nothing here trusts the link: a link that is not hierarchical, carries no state, or
// carries another call's state is dropped, and so is one that lands in a process that
// has no call waiting — a stale link, or a process restarted while the tab was open.
// Only a delivered relay brings the management activity forward; a dropped one leaves
// the tab exactly where it was.

package xyz.superfunction.spfn.social.apple

import android.app.Activity
import android.net.Uri
import android.os.Bundle

/**
 * Receives the server's relay link. Declare it in the app's manifest with the App Link's
 * intent filter — see [SpfnSocialAppleAuthorizationUrl] for the declaration.
 */
class SpfnSocialAppleRedirectActivity : Activity()
{
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState);
        val relay = relayOf(intent?.data);
        if (relay != null && SpfnSocialAppleRelays.shared.deliver(relay))
        {
            startActivity(SpfnSocialAppleAuthorizationActivity.answered(this));
        }
        finish();
    }
}

/**
 * The relay's three parameters. An opaque link has no query to read — `getQueryParameter`
 * throws on one — so it is no relay.
 */
private fun relayOf(link: Uri?): SpfnSocialAppleRelay?
{
    if (link == null || !link.isHierarchical)
    {
        return null;
    }
    return SpfnSocialAppleRelay(
        idToken = link.getQueryParameter("id_token"),
        state = link.getQueryParameter("state"),
        error = link.getQueryParameter("error")
    );
}
