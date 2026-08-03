// SPFN Mobile — custody on Android: what the suite can prove without a device.
//
// The Android Keystore only exists on a device or emulator, so every case here drives
// the engine seam with a software fake that honours the same interface, plus an
// in-memory metadata store. That is the deliberate split the provider states in its
// own header — the StrongBox/TEE branches are compiled by the library build and their
// runtime behaviour is real-device evidence, deferred with the COMPATIBILITY
// real-device axis.
//
// SPFNCustodyKeyTests.swift is the Apple counterpart over the same case set.

package xyz.superfunction.spfn.client

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.auth.SpfnEcdsa
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class SpfnKeystoreKeyProviderTest
{
    private val slot = "active"

    // ---- L1: which custody, recorded ---------------------------------------

    @Test
    fun aGeneratedKeyRecordsTheCustodyTheEngineLandedIn()
    {
        val strongBoxed = SpfnKeystoreCustodyKey.generate("key-custody-0001", FakeKeystoreEngine(hasStrongBox = true));
        assertEquals(SpfnKeyCustody.STRONG_BOX, strongBoxed.custody);
        assertEquals("key-custody-0001", strongBoxed.keyId);

        val fallenBack = SpfnKeystoreCustodyKey.generate("key-custody-0001", FakeKeystoreEngine(hasStrongBox = false));
        assertEquals(
            "a device without a secure element records the TEE fallback, never hides it",
            SpfnKeyCustody.TRUSTED_ENVIRONMENT,
            fallenBack.custody
        );
    }

    // ---- L2: the public half is the contract's representation --------------

    /**
     * The fixture keypair pins the exact SPKI shape the server's prime256v1 gate
     * parses; a fresh key must serialize under the same 26-byte algorithm header and
     * the uncompressed-point marker, differing only in the point itself.
     */
    @Test
    fun thePublicKeyIsP256SpkiDerMatchingTheFixtureShape()
    {
        val key = SpfnKeystoreCustodyKey.generate("key-custody-0002", FakeKeystoreEngine(hasStrongBox = false));
        val spki = key.publicKeySpkiDer;
        val fixture = ExecuteFixtures.fixturePublicKeySpkiDer();

        assertEquals("a P-256 SPKI is 91 bytes", fixture.size, spki.size);
        assertArrayEquals(
            "the algorithm identifier and uncompressed-point marker must match the fixture byte for byte",
            fixture.copyOfRange(0, 27),
            spki.copyOfRange(0, 27)
        );
        assertNotNull(SpfnEcdsa.publicKeyFromSpki(spki));
    }

    // ---- L3: the signer contract -------------------------------------------

    @Test
    fun signReturnsRawRSThatVerifiesAgainstTheAdvertisedPublicKey()
    {
        val key = SpfnKeystoreCustodyKey.generate("key-custody-0003", FakeKeystoreEngine(hasStrongBox = true));
        val message = "spfn-custody-sign-probe".toByteArray(Charsets.UTF_8);

        val signature = key.sign(message);

        assertEquals("raw r ‖ s, two 32-byte integers", 64, signature.size);
        assertTrue(verifies(key.publicKeySpkiDer, message, signature));
        assertFalse(
            "a verifier that accepts a tampered message discriminates nothing",
            verifies(key.publicKeySpkiDer, message + 0x78.toByte(), signature)
        );
    }

    // ---- L4: reload after a restart ----------------------------------------

    @Test
    fun aStoredKeyReloadsWithItsMetadataAndTheSameKeyMaterial()
    {
        val engine = FakeKeystoreEngine(hasStrongBox = true);
        val store = InMemoryKeyMetadataStore();
        val key = SpfnKeystoreCustodyKey.generate("key-custody-0004", engine);
        store.save(slot, key.metadata(clientId = "user-test-0001", createdAtMillis = 1_750_000_000_000));

        // A second process: nothing shared but the store and the platform keystore.
        val reloaded = SpfnKeystoreKeyProvider.load(store, slot, engine);

        assertNotNull(reloaded);
        assertEquals("user-test-0001", reloaded?.clientId);
        assertEquals("key-custody-0004", reloaded?.keyId);
        assertEquals(SpfnKeyCustody.STRONG_BOX, reloaded?.custody);

        // The same key, not merely the same names: what the reload signs must verify
        // against the public half the original advertised.
        val message = "spfn-custody-reload-probe".toByteArray(Charsets.UTF_8);
        assertTrue(verifies(key.publicKeySpkiDer, message, requireNotNull(reloaded).sign(message)));
    }

    @Test
    fun aRecordWithoutAnOwnerDoesNotLoadAsAProvider()
    {
        val engine = FakeKeystoreEngine(hasStrongBox = true);
        val store = InMemoryKeyMetadataStore();
        val key = SpfnKeystoreCustodyKey.generate("key-custody-0005", engine);
        store.save(slot, key.metadata(clientId = null, createdAtMillis = 1_750_000_000_000));

        assertNull(
            "a key that was never enrolled names no client and can prove nothing",
            SpfnKeystoreKeyProvider.load(store, slot, engine)
        );
    }

    // ---- L5: wipe ----------------------------------------------------------

    @Test
    fun aDestroyedKeyLeavesNothingToSignWith()
    {
        val engine = FakeKeystoreEngine(hasStrongBox = true);
        val store = InMemoryKeyMetadataStore();
        val key = SpfnKeystoreCustodyKey.generate("key-custody-0006", engine);
        store.save(slot, key.metadata(clientId = "user-test-0001", createdAtMillis = 1_750_000_000_000));

        key.destroy();
        store.delete(slot);

        assertNull("the metadata is gone", store.load(slot));
        assertFalse("the keystore entry is gone", engine.contains(key.alias));
        assertNull(
            "after a wipe there is no key to reload, so nothing can sign",
            SpfnKeystoreKeyProvider.load(store, slot, engine)
        );
    }

    /** The engine loses a key (a factory reset, a security event): reload answers null. */
    @Test
    fun metadataWhoseKeystoreEntryVanishedDoesNotLoad()
    {
        val engine = FakeKeystoreEngine(hasStrongBox = true);
        val store = InMemoryKeyMetadataStore();
        val key = SpfnKeystoreCustodyKey.generate("key-custody-0007", engine);
        store.save(slot, key.metadata(clientId = "user-test-0001", createdAtMillis = 1_750_000_000_000));

        engine.delete(key.alias);

        assertNull(SpfnKeystoreKeyProvider.load(store, slot, engine));
    }

    // ---- L6: redaction -----------------------------------------------------

    @Test
    fun noDefaultOutputPathPrintsKeyMaterial()
    {
        val key = SpfnKeystoreCustodyKey.generate("key-custody-0008", FakeKeystoreEngine(hasStrongBox = false));
        val provider = SpfnKeystoreKeyProvider("user-test-0001", key);

        assertEquals(
            "SpfnKeystoreCustodyKey(keyId=key-custody-0008, custody=trustedEnvironment, privateKey=redacted)",
            key.toString()
        );
        assertEquals(
            "SpfnKeystoreKeyProvider(clientId=user-test-0001, keyId=key-custody-0008, " +
                "custody=trustedEnvironment, privateKey=redacted)",
            provider.toString()
        );
    }

    private fun verifies(publicKeySpkiDer: ByteArray, message: ByteArray, rawSignature: ByteArray): Boolean
    {
        val verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(SpfnEcdsa.publicKeyFromSpki(publicKeySpkiDer));
        verifier.update(message);
        return verifier.verify(SpfnEcdsa.rawToDer(rawSignature));
    }
}

