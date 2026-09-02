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
    /** Returns the server epoch used to timestamp clientProofV1 proofs. */
    val coreTime: SpfnOperation = SpfnOperation(
        id = "core.time",
        method = "GET",
        path = "/_core/time",
        authProfile = "none",
        requiresSession = false,
        declaresResponse = true
    )

    /** Presents a client proof and opens a session. */
    val authClientProofHandshake: SpfnOperation = SpfnOperation(
        id = "auth.clientProof.handshake",
        method = "POST",
        path = "/v1/auth/client-proof/handshake",
        authProfile = "clientProofV1",
        requiresSession = false,
        declaresResponse = true
    )

    /** Authenticated round trip used as the smallest real vertical slice. */
    val echoSend: SpfnOperation = SpfnOperation(
        id = "echo.send",
        method = "POST",
        path = "/v1/echo",
        authProfile = "clientProofV1",
        requiresSession = true,
        declaresResponse = true
    )

    /** Authenticated paged read covering optional fields and arrays. */
    val itemsList: SpfnOperation = SpfnOperation(
        id = "items.list",
        method = "POST",
        path = "/v1/items/list",
        authProfile = "clientProofV1",
        requiresSession = true,
        declaresResponse = true
    )

    /** Registers an account with a verification token and enrolls the client-generated public key. */
    val authEnrollRegister: SpfnOperation = SpfnOperation(
        id = "auth.enroll.register",
        method = "POST",
        path = "/_auth/register",
        authProfile = "none",
        requiresSession = false,
        declaresResponse = true
    )

    /** Authenticates with password credentials and enrolls a fresh client-generated public key. */
    val authEnrollLogin: SpfnOperation = SpfnOperation(
        id = "auth.enroll.login",
        method = "POST",
        path = "/_auth/login",
        authProfile = "none",
        requiresSession = false,
        declaresResponse = true
    )

    /** Verifies a native/web social id_token server-side and enrolls the client-generated public key. */
    val authEnrollOauthNative: SpfnOperation = SpfnOperation(
        id = "auth.enroll.oauthNative",
        method = "POST",
        path = "/_auth/oauth/{provider}/native",
        authProfile = "none",
        requiresSession = false,
        declaresResponse = true
    )

    /** Replaces the authenticated key with a new client-generated public key before its TTL runs out. */
    val authKeysRotate: SpfnOperation = SpfnOperation(
        id = "auth.keys.rotate",
        method = "POST",
        path = "/_auth/keys/rotate",
        authProfile = "clientProofV1",
        requiresSession = false,
        declaresResponse = true
    )

    /** Lists the keys registered to the caller, one per device that can sign for them. */
    val authKeysList: SpfnOperation = SpfnOperation(
        id = "auth.keys.list",
        method = "POST",
        path = "/_auth/keys/list",
        authProfile = "clientProofV1",
        requiresSession = false,
        declaresResponse = true
    )

    /** Revokes one of the caller's keys, signing that device out. */
    val authKeysRevoke: SpfnOperation = SpfnOperation(
        id = "auth.keys.revoke",
        method = "POST",
        path = "/_auth/keys/revoke",
        authProfile = "clientProofV1",
        requiresSession = false,
        declaresResponse = true
    )

    /** Revokes every key the caller has, sparing the calling device unless asked otherwise. */
    val authKeysRevokeAll: SpfnOperation = SpfnOperation(
        id = "auth.keys.revokeAll",
        method = "POST",
        path = "/_auth/keys/revoke-all",
        authProfile = "clientProofV1",
        requiresSession = false,
        declaresResponse = true
    )

    /** Parks a new device's public key and returns the codes it shows and polls with. */
    val authDeviceStart: SpfnOperation = SpfnOperation(
        id = "auth.device.start",
        method = "POST",
        path = "/_auth/device/start",
        authProfile = "none",
        requiresSession = false,
        declaresResponse = true
    )

    /** Asks whether the request has been answered; the approved answer is the login it produced. */
    val authDevicePoll: SpfnOperation = SpfnOperation(
        id = "auth.device.poll",
        method = "POST",
        path = "/_auth/device/poll",
        authProfile = "none",
        requiresSession = false,
        declaresResponse = true
    )

    /** Describes the device waiting on a user code, so the approver can recognise it before deciding. */
    val authDeviceInfo: SpfnOperation = SpfnOperation(
        id = "auth.device.info",
        method = "POST",
        path = "/_auth/device/info",
        authProfile = "clientProofV1",
        requiresSession = false,
        declaresResponse = true
    )

    /** Lets the waiting device in, answering with the device it just let in. */
    val authDeviceApprove: SpfnOperation = SpfnOperation(
        id = "auth.device.approve",
        method = "POST",
        path = "/_auth/device/approve",
        authProfile = "clientProofV1",
        requiresSession = false,
        declaresResponse = true
    )

    /** Refuses the waiting device. Answers 204 with no body, so it names no response type. */
    val authDeviceDeny: SpfnOperation = SpfnOperation(
        id = "auth.device.deny",
        method = "POST",
        path = "/_auth/device/deny",
        authProfile = "clientProofV1",
        requiresSession = false,
        declaresResponse = false
    )

    /** Every operation, in bundle order. */
    val all: List<SpfnOperation> = listOf(
        coreTime,
        authClientProofHandshake,
        echoSend,
        itemsList,
        authEnrollRegister,
        authEnrollLogin,
        authEnrollOauthNative,
        authKeysRotate,
        authKeysList,
        authKeysRevoke,
        authKeysRevokeAll,
        authDeviceStart,
        authDevicePoll,
        authDeviceInfo,
        authDeviceApprove,
        authDeviceDeny
    )

    /**
     * Looks an operation up by contract id. Returns null rather than a nearest
     * match: an unknown id is a contract mismatch, not a typo to be forgiven.
     */
    fun operation(id: String): SpfnOperation? = all.firstOrNull { it.id == id }
}
