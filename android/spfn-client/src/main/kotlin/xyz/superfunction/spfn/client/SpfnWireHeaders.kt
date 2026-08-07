// SPFN Mobile — where a clientProofV1 proof rides on an HTTP request.
//
// Counterpart of Sources/SPFNClient/SPFNWireHeaders.swift. The pinned bundle's
// `wireMapping` section is the source of these names; they are restated here because
// the generator emits types and operations, not transport details. The restatement is
// not trusted: SpfnWireHeadersConformanceTest reads the bundle and fails if any name,
// the order or the content type drifts from it, and Contracts/fixtures/request/wire.json
// pins the exact bytes both platforms put on the wire.

package xyz.superfunction.spfn.client

/**
 * Header field names for the clientProofV1 wire mapping.
 *
 * Names are lowercase. HTTP field names are case-insensitive, but the transport refuses
 * a repeated name compared case-insensitively, so one spelling everywhere keeps that
 * check meaningful.
 */
object SpfnWireHeaders
{
    const val CONTENT_TYPE: String = "content-type"
    const val REQUEST_CONTENT_TYPE: String = "application/json"

    const val PROFILE: String = "x-spfn-auth-profile"
    const val CLIENT_ID: String = "x-spfn-client-id"
    const val KEY_ID: String = "x-spfn-key-id"
    const val NONCE: String = "x-spfn-nonce"
    const val ISSUED_AT_MILLIS: String = "x-spfn-issued-at"
    const val PROOF: String = "x-spfn-proof"
    const val SESSION: String = "x-spfn-session"

    // `wireMapping.clientIdentity`. Four of these names begin `x-spfn-client`, and only
    // the first is a proof field: CLIENT_ID above is the enrolled client's identifier and
    // is signed, while the three below are diagnostic and are not. Match a full name,
    // never a prefix (docs/IMPLEMENTATION-PITFALLS.md P5).
    const val CLIENT_KIND: String = "x-spfn-client-kind"
    const val CLIENT_VERSION: String = "x-spfn-client-version"
    const val CLIENT_CONTRACT_VERSION: String = "x-spfn-client-contract-version"

    // `wireMapping.serverAnnouncement`, which every response carries, refusals included.
    const val SERVER_CONTRACT_VERSION: String = "x-spfn-server-contract-version"
    const val SUPPORTED_CONTRACT_RANGE: String = "x-spfn-supported-contract-range"

    /**
     * Contract field names in the order a request carries them, exactly as
     * `wireMapping.headerOrder` fixes it. `content-type` precedes all of them and is not
     * part of this list, because it belongs to the body rather than to the proof.
     */
    val CONTRACT_FIELD_ORDER: List<String> = listOf(
        "profile",
        "clientId",
        "keyId",
        "nonce",
        "issuedAtMillis",
        "proof",
        "session"
    )

    /**
     * Contract field name to header field name, keyed the way the bundle keys it so a
     * test can compare the two maps directly.
     */
    val BY_CONTRACT_FIELD: Map<String, String> = mapOf(
        "profile" to PROFILE,
        "clientId" to CLIENT_ID,
        "keyId" to KEY_ID,
        "nonce" to NONCE,
        "issuedAtMillis" to ISSUED_AT_MILLIS,
        "proof" to PROOF,
        "session" to SESSION
    )
}
