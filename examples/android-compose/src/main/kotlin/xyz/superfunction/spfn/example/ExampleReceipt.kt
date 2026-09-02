// SPFN Mobile — what one cell run left behind.
//
// The shape is tools/harness/.../HarnessReceipt.kt's and the reasons are the same: the
// JSON is assembled by hand so that every field which reaches the file is named in this
// class, and the timestamp is formatted with an explicit UTC zone and Locale.ROOT because
// a default locale can carry a non-Gregorian calendar or non-ASCII digits (P9), and with
// SimpleDateFormat rather than java.time because java.time arrives on Android at API 26
// and this app's floor is 24 (P14).
//
// **Nothing here is a credential.** A cell id, a fixture name, a stack depth, a state
// word, and the two versions this build is. There is nothing else to carry: this app
// never enrols and never holds a key.
//
// It is a copy of the harness's pattern and not a shared helper. The two apps write
// different receipts about different things into different directories, and coupling them
// would make one app's evidence format a constraint on the other's.

package xyz.superfunction.spfn.example

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ExampleReceipt(
    /** The cell the launch named, or `none` when it named nothing this app knows. */
    val cell: String,
    /** The seeding that cell ran under, or `none`. */
    val fixture: String,
    /** How deep the flow's stack was when the receipt was written. */
    val stackDepth: Int,
    val timestampMillis: Long,
    val sdkVersion: String,
    val contractVersion: String
)
{
    /**
     * `receipt-<cell>-<epochMillis>.json`.
     *
     * Milliseconds, not seconds, for the reason the harness records: two runs of one cell
     * that finished inside a second shared a name, and the second destroyed the first's
     * evidence — a failure mode where the more you run, the less you have.
     */
    fun fileName(): String = "receipt-$cell-$timestampMillis.json";

    /** The receipt as JSON, in a fixed field order this class is the whole list of. */
    fun toJson(): String = buildString {
        append("{\n");
        appendField("schema", SCHEMA, last = false);
        appendField("platform", PLATFORM, last = false);
        appendField("cell", cell, last = false);
        appendField("fixture", fixture, last = false);
        append("  \"stackDepth\": ").append(stackDepth).append(",\n");
        appendField("timestamp", timestamp(timestampMillis), last = false);
        appendField("sdkVersion", sdkVersion, last = false);
        appendField("contractVersion", contractVersion, last = true);
        append("}\n");
    };

    private fun StringBuilder.appendField(name: String, value: String, last: Boolean)
    {
        append("  \"").append(name).append("\": ");
        appendJsonString(value);
        append(if (last) "\n" else ",\n");
    }

    companion object
    {
        const val SCHEMA: String = "spfn-ui-cell-receipt/1";
        const val PLATFORM: String = "android";

        /** Hex digits as data, so no formatter and therefore no locale is involved (P9). */
        private const val HEX: String = "0123456789abcdef";

        /** The run's instant, ISO-8601 in UTC. See this file's header for both reasons. */
        fun timestamp(millis: Long): String
        {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
            format.timeZone = TimeZone.getTimeZone("UTC");
            return format.format(Date(millis));
        }

        /** One JSON string literal, escaped to printable ASCII. */
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
 * A `receipts` subdirectory of the external files directory, which is the one an `adb pull`
 * reaches without root. Its own subdirectory, and a different app id from the harness's, so
 * neither run can be mistaken for the other's evidence.
 */
class ExampleReceiptStore(private val context: Context)
{
    /**
     * Writes one receipt and answers its file name.
     *
     * A failure to write is raised, never swallowed: a run whose receipt silently did not
     * appear is indistinguishable from a run that never happened (P7).
     */
    fun write(receipt: ExampleReceipt): String
    {
        val root = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("no external files directory; nowhere to write a receipt");
        val directory = File(root, "receipts");
        if (!directory.exists() && !directory.mkdirs())
        {
            throw IllegalStateException("the receipts directory could not be created");
        }
        val file = File(directory, receipt.fileName());
        file.writeText(receipt.toJson());
        return file.name;
    }
}
