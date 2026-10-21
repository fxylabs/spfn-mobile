// SPFN Mobile — the activity that holds the Custom Tab open, and decides what its
// return to the foreground means.
//
// It draws nothing. It starts the tab the first time it is resumed and is resumed again
// only when the tab leaves the screen: either because the redirect receiver answered the
// call and brought this activity forward — which clears the tab above it, since it is
// `singleTask` — or because the user closed the tab. The two are told apart by whether
// the call is still waiting, not by guessing at the intent.
//
// Whether the tab was launched is kept in saved state. A recreated activity that forgot
// it would open a second tab, and one that read its own recreation as a return would
// report a cancellation the user never made.

package xyz.superfunction.spfn.social.apple

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent

/** Not part of the module's surface: only this module starts it. */
class SpfnSocialAppleAuthorizationActivity : Activity()
{
    private val relays = SpfnSocialAppleRelays.shared
    private var state = ""
    private var launched = false
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState);
        state = savedInstanceState?.getString(KEY_STATE) ?: intent.getStringExtra(EXTRA_STATE).orEmpty();
        launched = savedInstanceState?.getBoolean(KEY_LAUNCHED) ?: false;
        answered = intent.getBooleanExtra(EXTRA_ANSWERED, false);
    }

    override fun onNewIntent(intent: Intent)
    {
        super.onNewIntent(intent);
        answered = answered || intent.getBooleanExtra(EXTRA_ANSWERED, false);
    }

    override fun onResume()
    {
        super.onResume();
        when (tabStep(launched, answered, relays.isWaiting(state)))
        {
            SpfnSocialAppleTabStep.LAUNCH -> launchTab()
            SpfnSocialAppleTabStep.CANCEL -> cancel()
            SpfnSocialAppleTabStep.FINISH -> finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle)
    {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_STATE, state);
        outState.putBoolean(KEY_LAUNCHED, launched);
    }

    override fun onDestroy()
    {
        // The task was removed under a waiting call. A recreation is not an ending (T5).
        if (endsCallOnDestroy(isFinishing, isChangingConfigurations))
        {
            relays.refuse(state, SpfnSocialAppleException.Cancelled());
        }
        super.onDestroy();
    }

    private fun launchTab()
    {
        launched = true;
        val url = intent.getStringExtra(EXTRA_URL);
        if (url == null)
        {
            relays.refuse(state, SpfnSocialAppleException.Failed(SpfnSocialApple.UNCLASSIFIED_REASON));
        }
        else
        {
            relays.openOrRefuse(state) { CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url)) };
        }
        if (!relays.isWaiting(state))
        {
            finish();
        }
    }

    /** Back on screen with the call still waiting: the user closed the tab (T1). */
    private fun cancel()
    {
        relays.refuse(state, SpfnSocialAppleException.Cancelled());
        finish();
    }

    internal companion object
    {
        private const val EXTRA_URL = "xyz.superfunction.spfn.social.apple.URL"
        private const val EXTRA_STATE = "xyz.superfunction.spfn.social.apple.STATE"
        private const val EXTRA_ANSWERED = "xyz.superfunction.spfn.social.apple.ANSWERED"
        private const val KEY_STATE = "state"
        private const val KEY_LAUNCHED = "launched"

        /** Starts the sign-in waiting under [state]. */
        fun launching(context: Context, url: Uri, state: String): Intent =
            Intent(context, SpfnSocialAppleAuthorizationActivity::class.java)
                .putExtra(EXTRA_URL, url.toString())
                .putExtra(EXTRA_STATE, state)

        /**
         * Brings the running instance forward once its call has been answered or
         * abandoned. It carries no part of the relay: the call was already answered.
         */
        fun answered(context: Context): Intent =
            Intent(context, SpfnSocialAppleAuthorizationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_ANSWERED, true)
    }
}

/** What a resumed management activity does. */
internal enum class SpfnSocialAppleTabStep
{
    LAUNCH,
    CANCEL,
    FINISH
}

/**
 * The resume decision, apart from the activity so a host JVM can read it. A call that is
 * no longer waiting — answered, abandoned, or lost with the process that held it — only
 * finishes. A waiting call is launched once and, after that, a return without an answer
 * is the user closing the tab.
 */
internal fun tabStep(launched: Boolean, answered: Boolean, waiting: Boolean): SpfnSocialAppleTabStep = when
{
    answered || !waiting -> SpfnSocialAppleTabStep.FINISH
    !launched -> SpfnSocialAppleTabStep.LAUNCH
    else -> SpfnSocialAppleTabStep.CANCEL
}

/** Only a real ending ends the call: a configuration change destroys and recreates. */
internal fun endsCallOnDestroy(finishing: Boolean, changingConfigurations: Boolean): Boolean =
    finishing && !changingConfigurations

/**
 * Opens the tab, or ends the call with [SpfnSocialApple.NO_BROWSER_REASON] when nothing
 * on the device can open it (T6).
 */
internal fun SpfnSocialAppleRelays.openOrRefuse(state: String, open: () -> Unit)
{
    try
    {
        open();
    }
    catch (missing: ActivityNotFoundException)
    {
        refuse(state, SpfnSocialAppleException.Failed(SpfnSocialApple.NO_BROWSER_REASON));
    }
}
