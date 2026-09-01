package xyz.superfunction.spfn.harness

import xyz.superfunction.spfn.client.SpfnTransportResponse

/**
 * What one attempt's requests said on the wire.
 *
 * `SpfnKeyLifecycle.enroll` answers with an enrolment, not with an HTTP response, and that
 * is the right boundary — an app has no business branching on a status. A receipt is not
 * an app, though: it records what the wire actually said, so the harness reads it at the
 * one place the response exists rather than asking the SDK to widen its surface for a test
 * tool.
 *
 * One of these belongs to ONE attempt. The transport writes into whichever is current, and
 * an attempt reads its own object afterwards, so a second tap cannot blank the first
 * attempt's status: the newer attempt installs a new object and the older one keeps
 * everything it had already recorded.
 *
 * `@Volatile` because the write happens on whichever thread the transport ran on and the
 * read happens after the call returns, on another.
 */
class HarnessObservation
{
    /** The status of the last response this attempt received, or null when none arrived. */
    @Volatile
    var statusCode: Int? = null
        private set;

    /** The commit the server named, or null when it named none this receipt may carry. */
    @Volatile
    var serverCommit: String? = null
        private set;

    fun record(response: SpfnTransportResponse)
    {
        statusCode = response.statusCode;
        serverCommit = commitOf(response.headers);
    }

    companion object
    {
        /**
         * The header names a server may name its build with, most specific first.
         *
         * The contract declares none, so the harness looks for what a server plausibly
         * sends and records `null` when it finds nothing — an absent commit is a fact
         * about the server, not a failure of the run.
         *
         * The name comparison is `lowercase()`, which is `Locale.ROOT` in Kotlin and
         * therefore not the Turkish dotless-i mapping a default locale would apply to `I`
         * (docs/IMPLEMENTATION-PITFALLS.md P9).
         */
        private val COMMIT_HEADERS: List<String> = listOf(
            "x-spfn-server-commit",
            "x-server-commit",
            "x-git-commit",
            "x-commit"
        );

        /**
         * What a value has to look like before a receipt will carry it.
         *
         * A header is written by whatever answered the request, and a receipt is a file
         * that gets pulled off a phone and pasted into a report. Copying an arbitrary
         * header value into it is how an address, a name or a token ends up in evidence
         * that was supposed to contain none — so the value is not sanitised, it is
         * RECOGNISED: a commit hash is 7 to 40 lowercase hex characters and anything else
         * is not one, whatever it is.
         *
         * The class is spelled as an explicit ASCII range rather than a digit or letter
         * predicate. Kotlin's `isDigit()` accepts Arabic-Indic and full-width digits, and
         * a receipt that admitted those would disagree with the Swift half over the same
         * bytes (P9).
         */
        private val COMMIT_SHAPE: Regex = Regex("^[0-9a-f]{7,40}$");

        /**
         * The commit these headers name, or null.
         *
         * The value is lowercased before it is judged, so a server that sends an uppercase
         * hash is read rather than dropped, and what lands in the receipt is the lowercase
         * form — one spelling, whichever spelling arrived.
         */
        fun commitOf(headers: List<Pair<String, String>>): String?
        {
            for (candidate in COMMIT_HEADERS)
            {
                val value = headers.firstOrNull { it.first.lowercase() == candidate }?.second;
                val commit = value?.trim()?.lowercase();
                if (commit != null && COMMIT_SHAPE.matches(commit))
                {
                    return commit;
                }
            }
            return null;
        }
    }
}
