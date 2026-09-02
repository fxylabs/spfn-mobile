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

import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnCanonicalValue
import xyz.superfunction.spfn.core.SpfnNoResponse

/**
 * Every operation as a value the caller hands to `SpfnClient.execute`.
 *
 * One descriptor per operation and no wrapper functions: an app reaches an
 * operation by naming it here, the way it already reaches `auth.keys.revoke`.
 * Two of these are listed for completeness rather than for use — the handshake
 * is the session's own operation and `execute` refuses it, and an operation
 * whose path carries a `{...}` segment needs that segment substituted, which
 * is the caller's own descriptor built from the operation constant.
 *
 * Every value is a `@JvmField`, so a Java caller reads
 * `SpfnGeneratedCalls.authDeviceApprove` as a field rather than through a getter.
 */
object SpfnGeneratedCalls
{
    /** Returns the server epoch used to timestamp clientProofV1 proofs. */
    @JvmField
    val coreTime: SpfnCall<Unit, SpfnServerTimeResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.coreTime,
        encode = { _ -> SpfnCanonicalValue.Obj(emptyMap()) },
        decode = { value -> SpfnServerTimeResponse.decode(value) }
    )

    /** Presents a client proof and opens a session. */
    @JvmField
    val authClientProofHandshake: SpfnCall<SpfnHandshakeRequest, SpfnHandshakeResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.authClientProofHandshake,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnHandshakeResponse.decode(value) }
    )

    /** Authenticated round trip used as the smallest real vertical slice. */
    @JvmField
    val echoSend: SpfnCall<SpfnEchoRequest, SpfnEchoResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.echoSend,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnEchoResponse.decode(value) }
    )

    /** Authenticated paged read covering optional fields and arrays. */
    @JvmField
    val itemsList: SpfnCall<SpfnListItemsRequest, SpfnListItemsResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.itemsList,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnListItemsResponse.decode(value) }
    )

    /** Registers an account with a verification token and enrolls the client-generated public key. */
    @JvmField
    val authEnrollRegister: SpfnCall<SpfnRegisterRequest, SpfnRegisterResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.authEnrollRegister,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnRegisterResponse.decode(value) }
    )

    /** Authenticates with password credentials and enrolls a fresh client-generated public key. */
    @JvmField
    val authEnrollLogin: SpfnCall<SpfnLoginRequest, SpfnLoginResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.authEnrollLogin,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnLoginResponse.decode(value) }
    )

    /** Verifies a native/web social id_token server-side and enrolls the client-generated public key. */
    @JvmField
    val authEnrollOauthNative: SpfnCall<SpfnOauthNativeRequest, SpfnOauthNativeResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.authEnrollOauthNative,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnOauthNativeResponse.decode(value) }
    )

    /** Replaces the authenticated key with a new client-generated public key before its TTL runs out. */
    @JvmField
    val authKeysRotate: SpfnCall<SpfnRotateKeyRequest, SpfnRotateKeyResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.authKeysRotate,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnRotateKeyResponse.decode(value) }
    )

    /** Lists the keys registered to the caller, one per device that can sign for them. */
    @JvmField
    val authKeysList: SpfnCall<SpfnListKeysRequest, SpfnListKeysResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.authKeysList,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnListKeysResponse.decode(value) }
    )

    /** Revokes one of the caller's keys, signing that device out. */
    @JvmField
    val authKeysRevoke: SpfnCall<SpfnRevokeKeyRequest, SpfnRevokeKeyResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.authKeysRevoke,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnRevokeKeyResponse.decode(value) }
    )

    /** Revokes every key the caller has, sparing the calling device unless asked otherwise. */
    @JvmField
    val authKeysRevokeAll: SpfnCall<SpfnRevokeAllKeysRequest, SpfnRevokeAllKeysResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.authKeysRevokeAll,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnRevokeAllKeysResponse.decode(value) }
    )

    /** Parks a new device's public key and returns the codes it shows and polls with. */
    @JvmField
    val authDeviceStart: SpfnCall<SpfnStartDeviceAuthRequest, SpfnStartDeviceAuthResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.authDeviceStart,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnStartDeviceAuthResponse.decode(value) }
    )

    /** Asks whether the request has been answered; the approved answer is the login it produced. */
    @JvmField
    val authDevicePoll: SpfnCall<SpfnPollDeviceAuthRequest, SpfnPollDeviceAuthResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.authDevicePoll,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnPollDeviceAuthResponse.decode(value) }
    )

    /** Describes the device waiting on a user code, so the approver can recognise it before deciding. */
    @JvmField
    val authDeviceInfo: SpfnCall<SpfnDeviceAuthInfoRequest, SpfnDeviceAuthInfoResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.authDeviceInfo,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnDeviceAuthInfoResponse.decode(value) }
    )

    /** Lets the waiting device in, answering with the device it just let in. */
    @JvmField
    val authDeviceApprove: SpfnCall<SpfnApproveDeviceAuthRequest, SpfnDeviceAuthInfoResponse> = SpfnCall(
        operation = SpfnGeneratedOperations.authDeviceApprove,
        encode = { request -> request.canonicalValue() },
        decode = { value -> SpfnDeviceAuthInfoResponse.decode(value) }
    )

    /** Refuses the waiting device. Answers 204 with no body, so it names no response type. */
    @JvmField
    val authDeviceDeny: SpfnCall<SpfnDenyDeviceAuthRequest, SpfnNoResponse> = SpfnCall.noResponse(
        operation = SpfnGeneratedOperations.authDeviceDeny,
        encode = { request -> request.canonicalValue() }
    )
}
