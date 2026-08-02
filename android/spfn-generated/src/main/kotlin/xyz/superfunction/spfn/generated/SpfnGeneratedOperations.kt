// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.1.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    96c48f9c01fb92817d86cad0ddddbe788018e886b1db92f8132ad5ef64a9b12c
// contractVersion: 0.1.0
// origin:          spfn-primitives-ci-export
//
// Bundle origin: spfn-primitives-ci-export.
//
// Regenerate with: ./gradlew :contract-codegen:spfnGenerateClients
// Verified by:     ./gradlew :contract-codegen:spfnCodegenVerify

package xyz.superfunction.spfn.generated

import xyz.superfunction.spfn.core.SpfnOperation

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

    /** Every operation, in bundle order. */
    val all: List<SpfnOperation> = listOf(
        authClientProofHandshake,
        echoSend,
        itemsList
    )

    /**
     * Looks an operation up by contract id. Returns null rather than a nearest
     * match: an unknown id is a contract mismatch, not a typo to be forgiven.
     */
    fun operation(id: String): SpfnOperation? = all.firstOrNull { it.id == id }
}
