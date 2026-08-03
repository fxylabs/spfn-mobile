// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.1.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    28f2fd4cf37ef903dd9746d4058d510435b3905b9b94312f6e95120ad3603084
// contractVersion: 0.2.0
// origin:          spfn-primitives-ci-export
//
// Bundle origin: spfn-primitives-ci-export.
//
// Regenerate with: ./gradlew :contract-codegen:spfnGenerateClients
// Verified by:     ./gradlew :contract-codegen:spfnCodegenVerify

package xyz.superfunction.spfn.generated

import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnDecoding

data class SpfnHandshakeRequest(
    val clientId: String,
    val keyId: String,
    val nonce: String,
    val issuedAtMillis: Long
)
{
    /**
     * The canonical form of this value. An absent optional field is omitted,
     * never written as null, so the digest of a value never depends on how a
     * caller happened to spell "nothing".
     */
    fun canonicalValue(): SpfnCanonicalValue
    {
        val members = LinkedHashMap<String, SpfnCanonicalValue>();
        members["clientId"] = SpfnCanonicalValue.Text(clientId);
        members["keyId"] = SpfnCanonicalValue.Text(keyId);
        members["nonce"] = SpfnCanonicalValue.Text(nonce);
        members["issuedAtMillis"] = SpfnCanonicalValue.Integer(issuedAtMillis);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnHandshakeRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnHandshakeRequest(
                clientId = SpfnDecoding.string(members["clientId"], "$path.clientId"),
                keyId = SpfnDecoding.string(members["keyId"], "$path.keyId"),
                nonce = SpfnDecoding.string(members["nonce"], "$path.nonce"),
                issuedAtMillis = SpfnDecoding.integer(members["issuedAtMillis"], "$path.issuedAtMillis")
            );
        }
    }
}

data class SpfnHandshakeResponse(
    val sessionId: String,
    val expiresAtMillis: Long
)
{
    /**
     * The canonical form of this value. An absent optional field is omitted,
     * never written as null, so the digest of a value never depends on how a
     * caller happened to spell "nothing".
     */
    fun canonicalValue(): SpfnCanonicalValue
    {
        val members = LinkedHashMap<String, SpfnCanonicalValue>();
        members["sessionId"] = SpfnCanonicalValue.Text(sessionId);
        members["expiresAtMillis"] = SpfnCanonicalValue.Integer(expiresAtMillis);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnHandshakeResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnHandshakeResponse(
                sessionId = SpfnDecoding.string(members["sessionId"], "$path.sessionId"),
                expiresAtMillis = SpfnDecoding.integer(members["expiresAtMillis"], "$path.expiresAtMillis")
            );
        }
    }
}

data class SpfnEchoRequest(
    val message: String,
    val sequence: Long
)
{
    /**
     * The canonical form of this value. An absent optional field is omitted,
     * never written as null, so the digest of a value never depends on how a
     * caller happened to spell "nothing".
     */
    fun canonicalValue(): SpfnCanonicalValue
    {
        val members = LinkedHashMap<String, SpfnCanonicalValue>();
        members["message"] = SpfnCanonicalValue.Text(message);
        members["sequence"] = SpfnCanonicalValue.Integer(sequence);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnEchoRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnEchoRequest(
                message = SpfnDecoding.string(members["message"], "$path.message"),
                sequence = SpfnDecoding.integer(members["sequence"], "$path.sequence")
            );
        }
    }
}

data class SpfnEchoResponse(
    val message: String,
    val sequence: Long,
    val serverTimeMillis: Long
)
{
    /**
     * The canonical form of this value. An absent optional field is omitted,
     * never written as null, so the digest of a value never depends on how a
     * caller happened to spell "nothing".
     */
    fun canonicalValue(): SpfnCanonicalValue
    {
        val members = LinkedHashMap<String, SpfnCanonicalValue>();
        members["message"] = SpfnCanonicalValue.Text(message);
        members["sequence"] = SpfnCanonicalValue.Integer(sequence);
        members["serverTimeMillis"] = SpfnCanonicalValue.Integer(serverTimeMillis);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnEchoResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnEchoResponse(
                message = SpfnDecoding.string(members["message"], "$path.message"),
                sequence = SpfnDecoding.integer(members["sequence"], "$path.sequence"),
                serverTimeMillis = SpfnDecoding.integer(members["serverTimeMillis"], "$path.serverTimeMillis")
            );
        }
    }
}

data class SpfnListItemsRequest(
    val limit: Long,
    val cursor: String? = null
)
{
    /**
     * The canonical form of this value. An absent optional field is omitted,
     * never written as null, so the digest of a value never depends on how a
     * caller happened to spell "nothing".
     */
    fun canonicalValue(): SpfnCanonicalValue
    {
        val members = LinkedHashMap<String, SpfnCanonicalValue>();
        members["limit"] = SpfnCanonicalValue.Integer(limit);
        if (cursor != null)
        {
            members["cursor"] = SpfnCanonicalValue.Text(cursor);
        }
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnListItemsRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnListItemsRequest(
                limit = SpfnDecoding.integer(members["limit"], "$path.limit"),
                cursor = SpfnDecoding.optionalString(members["cursor"], "$path.cursor")
            );
        }
    }
}

data class SpfnItem(
    val id: String,
    val name: String,
    val updatedAtMillis: Long
)
{
    /**
     * The canonical form of this value. An absent optional field is omitted,
     * never written as null, so the digest of a value never depends on how a
     * caller happened to spell "nothing".
     */
    fun canonicalValue(): SpfnCanonicalValue
    {
        val members = LinkedHashMap<String, SpfnCanonicalValue>();
        members["id"] = SpfnCanonicalValue.Text(id);
        members["name"] = SpfnCanonicalValue.Text(name);
        members["updatedAtMillis"] = SpfnCanonicalValue.Integer(updatedAtMillis);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnItem
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnItem(
                id = SpfnDecoding.string(members["id"], "$path.id"),
                name = SpfnDecoding.string(members["name"], "$path.name"),
                updatedAtMillis = SpfnDecoding.integer(members["updatedAtMillis"], "$path.updatedAtMillis")
            );
        }
    }
}

data class SpfnListItemsResponse(
    val items: List<SpfnItem>,
    val nextCursor: String? = null
)
{
    /**
     * The canonical form of this value. An absent optional field is omitted,
     * never written as null, so the digest of a value never depends on how a
     * caller happened to spell "nothing".
     */
    fun canonicalValue(): SpfnCanonicalValue
    {
        val members = LinkedHashMap<String, SpfnCanonicalValue>();
        members["items"] = SpfnCanonicalValue.Arr(items.map { it.canonicalValue() });
        if (nextCursor != null)
        {
            members["nextCursor"] = SpfnCanonicalValue.Text(nextCursor);
        }
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnListItemsResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnListItemsResponse(
                items = SpfnDecoding.array(members["items"], "$path.items").map { SpfnItem.decode(it, "$path.items") },
                nextCursor = SpfnDecoding.optionalString(members["nextCursor"], "$path.nextCursor")
            );
        }
    }
}
