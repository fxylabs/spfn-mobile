// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    0a91612158aaf9917be8487cf70e1df9ab4c12ac6c1106973afa99122e458795
// contractVersion: 0.6.0
// origin:          spfn-primitives-ci-export
//
// Bundle origin: spfn-primitives-ci-export.
//
// Regenerate with: ./gradlew :contract-codegen:spfnGenerateClients
// Verified by:     ./gradlew :contract-codegen:spfnCodegenVerify

package xyz.superfunction.spfn.generated

import xyz.superfunction.spfn.core.SpfnContractBinding

/** What this build was generated from. */
object SpfnGeneratedContract
{
    /** The generator that produced this directory. */
    const val GENERATOR_VERSION: String = "spfn-contract-codegen 0.2.0-dev"

    /** The pinned bundle these sources were derived from. */
    val BINDING: SpfnContractBinding = SpfnContractBinding(
        importedVersion = "0.6.0",
        importedManifestSha256 = "0a91612158aaf9917be8487cf70e1df9ab4c12ac6c1106973afa99122e458795",
        supportedRange = ">=0.6.0 <0.7.0",
        supportedMajor = 0,
        supportedMinor = 6,
        origin = "spfn-primitives-ci-export"
    )

    /** Every operation the contract declares, in bundle order. */
    val OPERATION_IDS: List<String> = listOf(
        "auth.clientProof.handshake",
        "echo.send",
        "items.list",
        "auth.enroll.register",
        "auth.enroll.login",
        "auth.enroll.oauthNative",
        "auth.keys.rotate",
        "auth.keys.list",
        "auth.keys.revoke",
        "auth.keys.revokeAll"
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

    /**
     * `keyPolicy.ttlDays`: a registered public key expires this many days after
     * registration, so the client rotates before the TTL runs out.
     */
    const val KEY_POLICY_TTL_DAYS: Long = 90L

    /** `keyPolicy.rotationOperation`: the operation that replaces a registered key. */
    const val KEY_ROTATION_OPERATION_ID: String = "auth.keys.rotate"

    /** `clientProofV1.clientIdRule`, verbatim from the bundle. */
    const val CLIENT_ID_RULE: String = "clientId identifies the key owner; the REST surface refuses a proof whose clientId is not the key's owner id, with the same PROOF_INVALID a failed signature answers"
}
