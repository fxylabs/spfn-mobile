// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
// contractVersion: 0.10.0
// origin:          spfn-primitives-ci-export
//
// Bundle origin: spfn-primitives-ci-export.
//
// Regenerate with: ./gradlew :contract-codegen:spfnGenerateClients
// Verified by:     ./gradlew :contract-codegen:spfnCodegenVerify

package xyz.superfunction.spfn.generated

import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnDecoding
import xyz.superfunction.spfn.core.SpfnDecodingException

/**
 * A value set the contract declares. Decoding is strict: an unknown value is
 * reported with the raw string preserved rather than mapped onto a member,
 * because the contract promises no set stays as it is — a value can be added,
 * and one can be withdrawn for a weakness found later.
 */
enum class SpfnKeyAlgorithm(val wireValue: String)
{
    ES256("ES256"),
    RS256("RS256");

    fun canonicalValue(): SpfnCanonicalValue = SpfnCanonicalValue.Text(wireValue);

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnKeyAlgorithm
        {
            val raw = SpfnDecoding.string(canonical, path);
            return entries.firstOrNull { it.wireValue == raw }
                ?: throw SpfnDecodingException("TYPE_MISMATCH", "$path is not a KeyAlgorithm");
        }
    }
}

/**
 * A value set the contract declares. Decoding is strict: an unknown value is
 * reported with the raw string preserved rather than mapped onto a member,
 * because the contract promises no set stays as it is — a value can be added,
 * and one can be withdrawn for a weakness found later.
 */
enum class SpfnKeyPlatform(val wireValue: String)
{
    IOS("ios"),
    ANDROID("android"),
    WEB("web"),
    DESKTOP("desktop");

    fun canonicalValue(): SpfnCanonicalValue = SpfnCanonicalValue.Text(wireValue);

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnKeyPlatform
        {
            val raw = SpfnDecoding.string(canonical, path);
            return entries.firstOrNull { it.wireValue == raw }
                ?: throw SpfnDecodingException("TYPE_MISMATCH", "$path is not a KeyPlatform");
        }
    }
}

/**
 * A value set the contract declares. Decoding is strict: an unknown value is
 * reported with the raw string preserved rather than mapped onto a member,
 * because the contract promises no set stays as it is — a value can be added,
 * and one can be withdrawn for a weakness found later.
 */
enum class SpfnDeviceAuthPollStatus(val wireValue: String)
{
    PENDING("pending"),
    APPROVED("approved");

    fun canonicalValue(): SpfnCanonicalValue = SpfnCanonicalValue.Text(wireValue);

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnDeviceAuthPollStatus
        {
            val raw = SpfnDecoding.string(canonical, path);
            return entries.firstOrNull { it.wireValue == raw }
                ?: throw SpfnDecodingException("TYPE_MISMATCH", "$path is not a DeviceAuthPollStatus");
        }
    }
}

data class SpfnServerTimeResponse(
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
        members["serverTimeMillis"] = SpfnCanonicalValue.Integer(serverTimeMillis);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnServerTimeResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnServerTimeResponse(
                serverTimeMillis = SpfnDecoding.integer(members["serverTimeMillis"], "$path.serverTimeMillis")
            );
        }
    }
}

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

data class SpfnRegisterRequest(
    val email: String? = null,
    val phone: String? = null,
    val verificationToken: String,
    val password: String,
    val publicKey: String,
    val keyId: String,
    val fingerprint: String,
    val algorithm: SpfnKeyAlgorithm
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
        if (email != null)
        {
            members["email"] = SpfnCanonicalValue.Text(email);
        }
        if (phone != null)
        {
            members["phone"] = SpfnCanonicalValue.Text(phone);
        }
        members["verificationToken"] = SpfnCanonicalValue.Text(verificationToken);
        members["password"] = SpfnCanonicalValue.Text(password);
        members["publicKey"] = SpfnCanonicalValue.Text(publicKey);
        members["keyId"] = SpfnCanonicalValue.Text(keyId);
        members["fingerprint"] = SpfnCanonicalValue.Text(fingerprint);
        members["algorithm"] = algorithm.canonicalValue();
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnRegisterRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnRegisterRequest(
                email = SpfnDecoding.optionalString(members["email"], "$path.email"),
                phone = SpfnDecoding.optionalString(members["phone"], "$path.phone"),
                verificationToken = SpfnDecoding.string(members["verificationToken"], "$path.verificationToken"),
                password = SpfnDecoding.string(members["password"], "$path.password"),
                publicKey = SpfnDecoding.string(members["publicKey"], "$path.publicKey"),
                keyId = SpfnDecoding.string(members["keyId"], "$path.keyId"),
                fingerprint = SpfnDecoding.string(members["fingerprint"], "$path.fingerprint"),
                algorithm = SpfnKeyAlgorithm.decode(members["algorithm"] ?: SpfnCanonicalValue.Null, "$path.algorithm")
            );
        }
    }
}

