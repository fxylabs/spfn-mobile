// SPFN Mobile — replay and revocation conformance, Kotlin half of the parity gate.
//
// The vectors are sequences rather than single calls, because replay is a property of
// a sequence: the same proof accepted once and refused the second time is the point.
// Every fixture `proof` value is a signature derive-expected-values.py produced with
// the test keypair, so admitting one is also a statement that this platform's verifier
// accepts a signature produced outside either SDK.

package xyz.superfunction.spfn.conformance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import xyz.superfunction.spfn.auth.SpfnAuthException
import xyz.superfunction.spfn.auth.SpfnClientProof
import xyz.superfunction.spfn.auth.SpfnProofAcceptance
import xyz.superfunction.spfn.auth.SpfnProofInput

class AcceptanceConformanceTest
{
    @Test
    fun replayVectors()
    {
        runAcceptanceVectors("replay/replay.json");
    }

    @Test
    fun revocationVectors()
    {
        runAcceptanceVectors("revoke/revoke.json");
    }

    private fun runAcceptanceVectors(path: String)
    {
        val fixture = Fixtures.load(path).members();
        val base = fixture.obj("base");
        val window = fixture.number("replayWindowMillis");
        val publicKey = ProofTestKeyPair.publicKeySpkiDer();
        val vectors = fixture.list("vectors");
        assertTrue(vectors.isNotEmpty());

        for (vector in vectors)
        {
            val entry = vector.members();
            val name = entry.text("name");
            val revoked = entry["revokedKeyIds"]?.elements().orEmpty().map { it.text() }.toSet();
            val acceptance = SpfnProofAcceptance(window, revoked);

            for (step in entry.list("steps"))
            {
                val stepEntry = step.members();
                val input = SpfnProofInput(
                    method = base.text("method"),
                    path = base.text("path"),
                    clientId = base.text("clientId"),
                    keyId = base.text("keyId"),
                    nonce = stepEntry.text("nonce"),
                    issuedAtMillis = stepEntry.number("issuedAtMillis"),
                    bodySha256 = base.text("bodySha256")
                );
                val expectation = stepEntry.text("expect");
                val presented = stepEntry.text("proof");
                val nowMillis = stepEntry.number("nowMillis");

                if (expectation == "accept")
                {
                    acceptance.admit(presented, input, publicKey, nowMillis);
                    continue;
                }

                try
                {
                    acceptance.admit(presented, input, publicKey, nowMillis);
                    fail("'$name' step with nonce ${input.nonce} should have been refused with $expectation");
                }
                catch (failure: SpfnAuthException)
                {
                    assertEquals("'$name' refused for the wrong reason", expectation, failure.code);
                }
            }
        }
    }

    @Test
    fun revocationOutranksAValidProof()
    {
        val fixture = Fixtures.load("revoke/revoke.json").members();
        val base = fixture.obj("base");
        val publicKey = ProofTestKeyPair.publicKeySpkiDer();
        val keyId = base.text("keyId");

        val input = SpfnProofInput(
            method = base.text("method"),
            path = base.text("path"),
            clientId = base.text("clientId"),
            keyId = keyId,
            nonce = "nonce-order-check",
            issuedAtMillis = 1_750_000_000_000L,
            bodySha256 = base.text("bodySha256")
        );
        val goodProof = SpfnClientProof.proof(input) { message -> ProofTestKeyPair.sign(message) };

        val acceptance = SpfnProofAcceptance(fixture.number("replayWindowMillis"));
        acceptance.revoke(keyId);

        try
        {
            acceptance.admit(goodProof, input, publicKey, 1_750_000_001_000L);
            fail("a revoked key must be refused even with a valid proof");
        }
        catch (failure: SpfnAuthException)
        {
            assertEquals("revocation must be decided before verification", "SESSION_REVOKED", failure.code);
        }
    }
}
