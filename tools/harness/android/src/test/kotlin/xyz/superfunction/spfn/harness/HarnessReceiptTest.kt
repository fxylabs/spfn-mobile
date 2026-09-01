package xyz.superfunction.spfn.harness

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a receipt's bytes must be.
 *
 * Every expected value here is written from the shared spec by hand — the field names, the
 * file-name shape, the three outcome words. None of it is read back out of a receipt this
 * code produced, because a table copied from its own subject asserts only that the subject
 * is self-consistent (docs/IMPLEMENTATION-PITFALLS.md P10).
 *
 * The rest of the harness is proved on a phone. This file exists because these particular
 * bytes fail silently: a locale-shifted year and a raw control character in a header both
 * produce a file that looks fine on the machine that wrote it.
 */
class HarnessReceiptTest
{
    private val original: Locale = Locale.getDefault();

    @After
    fun restoreLocale()
    {
        Locale.setDefault(original);
    }

    /**
     * The instant, in a locale whose calendar is not Gregorian.
     *
     * Thailand's locale prints Buddhist years — 2026 becomes 2569 — and a default-locale
     * formatter would put that in the file. `Locale.ROOT` is what keeps the receipt
     * readable by anything (P9).
     */
    @Test
    fun timestampIsGregorianUtcWhateverTheDefaultLocaleIs()
    {
        Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist-nu-thai"));
        assertEquals("2026-09-01T00:00:00Z", HarnessReceipt.timestamp(1_788_220_800_000L));
    }

    /** The epoch millisecond in the file name is the same instant, and ASCII digits. */
    @Test
    fun fileNameCarriesTheCaseAndTheEpochMillisecond()
    {
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"));
        assertEquals(
            "receipt-google-user-cancel-1788220800000.json",
            receipt(case = HarnessSocialCase.USER_CANCEL, outcome = "cancelled").fileName()
        );
    }

    /**
     * Two attempts inside the same second must not share a name.
     *
     * This is the failure the spec's millisecond granularity exists for: with seconds, the
     * second attempt overwrote the first, so a person running the same case twice in a row
     * to check something ended up with less evidence than one who ran it once.
     */
    @Test
    fun twoAttemptsInsideOneSecondGetDifferentNames()
    {
        val first = receipt(case = HarnessSocialCase.RE_LOGIN, outcome = "enrolled", millis = 1_788_220_800_123L);
        val second = receipt(case = HarnessSocialCase.RE_LOGIN, outcome = "enrolled", millis = 1_788_220_800_456L);
        // Same second by the receipt's own clock, and still two files.
        assertEquals(
            HarnessReceipt.timestamp(first.timestampMillis),
            HarnessReceipt.timestamp(second.timestampMillis)
        );
        assertTrue(first.fileName(), first.fileName() != second.fileName());
    }

    /** A field a case cannot know is null, never a plausible-looking default. */
    @Test
    fun unknownFieldsAreNullRatherThanFalse()
    {
        val json = receipt(case = HarnessSocialCase.USER_CANCEL, outcome = "cancelled").toJson();
        assertTrue(json, json.contains("\"isNewUser\": null"));
        assertTrue(json, json.contains("\"keyIdMatch\": null"));
        assertTrue(json, json.contains("\"responseCode\": null"));
        assertTrue(json, json.contains("\"keyRemainsAfterFailure\": false"));
    }

    /** Every field the spec names is present, spelled as the spec spells it. */
    @Test
    fun everyFieldTheSpecNamesIsPresent()
    {
        val json = receipt(case = HarnessSocialCase.FIRST_ENROLL, outcome = "enrolled").toJson();
        val fields = listOf(
            "schema", "provider", "platform", "case", "outcome", "responseCode", "errorCode",
            "isNewUser", "keyIdMatch", "keyRemainsAfterFailure", "timestamp", "serverBaseURL",
            "serverCommit", "sdkVersion", "contractVersion"
        );
        for (field in fields)
        {
            assertTrue(field, json.contains("\"$field\": "));
        }
        assertTrue(json, json.contains("\"schema\": \"spfn-device-receipt/1\""));
        assertTrue(json, json.contains("\"platform\": \"android\""));
        assertTrue(json, json.contains("\"case\": \"first-enroll\""));
    }

