// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    cf1b34a4081059c29f838b9e8b3a973a9fbb5e5e64a576c673f792d9c6b4ca46
// contractVersion: 0.9.0
// origin:          spfn-primitives-ci-export
//
// Bundle origin: spfn-primitives-ci-export.
//
// Regenerate with: ./gradlew :contract-codegen:spfnGenerateClients
// Verified by:     ./gradlew :contract-codegen:spfnCodegenVerify

import SPFNCore

/// Every auth class the contract declares. An operation's `authProfile` names one
/// of these; a value outside the list is a contract mismatch, and a caller refuses
/// to send rather than downgrading to any other class.
public enum SPFNGeneratedAuthClass: String, CaseIterable, Sendable
{
    case clientProofV1 = "clientProofV1"
    case none = "none"
}

public enum SPFNGeneratedOperations
{
    /// Returns the server epoch used to timestamp clientProofV1 proofs.
    public static let coreTime = SPFNOperation(
        id: "core.time",
        method: "GET",
        path: "/_core/time",
        authProfile: "none",
        requiresSession: false
    )

    /// Presents a client proof and opens a session.
    public static let authClientProofHandshake = SPFNOperation(
        id: "auth.clientProof.handshake",
        method: "POST",
        path: "/v1/auth/client-proof/handshake",
        authProfile: "clientProofV1",
        requiresSession: false
    )

    /// Authenticated round trip used as the smallest real vertical slice.
    public static let echoSend = SPFNOperation(
        id: "echo.send",
        method: "POST",
        path: "/v1/echo",
        authProfile: "clientProofV1",
        requiresSession: true
    )

    /// Authenticated paged read covering optional fields and arrays.
    public static let itemsList = SPFNOperation(
        id: "items.list",
        method: "POST",
        path: "/v1/items/list",
        authProfile: "clientProofV1",
        requiresSession: true
    )

    /// Registers an account with a verification token and enrolls the client-generated public key.
    public static let authEnrollRegister = SPFNOperation(
        id: "auth.enroll.register",
        method: "POST",
        path: "/_auth/register",
        authProfile: "none",
        requiresSession: false
    )

    /// Authenticates with password credentials and enrolls a fresh client-generated public key.
    public static let authEnrollLogin = SPFNOperation(
        id: "auth.enroll.login",
        method: "POST",
        path: "/_auth/login",
        authProfile: "none",
        requiresSession: false
    )

    /// Verifies a native/web social id_token server-side and enrolls the client-generated public key.
    public static let authEnrollOauthNative = SPFNOperation(
        id: "auth.enroll.oauthNative",
        method: "POST",
        path: "/_auth/oauth/{provider}/native",
        authProfile: "none",
        requiresSession: false
    )

    /// Replaces the authenticated key with a new client-generated public key before its TTL runs out.
    public static let authKeysRotate = SPFNOperation(
        id: "auth.keys.rotate",
        method: "POST",
        path: "/_auth/keys/rotate",
        authProfile: "clientProofV1",
        requiresSession: false
    )

    /// Lists the keys registered to the caller, one per device that can sign for them.
    public static let authKeysList = SPFNOperation(
        id: "auth.keys.list",
        method: "POST",
        path: "/_auth/keys/list",
        authProfile: "clientProofV1",
        requiresSession: false
    )

    /// Revokes one of the caller's keys, signing that device out.
    public static let authKeysRevoke = SPFNOperation(
        id: "auth.keys.revoke",
        method: "POST",
        path: "/_auth/keys/revoke",
        authProfile: "clientProofV1",
        requiresSession: false
    )

    /// Revokes every key the caller has, sparing the calling device unless asked otherwise.
    public static let authKeysRevokeAll = SPFNOperation(
        id: "auth.keys.revokeAll",
        method: "POST",
        path: "/_auth/keys/revoke-all",
        authProfile: "clientProofV1",
        requiresSession: false
    )

    /// Every operation, in bundle order.
    public static let all: [SPFNOperation] = [
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
    ]

    /// Looks an operation up by contract id. Returns nil rather than a nearest
    /// match: an unknown id is a contract mismatch, not a typo to be forgiven.
    public static func operation(id: String) -> SPFNOperation?
    {
        all.first { $0.id == id }
    }

    /// Resolves an operation's auth class, or nil for a class this contract does
    /// not declare. The caller fails closed on nil instead of guessing.
    public static func authClass(of operation: SPFNOperation) -> SPFNGeneratedAuthClass?
    {
        SPFNGeneratedAuthClass(rawValue: operation.authProfile)
    }
}
