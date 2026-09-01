package xyz.superfunction.spfn.harness

/**
 * What this build was configured with, and whether it was configured at all.
 *
 * The two values arrive as BuildConfig fields written from `local.properties`, which
 * .gitignore refuses to stage. A checkout of this repository therefore builds with both
 * empty, and empty is a state the app handles rather than a state it crashes in: the
 * sign-in button is disabled and the screen says `social=not-configured`.
 *
 * Nothing here is ever displayed, logged or written into a receipt. A client id is not a
 * secret in the sense a token is, but it identifies someone's Google project, and a
 * harness that printed it would put it in every transcript of every run.
 */
object HarnessSocialConfiguration
{
    /** The WEB client id Credential Manager is asked with, or empty when unconfigured. */
    val googleServerClientId: String = BuildConfig.HARNESS_GOOGLE_SERVER_CLIENT_ID;

    /** The SPFN server this build enrolls against, or empty when unconfigured. */
    val serverBaseUrl: String = BuildConfig.HARNESS_SERVER_BASE_URL;

    /** Both values present. A device sign-in needs both, so one without the other is not a state. */
    val isConfigured: Boolean = googleServerClientId.isNotEmpty() && serverBaseUrl.isNotEmpty();

    /** What the screen may say about the configuration. Never the values themselves. */
    val readout: String = if (isConfigured) "configured" else "not-configured";

    /**
     * A base URL reduced to scheme, host and port — what a receipt records.
     *
     * The spec fixes this: a receipt carries the host only, with no path and no query. The
     * reduction is character work over ASCII delimiters rather than a URL parser, because
     * a parser answers with its own idea of a default port and its own normalisation, and
     * the receipt is supposed to say what was configured.
     */
    fun origin(baseUrl: String): String
    {
        val scheme = baseUrl.substringBefore("://", "");
        if (scheme.isEmpty())
        {
            return baseUrl;
        }
        // Three delimiters, in the order they can appear after the authority: a path, a
        // query on a URL with no path, and a fragment on a URL with neither.
        val authority = baseUrl.substringAfter("://")
            .substringBefore("/")
            .substringBefore("?")
            .substringBefore("#");
        return "$scheme://$authority";
    }
}
