// SPFN Mobile — the one part of the client identity that needs the Android framework.
//
// `SpfnClientIdentity` holds the headers and stays framework-free, because the reference
// server's integration suite compiles the shipped client as a plain JVM module and has no
// android.* stubs. Reading the app's own version needs a Context, so it lives here and is
// excluded from that compilation by name, the same way the Keystore engine and the
// SharedPreferences store already are.
//
// That split is the file boundary doing what the exclusion list needs, rather than the
// exclusion list reaching into a file that also carries what the suite must compile.

package xyz.superfunction.spfn.client

import android.content.Context

/**
 * Fills [SpfnClientIdentity.appVersion] from the package manager.
 *
 * Call once, from `Application.onCreate` or wherever the app builds its client. It is
 * separate from constructing a client because a transport-level SDK that demanded a
 * Context would be demanding it for a diagnostic header — nothing is authorized by this
 * value, and an app that never calls this sends two identity headers instead of three.
 *
 * A package manager that cannot answer leaves the version unset rather than guessing.
 */
fun SpfnClientIdentity.readAppVersion(context: Context)
{
    appVersion = runCatching {
        val application = context.applicationContext;
        application.packageManager.getPackageInfo(application.packageName, 0).versionName;
    }.getOrNull();
}
