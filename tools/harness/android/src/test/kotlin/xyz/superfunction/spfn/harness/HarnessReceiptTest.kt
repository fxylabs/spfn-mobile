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

    /** The epoch second in the file name is the same instant, and ASCII digits. */
    @Test
    fun fileNameCarriesTheCaseAndTheEpochSecond()
    {
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"));
        assertEquals(
            "receipt-google-user-cancel-1788220800.json",
            receipt(case = HarnessSocialCase.USER_CANCEL, outcome = "cancelled").fileName()
        );
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
            "abc123",
            HarnessTransport.serverCommit(listOf("X-SPFN-SERVER-COMMIT" to "abc123"))
        );
        assertEquals(null, HarnessTransport.serverCommit(listOf("content-type" to "application/json")));
    }

    private fun receipt(
        case: HarnessSocialCase,
        outcome: String,
        serverCommit: String? = null
    ): HarnessReceipt = HarnessReceipt(
        provider = "google",
        case = case,
        outcome = outcome,
        responseCode = null,
        errorCode = null,
        isNewUser = null,
        keyIdMatch = null,
        keyRemainsAfterFailure = false,
        timestampMillis = 1_788_220_800_000L,
        serverBaseUrl = "s://h:1",
        serverCommit = serverCommit,
        sdkVersion = "0.0.0",
        contractVersion = "0.0.0"
    );
}