data class SpfnRegisterResponse(
    val userId: String,
    val publicId: String,
    val email: String? = null,
    val phone: String? = null
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
        members["userId"] = SpfnCanonicalValue.Text(userId);
        members["publicId"] = SpfnCanonicalValue.Text(publicId);
        if (email != null)
        {
            members["email"] = SpfnCanonicalValue.Text(email);
        }
        if (phone != null)
        {
            members["phone"] = SpfnCanonicalValue.Text(phone);
        }
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnRegisterResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnRegisterResponse(
                userId = SpfnDecoding.string(members["userId"], "$path.userId"),
                publicId = SpfnDecoding.string(members["publicId"], "$path.publicId"),
                email = SpfnDecoding.optionalString(members["email"], "$path.email"),
                phone = SpfnDecoding.optionalString(members["phone"], "$path.phone")
            );
        }
    }
}

data class SpfnLoginRequest(
    val email: String? = null,
    val phone: String? = null,
    val password: String,
    val publicKey: String,
    val keyId: String,
    val fingerprint: String,
    val algorithm: SpfnKeyAlgorithm,
    val oldKeyId: String? = null
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
        if (email != null)
        {
            members["email"] = SpfnCanonicalValue.Text(email);
        }
        if (phone != null)
        {
            members["phone"] = SpfnCanonicalValue.Text(phone);
        }
        members["password"] = SpfnCanonicalValue.Text(password);
        members["publicKey"] = SpfnCanonicalValue.Text(publicKey);
        members["keyId"] = SpfnCanonicalValue.Text(keyId);
        members["fingerprint"] = SpfnCanonicalValue.Text(fingerprint);
        members["algorithm"] = algorithm.canonicalValue();
        if (oldKeyId != null)
        {
            members["oldKeyId"] = SpfnCanonicalValue.Text(oldKeyId);
        }
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnLoginRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnLoginRequest(
                email = SpfnDecoding.optionalString(members["email"], "$path.email"),
                phone = SpfnDecoding.optionalString(members["phone"], "$path.phone"),
                password = SpfnDecoding.string(members["password"], "$path.password"),
                publicKey = SpfnDecoding.string(members["publicKey"], "$path.publicKey"),
                keyId = SpfnDecoding.string(members["keyId"], "$path.keyId"),
                fingerprint = SpfnDecoding.string(members["fingerprint"], "$path.fingerprint"),
                algorithm = SpfnKeyAlgorithm.decode(members["algorithm"] ?: SpfnCanonicalValue.Null, "$path.algorithm"),
                oldKeyId = SpfnDecoding.optionalString(members["oldKeyId"], "$path.oldKeyId")
            );
        }
    }
}

data class SpfnLoginResponse(
    val userId: String,
    val publicId: String,
    val email: String? = null,
    val phone: String? = null,
    val passwordChangeRequired: Boolean
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
        members["userId"] = SpfnCanonicalValue.Text(userId);
        members["publicId"] = SpfnCanonicalValue.Text(publicId);
        if (email != null)
        {
            members["email"] = SpfnCanonicalValue.Text(email);
        }
        if (phone != null)
        {
            members["phone"] = SpfnCanonicalValue.Text(phone);
        }
        members["passwordChangeRequired"] = SpfnCanonicalValue.Bool(passwordChangeRequired);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnLoginResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnLoginResponse(
                userId = SpfnDecoding.string(members["userId"], "$path.userId"),
                publicId = SpfnDecoding.string(members["publicId"], "$path.publicId"),
                email = SpfnDecoding.optionalString(members["email"], "$path.email"),
                phone = SpfnDecoding.optionalString(members["phone"], "$path.phone"),
                passwordChangeRequired = SpfnDecoding.boolean(members["passwordChangeRequired"], "$path.passwordChangeRequired")
            );
        }
    }
}

