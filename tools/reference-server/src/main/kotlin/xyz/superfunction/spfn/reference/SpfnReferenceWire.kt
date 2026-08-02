// SPFN Mobile — where the server expects a clientProofV1 proof to ride.
//
// The same restatement `SpfnWireHeaders` makes on the client side, for the same reason:
// the generator emits types and operations, not transport details. It is restated rather
// than shared because the client module is an Android library and cannot be a dependency
// here — and because a server that read its wire mapping out of the client would prove
// only that the client agrees with itself.
//
// The restatement is not trusted. SpfnReferenceWireConformanceTest reads
// `Contracts/spfn-mobile-contract.v1.json` and fails if any name here drifts from the
// bundle's `wireMapping` section.

package xyz.superfunction.spfn.reference

object SpfnReferenceWire
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

    /** The only auth profile this contract allows. */
    const val PROFILE_NAME: String = "clientProofV1"

    /** Contract field name to header field name, keyed the way the bundle keys it. */
    val BY_CONTRACT_FIELD: Map<String, String> = mapOf(
        "profile" to PROFILE,
        "clientId" to CLIENT_ID,
        "keyId" to KEY_ID,
        "nonce" to NONCE,
        "issuedAtMillis" to ISSUED_AT_MILLIS,
        "proof" to PROOF,
        "session" to SESSION
    )

    /**
     * True when a content type names the contract media type.
     *
     * Parameters are tolerated and the media type is not: `application/json; charset=utf-8`
     * is the same body, while `text/plain` is a different contract. Both SDKs send the
     * bare media type, so the tolerance costs nothing and refuses nothing real.
     */
    fun isRequestContentType(raw: String?): Boolean =
        raw != null && raw.substringBefore(';').trim().lowercase() == REQUEST_CONTENT_TYPE
}
