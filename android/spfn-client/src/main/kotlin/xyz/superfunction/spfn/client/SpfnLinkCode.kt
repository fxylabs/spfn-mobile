// SPFN Mobile — the link code a signed-in device shows, as this device reads it.
//
// A link code reaches this device one of two ways: a person types it off the other
// screen, or a camera reads the QR the other device draws. Both arrive here as a string,
// and both must come out as the one spelling `auth.deviceLink.redeem` is sent — or as
// nothing, before any key is generated or any request is built.
//
// Everything is judged character by character, in ASCII, and the URL form by hand rather
// than by java.net.URI: the two platforms' URL parsers disagree about percent-encoding,
// IDN hosts and empty segments, and their case mappings disagree about letters outside
// ASCII. A scan one SDK accepts and the other refuses is a bug report with no fix.
// Sources/SPFNClient/SPFNLinkCode.swift is the same function in Swift, and the two
// suites share one table.

package xyz.superfunction.spfn.client

/** Reads a link code off what a person typed or a camera scanned. */
object SpfnLinkCode
{
    /** The characters a link code is drawn from. */
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Characters in a code, not counting the dash it is shown with. */
    private const val LENGTH = 8

    private const val WHITESPACE = " \t\r\n"

    private const val URL_PREFIX = "https://"

    /**
     * Dropped from a typed code. Only the ASCII hyphen: a dash a keyboard substituted is
     * refused rather than guessed at.
     */
    private const val SEPARATORS = "$WHITESPACE-"

    /**
     * Refused anywhere after `https://`: query, fragment, percent-encoding, userinfo, a
     * backslash some parsers read as a slash, and whitespace.
     */
    private const val REFUSED_IN_URL = "$WHITESPACE?#%@\\"

    /**
     * The code a scan or a typed entry names, as `XXXX-XXXX`, or null.
     *
     * Surrounding ASCII whitespace is ignored. A typed code may carry spaces, dashes and
     * lower case (`abcd efgh`, `ABCD-EFGH`), which fold away; it must then be eight
     * characters of the device-code alphabet, which leaves out 0/O and 1/I/L.
     *
     * A URL is accepted only when all of these hold:
     *
     * - the scheme is `https`, in any case; `http` and every other scheme are refused;
     * - the host, compared in lower case, is one of [allowedHosts]. Only ASCII hosts
     *   match: an IDN host is compared in its `xn--` form, so an app that serves one
     *   lists that form, and a look-alike Unicode host never matches;
     * - there is no userinfo, no port, no query and no fragment — so a code carried in a
     *   query parameter is refused rather than searched for;
     * - the path has no empty segment and no percent sign: `/ABCD-EFGH/` (a trailing
     *   slash) and `/ABCD%2DEFGH` are refused, because a code never needs either;
     * - the last path segment is a code. A segment after the code is refused by that same
     *   rule, since it is then the last one.
     */
    fun parse(scanned: String, allowedHosts: Set<String>): String?
    {
        val trimmed = scanned.trim { it in WHITESPACE };
        val code = if (isHttps(trimmed))
        {
            codeInUrl(trimmed.substring(URL_PREFIX.length), allowedHosts)
        }
        else
        {
            normalized(trimmed)
        };
        return code?.let { "${it.take(LENGTH / 2)}-${it.drop(LENGTH / 2)}" };
    }

    /**
     * A typed code in the form the server stores it — eight alphabet characters, upper
     * case, no dash — or null. What `enrollByLinkCode` sends.
     *
     * ASCII letters are upper-cased and nothing else. A locale-aware mapping would turn
     * `ß` into `SS`, two alphabet characters nobody typed.
     */
    internal fun normalized(typed: String): String?
    {
        val code = typed.filterNot { it in SEPARATORS }.map(::asciiUpper).joinToString("");
        return code.takeIf { it.length == LENGTH && it.all { char -> char in ALPHABET } };
    }

    /** The code in what follows `https://`, or null when the URL breaks a rule [parse] lists. */
    private fun codeInUrl(rest: String, allowedHosts: Set<String>): String?
    {
        val slash = rest.indexOf('/');
        if (rest.any { it in REFUSED_IN_URL } || slash < 0)
        {
            return null;
        }
        val host = rest.substring(0, slash).map(::asciiLower).joinToString("");
        if (host.isEmpty() || host.any { it.code > 0x7F || it == ':' } || allowedHosts.none { it.lowercase() == host })
        {
            return null;
        }
        val segments = rest.substring(slash + 1).split('/');
        if (segments.any { it.isEmpty() })
        {
            return null;
        }
        return normalized(segments.last());
    }

    private fun isHttps(text: String): Boolean =
        text.take(URL_PREFIX.length).map(::asciiLower).joinToString("") == URL_PREFIX

    private fun asciiUpper(char: Char): Char = if (char in 'a'..'z') char - 32 else char

    private fun asciiLower(char: Char): Char = if (char in 'A'..'Z') char + 32 else char
}
