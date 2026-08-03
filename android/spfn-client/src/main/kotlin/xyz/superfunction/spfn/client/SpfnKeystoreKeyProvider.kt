// SPFN Mobile — hardware custody for the client key on Android.
//
// The provider is still only a signer; what this file adds is where the key lives. The
// key is generated in the Android Keystore — inside StrongBox when the device has one,
// in the ordinary hardware-backed Keystore (TEE) otherwise — and the choice is recorded
// in `custody` rather than hidden: a caller deciding what the key protects against
// reads the record, not the code path.
//
// The Keystore itself is only reachable on a device or emulator, so the engine is a
// seam: [SpfnAndroidKeystoreEngine] is what an app wires in, and the JVM unit suite
// injects a software fake that honours the same interface. What a test can prove here
// is the selection and lifecycle logic; StrongBox attestation and the hardware paths
// are real-device evidence, deferred with the COMPATIBILITY real-device axis.
//
// Sources/SPFNClient/SPFNSecureEnclaveKeyProvider.swift is the Apple counterpart.

package xyz.superfunction.spfn.client

import xyz.superfunction.spfn.auth.SpfnEcdsa

/** What generating one Keystore key produced: the custody it landed in and its public half. */
class SpfnKeystoreGeneratedKey(
    val custody: SpfnKeyCustody,
    publicKeySpkiDer: ByteArray
)
{
    // Copied in and out: a ByteArray is mutable and shared by reference.
    private val publicKey: ByteArray = publicKeySpkiDer.copyOf()

    val publicKeySpkiDer: ByteArray
        get() = publicKey.copyOf()
}

/**
 * The Keystore operations a provider needs, as a seam.
 *
 * Everything above this interface — slot lifecycle, custody records, DER-to-raw
 * conversion — is platform-independent and unit-tested; everything below it is the
 * platform. A fake engine in the test suite implements the same five operations over
 * software keys, which is what lets the logic run on a JVM at all.
 */
interface SpfnKeystoreEngine
{
    /** Generates a P-256 signing key under [alias], preferring StrongBox when asked. */
    fun generate(alias: String, preferStrongBox: Boolean): SpfnKeystoreGeneratedKey

    /** The public half (SPKI DER) of the key under [alias], or null when there is none. */
    fun publicKeySpkiDer(alias: String): ByteArray?

    /**
     * Signs with the key under [alias], returning the platform's DER signature.
     * The raw-r‖s conversion happens above the seam, in exactly one place.
     */
    fun signDer(alias: String, message: ByteArray): ByteArray

    fun contains(alias: String): Boolean

    fun delete(alias: String)
}

/**
 * A client key under Keystore custody, created before any identity exists.
 *
 * This is the pre-enrollment half of a provider: it can sign and it can advertise its
 * public half, but it names no client. Enrollment turns it into a full
 * [SpfnKeystoreKeyProvider] by attaching the owner id the server issued.
 */
class SpfnKeystoreCustodyKey private constructor(
    val keyId: String,
    val custody: SpfnKeyCustody,
    val alias: String,
    private val engine: SpfnKeystoreEngine,
    publicKeySpkiDer: ByteArray
)
{
    private val publicKey: ByteArray = publicKeySpkiDer.copyOf()

    /**
     * The public half in the contract's representation: SPKI DER. Not a secret — this
     * is the value enrollment registers with the server.
     */
    val publicKeySpkiDer: ByteArray
        get() = publicKey.copyOf()

    /** Raw `r ‖ s`, 64 bytes — the same signer contract [SpfnKeyProvider] states. */
    fun sign(message: ByteArray): ByteArray = SpfnEcdsa.derToRaw(engine.signDer(alias, message))

    /** The metadata that persists this key, before or after enrollment names its owner. */
    fun metadata(clientId: String?, createdAtMillis: Long): SpfnStoredKeyMetadata = SpfnStoredKeyMetadata(
        keyId = keyId,
        clientId = clientId,
        custody = custody,
        createdAtMillis = createdAtMillis,
        alias = alias
    )

    /** Deletes the Keystore entry. A destroyed key cannot be reloaded or re-derived. */
    fun destroy()
    {
        engine.delete(alias);
    }

    /** The key never prints; the public half is available as an explicit read. */
    override fun toString(): String =
        "SpfnKeystoreCustodyKey(keyId=$keyId, custody=${custody.wireName}, privateKey=redacted)"

    companion object
    {
        /** Generates a fresh key, inside StrongBox when the device has one. */
        fun generate(
            keyId: String,
            engine: SpfnKeystoreEngine,
            preferStrongBox: Boolean = true
        ): SpfnKeystoreCustodyKey
        {
            val alias = "spfn-client-key-$keyId";
            val generated = engine.generate(alias, preferStrongBox);
            return SpfnKeystoreCustodyKey(keyId, generated.custody, alias, engine, generated.publicKeySpkiDer);
        }

        /**
         * Reconstructs a key from stored metadata, or null when the Keystore no longer
         * holds the alias. Null rather than a throw: a key the Keystore lost is a key
         * this device cannot sign with, and the caller's answer is re-enrollment.
         */
        fun reload(metadata: SpfnStoredKeyMetadata, engine: SpfnKeystoreEngine): SpfnKeystoreCustodyKey?
        {
            val publicKey = engine.publicKeySpkiDer(metadata.alias) ?: return null;
            return SpfnKeystoreCustodyKey(metadata.keyId, metadata.custody, metadata.alias, engine, publicKey);
        }
    }
}

/**
 * A custody key plus the identity enrollment attached to it: the hardware-backed
 * [SpfnKeyProvider] the session signs proofs with.
 */
class SpfnKeystoreKeyProvider(
    override val clientId: String,
    val key: SpfnKeystoreCustodyKey
) : SpfnKeyProvider
{
    override val keyId: String
        get() = key.keyId

    /** Which custody the underlying key actually has — StrongBox, or the recorded TEE fallback. */
    val custody: SpfnKeyCustody
        get() = key.custody

    override fun sign(message: ByteArray): ByteArray = key.sign(message)

    /** The same exact-string redaction contract the software provider carries. */
    override fun toString(): String =
        "SpfnKeystoreKeyProvider(clientId=$clientId, keyId=$keyId, custody=${custody.wireName}, privateKey=redacted)"

    companion object
    {
        /**
         * Reconstructs the provider a store persisted, or null when the slot is empty,
         * the record has no owner yet, or the Keystore no longer holds the key.
         */
        fun load(
            store: SpfnKeyMetadataStore,
            slot: String,
            engine: SpfnKeystoreEngine
        ): SpfnKeystoreKeyProvider?
        {
            val metadata = store.load(slot) ?: return null;
            val clientId = metadata.clientId ?: return null;
            val key = SpfnKeystoreCustodyKey.reload(metadata, engine) ?: return null;
            return SpfnKeystoreKeyProvider(clientId, key);
        }
    }
}
