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

/**
 * An envelope's three fields are text a server wrote, so none of them may reach a log
 * through a default rendering — and the redaction that stops that must not disturb what
 * the rest of the SDK reads the envelope for. Counterpart of `SPFNErrorEnvelopeTests`.
 */
class SpfnErrorEnvelopeTest
{
    // Markers a real server would never send, so a hit is unambiguous.
    private val code = "MARKER_CODE_7f31"
    private val message = "session-marker-message-a4c2"
    private val requestId = "req-marker-b8e5"

    private fun envelope(): SpfnErrorEnvelope = SpfnErrorEnvelope(code, message, requestId)

    @Test
    fun toStringCarriesNoServerText()
    {
        val rendered = envelope().toString();

        for (marker in listOf(code, message, requestId))
        {
            assertFalse("toString exposed server-controlled text", rendered.contains(marker));
        }

        // Exact, so a rendering cannot start naming fields again in some other wording.
        assertEquals("SpfnErrorEnvelope(code=redacted, message=redacted, requestId=redacted)", rendered);
    }

    /**
     * The fields stay readable, because classifying an error is the whole point of having
     * them. Only printing one by accident is blocked.
     */
    @Test
    fun fieldsRemainReadable()
    {
        val subject = envelope();

        assertEquals(code, subject.code);
        assertEquals(message, subject.message);
        assertEquals(requestId, subject.requestId);
    }

    /**
     * `equals` and `hashCode` are hand-written now that this is no longer a data class,
     * so they are checked rather than assumed — including the canonical form, which the
     * conformance suite round-trips.
     */
    @Test
    fun equalityAndCanonicalFormAreUnchanged()
    {
        assertEquals(envelope(), envelope());
        assertEquals(envelope().hashCode(), envelope().hashCode());
        assertNotEquals(envelope(), SpfnErrorEnvelope(code, message, "req-other"));
        assertEquals(
            """{"error":{"code":"MARKER_CODE_7f31","message":"session-marker-message-a4c2","requestId":"req-marker-b8e5"}}""",
            SpfnCanonicalJson.encodeToString(envelope().canonicalValue())
        );
    }
}
