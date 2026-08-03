// SPFN Mobile — the metadata store an app wires in.
//
// In its own file, apart from the interface it implements, because this is the one
// metadata class that needs the Android platform: tools/reference-server compiles the
// rest of this package on a plain JVM and excludes exactly this file (and the real
// Keystore engine) by name.

package xyz.superfunction.spfn.client

import android.content.Context

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
