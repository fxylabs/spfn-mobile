// SPFN Mobile — core unit tests. Kotlin counterpart of Tests/SPFNCoreTests.

package xyz.superfunction.spfn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    private val preStable = SpfnContractBinding(
        importedVersion = "0.1.0",
        importedManifestSha256 = "b".repeat(64),
        supportedRange = ">=0.1.0 <0.2.0",
        supportedMajor = 0,
        supportedMinor = 1,
        origin = "spfn-primitives-ci-export"
    )

    private val devBundle = SpfnContractBinding(
        importedVersion = "1.0.0-dev.1",
        importedManifestSha256 = "c".repeat(64),
        supportedRange = ">=1.0.0-dev.1 <2.0.0",
        supportedMajor = 1,
        supportedMinor = 0,
        origin = "spfn-mobile-step2-dev-bundle"
    )

    @Test
    fun aDevBundleIsNeverReportedAsAnUpstreamExport()
    {
        assertFalse(devBundle.isUpstreamExport);
        assertTrue(preStable.isUpstreamExport);
    }

    /**
     * The upper bound is derived, never parsed out of the printed range, so the two
     * cannot disagree about what the SDK accepts.
     */
    @Test
    fun upperBoundFollowsTheBreakingAxis()
    {
        assertEquals("0.2.0", preStable.upperBound);
        assertEquals("2.0.0", devBundle.upperBound);
        assertTrue(preStable.supportedRange.endsWith("<${preStable.upperBound}"));
        assertTrue(devBundle.supportedRange.endsWith("<${devBundle.upperBound}"));
    }

    /**
     * Every case in the shared table, which the Swift suite reads too. A rule that drifts
     * on one platform fails there rather than against a real server.
     */
    @Test
    fun sharedRangeVectors()
    {
        val repoRoot = File(requireNotNull(System.getProperty("spfn.repoRoot")));
        val text = File(repoRoot, "tools/conformance/semver-range-vectors.json").readText();
        val root = SpfnCanonicalJson.parse(text.toByteArray()) as SpfnCanonicalValue.Obj;
        val cases = (root.members["cases"] as SpfnCanonicalValue.Arr).elements
            .map { (it as SpfnCanonicalValue.Obj).members };
        assertTrue("the shared table lost cases", cases.size >= 30);

        for (entry in cases)
        {
            fun text(key: String) = (entry[key] as SpfnCanonicalValue.Text).value;
            val candidate = text("candidate");
            val lower = text("lower");
            val upper = text("upper");
            val expected = (entry["supported"] as SpfnCanonicalValue.Bool).value;

            assertEquals(
                "'$candidate' against [$lower, $upper): ${text("why")}",
                expected,
                SpfnSemVer.satisfies(candidate, lower, upper)
            );
        }

        // The parser is asserted directly too. A range case can pass because the rule
        // refused for the right reason or because the parse failed for the wrong one, and
        // only these say which.
        val parsing = (root.members["parsing"] as SpfnCanonicalValue.Arr).elements
            .map { (it as SpfnCanonicalValue.Obj).members };
        assertTrue("the shared parser table lost cases", parsing.size >= 20);

        for (entry in parsing)
        {
            val subject = (entry["text"] as SpfnCanonicalValue.Text).value;
            val valid = (entry["valid"] as SpfnCanonicalValue.Bool).value;
            val why = (entry["why"] as SpfnCanonicalValue.Text).value;

            assertEquals("'$subject': $why", valid, SpfnSemVer.parse(subject) != null);
        }
    }

    /**
     * The same table driven through the public entry point, so the binding and the
     * comparator cannot pass separately while disagreeing with each other.
     */
    @Test
    fun theBindingRefusesWhatTheTableRefuses()
    {
        for (candidate in listOf("0.2.0", "0.1.0-rc.1", "0.1", "0.01.0", "", "1.0.0"))
        {
            try
            {
                preStable.requireSupported(candidate);
                fail("'$candidate' must be refused");
            }
            catch (failure: SpfnDecodingException)
            {
                assertEquals("CONTRACT_UNSUPPORTED", failure.code);
            }
        }
        preStable.requireSupported("0.1.0");
        preStable.requireSupported("0.1.9");
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