    /**
     * The one value in a receipt the app did not choose is a header the server sent, and
     * it reaches a JSON string literal. A quote, a backslash or a control character in it
     * must not be able to end that literal.
     */
    @Test
    fun aServerHeaderCannotBreakOutOfItsStringLiteral()
    {
        val json = receipt(
            case = HarnessSocialCase.FIRST_ENROLL,
            outcome = "enrolled",
            serverCommit = "a\"b\\c\nd\u0007e\u00e9"
        ).toJson();
        assertTrue(json, json.contains("\"serverCommit\": \"a\\\"b\\\\c\\u000ad\\u0007e\\u00e9\""));
    }

    /** The three words an outcome may be, and no fourth. */
    @Test
    fun theOutcomeVocabularyIsTheSpecs()
    {
        assertEquals("enrolled", HarnessSocialAttempt.OUTCOME_ENROLLED);
        assertEquals("cancelled", HarnessSocialAttempt.OUTCOME_CANCELLED);
        assertEquals("failed", HarnessSocialAttempt.OUTCOME_FAILED);
    }

    /** The five case names, spelled as the spec spells them. */
    @Test
    fun theCaseNamesAreTheSpecs()
    {
        assertEquals(
            listOf("first-enroll", "re-login", "user-cancel", "network-failure", "server-reject"),
            HarnessSocialCase.entries.map { it.wireName }
        );
    }

    /** A receipt records the server's address without its path or its query. */
    @Test
    fun theRecordedAddressIsHostOnly()
    {
        assertEquals("s://h:1", HarnessSocialConfiguration.origin("s://h:1/enroll?a=b"));
        assertEquals("s://h", HarnessSocialConfiguration.origin("s://h?a=b"));
        assertEquals("s://h", HarnessSocialConfiguration.origin("s://h#f"));
    }

    /** The commit header is found without regard to case, and without a locale's help. */
    @Test
    fun theCommitHeaderIsMatchedCaseInsensitively()
    {
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        assertEquals(
            "8becd16",
            HarnessObservation.commitOf(listOf("X-SPFN-SERVER-COMMIT" to "8BECD16"))
        );
        assertEquals(null, HarnessObservation.commitOf(listOf("content-type" to "application/json")));
    }

    /**
     * A header value only reaches a receipt if it IS a commit hash.
     *
     * The header is written by whatever answered the request, and the receipt is a file
     * that gets pulled off a phone and pasted into a report. A value that is not 7 to 40
     * lowercase hex characters is not a commit, whatever else it might be — an address, a
     * name, a token — and it is dropped rather than trimmed into shape.
     */
    @Test
    fun aHeaderThatIsNotACommitHashIsDropped()
    {
        val rejected = listOf(
            "release@example.com",
            "built by someone",
            // Both boundaries, one character outside: six hex digits and forty-one.
            "8becd1",
            "8becd168becd168becd168becd168becd168becd1",
            "8becd16z",
            // Arabic-Indic digits. A `isDigit()` predicate accepts these and an explicit
            // `[0-9]` range does not, which is the split the Swift half has to match (P9).
            "٨becd16",
            ""
        );
        for (value in rejected)
        {
            assertEquals(value, null, HarnessObservation.commitOf(listOf("x-commit" to value)));
        }

        // Both boundaries, inside: the short form git prints, and a full hash.
        val accepted = listOf("8becd16", "8becd168becd168becd168becd168becd168becd");
        for (value in accepted)
        {
            assertEquals(value, value, HarnessObservation.commitOf(listOf("x-commit" to value)));
        }
    }

    /** A receipt carries the commit the header named, and nothing a header merely said. */
    @Test
    fun aRejectedHeaderLeavesTheReceiptFieldNull()
    {
        val json = receipt(
            case = HarnessSocialCase.FIRST_ENROLL,
            outcome = "enrolled",
            serverCommit = HarnessObservation.commitOf(listOf("x-commit" to "release@example.com"))
        ).toJson();
        assertTrue(json, json.contains("\"serverCommit\": null"));
    }

    private fun receipt(
        case: HarnessSocialCase,
        outcome: String,
        serverCommit: String? = null,
        millis: Long = 1_788_220_800_000L
    ): HarnessReceipt = HarnessReceipt(
        provider = "google",
        case = case,
        outcome = outcome,
        responseCode = null,
        errorCode = null,
        isNewUser = null,
        keyIdMatch = null,
        keyRemainsAfterFailure = false,
        timestampMillis = millis,
        serverBaseUrl = "s://h:1",
        serverCommit = serverCommit,
        sdkVersion = "0.0.0",
        contractVersion = "0.0.0"
    );
}
