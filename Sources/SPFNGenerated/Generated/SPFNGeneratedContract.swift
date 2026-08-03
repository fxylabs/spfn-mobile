// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-contract-codegen 0.1.0-dev
// bundle:          Contracts/spfn-mobile-contract.json
// bundleSha256:    28f2fd4cf37ef903dd9746d4058d510435b3905b9b94312f6e95120ad3603084
// contractVersion: 0.2.0
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
    public static let generatorVersion: String = "spfn-contract-codegen 0.1.0-dev"

    /// The pinned bundle these sources were derived from.
    public static let binding = SPFNContractBinding(
        importedVersion: "0.2.0",
        importedManifestSha256: "28f2fd4cf37ef903dd9746d4058d510435b3905b9b94312f6e95120ad3603084",
        supportedRange: ">=0.2.0 <0.3.0",
        supportedMajor: 0,
        supportedMinor: 2,
        origin: "spfn-primitives-ci-export"
    )

    /// Every operation the contract declares, in bundle order.
    public static let operationIDs: [String] = [
        "auth.clientProof.handshake",
        "echo.send",
        "items.list",
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
}
