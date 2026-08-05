// SPFN Mobile — the REST enrollment surface, held to the contract's shapes (N1, N2, N4).
//
// Raw HTTP like the rest of the server suite, for the same reason: half of what these
// cases send — proof headers on an unproven operation, a proof whose clientId is not
// the key's owner — is exactly what the SDK exists to never send.

package xyz.superfunction.spfn.reference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.auth.SpfnClientProof
import xyz.superfunction.spfn.auth.SpfnEcdsa
import xyz.superfunction.spfn.auth.SpfnProofInput
import xyz.superfunction.spfn.core.SpfnCanonicalJson
import xyz.superfunction.spfn.core.SpfnDigest
import xyz.superfunction.spfn.generated.SpfnKeyAlgorithm
import xyz.superfunction.spfn.generated.SpfnGeneratedOperations
import xyz.superfunction.spfn.generated.SpfnHandshakeRequest
import xyz.superfunction.spfn.generated.SpfnOauthNativeRequest
import xyz.superfunction.spfn.generated.SpfnOauthNativeResponse
import xyz.superfunction.spfn.generated.SpfnRotateKeyRequest
import xyz.superfunction.spfn.generated.SpfnRotateKeyResponse
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class SpfnReferenceRestOpsTest
{
    private val contentTypeOnly = listOf(SpfnReferenceWire.CONTENT_TYPE to SpfnReferenceWire.REQUEST_CONTENT_TYPE)

    // ---- N1: the fake enrollment -------------------------------------------

    @Test
    fun enrollRegistersTheKeyUnderTheTokenUserAndTheKeyThenProves()
    {
        SpfnReferenceHarness().use { harness ->
            val keyPair = newKeyPair();
            val enrolled = enroll(harness, keyPair, keyId = "key-n1-0001", userId = "user-n1-0001");

            assertEquals(200, enrolled.statusCode);
            val response = SpfnOauthNativeResponse.decode(enrolled.value());
            assertEquals("user-n1-0001", response.userId);
            assertEquals("key-n1-0001", response.keyId);
            assertTrue("a first enrollment names a new user", response.isNewUser);

            // The registration is real: the enrolled key opens a session under the
            // owner the token named, which is the whole point of enrolling.
            val opened = handshake(harness, keyPair, clientId = "user-n1-0001", keyId = "key-n1-0001");
            assertEquals(200, opened.statusCode);

            // A second key for the same user is not a new user.
            val second = enroll(harness, newKeyPair(), keyId = "key-n1-0002", userId = "user-n1-0001");
            assertEquals(200, second.statusCode);
            assertFalse(SpfnOauthNativeResponse.decode(second.value()).isNewUser);
        }
    }

    /**
     * Cell 19: the contract's `nativeEnrollment.nonceRule` — the body's nonce must be the
     * body's fingerprint. The real server checks this in `assertNonceBindsPublicKey`, and
     * without it here the reference server would be more permissive than the thing it
     * stands in for, which is the one way a fake server does harm.
     *
     * The token carries the wrong nonce too, so this row would pass the earlier
     * token-grammar check on its own: what it isolates is a body whose two fields
     * disagree, which is what a stolen id_token paired with the thief's key looks like.
     */
    @Test
    fun enrollRefusesANonceThatIsNotTheKeysFingerprint()
    {
        SpfnReferenceHarness().use { harness ->
            val keyPair = newKeyPair();
            val wrongNonce = SpfnDigest.sha256Hex(newKeyPair().public.encoded);
            val body = enrollmentBody(
                keyPair,
                keyId = "key-n1-0009",
                userId = "user-n1-0009",
                nonce = wrongNonce,
                idToken = "spfn-test-idtoken.google.user-n1-0009.$wrongNonce"
            );

            val refused = harness.send("POST", ENROLL_PATH, body, contentTypeOnly);
            assertEquals(400, refused.statusCode);

            // Nothing was registered: the key cannot open a session.
            val opened = handshake(harness, keyPair, clientId = "user-n1-0009", keyId = "key-n1-0009");
            assertEquals("PROOF_INVALID", opened.errorCode());
        }
    }

    @Test
    fun enrollRefusesATokenThatFailsTheFixedRule()
    {
        SpfnReferenceHarness().use { harness ->
            val keyPair = newKeyPair();
            for (idToken in listOf(
                "not-a-test-token",
                // The provider segment disagrees with the path.
                "spfn-test-idtoken.apple.user-n1-0003.nonce-rest-0001",
                // The nonce segment disagrees with the body.
                "spfn-test-idtoken.google.user-n1-0003.nonce-other",
                // An empty userId segment.
                "spfn-test-idtoken.google..nonce-rest-0001"
            ))
            {
                val refused = enroll(
                    harness,
                    keyPair,
                    keyId = "key-n1-0003",
                    userId = "ignored",
                    idToken = idToken
                );
                assertEquals(401, refused.statusCode);
                assertEquals("AUTH_FAILED", refused.errorCode());
            }

            // Nothing was registered by any of those: the key cannot open a session.
            val opened = handshake(harness, keyPair, clientId = "user-n1-0003", keyId = "key-n1-0003");
            assertEquals("PROOF_INVALID", opened.errorCode());
        }
    }

    @Test
    fun enrollRefusesAFingerprintThatIsNotTheKeysDigest()
    {
        SpfnReferenceHarness().use { harness ->
            val body = enrollmentBody(
                newKeyPair(),
                keyId = "key-n1-0004",
                userId = "user-n1-0004",
                fingerprint = "0".repeat(64)
            );
            val refused = harness.send("POST", ENROLL_PATH, body, contentTypeOnly);
            assertEquals(400, refused.statusCode);
            assertEquals("BAD_REQUEST", refused.errorCode());
        }
    }

    @Test
    fun enrollRefusesAKeyIdThatAlreadyExists()
    {
        SpfnReferenceHarness().use { harness ->
            assertEquals(200, enroll(harness, newKeyPair(), keyId = "key-n1-0005", userId = "user-n1-0005").statusCode);
            val refused = enroll(harness, newKeyPair(), keyId = "key-n1-0005", userId = "user-n1-0006");
            assertEquals(400, refused.statusCode);
            assertEquals("BAD_REQUEST", refused.errorCode());
        }
    }

    /** K1's server half: the unproven class means NEITHER proof headers NOR a session. */
    @Test
    fun enrollRefusesARequestCarryingProofHeaders()
    {
        SpfnReferenceHarness().use { harness ->
            val body = enrollmentBody(newKeyPair(), keyId = "key-n1-0006", userId = "user-n1-0006");
            val operation = SpfnGeneratedOperations.authEnrollOauthNative;
            val proven = harness.proofHeaders(operation, body);
            val refused = harness.send("POST", ENROLL_PATH, body, proven);
            assertEquals(400, refused.statusCode);
            assertEquals("BAD_REQUEST", refused.errorCode());
        }
    }

    // ---- N2: rotation ------------------------------------------------------

    @Test
    fun rotateReplacesTheKeyAndOnlyTheNewKeyProvesAfterwards()
    {
        SpfnReferenceHarness().use { harness ->
            val oldPair = newKeyPair();
            val newPair = newKeyPair();
            assertEquals(200, enroll(harness, oldPair, keyId = "key-n2-old", userId = "user-n2-0001").statusCode);

            // A session the old key opened, to prove rotation drops it.
            val oldSession = sessionIdOf(handshake(harness, oldPair, "user-n2-0001", "key-n2-old"));

            val rotated = rotate(
                harness,
                provedBy = oldPair,
                clientId = "user-n2-0001",
                oldKeyId = "key-n2-old",
                newPair = newPair,
                newKeyId = "key-n2-new"
            );
            assertEquals(200, rotated.statusCode);
            val response = SpfnRotateKeyResponse.decode(rotated.value());
            assertTrue(response.success);
            assertEquals("key-n2-new", response.keyId);

            // The old key is gone — indistinguishable from never having existed.
            val oldAgain = handshake(harness, oldPair, "user-n2-0001", "key-n2-old");
            assertEquals("PROOF_INVALID", oldAgain.errorCode());

            // The session the old key opened died with it.
            val echoBody = SpfnCanonicalJson.encode(
                xyz.superfunction.spfn.generated.SpfnEchoRequest(message = "after", sequence = 1).canonicalValue()
            );
            val echoWithOldSession = harness.send(
                SpfnGeneratedOperations.echoSend,
                echoBody,
                harness.proofHeaders(
                    SpfnGeneratedOperations.echoSend,
                    echoBody,
                    sessionId = oldSession,
                    clientId = "user-n2-0001",
                    keyId = "key-n2-new",
                    proofFor = signerOf(newPair)
                )
            );
            assertEquals("SESSION_REVOKED", echoWithOldSession.errorCode());

            // The new key proves, under the same owner.
            val opened = handshake(harness, newPair, "user-n2-0001", "key-n2-new");
            assertEquals(200, opened.statusCode);
        }
    }

    // ---- N4: the G9 mirror -------------------------------------------------

    /**
     * A proof whose clientId is not the key's owner is PROOF_INVALID — and it must be
     * byte-indistinguishable (but for requestId and HTTP artifacts) from an
     * unregistered keyId and a failed signature, or the refusal would disclose which
     * of the three it was.
     */
    @Test
    fun aClientIdThatIsNotTheKeysOwnerIsTheSameProofInvalidAsEveryOtherProofFailure()
    {
        SpfnReferenceHarness().use { harness ->
            val keyPair = newKeyPair();
            assertEquals(200, enroll(harness, keyPair, keyId = "key-n4-0001", userId = "user-n4-0001").statusCode);

            // Owner mismatch: a perfectly valid signature under somebody else's name.
            val ownerMismatch = handshake(harness, keyPair, clientId = "user-n4-9999", keyId = "key-n4-0001");
            // Unregistered keyId.
            val unregistered = handshake(harness, keyPair, clientId = "user-n4-0001", keyId = "key-n4-none");
            // Failed signature: another key signs under the registered keyId.
            val badSignature = handshake(
                harness,
                newKeyPair(),
                clientId = "user-n4-0001",
                keyId = "key-n4-0001"
            );

            for (refused in listOf(ownerMismatch, unregistered, badSignature))
            {
                assertEquals(401, refused.statusCode);
                assertEquals("PROOF_INVALID", refused.errorCode());
            }
            assertEquals(ownerMismatch.envelope().message, unregistered.envelope().message);
            assertEquals(ownerMismatch.envelope().message, badSignature.envelope().message);
        }
    }

    /** The pre-registered test key is owned too, so G9 binds the dev-surface fixture key. */
    @Test
    fun thePreRegisteredTestKeyIsOwnedByTheTestClient()
    {
        SpfnReferenceHarness().use { harness ->
            val body = SpfnCanonicalJson.encode(
                SpfnHandshakeRequest(
                    clientId = "client-somebody-else",
                    keyId = SpfnReferenceTestKeys.KEY_ID,
                    nonce = harness.nextNonce(),
                    issuedAtMillis = harness.clock.nowMillis()
                ).canonicalValue()
            );
            val refused = harness.send(
                SpfnGeneratedOperations.authClientProofHandshake,
                body,
                harness.proofHeaders(
                    SpfnGeneratedOperations.authClientProofHandshake,
                    body,
                    clientId = "client-somebody-else"
                )
            );
            assertEquals("PROOF_INVALID", refused.errorCode());
        }
    }

    // ---- the unimplemented pair stays honest -------------------------------

    @Test
    fun registerAndLoginAnswerNotImplementedRatherThanAFake404()
    {
        SpfnReferenceHarness().use { harness ->
            for (operation in listOf(
                SpfnGeneratedOperations.authEnrollRegister,
                SpfnGeneratedOperations.authEnrollLogin
            ))
            {
                val refused = harness.send(operation.method, operation.path, "{}".toByteArray(), contentTypeOnly);
                assertEquals(501, refused.statusCode);
                assertEquals("NOT_IMPLEMENTED", refused.errorCode());
            }
        }
    }

    // ---- assembly ----------------------------------------------------------

    private fun newKeyPair(): KeyPair
    {
        val generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private fun signerOf(keyPair: KeyPair): (SpfnProofInput) -> String = { input ->
        SpfnClientProof.proof(input) { message ->
            val signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(keyPair.private);
            signer.update(message);
            SpfnEcdsa.derToRaw(signer.sign());
        }
    }

    /**
     * The contract's `nativeEnrollment.nonceRule` shapes the defaults: the nonce IS the
     * fingerprint, and the fixed test token carries that same value. A row that wants
     * them to disagree overrides one of them, which is exactly what the rule's own row
     * does.
     */
    private fun enrollmentBody(
        keyPair: KeyPair,
        keyId: String,
        userId: String,
        fingerprint: String = SpfnDigest.sha256Hex(keyPair.public.encoded),
        nonce: String = fingerprint,
        idToken: String = "spfn-test-idtoken.google.$userId.$nonce"
    ): ByteArray = SpfnCanonicalJson.encode(
        SpfnOauthNativeRequest(
            idToken = idToken,
            nonce = nonce,
            publicKey = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            keyId = keyId,
            fingerprint = fingerprint,
            algorithm = SpfnKeyAlgorithm.ES256
        ).canonicalValue()
    )

    private fun enroll(
        harness: SpfnReferenceHarness,
        keyPair: KeyPair,
        keyId: String,
        userId: String,
        idToken: String? = null
    ): SpfnRawResponse
    {
        val fingerprint = SpfnDigest.sha256Hex(keyPair.public.encoded);
        val body = enrollmentBody(
            keyPair,
            keyId = keyId,
            userId = userId,
            idToken = idToken ?: "spfn-test-idtoken.google.$userId.$fingerprint"
        );
        return harness.send("POST", ENROLL_PATH, body, contentTypeOnly);
    }

    private fun handshake(
        harness: SpfnReferenceHarness,
        keyPair: KeyPair,
        clientId: String,
        keyId: String
    ): SpfnRawResponse
    {
        val operation = SpfnGeneratedOperations.authClientProofHandshake;
        val nonce = harness.nextNonce();
        val body = SpfnCanonicalJson.encode(
            SpfnHandshakeRequest(
                clientId = clientId,
                keyId = keyId,
                nonce = nonce,
                issuedAtMillis = harness.clock.nowMillis()
            ).canonicalValue()
        );
        return harness.send(
            operation,
            body,
            harness.proofHeaders(
                operation,
                body,
                nonce = nonce,
                clientId = clientId,
                keyId = keyId,
                proofFor = signerOf(keyPair)
            )
        );
    }

    private fun sessionIdOf(response: SpfnRawResponse): String
    {
        check(response.statusCode == 200) { "handshake refused with ${response.statusCode}" };
        return xyz.superfunction.spfn.generated.SpfnHandshakeResponse.decode(response.value()).sessionId;
    }

    private fun rotate(
        harness: SpfnReferenceHarness,
        provedBy: KeyPair,
        clientId: String,
        oldKeyId: String,
        newPair: KeyPair,
        newKeyId: String
    ): SpfnRawResponse
    {
        val operation = SpfnGeneratedOperations.authKeysRotate;
        val body = SpfnCanonicalJson.encode(
            SpfnRotateKeyRequest(
                publicKey = Base64.getEncoder().encodeToString(newPair.public.encoded),
                keyId = newKeyId,
                fingerprint = SpfnDigest.sha256Hex(newPair.public.encoded),
                algorithm = SpfnKeyAlgorithm.ES256
            ).canonicalValue()
        );
        return harness.send(
            operation,
            body,
            harness.proofHeaders(
                operation,
                body,
                clientId = clientId,
                keyId = oldKeyId,
                proofFor = signerOf(provedBy)
            )
        );
    }

    private companion object
    {
        const val ENROLL_PATH = "/_auth/oauth/google/native"
    }
}
