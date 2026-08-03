// SPFN Mobile — clientProofV1 conformance, Kotlin half of the parity gate.
//
// The proof-input bytes stay byte-pinned. The signature does not — an ECDSA signer
// draws a random nonce — so it is judged in both directions instead: the fixture's
// recorded signature (produced by derive-expected-values.py, outside either SDK) must
// verify here, and a signature this platform produces must verify under the fixture
// public key. The reject table then proves the verifier refuses everything that is
// not a valid raw-r‖s base16-lower signature under the named key.

package xyz.superfunction.spfn.conformance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import xyz.superfunction.spfn.auth.SpfnAuthException
import xyz.superfunction.spfn.auth.SpfnClientProof
import xyz.superfunction.spfn.auth.SpfnEcdsa
import xyz.superfunction.spfn.auth.SpfnProofInput
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

fun proofInput(entry: Map<String, SpfnCanonicalValue>): SpfnProofInput = SpfnProofInput(
    method = entry.text("method"),
    path = entry.text("path"),
    clientId = entry.text("clientId"),
    keyId = entry.text("keyId"),
    nonce = entry.text("nonce"),
    issuedAtMillis = entry.number("issuedAtMillis"),
    bodySha256 = entry.text("bodySha256")
)

/**
 * The fixture test keypair. TEST ONLY — its private half is published on purpose; see
 * the `testKeyPair.note` field in proof/proof-input.json.
 */
object ProofTestKeyPair
{
    private fun block(): Map<String, SpfnCanonicalValue> =
        Fixtures.load("proof/proof-input.json").members().obj("testKeyPair")

    fun publicKeySpkiDer(): ByteArray = Base64.getDecoder().decode(block().text("publicKeySpkiBase64"))

    /** Signs with the fixture private key, DER converted to the contract's raw form. */
    fun sign(message: ByteArray): ByteArray
    {
        val privateKey = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(block().text("privateKeyPkcs8Base64"))));
        val signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(message);
        return SpfnEcdsa.derToRaw(signer.sign());
    }
}

