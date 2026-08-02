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

import xyz.superfunction.spfn.core.SpfnContractBinding

/** What this build was generated from. */
object SpfnGeneratedContract
{
    /** The generator that produced this directory. */
    const val GENERATOR_VERSION: String = "spfn-contract-codegen 0.1.0-dev"

    /** The pinned bundle these sources were derived from. */
    val BINDING: SpfnContractBinding = SpfnContractBinding(
        importedVersion = "1.0.0-dev.1",
        importedManifestSha256 = "07fd82683576e3343753b590e00b5bf9725b2e598e1e5e6282f251e73a433e45",
        supportedRange = ">=1.0.0-dev.1 <2.0.0",
        supportedMajor = 1,
        origin = "spfn-mobile-step2-dev-bundle"
    )

    /** Every operation the contract declares, in bundle order. */
    val OPERATION_IDS: List<String> = listOf(
        "auth.clientProof.handshake",
        "echo.send",
        "items.list"
    )

    /** The replay window the contract fixes, in milliseconds. */
    const val REPLAY_WINDOW_MILLIS: Long = 300000L

    /** The proof-input field order the contract fixes. */
    val PROOF_INPUT_FIELDS: List<String> = listOf(
        "profile",
        "method",
        "path",
        "clientId",
        "keyId",
        "nonce",
        "issuedAtMillis",
        "bodySha256"
    )
}
