// SPFN Mobile — where the client key lives, and the one thing it does.
//
// Counterpart of Sources/SPFNClient/SPFNKeyProvider.swift. The provider is a signer:
// the session hands it the canonical proof-input bytes and gets a raw ECDSA signature
// back, so the private key never exists as a value outside the provider. That seam is
// what lets a hardware-backed provider — Android Keystore, StrongBox — replace this one
// later without an interface change, because hardware keys cannot be exported either;
// signing is the only operation they have.
//
// Key custody (Keystore, StrongBox, attestation) is a separate decision
// (docs/OPEN-DECISIONS.md). The software provider below is an alpha stand-in and says so.

package xyz.superfunction.spfn.client

import xyz.superfunction.spfn.auth.SpfnEcdsa
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec

/** Supplies the client identity and signs one message with the client key. */
interface SpfnKeyProvider
{
    /** The client identifier the proof is taken over. Not a secret. */
    val clientId: String

    /** The key identifier the proof is taken over. Not a secret. */
    val keyId: String

    /**
     * Signs the canonical proof-input bytes with ECDSA P-256 over SHA-256 and returns
     * the raw `r ‖ s` signature: two 32-byte big-endian integers, 64 bytes total.
     *
     * The key is never returned, so a caller cannot retain it by accident. A provider
     * whose platform signer emits DER converts to raw before returning.
     */
    fun sign(message: ByteArray): ByteArray
}

/**
 * Holds a P-256 private key in memory for the life of the process.
 *
 * Suitable for tests and for the reference-server integration, not for a shipped app:
 * nothing here survives a restart and nothing here is protected by the platform
 * keystore. A hardware-backed provider is a separate decision.
 */
class SpfnSoftwareKeyProvider private constructor(
    override val clientId: String,
    override val keyId: String,
    private val privateKey: PrivateKey,
    publicKeySpkiDer: ByteArray
) : SpfnKeyProvider
{
    // Copied in, and copied out on every read. A ByteArray is mutable and passed by
    // reference, so sharing the caller's array would let a later mutation change what
    // this provider advertises for registration.
    private val publicKey: ByteArray = publicKeySpkiDer.copyOf()

    /**
     * The public half in the contract's representation: SPKI DER. Not a secret — this
     * is the value a client registers with the server.
     */
    val publicKeySpkiDer: ByteArray
        get() = publicKey.copyOf()

    init
    {
        // A mismatched pair fails here, at construction, rather than as an
        // unexplainable PROOF_INVALID against a server: sign one probe message and
        // verify it with the public half this provider will advertise.
        val probe = "spfn-software-key-provider-pair-check".toByteArray(Charsets.UTF_8);
        val verifier = Signature.getInstance(ALGORITHM);
        verifier.initVerify(SpfnEcdsa.publicKeyFromSpki(publicKey));
        verifier.update(probe);
        require(verifier.verify(SpfnEcdsa.rawToDer(sign(probe))))
        {
            "the private key and publicKeySpkiDer are not halves of one keypair"
        };
    }

    /**
     * The JCA signs into DER, and the contract's wire form is raw r ‖ s, so the
     * conversion happens here — inside the provider, never at a call site that would
     * then be one more place to get the r/s padding wrong.
     */
    override fun sign(message: ByteArray): ByteArray
    {
        val signer = Signature.getInstance(ALGORITHM);
        signer.initSign(privateKey, random);
        signer.update(message);
        return SpfnEcdsa.derToRaw(signer.sign());
    }

    /** Deliberately not a data class: a generated `toString` would print the key. */
    override fun toString(): String =
        "SpfnSoftwareKeyProvider(clientId=$clientId, keyId=$keyId, privateKey=redacted)"

    companion object
    {
        private const val ALGORITHM = "SHA256withECDSA"
        private const val CURVE = "secp256r1"

        private val random = SecureRandom()

        /**
         * A provider over a fresh random keypair. Register [publicKeySpkiDer] with the
         * verifier before the first handshake.
         */
        fun generate(clientId: String, keyId: String): SpfnSoftwareKeyProvider
        {
            val generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(ECGenParameterSpec(CURVE), random);
            val pair = generator.generateKeyPair();
            return SpfnSoftwareKeyProvider(clientId, keyId, pair.private, pair.public.encoded);
        }

        /**
         * A provider over a fixed keypair, as the conformance fixtures pin one:
         * the private half as PKCS#8 DER, the public half as SPKI DER.
         */
        fun fromPkcs8(
            clientId: String,
            keyId: String,
            privateKeyPkcs8: ByteArray,
            publicKeySpkiDer: ByteArray
        ): SpfnSoftwareKeyProvider
        {
            val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateKeyPkcs8));
            return SpfnSoftwareKeyProvider(clientId, keyId, privateKey, publicKeySpkiDer);
        }
    }
}
