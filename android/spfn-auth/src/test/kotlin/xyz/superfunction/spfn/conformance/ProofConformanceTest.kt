// SPFN Mobile — clientProofV1 conformance, Kotlin half of the parity gate.

package xyz.superfunction.spfn.conformance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import xyz.superfunction.spfn.auth.SpfnAuthException
import xyz.superfunction.spfn.auth.SpfnClientProof
import xyz.superfunction.spfn.auth.SpfnProofInput
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue

fun proofInput(entry: Map<String, SpfnCanonicalValue>): SpfnProofInput = SpfnProofInput(
    method = entry.text("method"),
    path = entry.text("path"),
    clientId = entry.text("clientId"),
    keyId = entry.text("keyId"),
    nonce = entry.text("nonce"),
    issuedAtMillis = entry.number("issuedAtMillis"),
    bodySha256 = entry.text("bodySha256")
)

class ProofConformanceTest
{
    @Test
    fun proofInputVectors()
    {
        val fixture = Fixtures.load("proof/proof-input.json").members();
        val key = syntheticKey();
        val vectors = fixture.list("vectors");
        assertTrue(vectors.isNotEmpty());

        for (vector in vectors)
        {
            val entry = vector.members();
            val name = entry.text("name");
            val input = proofInput(entry.obj("input"));

            assertEquals(
                "canonical proof input differs for '$name'",
                entry.text("canonicalString"),
                SpfnClientProof.canonicalString(input)
            );
            assertEquals(
                "proof input digest differs for '$name'",
                entry.text("canonicalSha256"),
                SpfnClientProof.canonicalDigest(input)
            );
            assertEquals(
                "proof MAC differs for '$name'",
                entry.text("proofHmacSha256"),
                SpfnClientProof.proof(input, key)
            );
            SpfnClientProof.verify(entry.text("proofHmacSha256"), input, key);
        }
    }

    @Test
    fun proofFieldOrderMatchesTheContract()
    {
        val bundle = SpfnCanonicalJson
            .parse(Fixtures.bytes("Contracts/spfn-mobile-contract.json"))
            .members();
        val declared = bundle.obj("clientProofV1").obj("proofInput").list("fields").map { it.text() };

        assertEquals(declared, SpfnClientProof.PROOF_INPUT_FIELDS);
    }

    @Test
    fun controlCharactersInProofFieldsAreRejected()
    {
        val fixture = Fixtures.load("proof/rejects.json").members();
        val vectors = fixture.list("vectors");
        assertTrue(vectors.isNotEmpty());

        for (vector in vectors)
        {
            val entry = vector.members();
            val name = entry.text("name");
            val input = proofInput(entry.obj("input"));

            try
            {
                SpfnClientProof.canonicalString(input);
                fail("'$name' was accepted but must be refused");
            }
            catch (failure: SpfnAuthException)
            {
                assertEquals("'$name' reported the wrong code", entry.text("errorCode"), failure.code);
                assertTrue(
                    "'$name' must name the offending field '${entry.text("field")}'",
                    failure.message.orEmpty().contains(entry.text("field"))
                );
            }
        }
    }
}
