// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    cf1b34a4081059c29f838b9e8b3a973a9fbb5e5e64a576c673f792d9c6b4ca46
// contractVersion: 0.9.0
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
        importedVersion = "0.9.0",
        importedManifestSha256 = "cf1b34a4081059c29f838b9e8b3a973a9fbb5e5e64a576c673f792d9c6b4ca46",
        supportedRange = ">=0.9.0 <0.10.0",
        supportedMajor = 0,
        supportedMinor = 9,
        origin = "spfn-primitives-ci-export"
    )

    /** The unproven operation used to synchronize before the first proof. */
    const val CLOCK_SYNCHRONIZATION_OPERATION_ID: String = "core.time"

    /** The required integer response field that anchors proof time. */
    const val CLOCK_SYNCHRONIZATION_EPOCH_FIELD: String = "serverTimeMillis"

    /** Every operation the contract declares, in bundle order. */
    val OPERATION_IDS: List<String> = listOf(
        "core.time",
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