data class SpfnOauthNativeRequest(
    val idToken: String,
    val nonce: String,
    val accessToken: String? = null,
    val publicKey: String,
    val keyId: String,
    val fingerprint: String,
    val algorithm: SpfnKeyAlgorithm
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
        members["idToken"] = SpfnCanonicalValue.Text(idToken);
        members["nonce"] = SpfnCanonicalValue.Text(nonce);
        if (accessToken != null)
        {
            members["accessToken"] = SpfnCanonicalValue.Text(accessToken);
        }
        members["publicKey"] = SpfnCanonicalValue.Text(publicKey);
        members["keyId"] = SpfnCanonicalValue.Text(keyId);
        members["fingerprint"] = SpfnCanonicalValue.Text(fingerprint);
        members["algorithm"] = algorithm.canonicalValue();
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnOauthNativeRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnOauthNativeRequest(
                idToken = SpfnDecoding.string(members["idToken"], "$path.idToken"),
                nonce = SpfnDecoding.string(members["nonce"], "$path.nonce"),
                accessToken = SpfnDecoding.optionalString(members["accessToken"], "$path.accessToken"),
                publicKey = SpfnDecoding.string(members["publicKey"], "$path.publicKey"),
                keyId = SpfnDecoding.string(members["keyId"], "$path.keyId"),
                fingerprint = SpfnDecoding.string(members["fingerprint"], "$path.fingerprint"),
                algorithm = SpfnKeyAlgorithm.decode(members["algorithm"] ?: SpfnCanonicalValue.Null, "$path.algorithm")
            );
        }
    }
}

data class SpfnOauthNativeResponse(
    val userId: String,
    val keyId: String,
    val isNewUser: Boolean
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
        members["userId"] = SpfnCanonicalValue.Text(userId);
        members["keyId"] = SpfnCanonicalValue.Text(keyId);
        members["isNewUser"] = SpfnCanonicalValue.Bool(isNewUser);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnOauthNativeResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnOauthNativeResponse(
                userId = SpfnDecoding.string(members["userId"], "$path.userId"),
                keyId = SpfnDecoding.string(members["keyId"], "$path.keyId"),
                isNewUser = SpfnDecoding.boolean(members["isNewUser"], "$path.isNewUser")
            );
        }
    }
}

data class SpfnRotateKeyRequest(
    val publicKey: String,
    val keyId: String,
    val fingerprint: String,
    val algorithm: SpfnKeyAlgorithm
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
        members["publicKey"] = SpfnCanonicalValue.Text(publicKey);
        members["keyId"] = SpfnCanonicalValue.Text(keyId);
        members["fingerprint"] = SpfnCanonicalValue.Text(fingerprint);
        members["algorithm"] = algorithm.canonicalValue();
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnRotateKeyRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnRotateKeyRequest(
                publicKey = SpfnDecoding.string(members["publicKey"], "$path.publicKey"),
                keyId = SpfnDecoding.string(members["keyId"], "$path.keyId"),
                fingerprint = SpfnDecoding.string(members["fingerprint"], "$path.fingerprint"),
                algorithm = SpfnKeyAlgorithm.decode(members["algorithm"] ?: SpfnCanonicalValue.Null, "$path.algorithm")
            );
        }
    }
}

data class SpfnRotateKeyResponse(
    val success: Boolean,
    val keyId: String
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
        members["success"] = SpfnCanonicalValue.Bool(success);
        members["keyId"] = SpfnCanonicalValue.Text(keyId);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnRotateKeyResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnRotateKeyResponse(
                success = SpfnDecoding.boolean(members["success"], "$path.success"),
                keyId = SpfnDecoding.string(members["keyId"], "$path.keyId")
            );
        }
    }
}

data class SpfnListKeysRequest(
    val includeRevoked: Boolean? = null
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
        if (includeRevoked != null)
        {
            members["includeRevoked"] = SpfnCanonicalValue.Bool(includeRevoked);
        }
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnListKeysRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnListKeysRequest(
                includeRevoked = SpfnDecoding.boolean(members["includeRevoked"], "$path.includeRevoked")
            );
        }
    }
}

