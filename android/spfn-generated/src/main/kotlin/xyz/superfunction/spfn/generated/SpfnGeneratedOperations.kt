// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    0a91612158aaf9917be8487cf70e1df9ab4c12ac6c1106973afa99122e458795
// contractVersion: 0.6.0
// origin:          spfn-primitives-ci-export
//
// Bundle origin: spfn-primitives-ci-export.
//
// Regenerate with: ./gradlew :contract-codegen:spfnGenerateClients
// Verified by:     ./gradlew :contract-codegen:spfnCodegenVerify

package xyz.superfunction.spfn.generated

import xyz.superfunction.spfn.core.SpfnOperation

/**
 * Every auth class the contract declares. An operation's `authProfile` names one
 * of these; a value outside the list is a contract mismatch, and a caller refuses
 * to send rather than downgrading to any other class.
 */
enum class SpfnGeneratedAuthClass(val wireName: String)
{
    CLIENT_PROOF_V1("clientProofV1"),
    NONE("none");

    companion object
    {
        /**
         * Resolves an operation's auth class, or null for a class this contract
         * does not declare. The caller fails closed on null instead of guessing.
         */
        fun of(operation: SpfnOperation): SpfnGeneratedAuthClass? =
            entries.firstOrNull { it.wireName == operation.authProfile }
    }
}

object SpfnGeneratedOperations
{
    /** Presents a client proof and opens a session. */
    val authClientProofHandshake: SpfnOperation = SpfnOperation(
        id = "auth.clientProof.handshake",
        method = "POST",
        path = "/v1/auth/client-proof/handshake",
        authProfile = "clientProofV1",
        requiresSession = false
    )

    /** Authenticated round trip used as the smallest real vertical slice. */
    val echoSend: SpfnOperation = SpfnOperation(
        id = "echo.send",
        method = "POST",
        path = "/v1/echo",
        authProfile = "clientProofV1",
        requiresSession = true
    )

    /** Authenticated paged read covering optional fields and arrays. */
    val itemsList: SpfnOperation = SpfnOperation(
        id = "items.list",
        method = "POST",
        path = "/v1/items/list",
        authProfile = "clientProofV1",
        requiresSession = true
    )

    /** Registers an account with a verification token and enrolls the client-generated public key. */
    val authEnrollRegister: SpfnOperation = SpfnOperation(
        id = "auth.enroll.register",
        method = "POST",
        path = "/_auth/register",
        authProfile = "none",
        requiresSession = false
    )

    /** Authenticates with password credentials and enrolls a fresh client-generated public key. */
    val authEnrollLogin: SpfnOperation = SpfnOperation(
        id = "auth.enroll.login",
        method = "POST",
        path = "/_auth/login",
        authProfile = "none",
        requiresSession = false
    )

    /** Verifies a native/web social id_token server-side and enrolls the client-generated public key. */
    val authEnrollOauthNative: SpfnOperation = SpfnOperation(
        id = "auth.enroll.oauthNative",
        method = "POST",
        path = "/_auth/oauth/{provider}/native",
        authProfile = "none",
        requiresSession = false
    )

    /** Replaces the authenticated key with a new client-generated public key before its TTL runs out. */
    val authKeysRotate: SpfnOperation = SpfnOperation(
        id = "auth.keys.rotate",
        method = "POST",
        path = "/_auth/keys/rotate",
        authProfile = "clientProofV1",
        requiresSession = false
    )

    /** Lists the keys registered to the caller, one per device that can sign for them. */
    val authKeysList: SpfnOperation = SpfnOperation(
        id = "auth.keys.list",
        method = "POST",
        path = "/_auth/keys/list",
        authProfile = "clientProofV1",
        requiresSession = false
    )

    /** Revokes one of the caller's keys, signing that device out. */
    val authKeysRevoke: SpfnOperation = SpfnOperation(
        id = "auth.keys.revoke",
        method = "POST",
        path = "/_auth/keys/revoke",
        authProfile = "clientProofV1",
        requiresSession = false
    )

    /** Revokes every key the caller has, sparing the calling device unless asked otherwise. */
    val authKeysRevokeAll: SpfnOperation = SpfnOperation(
        id = "auth.keys.revokeAll",
        method = "POST",
        path = "/_auth/keys/revoke-all",
        authProfile = "clientProofV1",
        requiresSession = false
    )

    /** Every operation, in bundle order. */
    val all: List<SpfnOperation> = listOf(
        authClientProofHandshake,
        echoSend,
        itemsList,
        authEnrollRegister,
        authEnrollLogin,
        authEnrollOauthNative,
        authKeysRotate,
        authKeysList,
        authKeysRevoke,
        authKeysRevokeAll
    )

    /**
     * Looks an operation up by contract id. Returns null rather than a nearest
     * match: an unknown id is a contract mismatch, not a typo to be forgiven.
     */
    fun operation(id: String): SpfnOperation? = all.firstOrNull { it.id == id }
}
