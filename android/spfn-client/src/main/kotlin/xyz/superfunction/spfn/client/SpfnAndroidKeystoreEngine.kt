// SPFN Mobile — the real Keystore engine an app wires in.
//
// In its own file, apart from the seam it implements, because this is the one engine
// that needs the Android platform: tools/reference-server compiles the rest of this
// package on a plain JVM and excludes exactly this file (and the SharedPreferences
// store) by name.

package xyz.superfunction.spfn.client

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The real engine: the Android Keystore, StrongBox preferred.
 *
 * Constructed lazily per call rather than held: a `KeyStore` instance is cheap, and a
 * held one is state that can outlive the entry it describes.
 */
class SpfnAndroidKeystoreEngine : SpfnKeystoreEngine
{
    override fun generate(alias: String, preferStrongBox: Boolean): SpfnKeystoreGeneratedKey
    {
        if (preferStrongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        {
            try
            {
                return generateWith(alias, strongBox = true);
            }
            catch (_: StrongBoxUnavailableException)
            {
                // The device advertises no secure element; the TEE path below is the
                // fallback the custody record will honestly report.
            }
        }
        return generateWith(alias, strongBox = false);
    }

    private fun generateWith(alias: String, strongBox: Boolean): SpfnKeystoreGeneratedKey
    {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE);
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256);
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        {
            spec.setIsStrongBoxBacked(true);
        }
        generator.initialize(spec.build());
        val pair = generator.generateKeyPair();
        val custody = if (strongBox) SpfnKeyCustody.STRONG_BOX else SpfnKeyCustody.TRUSTED_ENVIRONMENT;
        return SpfnKeystoreGeneratedKey(custody, pair.public.encoded);
    }

    override fun publicKeySpkiDer(alias: String): ByteArray?
    {
        val entry = keystore().getCertificate(alias) ?: return null;
        return entry.publicKey.encoded;
    }

    override fun signDer(alias: String, message: ByteArray): ByteArray
    {
        val key = keystore().getKey(alias, null) as? PrivateKey
            ?: throw IllegalStateException("no signing key under this alias");
        val signer = Signature.getInstance(ALGORITHM);
        signer.initSign(key);
        signer.update(message);
        return signer.sign();
    }

    override fun contains(alias: String): Boolean = keystore().containsAlias(alias)

    override fun delete(alias: String)
    {
        val keystore = keystore();
        if (keystore.containsAlias(alias))
        {
            keystore.deleteEntry(alias);
        }
    }

    private fun keystore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object
    {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALGORITHM = "SHA256withECDSA"
        private const val CURVE = "secp256r1"
    }
}