data class SpfnKeySummary(
    val keyId: String,
    val deviceName: String? = null,
    val platform: SpfnKeyPlatform? = null,
    val algorithm: SpfnKeyAlgorithm,
    val fingerprintPrefix: String,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long? = null,
    val expiresAtMillis: Long? = null,
    val isExpired: Boolean,
    val isActive: Boolean,
    val revokedAtMillis: Long? = null
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
        members["keyId"] = SpfnCanonicalValue.Text(keyId);
        if (deviceName != null)
        {
            members["deviceName"] = SpfnCanonicalValue.Text(deviceName);
        }
        if (platform != null)
        {
            members["platform"] = platform.canonicalValue();
        }
        members["algorithm"] = algorithm.canonicalValue();
        members["fingerprintPrefix"] = SpfnCanonicalValue.Text(fingerprintPrefix);
        members["createdAtMillis"] = SpfnCanonicalValue.Integer(createdAtMillis);
        if (lastUsedAtMillis != null)
        {
            members["lastUsedAtMillis"] = SpfnCanonicalValue.Integer(lastUsedAtMillis);
        }
        if (expiresAtMillis != null)
        {
            members["expiresAtMillis"] = SpfnCanonicalValue.Integer(expiresAtMillis);
        }
        members["isExpired"] = SpfnCanonicalValue.Bool(isExpired);
        members["isActive"] = SpfnCanonicalValue.Bool(isActive);
        if (revokedAtMillis != null)
        {
            members["revokedAtMillis"] = SpfnCanonicalValue.Integer(revokedAtMillis);
        }
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnKeySummary
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnKeySummary(
                keyId = SpfnDecoding.string(members["keyId"], "$path.keyId"),
                deviceName = SpfnDecoding.optionalString(members["deviceName"], "$path.deviceName"),
                platform = members["platform"]?.takeIf { it !is SpfnCanonicalValue.Null }?.let { SpfnKeyPlatform.decode(it, "$path.platform") },
                algorithm = SpfnKeyAlgorithm.decode(members["algorithm"] ?: SpfnCanonicalValue.Null, "$path.algorithm"),
                fingerprintPrefix = SpfnDecoding.string(members["fingerprintPrefix"], "$path.fingerprintPrefix"),
                createdAtMillis = SpfnDecoding.integer(members["createdAtMillis"], "$path.createdAtMillis"),
                lastUsedAtMillis = SpfnDecoding.optionalInteger(members["lastUsedAtMillis"], "$path.lastUsedAtMillis"),
                expiresAtMillis = SpfnDecoding.optionalInteger(members["expiresAtMillis"], "$path.expiresAtMillis"),
                isExpired = SpfnDecoding.boolean(members["isExpired"], "$path.isExpired"),
                isActive = SpfnDecoding.boolean(members["isActive"], "$path.isActive"),
                revokedAtMillis = SpfnDecoding.optionalInteger(members["revokedAtMillis"], "$path.revokedAtMillis")
            );
        }
    }
}

data class SpfnListKeysResponse(
    val keys: List<SpfnKeySummary>
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
        members["keys"] = SpfnCanonicalValue.Arr(keys.map { it.canonicalValue() });
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnListKeysResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnListKeysResponse(
                keys = SpfnDecoding.array(members["keys"], "$path.keys").map { SpfnKeySummary.decode(it, "$path.keys") }
            );
        }
    }
}

data class SpfnRevokeKeyRequest(
    val keyId: String
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
        members["keyId"] = SpfnCanonicalValue.Text(keyId);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnRevokeKeyRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnRevokeKeyRequest(
                keyId = SpfnDecoding.string(members["keyId"], "$path.keyId")
            );
        }
    }
}

data class SpfnRevokeKeyResponse(
    val keyId: String,
    val selfRevoked: Boolean
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
        members["keyId"] = SpfnCanonicalValue.Text(keyId);
        members["selfRevoked"] = SpfnCanonicalValue.Bool(selfRevoked);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnRevokeKeyResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnRevokeKeyResponse(
                keyId = SpfnDecoding.string(members["keyId"], "$path.keyId"),
                selfRevoked = SpfnDecoding.boolean(members["selfRevoked"], "$path.selfRevoked")
            );
        }
    }
}

