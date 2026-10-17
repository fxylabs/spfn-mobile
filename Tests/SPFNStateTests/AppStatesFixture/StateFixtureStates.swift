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
import SPFNState

/// The state descriptors generated from this app's contract document, one per state its
/// `getStates` snapshot names. Hand them to the state client; add `events` to the event
/// stream's configuration.
public enum StateFixtureStates
{
    public static let aiAccounts = SPFNStateDescriptor(
        id: "aiAccounts",
        keyFields: ["workspaceId"],
        read: { key in StateFixtureAPI.getAiAccountsState(workspaceId: key["workspaceId"]!) },
        frame: { SPFNStateFrame(at: $0.at, value: $0.value, data: SPFNNoStateData()) }
    )

    public static let consent = SPFNStateDescriptor(
        id: "consent",
        read: { _ in StateFixtureAPI.getConsentState() },
        frame: { SPFNStateFrame(at: $0.at, value: $0.value, data: $0.data) }
    ).writable(write: { _ in StateFixtureAPI.putConsentState() })

    public static let all: [any SPFNAnyStateDescriptor] = [aiAccounts, consent]

    public static let events: [String] = ["state.aiAccounts.changed", "state.consent.changed"]

    public static let snapshot: @Sendable (SPFNStateKey) -> SPFNCall<Void, StateFixtureAPI.GetStatesResponse> =
        { query in StateFixtureAPI.getStates(workspaceId: query["workspaceId"]) }
}
