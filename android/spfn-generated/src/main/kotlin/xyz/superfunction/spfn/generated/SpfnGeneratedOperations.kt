// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.1.0-dev
// bundle:          Contracts/spfn-mobile-contract.v1.json
// bundleSha256:    07fd82683576e3343753b590e00b5bf9725b2e598e1e5e6282f251e73a433e45
// contractVersion: 1.0.0-dev.1
// origin:          spfn-mobile-step2-dev-bundle
//
// The bundle was hand-authored in this repository and was NOT exported by SPFN primitives CI.
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