data class SpfnRevokeAllKeysRequest(
    val includeCurrent: Boolean? = null
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
        if (includeCurrent != null)
        {
            members["includeCurrent"] = SpfnCanonicalValue.Bool(includeCurrent);
        }
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnRevokeAllKeysRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnRevokeAllKeysRequest(
                includeCurrent = SpfnDecoding.boolean(members["includeCurrent"], "$path.includeCurrent")
            );
        }
    }
}

data class SpfnRevokeAllKeysResponse(
    val revokedCount: Long,
    val currentKeyRevoked: Boolean
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
        members["revokedCount"] = SpfnCanonicalValue.Integer(revokedCount);
        members["currentKeyRevoked"] = SpfnCanonicalValue.Bool(currentKeyRevoked);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnRevokeAllKeysResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnRevokeAllKeysResponse(
                revokedCount = SpfnDecoding.integer(members["revokedCount"], "$path.revokedCount"),
                currentKeyRevoked = SpfnDecoding.boolean(members["currentKeyRevoked"], "$path.currentKeyRevoked")
            );
        }
    }
}

data class SpfnStartDeviceAuthRequest(
    val publicKey: String,
    val keyId: String,
    val fingerprint: String,
    val algorithm: SpfnKeyAlgorithm? = null,
    val deviceName: String? = null,
    val platform: SpfnKeyPlatform? = null
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
        members["publicKey"] = SpfnCanonicalValue.Text(publicKey);
        members["keyId"] = SpfnCanonicalValue.Text(keyId);
        members["fingerprint"] = SpfnCanonicalValue.Text(fingerprint);
        if (algorithm != null)
        {
            members["algorithm"] = algorithm.canonicalValue();
        }
        if (deviceName != null)
        {
            members["deviceName"] = SpfnCanonicalValue.Text(deviceName);
        }
        if (platform != null)
        {
            members["platform"] = platform.canonicalValue();
        }
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnStartDeviceAuthRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnStartDeviceAuthRequest(
                publicKey = SpfnDecoding.string(members["publicKey"], "$path.publicKey"),
                keyId = SpfnDecoding.string(members["keyId"], "$path.keyId"),
                fingerprint = SpfnDecoding.string(members["fingerprint"], "$path.fingerprint"),
                algorithm = members["algorithm"]?.takeIf { it !is SpfnCanonicalValue.Null }?.let { SpfnKeyAlgorithm.decode(it, "$path.algorithm") },
                deviceName = SpfnDecoding.optionalString(members["deviceName"], "$path.deviceName"),
                platform = members["platform"]?.takeIf { it !is SpfnCanonicalValue.Null }?.let { SpfnKeyPlatform.decode(it, "$path.platform") }
            );
        }
    }
}

data class SpfnStartDeviceAuthResponse(
    val deviceCode: String,
    val userCode: String,
    val expiresAtMillis: Long,
    val intervalMillis: Long
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
        members["deviceCode"] = SpfnCanonicalValue.Text(deviceCode);
        members["userCode"] = SpfnCanonicalValue.Text(userCode);
        members["expiresAtMillis"] = SpfnCanonicalValue.Integer(expiresAtMillis);
        members["intervalMillis"] = SpfnCanonicalValue.Integer(intervalMillis);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnStartDeviceAuthResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnStartDeviceAuthResponse(
                deviceCode = SpfnDecoding.string(members["deviceCode"], "$path.deviceCode"),
                userCode = SpfnDecoding.string(members["userCode"], "$path.userCode"),
                expiresAtMillis = SpfnDecoding.integer(members["expiresAtMillis"], "$path.expiresAtMillis"),
                intervalMillis = SpfnDecoding.integer(members["intervalMillis"], "$path.intervalMillis")
            );
        }
    }
}

data class SpfnPollDeviceAuthRequest(
    val deviceCode: String
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
        members["deviceCode"] = SpfnCanonicalValue.Text(deviceCode);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnPollDeviceAuthRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnPollDeviceAuthRequest(
                deviceCode = SpfnDecoding.string(members["deviceCode"], "$path.deviceCode")
            );
        }
    }
}

