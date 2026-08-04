// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.2.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    999b98b1f6c207ff0ab4f2d151bfb3d327e6079dd3f22bcd8216c92a59aec4e3
// contractVersion: 0.4.1
// origin:          spfn-primitives-ci-export
//
// Bundle origin: spfn-primitives-ci-export.
//
// Regenerate with: ./gradlew :contract-codegen:spfnGenerateClients
// Verified by:     ./gradlew :contract-codegen:spfnCodegenVerify

import SPFNCore

/// What this build was generated from.
public enum SPFNGeneratedContract
{
    /// The generator that produced this directory.
    public static let generatorVersion: String = "spfn-contract-codegen 0.2.0-dev"

    /// The pinned bundle these sources were derived from.
    public static let binding = SPFNContractBinding(
        importedVersion: "0.4.1",
        importedManifestSha256: "999b98b1f6c207ff0ab4f2d151bfb3d327e6079dd3f22bcd8216c92a59aec4e3",
        supportedRange: ">=0.4.1 <0.5.0",
        supportedMajor: 0,
        supportedMinor: 4,
        origin: "spfn-primitives-ci-export"
    )

    /// Every operation the contract declares, in bundle order.
    public static let operationIDs: [String] = [
        "auth.clientProof.handshake",
        "echo.send",
        "items.list",
        "auth.enroll.register",
        "auth.enroll.login",
        "auth.enroll.oauthNative",
        "auth.keys.rotate",
        "auth.keys.list",
        "auth.keys.revoke",
        "auth.keys.revokeAll",
    ]

    /// The replay window the contract fixes, in milliseconds.
    public static let replayWindowMillis: Int64 = 300000

    /// The proof-input field order the contract fixes.
    public static let proofInputFields: [String] = [
        "profile",
        "method",
        "path",
        "clientId",
        "keyId",
        "nonce",
        "issuedAtMillis",
        "bodySha256",
    ]

    /// `keyPolicy.ttlDays`: a registered public key expires this many days after
    /// registration, so the client rotates before the TTL runs out.
    public static let keyPolicyTtlDays: Int64 = 90

    /// `keyPolicy.rotationOperation`: the operation that replaces a registered key.
    public static let keyRotationOperationID: String = "auth.keys.rotate"

    /// `clientProofV1.clientIdRule`, verbatim from the bundle.
    public static let clientIdRule: String = "clientId identifies the key owner; the REST surface refuses a proof whose clientId is not the key's owner id, with the same PROOF_INVALID a failed signature answers"
}
