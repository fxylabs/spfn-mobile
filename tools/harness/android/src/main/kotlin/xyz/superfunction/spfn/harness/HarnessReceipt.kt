package xyz.superfunction.spfn.harness

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What one device sign-in attempt left behind.
 *
 * The field list and the file name are the shared spec's, written from the spec rather
 * than derived from the iOS half or from a file a run produced (P10). The JSON is
 * assembled by hand here for one reason: every field that reaches the file is named in
 * this class, so "does a receipt ever carry a token" is answered by reading one screen of
 * code rather than by trusting a serializer's idea of what an object contains.
 *
 * **Nothing here is a credential.** No id_token, no email, no display name, no account
 * identifier. A receipt carrying a token would be a credential itself, and that is a
 * blocking defect rather than a tidy-up.
 *
 * A field a case cannot know is `null`, never a plausible default. `isNewUser: false` on a
 * cancelled attempt would read as "the server said this user already existed", which no
 * server said.
 */
class HarnessReceipt(
    val provider: String,
    val case: HarnessSocialCase,
    /** `enrolled`, `cancelled` or `failed`. */
    val outcome: String,
    /** The status of the last response this attempt received, or null when none arrived. */
    val responseCode: Int?,
    /** The SDK's own name for the refusal, or null on success. */
    val errorCode: String?,
    val isNewUser: Boolean?,
    /** Whether the key the server registered is the key this install now holds. */
    val keyIdMatch: Boolean?,
    /** Whether a Keystore entry outlived an attempt that did not enrol. */
    val keyRemainsAfterFailure: Boolean,
    val timestampMillis: Long,
    val serverBaseUrl: String,
    val serverCommit: String?,
    val sdkVersion: String,
    val contractVersion: String
)
{
    /** `receipt-<provider>-<case>-<epochSeconds>.json`, exactly as the spec fixes it. */
    fun fileName(): String = "receipt-$provider-${case.wireName}-${timestampMillis / 1000L}.json";

    /**
     * The receipt as JSON, in the spec's field order.
     *
     * Written with a fixed field list rather than a map so the order is the spec's and a
     * field cannot be added by accident somewhere else in the app.
     */
    fun toJson(): String = buildString {
        append("{\n");
        appendField("schema", SCHEMA, last = false);
        appendField("provider", provider, last = false);
        appendField("platform", PLATFORM, last = false);
        appendField("case", case.wireName, last = false);
        appendField("outcome", outcome, last = false);
        appendNumber("responseCode", responseCode, last = false);
        appendField("errorCode", errorCode, last = false);
        appendBoolean("isNewUser", isNewUser, last = false);
        appendBoolean("keyIdMatch", keyIdMatch, last = false);
        appendBoolean("keyRemainsAfterFailure", keyRemainsAfterFailure, last = false);
        appendField("timestamp", timestamp(timestampMillis), last = false);
        appendField("serverBaseURL", serverBaseUrl, last = false);
        appendField("serverCommit", serverCommit, last = false);
        appendField("sdkVersion", sdkVersion, last = false);
        appendField("contractVersion", contractVersion, last = true);
        append("}\n");
    };

    private fun StringBuilder.appendField(name: String, value: String?, last: Boolean)
    {
        append("  \"").append(name).append("\": ");
        if (value == null)
        {
            append("null");
        }
        else
        {
            appendJsonString(value);
        }
        append(if (last) "\n" else ",\n");
    }

    private fun StringBuilder.appendNumber(name: String, value: Int?, last: Boolean)
    {
        append("  \"").append(name).append("\": ").append(value?.toString() ?: "null");
        append(if (last) "\n" else ",\n");
    }

    private fun StringBuilder.appendBoolean(name: String, value: Boolean?, last: Boolean)
    {
        append("  \"").append(name).append("\": ").append(
            when (value)
            {
                true -> "true"
                false -> "false"
                null -> "null"
            }
        );
        append(if (last) "\n" else ",\n");
    }

    companion object
    {
        const val SCHEMA: String = "spfn-device-receipt/1";
        const val PLATFORM: String = "android";

        /** Hex digits as data, so no formatter and therefore no locale is involved (P9). */
        private const val HEX: String = "0123456789abcdef";

        /**
         * The attempt's instant, ISO-8601 in UTC.
         *
         * `Locale.ROOT` and an explicit UTC zone are both load-bearing. A default locale
         * can carry a non-Gregorian calendar — a Thai or Japanese-imperial locale prints a
         * different year for the same instant — and a default locale can render digits in
         * a non-ASCII script. Neither is a receipt anything can read (P9).
         *
         * `SimpleDateFormat` rather than `java.time.Instant`: `java.time` arrives on
         * Android at API 26 and this app's floor is 24, so an Instant here would compile,
         * pass every JVM unit test, and crash on the oldest phone the SDK claims (P14).
         */
        fun timestamp(millis: Long): String
        {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            format.timeZone = TimeZone.getTimeZone("UTC");
            return format.format(Date(millis));
        }

        /**
         * One JSON string literal, escaped to printable ASCII.
         *
         * Everything outside `0x20`–`0x7e` becomes a `\u` escape, which covers the one
         * value here the app did not choose — a commit header a server sent — without the
         * receipt's bytes depending on anyone's encoding.
         */
        fun StringBuilder.appendJsonString(value: String)
        {
            append('"');
            for (character in value)
            {
                when
                {
                    character == '"' -> append("\\\"")
                    character == '\\' -> append("\\\\")
                    character.code in 0x20..0x7e -> append(character)
                    else -> appendUnicodeEscape(character)
                }
            }
            append('"');
        }

        private fun StringBuilder.appendUnicodeEscape(character: Char)
        {
            append("\\u");
            append(HEX[(character.code shr 12) and 0xf]);
            append(HEX[(character.code shr 8) and 0xf]);
            append(HEX[(character.code shr 4) and 0xf]);
            append(HEX[character.code and 0xf]);
        }
    }
}

/**
 * Where receipts land, and what happens when they cannot.
 *
 * The external files directory is the one an `adb pull` reaches without root, which is the
 * whole point: a run on a phone has to be collectable from the Mac that started it.
 */
class HarnessReceiptStore(private val context: Context)
{
    /**
     * Writes one receipt and answers its file name.
     *
     * A failure to write is raised, never swallowed. A run whose receipt silently did not
     * appear is indistinguishable from a run that never happened, and the difference
     * between "no receipt" and "a receipt nobody could write" is the whole value of the
     * file (P7).
     */
    fun write(receipt: HarnessReceipt): String
    {
        val directory = context.getExternalFilesDir(null)
            ?: throw HarnessException.ReceiptDirectoryUnavailable();
        if (!directory.exists() && !directory.mkdirs())
        {
            throw HarnessException.ReceiptDirectoryUnavailable();
        }
        val file = File(directory, receipt.fileName());
        file.writeText(receipt.toJson());
        return file.name;
    }
}
