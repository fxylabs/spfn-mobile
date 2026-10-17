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

/// The calls and types generated from this app's contract document.
///
/// Hand a call to `SPFNClient.execute`: the SDK signs, sends, retries and classifies
/// failures exactly as it does for its own operations.
public enum StateFixtureAPI
{
    /// Absent and null alike as nil, for an optional or nullable property.
    static func nonNull(_ value: SPFNCanonicalValue?) -> SPFNCanonicalValue?
    {
        guard let value, value != .null
        else
        {
            return nil
        }
        return value
    }
}
