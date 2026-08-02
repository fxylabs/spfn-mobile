// SPFN Mobile — where a clientProofV1 proof rides on an HTTP request.
//
// The pinned bundle's `wireMapping` section is the source of these names. They are
// restated here because the generator emits types and operations, not transport
// details, and a session has to name a header at compile time. The restatement is not
// trusted: SPFNWireHeadersConformanceTests reads the bundle and fails if any name, the
// order or the content type drifts from it, and Contracts/fixtures/request/wire.json
// pins the exact bytes both platforms put on the wire.
//
// android/spfn-client/.../SpfnWireHeaders.kt is the same table in Kotlin.

/// Header field names for the clientProofV1 wire mapping.
///
/// Names are lowercase. HTTP field names are case-insensitive, but the transport
/// refuses a repeated name compared case-insensitively, so one spelling everywhere
/// keeps that check meaningful.
public enum SPFNWireHeaders
{
    public static let contentType = "content-type"
    public static let requestContentType = "application/json"

    public static let profile = "x-spfn-auth-profile"
    public static let clientID = "x-spfn-client-id"
    public static let keyID = "x-spfn-key-id"
    public static let nonce = "x-spfn-nonce"
    public static let issuedAtMillis = "x-spfn-issued-at"
    public static let proof = "x-spfn-proof"
    public static let session = "x-spfn-session"

    /// Contract field names in the order a request carries them, exactly as
    /// `wireMapping.headerOrder` fixes it. `content-type` precedes all of them and is
    /// not part of this list, because it belongs to the body rather than to the proof.
    public static let contractFieldOrder: [String] = [
        "profile",
        "clientId",
        "keyId",
        "nonce",
        "issuedAtMillis",
        "proof",
        "session",
    ]

    /// Contract field name to header field name, keyed the way the bundle keys it so a
    /// test can compare the two dictionaries directly.
    public static let byContractField: [String: String] = [
        "profile": profile,
        "clientId": clientID,
        "keyId": keyID,
        "nonce": nonce,
        "issuedAtMillis": issuedAtMillis,
        "proof": proof,
        "session": session,
    ]
}
