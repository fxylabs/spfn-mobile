// SPFN Mobile — where the client key comes from.
//
// Counterpart of Sources/SPFNClient/SPFNKeyProvider.swift. The session never owns key
// material: it asks a provider to apply the key to one message and gets a proof back,
// so the only place a key exists as a value is inside the provider and inside one call.
//
// Key custody — Keystore, StrongBox, attestation — is not decided
// (docs/OPEN-DECISIONS.md). The in-memory provider below is an alpha stand-in.

package xyz.superfunction.spfn.client

/** Supplies the client identity and applies the client key. */
interface SpfnKeyProvider
{
    /** The client identifier the proof is taken over. Not a secret. */
    val clientId: String

    /** The key identifier the proof is taken over. Not a secret. */
    val keyId: String

    /**
     * Hands the key to [body] for the duration of one call and returns its result.
     *
     * The key is never returned, so a caller cannot retain it by accident.
     */
    fun <T> withKey(body: (ByteArray) -> T): T
}

/**
 * Holds the key in memory for the life of the process.
 *
 * Suitable for tests and for the reference-server integration, not for a shipped app:
 * nothing here survives a restart and nothing here is protected by the platform
 * keystore. A real provider is a separate decision.
 */
class SpfnInMemoryKeyProvider(
    override val clientId: String,
    override val keyId: String,
    key: ByteArray
) : SpfnKeyProvider
{
    // Copied in, and copied out again on every use. A ByteArray is mutable and is passed
    // by reference, so sharing the caller's array would let a later mutation change what
    // this provider signs with. The Swift counterpart gets this from value semantics.
    private val key: ByteArray = key.copyOf()

    override fun <T> withKey(body: (ByteArray) -> T): T = body(key.copyOf())

    /** Deliberately not a data class: the generated `toString` would print the key. */
    override fun toString(): String =
        "SpfnInMemoryKeyProvider(clientId=$clientId, keyId=$keyId, key=redacted)"
}
