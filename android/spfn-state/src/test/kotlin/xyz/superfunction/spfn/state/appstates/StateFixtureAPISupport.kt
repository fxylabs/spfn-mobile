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

import xyz.superfunction.spfn.core.SpfnCanonicalValue

/** Helpers the calls and types generated from this app's contract document share. */
internal object StateFixtureAPISupport
{
    /** Absent and null alike as null, for an optional or nullable property. */
    fun nonNull(value: SpfnCanonicalValue?): SpfnCanonicalValue? = value?.takeIf { it !is SpfnCanonicalValue.Null }
}
