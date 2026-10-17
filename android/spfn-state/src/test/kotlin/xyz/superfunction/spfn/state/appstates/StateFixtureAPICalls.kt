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

import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnOperation

/**
 * The calls generated from this app's contract document. Hand one to
 * `SpfnClient.execute`: the SDK signs, sends, retries and classifies failures exactly
 * as it does for its own operations.
 */
object StateFixtureAPI
{
    /** `GET /state/aiAccounts` — since 1.0.0. */
    fun getAiAccountsState(workspaceId: String): SpfnCall<Unit, GetAiAccountsStateResponse> = SpfnCall(
        operation = SpfnOperation(
            id = "getAiAccountsState",
            method = "GET",
            path = "/state/aiAccounts",
            authProfile = "clientProofV1",
            requiresSession = false,
            declaresResponse = true
        ),
        query = listOf("workspaceId" to workspaceId),
        encode = { _ -> null },
        decode = { value -> GetAiAccountsStateResponse.decode(value) }
    )

    /** `GET /state/consent` — since 1.0.0. */
    fun getConsentState(): SpfnCall<Unit, GetConsentStateResponse> = SpfnCall(
        operation = SpfnOperation(
            id = "getConsentState",
            method = "GET",
            path = "/state/consent",
            authProfile = "clientProofV1",
            requiresSession = false,
            declaresResponse = true
        ),
        encode = { _ -> null },
        decode = { value -> GetConsentStateResponse.decode(value) }
    )

    /** `GET /state` — since 1.0.0. */
    fun getStates(workspaceId: String? = null): SpfnCall<Unit, GetStatesResponse> = SpfnCall(
        operation = SpfnOperation(
            id = "getStates",
            method = "GET",
            path = "/state",
            authProfile = "clientProofV1",
            requiresSession = false,
            declaresResponse = true
        ),
        query = listOf("workspaceId" to workspaceId),
        encode = { _ -> null },
        decode = { value -> GetStatesResponse.decode(value) }
    )

    /** `PUT /state/consent` — since 1.0.0. */
    fun putConsentState(): SpfnCall<PutConsentStateBody, PutConsentStateResponse> = SpfnCall(
        operation = SpfnOperation(
            id = "putConsentState",
            method = "PUT",
            path = "/state/consent",
            authProfile = "clientProofV1",
            requiresSession = false,
            declaresResponse = true
        ),
        encode = { body -> body.canonicalValue() },
        decode = { value -> PutConsentStateResponse.decode(value) }
    )
}
