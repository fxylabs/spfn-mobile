// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.1.0-dev
// bundle:          Contracts/spfn-mobile-contract.v1.json
// bundleSha256:    c696d9ff3b71fbae5a427de090693d014351e047013043e7ca21b75a90de68ac
// contractVersion: 1.0.0-dev.1
// origin:          spfn-mobile-step2-dev-bundle
//
// The bundle was hand-authored in this repository and was NOT exported by SPFN primitives CI.
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
