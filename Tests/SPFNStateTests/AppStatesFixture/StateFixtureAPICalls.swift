// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev (app contract)
// documentSha256:  9699aecc37719004922e7991e1be7cf224a467f44e82cce15b37528b37b0d2bb
// documentVersion: 1
// operations:      getAiAccountsState, getConsentState, getStates, putConsentState
//
// Regenerate with the consumer's spfnAppContractGenerate run. Its spfnAppContractVerify
// fails the build when the document changed or this file was edited.

import SPFNCore

extension StateFixtureAPI
{
    /// `GET /state/aiAccounts` — since 1.0.0.
    public static func getAiAccountsState(workspaceId: String) -> SPFNCall<Void, GetAiAccountsStateResponse>
    {
        SPFNCall(
            operation: SPFNOperation(
                id: "getAiAccountsState",
                method: "GET",
                path: "/state/aiAccounts",
                authProfile: "clientProofV1",
                requiresSession: false,
                declaresResponse: true
            ),
            query: [("workspaceId", workspaceId)],
            encode: { _ in nil },
            decode: { try GetAiAccountsStateResponse(canonical: $0) }
        )
    }

    /// `GET /state/consent` — since 1.0.0.
    public static func getConsentState() -> SPFNCall<Void, GetConsentStateResponse>
    {
        SPFNCall(
            operation: SPFNOperation(
                id: "getConsentState",
                method: "GET",
                path: "/state/consent",
                authProfile: "clientProofV1",
                requiresSession: false,
                declaresResponse: true
            ),
            encode: { _ in nil },
            decode: { try GetConsentStateResponse(canonical: $0) }
        )
    }

    /// `GET /state` — since 1.0.0.
    public static func getStates(workspaceId: String? = nil) -> SPFNCall<Void, GetStatesResponse>
    {
        SPFNCall(
            operation: SPFNOperation(
                id: "getStates",
                method: "GET",
                path: "/state",
                authProfile: "clientProofV1",
                requiresSession: false,
                declaresResponse: true
            ),
            query: [("workspaceId", workspaceId)],
            encode: { _ in nil },
            decode: { try GetStatesResponse(canonical: $0) }
        )
    }

    /// `PUT /state/consent` — since 1.0.0.
    public static func putConsentState() -> SPFNCall<PutConsentStateBody, PutConsentStateResponse>
    {
        SPFNCall(
            operation: SPFNOperation(
                id: "putConsentState",
                method: "PUT",
                path: "/state/consent",
                authProfile: "clientProofV1",
                requiresSession: false,
                declaresResponse: true
            ),
            encode: { $0.canonicalValue() },
            decode: { try PutConsentStateResponse(canonical: $0) }
        )
    }
}
