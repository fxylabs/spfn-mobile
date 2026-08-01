// SPFN Mobile — core unit tests. Kotlin counterpart of Tests/SPFNCoreTests.

package xyz.superfunction.spfn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class SpfnVersionTest
{
    @Test
    fun versionConstantMatchesVersionFile()
    {
        val repoRoot = File(requireNotNull(System.getProperty("spfn.repoRoot")));
        val onDisk = File(repoRoot, "VERSION").readText().trim();
        assertEquals("SpfnVersion.CURRENT drifted from the VERSION file", onDisk, SpfnVersion.CURRENT);
    }

    @Test
    fun versionIsAPreRelease()
    {
        assertTrue(
            "no stable release exists, so the version must carry a SemVer pre-release identifier (D9)",
            SpfnVersion.CURRENT.contains("-")
        );
    }

    @Test
    fun buildDeclaresItselfAScaffold()
    {
        assertTrue(SpfnScaffold.IS_SCAFFOLD);
        assertTrue(SpfnScaffold.DISCLAIMER.contains("no supported release"));
    }
}

class SpfnDigestTest
{
    @Test
    fun knownSha256Vector()
    {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SpfnDigest.sha256Hex("abc")
        );
    }

    @Test
    fun absentBodyDigestIsNotTheDigestOfTheEmptyString()
    {
        assertNotEquals(SpfnDigest.sha256Hex(""), SpfnDigest.ABSENT_BODY_DIGEST);
        assertEquals(64, SpfnDigest.ABSENT_BODY_DIGEST.length);
    }

    @Test
    fun constantTimeEqualsAgreesWithEquality()
    {
        assertTrue(SpfnDigest.constantTimeEquals("abcd", "abcd"));
        assertFalse(SpfnDigest.constantTimeEquals("abcd", "abce"));
        assertFalse(SpfnDigest.constantTimeEquals("abcd", "abcde"));
    }
}

class SpfnContractBindingTest
{
    private val binding = SpfnContractBinding(
        importedVersion = "1.0.0-dev.1",
        importedManifestSha256 = "a".repeat(64),
        supportedRange = ">=1.0.0-dev.1 <2.0.0",
        supportedMajor = 1,
        origin = "spfn-mobile-step2-dev-bundle"
    )

    @Test
    fun aDevBundleIsNeverReportedAsAnUpstreamExport()
    {
        assertFalse(binding.isUpstreamExport);
    }

    @Test
    fun supportedMajorIsAccepted()
    {
        binding.requireSupported("1.7.3");
    }

    @Test
    fun otherMajorsRaiseAnUpgradeError()
    {
        for (version in listOf("2.0.0", "0.9.0", "not-a-version"))
        {
            try
            {
                binding.requireSupported(version);
                fail("'$version' must be refused");
            }
            catch (failure: SpfnDecodingException)
            {
                assertEquals("CONTRACT_UNSUPPORTED", failure.code);
            }
        }
    }
}