// ---- the injected seams -----------------------------------------------------

/**
 * The engine seam's test half: JCA software keys standing in for the Keystore, and an
 * advertised StrongBox flag standing in for the device's secure element. The custody
 * it reports follows the same rule the real engine implements — StrongBox when asked
 * for and present, TEE otherwise.
 */
class FakeKeystoreEngine(private val hasStrongBox: Boolean) : SpfnKeystoreEngine
{
    private val keys = mutableMapOf<String, java.security.KeyPair>()

    override fun generate(alias: String, preferStrongBox: Boolean): SpfnKeystoreGeneratedKey
    {
        val generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(ECGenParameterSpec("secp256r1"));
        val pair = generator.generateKeyPair();
        keys[alias] = pair;
        val custody = if (preferStrongBox && hasStrongBox)
        {
            SpfnKeyCustody.STRONG_BOX
        }
        else
        {
            SpfnKeyCustody.TRUSTED_ENVIRONMENT
        };
        return SpfnKeystoreGeneratedKey(custody, pair.public.encoded);
    }

    override fun publicKeySpkiDer(alias: String): ByteArray? = keys[alias]?.public?.encoded

    override fun signDer(alias: String, message: ByteArray): ByteArray
    {
        val key: PrivateKey = keys[alias]?.private ?: throw IllegalStateException("no signing key under this alias");
        val signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(message);
        return signer.sign();
    }

    override fun contains(alias: String): Boolean = keys.containsKey(alias)

    override fun delete(alias: String)
    {
        keys.remove(alias);
    }
}

/** The metadata seam's test half: slot semantics without SharedPreferences. */
class InMemoryKeyMetadataStore : SpfnKeyMetadataStore
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
