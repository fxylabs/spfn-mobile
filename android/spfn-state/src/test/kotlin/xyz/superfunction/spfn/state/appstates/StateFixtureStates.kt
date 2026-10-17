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
import xyz.superfunction.spfn.state.SpfnStateDescriptor
import xyz.superfunction.spfn.state.SpfnStateFrame
import xyz.superfunction.spfn.state.SpfnStateKey

/**
 * The state descriptors generated from this app's contract document, one per state its
 * `getStates` snapshot names. Hand them to the state client; add [events] to the event
 * stream's configuration.
 */
object StateFixtureStates
{
    val aiAccounts = SpfnStateDescriptor(
        id = "aiAccounts",
        keyFields = listOf("workspaceId"),
        read = { key -> StateFixtureAPI.getAiAccountsState(workspaceId = key.getValue("workspaceId")) },
        frame = { SpfnStateFrame(it.at, it.value, Unit) },
    );

    val consent = SpfnStateDescriptor(
        id = "consent",
        read = { StateFixtureAPI.getConsentState() },
        frame = { SpfnStateFrame(it.at, it.value, it.data) },
    ).writable(write = { StateFixtureAPI.putConsentState() });

    val all: List<SpfnStateDescriptor<*, *>> = listOf(aiAccounts, consent);

    val events: List<String> = listOf("state.aiAccounts.changed", "state.consent.changed");

    val snapshot: (SpfnStateKey) -> SpfnCall<Unit, GetStatesResponse> =
        { query -> StateFixtureAPI.getStates(workspaceId = query["workspaceId"]) };
}
