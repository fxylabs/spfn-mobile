// SPFN Mobile — clientProofV1, algorithm SPFN-PROOF-INPUT-1.
//
// The proof input is a newline-joined list of eight fields in a fixed order. That is
// only unambiguous if no field can contain the separator, so a C0 control character in
// any field is an error rather than something to escape: an escaping scheme is one more
// thing two platforms can disagree about.
//
// android/spfn-auth/.../SpfnClientProof.kt implements the same algorithm, and
// Contracts/fixtures/proof/ pins the agreed output of both.

import SPFNCore

/// The fields the proof is taken over, in the order the contract fixes.
public struct SPFNProofInput: Equatable, Sendable
{
    public let method: String
    public let path: String
    public let clientID: String
    public let keyID: String
    public let nonce: String
    public let issuedAtMillis: Int64
    public let bodySha256: String

    public init(
        method: String,
        path: String,
        clientID: String,
        keyID: String,
        nonce: String,
        issuedAtMillis: Int64,
        bodySha256: String
    )
    {
        self.method = method
        self.path = path
        self.clientID = clientID
        self.keyID = keyID
        self.nonce = nonce
        self.issuedAtMillis = issuedAtMillis
        self.bodySha256 = bodySha256
    }

    /// Builds the input for a request whose body is already canonical. An operation
    /// with no body carries the absent-body digest rather than the digest of `""`.
    public static func forRequest(
        method: String,
        path: String,
        clientID: String,
        keyID: String,
        nonce: String,
        issuedAtMillis: Int64,
        canonicalBody: [UInt8]?
    ) -> SPFNProofInput
    {
        SPFNProofInput(
            method: method,
            path: path,
            clientID: clientID,
            keyID: keyID,
            nonce: nonce,
            issuedAtMillis: issuedAtMillis,
            bodySha256: canonicalBody.map { SPFNDigest.sha256Hex($0) } ?? SPFNDigest.absentBodyDigest
        )
    }
}

public enum SPFNClientProof
{
    /// The only profile name that ever appears in a proof input.
    public static let profileName: String = SPFNAuthProfile.clientProofV1.rawValue

    /// The field order the contract fixes, named so a test can assert on it directly.
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

    /// The canonical proof input string, before any MAC is applied.
    public static func canonicalString(for input: SPFNProofInput) throws -> String
    {
        let values = [
            ("profile", profileName),
            ("method", input.method),
            ("path", input.path),
            ("clientId", input.clientID),
            ("keyId", input.keyID),
            ("nonce", input.nonce),
            ("issuedAtMillis", String(input.issuedAtMillis)),
            ("bodySha256", input.bodySha256),
        ]

        for (field, value) in values where value.unicodeScalars.contains(where: { $0.value < 0x20 })
        {
            throw SPFNAuthError.controlCharacterInProofField(field)
        }

        return values.map(\.1).joined(separator: "\n")
    }

    /// The canonical proof input bytes. This is what the digest and the MAC cover.
    public static func canonicalBytes(for input: SPFNProofInput) throws -> [UInt8]
    {
        Array(try canonicalString(for: input).utf8)
    }

    /// SHA-256 of the canonical proof input, as lowercase base16.
    ///
    /// Carries no secret, so it is the value the conformance fixtures use to prove Swift
    /// and Kotlin agree byte for byte before any key is involved.
    public static func canonicalDigest(for input: SPFNProofInput) throws -> String
    {
        SPFNDigest.sha256Hex(try canonicalBytes(for: input))
    }

    /// The proof itself: HMAC-SHA-256 over the canonical input, as lowercase base16.
    public static func proof(for input: SPFNProofInput, key: [UInt8]) throws -> String
    {
        SPFNDigest.hmacSHA256Hex(key: key, message: try canonicalBytes(for: input))
    }

    /// Verifies a presented proof without leaking where it first differs.
    public static func verify(presented: String, for input: SPFNProofInput, key: [UInt8]) throws
    {
        let expected = try proof(for: input, key: key)
        guard SPFNDigest.constantTimeEquals(expected, presented)
        else
        {
            throw SPFNAuthError.proofInvalid
        }
    }
}

/// Replay and revocation state for a verifier.
///
/// The SDK ships this so a client can be exercised against the same acceptance rules
/// the server applies. Check order is part of the contract: a revoked key is rejected
/// before the proof is verified, which keeps revocation distinguishable from a bad
/// proof instead of collapsing both into one opaque failure.
public struct SPFNProofAcceptance: Sendable
{
    public let replayWindowMillis: Int64

    private var revokedKeyIDs: Set<String>
    private var seen: Set<String>

    public init(replayWindowMillis: Int64, revokedKeyIDs: Set<String> = [])
    {
        self.replayWindowMillis = replayWindowMillis
        self.revokedKeyIDs = revokedKeyIDs
        self.seen = []
    }

    public mutating func revoke(keyID: String)
    {
        revokedKeyIDs.insert(keyID)
    }

    /// Admits one proof presentation, or throws the reason it was refused.
    public mutating func admit(
        presented: String,
        input: SPFNProofInput,
        key: [UInt8],
        nowMillis: Int64
    ) throws
    {
        guard !revokedKeyIDs.contains(input.keyID)
        else
        {
            throw SPFNAuthError.sessionRevoked
        }

        let age = nowMillis - input.issuedAtMillis
        guard age >= 0, age <= replayWindowMillis
        else
        {
            throw SPFNAuthError.proofExpired
        }

        let replayKey = "\(input.clientID)\u{1F}\(input.nonce)"
        guard !seen.contains(replayKey)
        else
        {
            throw SPFNAuthError.proofReplayed
        }

        try SPFNClientProof.verify(presented: presented, for: input, key: key)
        seen.insert(replayKey)
    }
}
