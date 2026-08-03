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

import SPFNCore

public enum SPFNGeneratedOperations
{
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

    /// Every operation, in bundle order.
    public static let all: [SPFNOperation] = [
        authClientProofHandshake,
        echoSend,
        itemsList,
    ]

    /// Looks an operation up by contract id. Returns nil rather than a nearest
    /// match: an unknown id is a contract mismatch, not a typo to be forgiven.
    public static func operation(id: String) -> SPFNOperation?
    {
        all.first { $0.id == id }
    }
}
