// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev (app contract)
// documentSha256:  9aaec69a8080fdf8f45188c89b1cf1128a0935bf7ae7636fb67e713c0dbf586f
// documentVersion: 1
// operations:      getItem, listItems, putItemNote
//
// Regenerate with the consumer's spfnAppContractGenerate run. Its spfnAppContractVerify
// fails the build when the document changed or this file was edited.

import SPFNCore

extension FixtureAPI
{
    /// `GET /v1/items/:itemId` — since 1.1.0.
    public static func getItem(itemId: String) -> SPFNCall<Void, GetItemResponse>
    {
        SPFNCall(
            operation: SPFNOperation(
                id: "getItem",
                method: "GET",
                path: "/v1/items/" + FixtureAPI.pathSegment(itemId),
                authProfile: "clientProofV1",
                requiresSession: false,
                declaresResponse: true
            ),
            encode: { _ in SPFNCanonicalValue.object([:]) },
            decode: { try GetItemResponse(canonical: $0) }
        )
    }

    /// `GET /v1/items` — since 1.0.0.
    public static func listItems(archived: Bool? = nil, cursor: String? = nil, limit: Int64, sort: ListItemsQuerySort? = nil) -> SPFNCall<Void, ListItemsResponse>
    {
        SPFNCall(
            operation: SPFNOperation(
                id: "listItems",
                method: "GET",
                path: "/v1/items" + FixtureAPI.queryString([("archived", archived.map { String($0) }), ("cursor", cursor), ("limit", String(limit)), ("sort", sort?.wireValue)]),
                authProfile: "none",
                requiresSession: false,
                declaresResponse: true
            ),
            encode: { _ in SPFNCanonicalValue.object([:]) },
            decode: { try ListItemsResponse(canonical: $0) }
        )
    }

    /// `PUT /v1/items/:itemId/notes/:noteNumber` — since 1.2.0.
    public static func putItemNote(itemId: String, noteNumber: Int64) -> SPFNCall<PutItemNoteBody, SPFNNoResponse>
    {
        SPFNCall<PutItemNoteBody, SPFNNoResponse>.noResponse(
            operation: SPFNOperation(
                id: "putItemNote",
                method: "PUT",
                path: "/v1/items/" + FixtureAPI.pathSegment(itemId) + "/notes/" + FixtureAPI.pathSegment(String(noteNumber)),
                authProfile: "clientProofV1",
                requiresSession: true,
                declaresResponse: false
            ),
            encode: { $0.canonicalValue() }
        )
    }
}
