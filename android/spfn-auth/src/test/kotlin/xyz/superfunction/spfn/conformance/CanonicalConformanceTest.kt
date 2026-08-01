// SPFN Mobile — canonical JSON conformance, Kotlin half of the parity gate.

package xyz.superfunction.spfn.conformance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnCanonicalException
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnDigest

class CanonicalConformanceTest
{
    @Test
    fun canonicalSerializationVectors()
    {
        val fixture = Fixtures.load("canonical/serialization.json").members();
        val vectors = fixture.list("vectors");
        assertTrue("the fixture must carry vectors", vectors.isNotEmpty());

        for (vector in vectors)
        {
            val entry = vector.members();
            val name = entry.text("name");
            val encoded = SpfnCanonicalJson.encodeToString(SpfnCanonicalJson.parse(entry.text("input")));

            assertEquals("canonical bytes differ for '$name'", entry.text("canonical"), encoded);
            assertEquals(
                "canonical digest differs for '$name'",
                entry.text("sha256"),
                SpfnDigest.sha256Hex(encoded)
            );
        }
    }

    @Test
    fun canonicalFormIsIdempotent()
    {
        val fixture = Fixtures.load("canonical/serialization.json").members();
        for (vector in fixture.list("vectors"))
        {
            val entry = vector.members();
            val once = SpfnCanonicalJson.encodeToString(SpfnCanonicalJson.parse(entry.text("input")));
            val twice = SpfnCanonicalJson.encodeToString(SpfnCanonicalJson.parse(once));
            assertEquals("canonicalizing a canonical form changed it for '${entry.text("name")}'", once, twice);
        }
    }

    @Test
    fun rejectedInputsFailWithTheNamedCode()
    {
        val fixture = Fixtures.load("canonical/rejects.json").members();
        val vectors = fixture.list("vectors");
        assertTrue(vectors.isNotEmpty());

        for (vector in vectors)
        {
            val entry = vector.members();
            val name = entry.text("name");
            val expected = entry.text("errorCode");

            try
            {
                SpfnCanonicalJson.parse(entry.text("input"));
                fail("'$name' was accepted but must be refused with $expected");
            }
            catch (failure: SpfnCanonicalException)
            {
                assertEquals("'$name' reported the wrong code", expected, failure.code);
            }
        }
    }
}
