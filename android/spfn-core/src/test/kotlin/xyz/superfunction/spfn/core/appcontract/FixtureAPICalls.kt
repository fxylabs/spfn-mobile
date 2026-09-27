// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev (app contract)
// documentSha256:  9aaec69a8080fdf8f45188c89b1cf1128a0935bf7ae7636fb67e713c0dbf586f
// documentVersion: 1
// operations:      getItem, listItems, putItemNote
//
// Regenerate with the consumer's spfnAppContractGenerate run. Its spfnAppContractVerify
// fails the build when the document changed or this file was edited.

package xyz.superfunction.spfn.core.appcontract

import xyz.superfunction.spfn.core.SpfnCall
import xyz.superfunction.spfn.core.SpfnNoResponse
import xyz.superfunction.spfn.core.SpfnOperation

/**
 * The calls generated from this app's contract document. Hand one to
 * `SpfnClient.execute`: the SDK signs, sends, retries and classifies failures exactly
 * as it does for its own operations.
 */
object FixtureAPI
{
    /** `GET /v1/items/:itemId` — since 1.1.0. */
    fun getItem(itemId: String): SpfnCall<Unit, GetItemResponse> = SpfnCall(
        operation = SpfnOperation(
            id = "getItem",
            method = "GET",
            path = "/v1/items/" + FixtureAPISupport.pathSegment(itemId),
            authProfile = "clientProofV1",
            requiresSession = false,
            declaresResponse = true
        ),
        encode = { _ -> null },
        decode = { value -> GetItemResponse.decode(value) }
    )

    /** `GET /v1/items` — since 1.0.0. */
    fun listItems(archived: Boolean? = null, cursor: String? = null, limit: Long, sort: ListItemsQuerySort? = null): SpfnCall<Unit, ListItemsResponse> = SpfnCall(
        operation = SpfnOperation(
            id = "listItems",
            method = "GET",
            path = "/v1/items" + FixtureAPISupport.queryString(listOf("archived" to archived?.toString(), "cursor" to cursor, "limit" to limit.toString(), "sort" to sort?.wireValue)),
            authProfile = "none",
            requiresSession = false,
            declaresResponse = true
        ),
        encode = { _ -> null },
        decode = { value -> ListItemsResponse.decode(value) }
    )

    /** `PUT /v1/items/:itemId/notes/:noteNumber` — since 1.2.0. */
    fun putItemNote(itemId: String, noteNumber: Long): SpfnCall<PutItemNoteBody, SpfnNoResponse> = SpfnCall.noResponse(
        operation = SpfnOperation(
            id = "putItemNote",
            method = "PUT",
            path = "/v1/items/" + FixtureAPISupport.pathSegment(itemId) + "/notes/" + FixtureAPISupport.pathSegment(noteNumber.toString()),
            authProfile = "clientProofV1",
            requiresSession = true,
            declaresResponse = false
        ),
        encode = { body -> body.canonicalValue() }
    )
}
