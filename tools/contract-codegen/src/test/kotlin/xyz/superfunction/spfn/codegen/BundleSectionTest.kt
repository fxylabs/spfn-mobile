// SPFN Mobile — the generator must refuse a bundle missing a section it consumes.
//
// The probe style is the one the conformance suites use against the `mac` clause: read
// the real pinned bundle, then hold the parser to what it must refuse. P8 is the pattern
// under test — a parser that lets an unrecognised or absent structure fall through an
// else-branch does not fail; it emits plausible clients from a contract it never read.
// Each case here removes or corrupts one section and requires generation to refuse.

package xyz.superfunction.spfn.codegen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class BundleSectionTest
{
    private val bundleText: String =
        File("../../Contracts/spfn-mobile-contract.json").readText(Charsets.UTF_8)

    private fun read(text: String): Bundle = Bundle.read(
        bundleText = text,
        sha256 = "unused-under-test",
        supportedRange = ">=0.3.0 <0.4.0",
        contractMajor = 0,
        contractMinor = 3
    )

    /** Renames one key so `required()` cannot find it, leaving the JSON well-formed. */
    private fun withoutKey(key: String): String
    {
        val marker = "\"$key\"";
        assertTrue("the pinned bundle no longer carries '$key'", bundleText.contains(marker));
        return bundleText.replace(marker, "\"${key}Removed\"");
    }

    private fun assertRefused(section: String, text: String)
    {
        try
        {
            read(text);
            fail("the generator read a bundle whose '$section' section is missing");
        }
        catch (_: JsonException)
        {
            // The refusal this probe exists to require.
        }
    }

    /** Requires the refusal AND that its message carries the reason, not a P8 fallback. */
    private fun assertRefusedSaying(section: String, text: String, fragment: String)
    {
        try
        {
            read(text);
            fail("the generator accepted a bundle it must refuse: $section");
        }
        catch (refusal: JsonException)
        {
            assertTrue(
                "refusal for $section says '${refusal.message}', expected it to name: $fragment",
                refusal.message.orEmpty().contains(fragment)
            );
        }
    }

    /** Rewrites every string-typed field to the given spelling, keeping the JSON well-formed. */
    private fun withFieldType(spelling: String): String
    {
        val marker = "\"type\": \"string\"";
        assertTrue("the pinned bundle no longer carries a string field", bundleText.contains(marker));
        return bundleText.replace(marker, "\"type\": \"$spelling\"");
    }

    @Test
    fun thePinnedBundleReadsAndExposesTheNewSections()
    {
        val bundle = read(bundleText);
        assertEquals(listOf("clientProofV1", "none"), bundle.authClasses);
        assertEquals(90L, bundle.keyPolicyTtlDays);
        assertEquals("auth.keys.rotate", bundle.keyRotationOperationId);
        assertTrue(bundle.clientIdRule.contains("key owner"));
        assertTrue(bundle.operations.any { it.id == "auth.enroll.oauthNative" });
        assertTrue(bundle.operations.any { it.id == "auth.keys.rotate" });
    }

    @Test
    fun aBundleWithoutOperationAuthClassesIsRefused()
    {
        assertRefused("operationAuthClasses", withoutKey("operationAuthClasses"));
    }

    @Test
    fun aBundleWithoutKeyPolicyIsRefused()
    {
        assertRefused("keyPolicy", withoutKey("keyPolicy"));
    }

    @Test
    fun aBundleWithoutRestOperationsIsRefused()
    {
        assertRefused("restOperations", withoutKey("restOperations"));
    }

    @Test
    fun aBundleWithoutClientIdRuleIsRefused()
    {
        assertRefused("clientIdRule", withoutKey("clientIdRule"));
    }

    @Test
    fun anOperationNamingAnUndeclaredAuthClassIsRefused()
    {
        val marker = "\"authProfile\": \"none\"";
        assertTrue(bundleText.contains(marker));
        // Every `none` operation now names a class the contract does not declare. The
        // generator must refuse rather than emit `mystery` as a plausible class name.
        assertRefused(
            "operationAuthClasses cross-reference",
            bundleText.replace(marker, "\"authProfile\": \"mystery\"")
        );
    }

    // The 0.7.0/0.8.0 grammar: decimal<scale> is parsed and bounds-checked, and still
    // refused for emission — the encode-time rejection path into the integer-only
    // canonical value model is a design decision the generator must not guess at.

    @Test
    fun aDecimalFieldIsRefusedUntilTheEmitterLearnsIt()
    {
        assertRefusedSaying("decimal<2> field", withFieldType("decimal<2>"), "does not emit yet");
    }

    @Test
    fun aDecimalScaleOutsideOneToEighteenIsRefused()
    {
        assertRefusedSaying("decimal<0> field", withFieldType("decimal<0>"), "outside 1..18");
        assertRefusedSaying("decimal<19> field", withFieldType("decimal<19>"), "outside 1..18");
    }

    @Test
    fun aMalformedDecimalScaleIsRefusedRatherThanReadAsATypeName()
    {
        assertRefusedSaying("decimal<2x> field", withFieldType("decimal<2x>"), "not a decimal spelling");
    }

    @Test
    fun aNumberFieldIsRefusedNamingTheSpellingThatReplacedIt()
    {
        assertRefusedSaying("number field", withFieldType("number"), "decimal<scale>");
    }

    /**
     * Contract 0.6.1 put `since`/`deprecatedIn`/`removedIn` on operations and an
     * `operationAvailability` block beside them. The generator consumes none of it and
     * must read past all of it: an unknown key is left alone, only a missing needed key
     * refuses.
     */
    @Test
    fun operationAvailabilityKeysAreReadPast()
    {
        val marker = "\"id\": \"echo.send\",";
        assertTrue(bundleText.contains(marker));
        val bundle = read(
            bundleText.replace(marker, "\"id\": \"echo.send\", \"deprecatedIn\": \"0.99.0\", \"removedIn\": \"1.0.0\",")
        );
        assertTrue(bundle.operations.any { it.id == "echo.send" });
    }

    @Test
    fun aRotationOperationNamingNoOperationIsRefused()
    {
        val marker = "\"rotationOperation\": \"auth.keys.rotate\"";
        assertTrue(bundleText.contains(marker));
        assertRefused(
            "keyPolicy.rotationOperation cross-reference",
            bundleText.replace(marker, "\"rotationOperation\": \"auth.keys.retire\"")
        );
    }
}