data class SpfnPollDeviceAuthResponse(
    val status: SpfnDeviceAuthPollStatus,
    val intervalMillis: Long? = null,
    val userId: String? = null,
    val publicId: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val passwordChangeRequired: Boolean? = null
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
        members["status"] = status.canonicalValue();
        if (intervalMillis != null)
        {
            members["intervalMillis"] = SpfnCanonicalValue.Integer(intervalMillis);
        }
        if (userId != null)
        {
            members["userId"] = SpfnCanonicalValue.Text(userId);
        }
        if (publicId != null)
        {
            members["publicId"] = SpfnCanonicalValue.Text(publicId);
        }
        if (email != null)
        {
            members["email"] = SpfnCanonicalValue.Text(email);
        }
        if (phone != null)
        {
            members["phone"] = SpfnCanonicalValue.Text(phone);
        }
        if (passwordChangeRequired != null)
        {
            members["passwordChangeRequired"] = SpfnCanonicalValue.Bool(passwordChangeRequired);
        }
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnPollDeviceAuthResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnPollDeviceAuthResponse(
                status = SpfnDeviceAuthPollStatus.decode(members["status"] ?: SpfnCanonicalValue.Null, "$path.status"),
                intervalMillis = SpfnDecoding.optionalInteger(members["intervalMillis"], "$path.intervalMillis"),
                userId = SpfnDecoding.optionalString(members["userId"], "$path.userId"),
                publicId = SpfnDecoding.optionalString(members["publicId"], "$path.publicId"),
                email = SpfnDecoding.optionalString(members["email"], "$path.email"),
                phone = SpfnDecoding.optionalString(members["phone"], "$path.phone"),
                passwordChangeRequired = SpfnDecoding.boolean(members["passwordChangeRequired"], "$path.passwordChangeRequired")
            );
        }
    }
}

data class SpfnDeviceAuthInfoRequest(
    val userCode: String
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
        members["userCode"] = SpfnCanonicalValue.Text(userCode);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnDeviceAuthInfoRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnDeviceAuthInfoRequest(
                userCode = SpfnDecoding.string(members["userCode"], "$path.userCode")
            );
        }
    }
}

data class SpfnDeviceAuthInfoResponse(
    val deviceName: String? = null,
    val platform: SpfnKeyPlatform? = null,
    val fingerprintPrefix: String,
    val requestedAtMillis: Long,
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
        if (deviceName != null)
        {
            members["deviceName"] = SpfnCanonicalValue.Text(deviceName);
        }
        if (platform != null)
        {
            members["platform"] = platform.canonicalValue();
        }
        members["fingerprintPrefix"] = SpfnCanonicalValue.Text(fingerprintPrefix);
        members["requestedAtMillis"] = SpfnCanonicalValue.Integer(requestedAtMillis);
        members["expiresAtMillis"] = SpfnCanonicalValue.Integer(expiresAtMillis);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnDeviceAuthInfoResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnDeviceAuthInfoResponse(
                deviceName = SpfnDecoding.optionalString(members["deviceName"], "$path.deviceName"),
                platform = members["platform"]?.takeIf { it !is SpfnCanonicalValue.Null }?.let { SpfnKeyPlatform.decode(it, "$path.platform") },
                fingerprintPrefix = SpfnDecoding.string(members["fingerprintPrefix"], "$path.fingerprintPrefix"),
                requestedAtMillis = SpfnDecoding.integer(members["requestedAtMillis"], "$path.requestedAtMillis"),
                expiresAtMillis = SpfnDecoding.integer(members["expiresAtMillis"], "$path.expiresAtMillis")
            );
        }
    }
}

data class SpfnApproveDeviceAuthRequest(
    val userCode: String
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
        members["userCode"] = SpfnCanonicalValue.Text(userCode);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnApproveDeviceAuthRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnApproveDeviceAuthRequest(
                userCode = SpfnDecoding.string(members["userCode"], "$path.userCode")
            );
        }
    }
}

data class SpfnDenyDeviceAuthRequest(
    val userCode: String
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
        members["userCode"] = SpfnCanonicalValue.Text(userCode);
        return SpfnCanonicalValue.Obj(members);
    }

    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): SpfnDenyDeviceAuthRequest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return SpfnDenyDeviceAuthRequest(
                userCode = SpfnDecoding.string(members["userCode"], "$path.userCode")
            );
        }
    }
}
