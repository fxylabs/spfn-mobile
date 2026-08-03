// SPFN Mobile — where a client key's metadata persists, and what one record holds.
//
// The Android Keystore holds the private key itself, addressed by alias; nothing secret
// ever needs a second home. What does need one is the metadata around the key — keyId,
// the owner the server issued, which custody actually generated the key — because the
// Keystore stores none of it. This seam mirrors Sources/SPFNClient/SPFNKeyStore.swift:
// tests inject an in-memory store, the SharedPreferences store is what an app wires in,
// and there is deliberately no file-based key fallback anywhere.

package xyz.superfunction.spfn.client

import android.content.Context

/**
 * Which custody actually generated a private key.
 *
 * Recorded rather than implied: a caller deciding what a key protects against needs to
 * know whether StrongBox was available when the key was made, and the answer must not
 * change shape depending on which code path happened to run.
 */
enum class SpfnKeyCustody(val wireName: String)
{
    /** The key was generated inside a StrongBox secure element. */
    STRONG_BOX("strongBox"),

    /**
     * StrongBox was unavailable, so the key lives in the ordinary hardware-backed
     * Keystore (TEE). The fallback is recorded, never hidden.
     */
    TRUSTED_ENVIRONMENT("trustedEnvironment");

    companion object
    {
        fun of(wireName: String): SpfnKeyCustody? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * One persisted client key's metadata. Nothing here is a secret: `keyId` and `clientId`
 * are proof-input fields and `alias` only names a Keystore entry on this device.
 */
class SpfnStoredKeyMetadata(
    val keyId: String,
    /**
     * The key owner's identity, which is the enrollment response's `userId`. Null for
     * a key that has been generated but not yet enrolled.
     */
    val clientId: String?,
    val custody: SpfnKeyCustody,
    /**
     * When the key was generated, in epoch milliseconds. The TTL judgment reads this:
     * `keyPolicy.ttlDays` counts from registration, and generation is the client-side
     * moment closest to it that survives a restart.
     */
    val createdAtMillis: Long,
    /** The Keystore alias the private key lives under. */
    val alias: String
)
{
    override fun equals(other: Any?): Boolean =
        other is SpfnStoredKeyMetadata &&
            other.keyId == keyId &&
            other.clientId == clientId &&
            other.custody == custody &&
            other.createdAtMillis == createdAtMillis &&
            other.alias == alias

    override fun hashCode(): Int =
        (((31 * keyId.hashCode() + (clientId?.hashCode() ?: 0)) * 31 + custody.hashCode()) * 31 +
            createdAtMillis.hashCode()) * 31 + alias.hashCode()

    override fun toString(): String =
        "SpfnStoredKeyMetadata(keyId=$keyId, clientId=$clientId, custody=${custody.wireName}, " +
            "createdAtMillis=$createdAtMillis, alias=$alias)"
}

/**
 * Persists key metadata under slot names.
 *
 * Two slots exist today: the active key and a rotation candidate. The store does not
 * know that — slots are the caller's vocabulary — and it never interprets a record.
 */
interface SpfnKeyMetadataStore
{
    fun load(slot: String): SpfnStoredKeyMetadata?

    fun save(slot: String, metadata: SpfnStoredKeyMetadata)

    fun delete(slot: String)
}

/**
 * SharedPreferences as an [SpfnKeyMetadataStore] — the store an app wires in.
 *
 * Plain preferences on purpose: every field is non-secret metadata, and dressing it in
 * an encrypted store would imply the opposite. The private key itself never passes
 * through here; it lives in the Keystore under [SpfnStoredKeyMetadata.alias].
 */
class SpfnSharedPreferencesKeyMetadataStore(
    context: Context,
    name: String = "xyz.superfunction.spfn.client-key"
) : SpfnKeyMetadataStore
{
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun load(slot: String): SpfnStoredKeyMetadata?
    {
        val keyId = preferences.getString(key(slot, "keyId"), null) ?: return null;
        val custodyName = preferences.getString(key(slot, "custody"), null) ?: return null;
        val custody = SpfnKeyCustody.of(custodyName) ?: return null;
        val alias = preferences.getString(key(slot, "alias"), null) ?: return null;
        if (!preferences.contains(key(slot, "createdAtMillis")))
        {
            return null;
        }
        return SpfnStoredKeyMetadata(
            keyId = keyId,
            clientId = preferences.getString(key(slot, "clientId"), null),
            custody = custody,
            createdAtMillis = preferences.getLong(key(slot, "createdAtMillis"), 0),
            alias = alias
        );
    }

    override fun save(slot: String, metadata: SpfnStoredKeyMetadata)
    {
        val editor = preferences.edit()
            .putString(key(slot, "keyId"), metadata.keyId)
            .putString(key(slot, "custody"), metadata.custody.wireName)
            .putLong(key(slot, "createdAtMillis"), metadata.createdAtMillis)
            .putString(key(slot, "alias"), metadata.alias);
        if (metadata.clientId != null)
        {
            editor.putString(key(slot, "clientId"), metadata.clientId);
        }
        else
        {
            editor.remove(key(slot, "clientId"));
        }
        editor.apply();
    }

    override fun delete(slot: String)
    {
        preferences.edit()
            .remove(key(slot, "keyId"))
            .remove(key(slot, "clientId"))
            .remove(key(slot, "custody"))
            .remove(key(slot, "createdAtMillis"))
            .remove(key(slot, "alias"))
            .apply();
    }

    private fun key(slot: String, field: String): String = "$slot.$field"
}
