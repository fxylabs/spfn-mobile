// SPFN Mobile — error model conformance, Kotlin half of the parity gate.

package xyz.superfunction.spfn.conformance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnDecodingException
import xyz.superfunction.spfn.core.SpfnDigest
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.generated.SpfnGeneratedErrorCode

class ErrorConformanceTest
{
    @Test
    fun knownErrorEnvelopesDecode()
    {
        val fixture = Fixtures.load("error/envelopes.json").members();
        val known = fixture.list("known");
        assertTrue(known.isNotEmpty());

        for (vector in known)
        {
            val entry = vector.members();
            val name = entry.text("name");
            val envelope = SpfnErrorEnvelope.decode(SpfnCanonicalJson.parse(entry.text("wire")));
            val code = SpfnGeneratedErrorCode.decode(envelope.code);

            assertEquals("code differs for '$name'", entry.text("code"), envelope.code);
            assertEquals("status differs for '$name'", entry.number("httpStatus"), code.httpStatus.toLong());
            assertEquals(
                "re-encoding the envelope changed it for '$name'",
                entry.text("wire"),
                SpfnCanonicalJson.encodeToString(envelope.canonicalValue())
            );
            assertEquals(
                "envelope digest differs for '$name'",
                entry.text("sha256"),
                SpfnDigest.sha256Hex(SpfnCanonicalJson.encode(envelope.canonicalValue()))
            );
        }
    }

    @Test
    fun everyContractErrorCodeIsGenerated()
    {
        val fixture = Fixtures.load("error/envelopes.json").members();
        val expected = fixture.list("known").map { it.members().text("code") }.sorted();
        assertEquals(expected, SpfnGeneratedErrorCode.entries.map { it.wireCode }.sorted());
    }

    @Test
    fun unknownErrorCodeIsRejectedRatherThanMapped()
    {
        val fixture = Fixtures.load("error/envelopes.json").members();
        for (vector in fixture.list("rejected"))
        {
            val entry = vector.members();
            val envelope = SpfnErrorEnvelope.decode(SpfnCanonicalJson.parse(entry.text("wire")));
            assertEquals(entry.text("rawCode"), envelope.code);

            try
            {
                SpfnGeneratedErrorCode.decode(envelope.code);
                fail("an unknown code must be refused, not mapped to a neighbour");
            }
            catch (failure: SpfnDecodingException)
            {
                assertEquals(entry.text("errorCode"), failure.code);
                assertTrue(
                    "the raw code must survive the failure",
                    failure.message.orEmpty().contains(entry.text("rawCode"))
                );
            }
        }
    }
}