class ProofConformanceTest
{
    @Test
    fun proofInputVectors()
    {
        val fixture = Fixtures.load("proof/proof-input.json").members();
        val publicKey = ProofTestKeyPair.publicKeySpkiDer();
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

            // The fixture signature was produced outside either SDK; verifying it
            // proves this platform's verifier interoperates rather than agreeing only
            // with its own signer.
            SpfnClientProof.verify(entry.text("signatureRsHex"), input, publicKey);

            // And this platform's own signature must verify under the same key.
            val own = SpfnClientProof.proof(input) { message -> ProofTestKeyPair.sign(message) };
            assertEquals("a wire proof is 128 hex characters for '$name'", 128, own.length);
            assertEquals("a wire proof is base16-lower for '$name'", own.lowercase(), own);
            SpfnClientProof.verify(own, input, publicKey);
        }
    }

    /**
     * A signature over one input must fail when any single field differs — one case
     * per proof-input field (A1–A8), because a verifier that ignored one line of the
     * canonical form would stay green under a single-field probe of any other line.
     *
     * Each tampered value avoids C0 controls (so the failure is the verification
     * path, never the proof-input error path), `issuedAtMillis` changes as a number,
     * and the tampered `bodySha256` stays 64 lowercase hex (so it reaches signature
     * verification rather than any format gate). Every case first asserts the
     * canonical bytes really changed, so a vacuous tamper cannot pass.
     */
    @Test
    fun eachProofInputFieldTamperedIndividuallyFailsVerification()
    {
        val fixture = Fixtures.load("proof/proof-input.json").members();
        val publicKey = ProofTestKeyPair.publicKeySpkiDer();
        val entry = fixture.list("vectors").first().members();
        val input = proofInput(entry.obj("input"));
        val recorded = entry.text("signatureRsHex");
        val originalBytes = SpfnClientProof.canonicalBytes(input);

        // A2–A8: the seven fields the input type carries.
        val cases = listOf(
            "A2-method" to input.copy(method = "PUT"),
            "A3-path" to input.copy(path = input.path + "-x"),
            "A4-clientId" to input.copy(clientId = input.clientId + "-x"),
            "A5-keyId" to input.copy(keyId = input.keyId + "-x"),
            "A6-nonce" to input.copy(nonce = input.nonce + "-x"),
            "A7-issuedAtMillis" to input.copy(issuedAtMillis = input.issuedAtMillis + 1),
            "A8-bodySha256" to input.copy(bodySha256 = tamperedHexDigest(input.bodySha256))
        );

        for ((field, tampered) in cases)
        {
            assertFalse(
                "'$field' tampering did not change the canonical bytes; the case is vacuous",
                SpfnClientProof.canonicalBytes(tampered).contentEquals(originalBytes)
            );
            try
            {
                SpfnClientProof.verify(recorded, tampered, publicKey);
                fail("'$field' tampering was accepted");
            }
            catch (failure: SpfnAuthException)
            {
                assertEquals("'$field' refused with the wrong error", "PROOF_INVALID", failure.code);
            }
        }

        // A1: the profile is a constant the input type cannot carry, so the tamper
        // runs the other way — a signature over bytes whose profile line differs must
        // fail against the real input. The verify call here is the same code path as
        // above; only the signed message is different. This never touches profile
        // *policy* (unknownProfilePolicy is a contract refusal), only the signature.
        val originalString = String(originalBytes, Charsets.UTF_8);
        assertTrue("the first proof-input line is the profile", originalString.startsWith("clientProofV1\n"));
        val profileTamperedBytes = ("clientProofX" + originalString.removePrefix("clientProofV1"))
            .toByteArray(Charsets.UTF_8);
        assertFalse("'A1-profile' tampering is vacuous", profileTamperedBytes.contentEquals(originalBytes));

        val overTamperedProfile = hexLower(ProofTestKeyPair.sign(profileTamperedBytes));
        try
        {
            SpfnClientProof.verify(overTamperedProfile, input, publicKey);
            fail("'A1-profile' tampering was accepted");
        }
        catch (failure: SpfnAuthException)
        {
            assertEquals("'A1-profile' refused with the wrong error", "PROOF_INVALID", failure.code);
        }
    }

    /**
     * A different 64-character lowercase hex digest: still valid in form, so the
     * refusal it provokes is the signature check and never a format gate.
     */
    private fun tamperedHexDigest(digest: String): String =
        digest.dropLast(1) + if (digest.last() == '0') "f" else "0"

    private fun hexLower(bytes: ByteArray): String
    {
        val digits = "0123456789abcdef";
        val out = StringBuilder(bytes.size * 2);
        for (byte in bytes)
        {
            val value = byte.toInt() and 0xFF;
            out.append(digits[value shr 4]);
            out.append(digits[value and 0x0F]);
        }
        return out.toString();
    }

    /**
     * DER, uppercase, truncation, non-hex, a wrong key and r = s = 0 are all one
     * refusal. The DER, uppercase and truncated entries derive from a signature that
     * DOES verify in its correct form, so a verifier that ignores encoding admits them
     * and fails here.
     */
    @Test
    fun signatureRejectVectors()
    {
        val fixture = Fixtures.load("proof/proof-input.json").members();
        val publicKey = ProofTestKeyPair.publicKeySpkiDer();
        val vectors = fixture.list("vectors").map { it.members() };
        val rejects = fixture.list("signatureRejects");
        assertTrue(rejects.isNotEmpty());

        for (reject in rejects)
        {
            val entry = reject.members();
            val name = entry.text("name");
            val vector = vectors.firstOrNull { it.text("name") == entry.text("vector") }
                ?: error("'$name' names an unknown vector '${entry.text("vector")}'");
            val input = proofInput(vector.obj("input"));

            try
            {
                SpfnClientProof.verify(entry.text("presented"), input, publicKey);
                fail("'$name' was accepted but must be refused");
            }
            catch (failure: SpfnAuthException)
            {
                assertEquals("'$name' refused with the wrong error", "PROOF_INVALID", failure.code);
            }
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

    /**
     * The bundle's signature clause and this SDK's mechanism have to be the same
     * statement — and the retired `mac` clause has to be gone, so a stale bundle (or a
     * stale SDK) fails a test run rather than surfacing as a 401 against a server.
     */
    @Test
    fun signatureClauseMatchesTheContract()
    {
        val profile = SpfnCanonicalJson
            .parse(Fixtures.bytes("Contracts/spfn-mobile-contract.json"))
            .members()
            .obj("clientProofV1");

        assertNull("the HMAC clause was retired with contract 0.2.0", profile["mac"]);

        val clause = profile.obj("signature");
        assertEquals("ECDSA P-256 with SHA-256", clause.text("algorithm"));
        assertTrue(clause.text("encoding").contains("base16-lower (128 hex characters)"));
        assertTrue(clause.text("publicKey").startsWith("SPKI DER, base64"));
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
