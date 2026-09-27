// SPFN Mobile — reading a 429's Retry-After (design §10 Q-D).
//
// The execute path has no Retry-After rule of its own: it retries nothing but an auth
// refusal. So this is the one rule, and both halves of the event stream use it — the
// token call reads the header off `SpfnServerFailure.retryAfter`, the stream open reads it
// off the response. RFC 9110 §10.2.3 admits two spellings, delay-seconds and an HTTP-date;
// anything else is ignored rather than guessed at, and the backoff stands alone.

package xyz.superfunction.spfn.client

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object SpfnRetryAfter
{
    /** The most a server's Retry-After can hold the stream back: five minutes. */
    const val CAP_MILLIS: Long = 300_000;

    /**
     * The wait the header asks for, in milliseconds, capped at [CAP_MILLIS]; a date in the
     * past is 0. Null when the header is absent or malformed.
     */
    fun millis(value: String?, nowMillis: Long): Long?
    {
        val text = value?.trim() ?: return null;
        val requested = if (text.isNotEmpty() && text.all { it in '0'..'9' })
        {
            text.toLongOrNull()?.let { if (it > CAP_MILLIS / 1_000) CAP_MILLIS else it * 1_000 } ?: CAP_MILLIS
        }
        else
        {
            dateMillis(text)?.let { it - nowMillis } ?: return null
        };
        return requested.coerceIn(0, CAP_MILLIS);
    }

    /** The header's value in `headers`, matched case-insensitively. */
    fun header(headers: List<Pair<String, String>>): String? =
        headers.firstOrNull { it.first.equals("retry-after", ignoreCase = true) }?.second;

    /**
     * An IMF-fixdate (`Sun, 06 Nov 1994 08:49:37 GMT`). `SimpleDateFormat` rather than
     * `java.time`, which needs API 26 and this module's floor is 24. A new formatter per
     * call, because the type is not thread-safe and a 429 is rare.
     */
    private fun dateMillis(text: String): Long?
    {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        format.timeZone = TimeZone.getTimeZone("GMT");
        format.isLenient = false;
        val position = ParsePosition(0);
        val date = format.parse(text, position);
        return if (date != null && position.index == text.length) date.time else null;
    }
}
