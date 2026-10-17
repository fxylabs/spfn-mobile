// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev (app contract)
// documentSha256:  9699aecc37719004922e7991e1be7cf224a467f44e82cce15b37528b37b0d2bb
// documentVersion: 1
// operations:      getAiAccountsState, getConsentState, getStates, putConsentState
//
// Regenerate with the consumer's spfnAppContractGenerate run. Its spfnAppContractVerify
// fails the build when the document changed or this file was edited.

package xyz.superfunction.spfn.state.appstates

import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnDecoding

data class GetAiAccountsStateResponse(
    val at: String,
    val value: GetAiAccountsStateResponseValue
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetAiAccountsStateResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetAiAccountsStateResponse(
                at = SpfnDecoding.string(members["at"], "$path.at"),
                value = GetAiAccountsStateResponseValue.decode(members["value"] ?: SpfnCanonicalValue.Null, "$path.value")
            );
        }
    }
}

data class GetAiAccountsStateResponseValue(
    val connected: Boolean,
    val providers: List<String>
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetAiAccountsStateResponseValue
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetAiAccountsStateResponseValue(
                connected = SpfnDecoding.boolean(members["connected"], "$path.connected"),
                providers = SpfnDecoding.array(members["providers"], "$path.providers").map { value0 -> SpfnDecoding.string(value0, "$path.providers") }
            );
        }
    }
}

data class GetConsentStateResponse(
    val at: String,
    val data: GetConsentStateResponseData,
    val value: GetConsentStateResponseValue
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetConsentStateResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetConsentStateResponse(
                at = SpfnDecoding.string(members["at"], "$path.at"),
                data = GetConsentStateResponseData.decode(members["data"] ?: SpfnCanonicalValue.Null, "$path.data"),
                value = GetConsentStateResponseValue.of(SpfnDecoding.string(members["value"], "$path.value"))
            );
        }
    }
}

data class GetConsentStateResponseData(
    val marketing: Boolean,
    val version: Long
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetConsentStateResponseData
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetConsentStateResponseData(
                marketing = SpfnDecoding.boolean(members["marketing"], "$path.marketing"),
                version = SpfnDecoding.integer(members["version"], "$path.version")
            );
        }
    }
}

/**
 * An open set: a value this build does not know decodes as [Unknown], so a server that
 * adds one does not fail the whole response.
 */
sealed interface GetConsentStateResponseValue
{
    val wireValue: String

    data object Pending : GetConsentStateResponseValue
    {
        override val wireValue: String = "pending"
    }

    data object Agreed : GetConsentStateResponseValue
    {
        override val wireValue: String = "agreed"
    }

    /** A value this build does not know: a server newer than this app sent it. */
    data class Unknown(override val wireValue: String) : GetConsentStateResponseValue

    companion object
    {
        fun of(wireValue: String): GetConsentStateResponseValue = when (wireValue)
        {
            "pending" -> Pending
            "agreed" -> Agreed
            else -> Unknown(wireValue)
        }
    }
}

data class GetStatesResponse(
    val at: String,
    val manifest: GetStatesResponseManifest,
    val states: GetStatesResponseStates
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetStatesResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetStatesResponse(
                at = SpfnDecoding.string(members["at"], "$path.at"),
                manifest = GetStatesResponseManifest.decode(members["manifest"] ?: SpfnCanonicalValue.Null, "$path.manifest"),
                states = GetStatesResponseStates.decode(members["states"] ?: SpfnCanonicalValue.Null, "$path.states")
            );
        }
    }
}

data class GetStatesResponseManifest(
    val aiAccounts: GetStatesResponseManifestAiAccounts,
    val consent: GetStatesResponseManifestConsent
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetStatesResponseManifest
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetStatesResponseManifest(
                aiAccounts = GetStatesResponseManifestAiAccounts.decode(members["aiAccounts"] ?: SpfnCanonicalValue.Null, "$path.aiAccounts"),
                consent = GetStatesResponseManifestConsent.decode(members["consent"] ?: SpfnCanonicalValue.Null, "$path.consent")
            );
        }
    }
}

data class GetStatesResponseManifestAiAccounts(
    val key: List<String>,
    val write: Boolean
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetStatesResponseManifestAiAccounts
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetStatesResponseManifestAiAccounts(
                key = SpfnDecoding.array(members["key"], "$path.key").map { value0 -> SpfnDecoding.string(value0, "$path.key") },
                write = SpfnDecoding.boolean(members["write"], "$path.write")
            );
        }
    }
}

data class GetStatesResponseManifestConsent(
    val key: List<String>,
    val write: Boolean
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetStatesResponseManifestConsent
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetStatesResponseManifestConsent(
                key = SpfnDecoding.array(members["key"], "$path.key").map { value0 -> SpfnDecoding.string(value0, "$path.key") },
                write = SpfnDecoding.boolean(members["write"], "$path.write")
            );
        }
    }
}

data class GetStatesResponseStates(
    val aiAccounts: GetStatesResponseStatesAiAccounts? = null,
    val consent: GetStatesResponseStatesConsent? = null
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetStatesResponseStates
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetStatesResponseStates(
                aiAccounts = StateFixtureAPISupport.nonNull(members["aiAccounts"])?.let { value0 -> GetStatesResponseStatesAiAccounts.decode(value0, "$path.aiAccounts") },
                consent = StateFixtureAPISupport.nonNull(members["consent"])?.let { value0 -> GetStatesResponseStatesConsent.decode(value0, "$path.consent") }
            );
        }
    }
}

