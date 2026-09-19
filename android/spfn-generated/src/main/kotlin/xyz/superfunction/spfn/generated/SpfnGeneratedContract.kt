// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    bb0373c2c3e95bcc3923c84a160945e17ca57d5c13fd8122f341f1df181bc658
// contractVersion: 0.13.0
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
        importedVersion = "0.13.0",
        importedManifestSha256 = "bb0373c2c3e95bcc3923c84a160945e17ca57d5c13fd8122f341f1df181bc658",
        supportedRange = ">=0.13.0 <0.14.0",
        supportedMajor = 0,
        supportedMinor = 13,
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
        "auth.mfa.verify",
        "auth.mfa.status",
        "auth.keys.rotate",
        "auth.keys.list",
        "auth.keys.revoke",
        "auth.keys.revokeAll",
        "auth.device.start",
        "auth.device.poll",
        "auth.device.info",
        "auth.device.approve",
        "auth.device.deny"
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
