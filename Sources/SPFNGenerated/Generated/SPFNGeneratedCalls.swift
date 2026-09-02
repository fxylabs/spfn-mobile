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

import SPFNCore

/// Every operation as a value the caller hands to `SPFNClient.execute`.
///
/// One descriptor per operation and no wrapper functions: an app reaches an
/// operation by naming it here, the way it already reaches `auth.keys.revoke`.
/// Two of these are listed for completeness rather than for use — the handshake
/// is the session's own operation and `execute` refuses it, and an operation
/// whose path carries a `{...}` segment needs that segment substituted, which
/// is the caller's own descriptor built from the operation constant.
public enum SPFNGeneratedCalls
{
    /// Returns the server epoch used to timestamp clientProofV1 proofs.
    public static let coreTime: SPFNCall<Void, SPFNServerTimeResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.coreTime,
        encode: { _ in SPFNCanonicalValue.object([:]) },
        decode: { try SPFNServerTimeResponse(canonical: $0) }
    )

    /// Presents a client proof and opens a session.
    public static let authClientProofHandshake: SPFNCall<SPFNHandshakeRequest, SPFNHandshakeResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.authClientProofHandshake,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNHandshakeResponse(canonical: $0) }
    )

    /// Authenticated round trip used as the smallest real vertical slice.
    public static let echoSend: SPFNCall<SPFNEchoRequest, SPFNEchoResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.echoSend,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNEchoResponse(canonical: $0) }
    )

    /// Authenticated paged read covering optional fields and arrays.
    public static let itemsList: SPFNCall<SPFNListItemsRequest, SPFNListItemsResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.itemsList,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNListItemsResponse(canonical: $0) }
    )

    /// Registers an account with a verification token and enrolls the client-generated public key.
    public static let authEnrollRegister: SPFNCall<SPFNRegisterRequest, SPFNRegisterResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.authEnrollRegister,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNRegisterResponse(canonical: $0) }
    )

    /// Authenticates with password credentials and enrolls a fresh client-generated public key.
    public static let authEnrollLogin: SPFNCall<SPFNLoginRequest, SPFNLoginResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.authEnrollLogin,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNLoginResponse(canonical: $0) }
    )

    /// Verifies a native/web social id_token server-side and enrolls the client-generated public key.
    public static let authEnrollOauthNative: SPFNCall<SPFNOauthNativeRequest, SPFNOauthNativeResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.authEnrollOauthNative,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNOauthNativeResponse(canonical: $0) }
    )

    /// Replaces the authenticated key with a new client-generated public key before its TTL runs out.
    public static let authKeysRotate: SPFNCall<SPFNRotateKeyRequest, SPFNRotateKeyResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.authKeysRotate,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNRotateKeyResponse(canonical: $0) }
    )

    /// Lists the keys registered to the caller, one per device that can sign for them.
    public static let authKeysList: SPFNCall<SPFNListKeysRequest, SPFNListKeysResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.authKeysList,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNListKeysResponse(canonical: $0) }
    )

    /// Revokes one of the caller's keys, signing that device out.
    public static let authKeysRevoke: SPFNCall<SPFNRevokeKeyRequest, SPFNRevokeKeyResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.authKeysRevoke,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNRevokeKeyResponse(canonical: $0) }
    )

    /// Revokes every key the caller has, sparing the calling device unless asked otherwise.
    public static let authKeysRevokeAll: SPFNCall<SPFNRevokeAllKeysRequest, SPFNRevokeAllKeysResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.authKeysRevokeAll,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNRevokeAllKeysResponse(canonical: $0) }
    )

    /// Parks a new device's public key and returns the codes it shows and polls with.
    public static let authDeviceStart: SPFNCall<SPFNStartDeviceAuthRequest, SPFNStartDeviceAuthResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.authDeviceStart,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNStartDeviceAuthResponse(canonical: $0) }
    )

    /// Asks whether the request has been answered; the approved answer is the login it produced.
    public static let authDevicePoll: SPFNCall<SPFNPollDeviceAuthRequest, SPFNPollDeviceAuthResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.authDevicePoll,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNPollDeviceAuthResponse(canonical: $0) }
    )

    /// Describes the device waiting on a user code, so the approver can recognise it before deciding.
    public static let authDeviceInfo: SPFNCall<SPFNDeviceAuthInfoRequest, SPFNDeviceAuthInfoResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.authDeviceInfo,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNDeviceAuthInfoResponse(canonical: $0) }
    )

    /// Lets the waiting device in, answering with the device it just let in.
    public static let authDeviceApprove: SPFNCall<SPFNApproveDeviceAuthRequest, SPFNDeviceAuthInfoResponse> = SPFNCall(
        operation: SPFNGeneratedOperations.authDeviceApprove,
        encode: { try $0.canonicalValue() },
        decode: { try SPFNDeviceAuthInfoResponse(canonical: $0) }
    )

    /// Refuses the waiting device. Answers 204 with no body, so it names no response type.
    public static let authDeviceDeny: SPFNCall<SPFNDenyDeviceAuthRequest, SPFNNoResponse> =
        SPFNCall<SPFNDenyDeviceAuthRequest, SPFNNoResponse>.noResponse(
            operation: SPFNGeneratedOperations.authDeviceDeny,
            encode: { try $0.canonicalValue() }
        )
}
