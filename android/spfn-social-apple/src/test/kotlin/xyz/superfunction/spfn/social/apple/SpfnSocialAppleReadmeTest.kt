// SPFN Mobile — the README's "The person's name" example, compiled and run.
//
// The repository has no doc-snippet compile check, so the README's call is kept here in
// the exact shape it is printed in, against a real SpfnKeyLifecycle: a signature that
// moves breaks this file before it breaks an app. Only the seams under the lifecycle are
// doubles — a transport that answers one enrollment, a JCA key in place of the Keystore,
// and the scripted Apple driver SpfnSocialAppleTest.kt uses.

package xyz.superfunction.spfn.social.apple

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.client.SpfnKeyCustody
import xyz.superfunction.spfn.client.SpfnKeyLifecycle
import xyz.superfunction.spfn.client.SpfnKeyMetadataStore
import xyz.superfunction.spfn.client.SpfnKeystoreEngine
import xyz.superfunction.spfn.client.SpfnKeystoreGeneratedKey
import xyz.superfunction.spfn.client.SpfnSocialSignIn
import xyz.superfunction.spfn.client.SpfnStoredKeyMetadata
import xyz.superfunction.spfn.client.SpfnTransport
import xyz.superfunction.spfn.client.SpfnTransportRequest
import xyz.superfunction.spfn.client.SpfnTransportResponse
import xyz.superfunction.spfn.client.SpfnWireHeaders
import xyz.superfunction.spfn.generated.SpfnGeneratedContract

class SpfnSocialAppleReadmeTest
{
    /** A first authorization: the README's call sends the app's rendering of the name. */
    @Test
    fun readme_aFirstAuthorizationSendsTheNameTheAppComposed() = runBlocking {
        val transport = OneEnrollmentTransport();
        val apple = SpfnSocialApple(RecordingAppleDriver(token = TOKEN, name = SpfnSocialAppleName("Jane", "Appleseed")));
        val lifecycle = lifecycle(transport);

        val result = lifecycle.enrollWithSignIn(provider = "apple") { nonce ->
            val credential = apple.credential(nonce)
            SpfnSocialSignIn(idToken = credential.idToken, displayName = displayNameFor(credential.name))
        }

        assertEquals(KEY_ID, result.keyId);
        assertTrue(transport.body(), transport.body().contains("\"profile\":{\"name\":\"Jane Appleseed\"}"));
    }

    /** Every later sign-in: Apple sends no name, so the same call sends no `profile`. */
    @Test
    fun readme_aLaterSignInSendsNoProfile() = runBlocking {
        val transport = OneEnrollmentTransport();
        val apple = SpfnSocialApple(RecordingAppleDriver(token = TOKEN));
        val lifecycle = lifecycle(transport);

        lifecycle.enrollWithSignIn(provider = "apple") { nonce ->
            val credential = apple.credential(nonce)
            SpfnSocialSignIn(idToken = credential.idToken, displayName = displayNameFor(credential.name))
        }

        assertFalse(transport.body(), transport.body().contains("\"profile\""));
    }

    /** The app's own function; this one renders given before family, as English does. */
    private fun displayNameFor(name: SpfnSocialAppleName?): String? =
        name?.let { listOfNotNull(it.givenName, it.familyName).joinToString(" ") }

    private fun lifecycle(transport: SpfnTransport): SpfnKeyLifecycle =
        SpfnKeyLifecycle(
            transport = transport,
            store = InMemoryStore(),
            engine = JcaKeystoreEngine(),
            baseUrl = "https://example.invalid",
            newKeyId = { KEY_ID }
        )

    private companion object
    {
        const val TOKEN = "apple-token-0001"
        const val KEY_ID = "key-test-0001"
    }
}

/**
 * Answers the one enrollment request with success, announcing the contract this build was
 * generated from as a real server does, and keeps the request's body.
 */
private class OneEnrollmentTransport : SpfnTransport
{
    private val received = mutableListOf<SpfnTransportRequest>()

    override suspend fun execute(request: SpfnTransportRequest): SpfnTransportResponse
    {
        received.add(request);
        val enrolled = "{\"mfaRequired\":false,\"isNewUser\":true,\"keyId\":\"key-test-0001\",\"userId\":\"user-test-0001\"}";
        return SpfnTransportResponse(200, headers(), enrolled.toByteArray());
    }

    private fun headers(): List<Pair<String, String>> =
        listOf(
            "content-type" to "application/json",
            SpfnWireHeaders.SERVER_CONTRACT_VERSION to SpfnGeneratedContract.BINDING.importedVersion,
            SpfnWireHeaders.SUPPORTED_CONTRACT_RANGE to SpfnGeneratedContract.BINDING.supportedRange
        )

    fun body(): String = received.single().body!!.toString(Charsets.UTF_8)
}

private class InMemoryStore : SpfnKeyMetadataStore
{
    private val records = mutableMapOf<String, SpfnStoredKeyMetadata>()

    override fun load(slot: String): SpfnStoredKeyMetadata? = records[slot]

    override fun save(slot: String, metadata: SpfnStoredKeyMetadata)
    {
        records[slot] = metadata;
    }

    override fun delete(slot: String)
    {
        records.remove(slot);
    }
}

/** A P-256 key from the JVM's own provider, standing in for the Android Keystore. */
private class JcaKeystoreEngine : SpfnKeystoreEngine
{
    private val keys = mutableMapOf<String, KeyPair>()

    override fun generate(alias: String, preferStrongBox: Boolean): SpfnKeystoreGeneratedKey
    {
        val generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(ECGenParameterSpec("secp256r1"));
        val pair = generator.generateKeyPair();
        keys[alias] = pair;
        return SpfnKeystoreGeneratedKey(SpfnKeyCustody.TRUSTED_ENVIRONMENT, pair.public.encoded);
    }

    override fun publicKeySpkiDer(alias: String): ByteArray? = keys[alias]?.public?.encoded

    override fun signDer(alias: String, message: ByteArray): ByteArray
    {
        val signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getValue(alias).private);
        signer.update(message);
        return signer.sign();
    }

    override fun contains(alias: String): Boolean = keys.containsKey(alias)

    override fun delete(alias: String)
    {
        keys.remove(alias);
    }
}