data class GetStatesResponseStatesAiAccounts(
    val value: GetStatesResponseStatesAiAccountsValue
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetStatesResponseStatesAiAccounts
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetStatesResponseStatesAiAccounts(
                value = GetStatesResponseStatesAiAccountsValue.decode(members["value"] ?: SpfnCanonicalValue.Null, "$path.value")
            );
        }
    }
}

data class GetStatesResponseStatesAiAccountsValue(
    val connected: Boolean,
    val providers: List<String>
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetStatesResponseStatesAiAccountsValue
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetStatesResponseStatesAiAccountsValue(
                connected = SpfnDecoding.boolean(members["connected"], "$path.connected"),
                providers = SpfnDecoding.array(members["providers"], "$path.providers").map { value0 -> SpfnDecoding.string(value0, "$path.providers") }
            );
        }
    }
}

data class GetStatesResponseStatesConsent(
    val data: GetStatesResponseStatesConsentData,
    val value: GetStatesResponseStatesConsentValue
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetStatesResponseStatesConsent
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetStatesResponseStatesConsent(
                data = GetStatesResponseStatesConsentData.decode(members["data"] ?: SpfnCanonicalValue.Null, "$path.data"),
                value = GetStatesResponseStatesConsentValue.of(SpfnDecoding.string(members["value"], "$path.value"))
            );
        }
    }
}

data class GetStatesResponseStatesConsentData(
    val marketing: Boolean,
    val version: Long
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): GetStatesResponseStatesConsentData
        {
            val members = SpfnDecoding.obj(canonical, path);
            return GetStatesResponseStatesConsentData(
                marketing = SpfnDecoding.boolean(members["marketing"], "$path.marketing"),
                version = SpfnDecoding.integer(members["version"], "$path.version")
            );
        }
    }
}

/**
 * An open set: a value this build does not know decodes as [Unknown], so a server that
 * adds one does not fail the whole response.
 */
sealed interface GetStatesResponseStatesConsentValue
{
    val wireValue: String

    data object Pending : GetStatesResponseStatesConsentValue
    {
        override val wireValue: String = "pending"
    }

    data object Agreed : GetStatesResponseStatesConsentValue
    {
        override val wireValue: String = "agreed"
    }

    /** A value this build does not know: a server newer than this app sent it. */
    data class Unknown(override val wireValue: String) : GetStatesResponseStatesConsentValue

    companion object
    {
        fun of(wireValue: String): GetStatesResponseStatesConsentValue = when (wireValue)
        {
            "pending" -> Pending
            "agreed" -> Agreed
            else -> Unknown(wireValue)
        }
    }
}

data class PutConsentStateBody(
    val agreed: Boolean,
    val marketing: Boolean
)
{
    fun canonicalValue(): SpfnCanonicalValue
    {
        val members = LinkedHashMap<String, SpfnCanonicalValue>();
        members["agreed"] = SpfnCanonicalValue.Bool(this.agreed);
        members["marketing"] = SpfnCanonicalValue.Bool(this.marketing);
        return SpfnCanonicalValue.Obj(members);
    }
}

data class PutConsentStateResponse(
    val at: String,
    val data: PutConsentStateResponseData,
    val value: PutConsentStateResponseValue
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): PutConsentStateResponse
        {
            val members = SpfnDecoding.obj(canonical, path);
            return PutConsentStateResponse(
                at = SpfnDecoding.string(members["at"], "$path.at"),
                data = PutConsentStateResponseData.decode(members["data"] ?: SpfnCanonicalValue.Null, "$path.data"),
                value = PutConsentStateResponseValue.of(SpfnDecoding.string(members["value"], "$path.value"))
            );
        }
    }
}

data class PutConsentStateResponseData(
    val marketing: Boolean,
    val version: Long
)
{
    companion object
    {
        fun decode(canonical: SpfnCanonicalValue, path: String = "\$"): PutConsentStateResponseData
        {
            val members = SpfnDecoding.obj(canonical, path);
            return PutConsentStateResponseData(
                marketing = SpfnDecoding.boolean(members["marketing"], "$path.marketing"),
                version = SpfnDecoding.integer(members["version"], "$path.version")
            );
        }
    }
}

/**
 * An open set: a value this build does not know decodes as [Unknown], so a server that
 * adds one does not fail the whole response.
 */
sealed interface PutConsentStateResponseValue
{
    val wireValue: String

    data object Pending : PutConsentStateResponseValue
    {
        override val wireValue: String = "pending"
    }

    data object Agreed : PutConsentStateResponseValue
    {
        override val wireValue: String = "agreed"
    }

    /** A value this build does not know: a server newer than this app sent it. */
    data class Unknown(override val wireValue: String) : PutConsentStateResponseValue

    companion object
    {
        fun of(wireValue: String): PutConsentStateResponseValue = when (wireValue)
        {
            "pending" -> Pending
            "agreed" -> Agreed
            else -> Unknown(wireValue)
        }
    }
}
