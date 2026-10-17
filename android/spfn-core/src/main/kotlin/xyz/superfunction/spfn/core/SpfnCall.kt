// SPFN Mobile — one operation with the codecs that turn its types into contract bytes.
//
// In core rather than beside the client, because the generated module is where the
// per-operation values live and it depends on core alone. The type itself needs nothing
// the client owns: an operation, a canonical value, and the unit answer a bodyless
// operation gives back — all three are here.
//
// Sources/SPFNCore/SPFNCall.swift is the same type in Swift.

package xyz.superfunction.spfn.core

/**
 * One operation with the two functions that turn its types into contract bytes.
 *
 * The client is generic over request and response rather than knowing any operation, so
 * the per-operation values that fill this in are generated — `SpfnGeneratedCalls` — with
 * neither this file nor the execute path changing. Keeping the operation inside the
 * descriptor is what stops a caller pairing `echo.send` with the codec for `items.list`.
 *
 * [encode] answers `null` for an operation the contract declares no request type for.
 * `null` is not an empty object: the request carries no body at all — no bytes and no
 * `content-type` — and its proof signs the absent-body digest, the bundle's
 * `clientProofV1.proofInput.bodySha256`: "the literal string of 64 zero characters when
 * an operation has no body". A declared body that happens to be empty still encodes as
 * `{}` and is digested like any other body.
 *
 * [query] is the query as `name to value` pairs in the order they are sent; a `null` value
 * is left out of the URL rather than sent as `name=`. It sits beside the operation rather
 * than inside its path, because the two go different places: the proof signs
 * `operation.path` alone — the server verifies the path it routed, without its query —
 * while the request is sent to [pathAndQuery]. A query folded into the path would be
 * signed by the client and not by the server. Last among the parameters so a positional
 * call written before it existed still reads the same.
 */
class SpfnCall<Req, Resp>(
    val operation: SpfnOperation,
    val encode: (Req) -> SpfnCanonicalValue?,
    val decode: (SpfnCanonicalValue) -> Resp,
    val query: List<Pair<String, String?>> = emptyList()
)
{
    /**
     * What the request is sent to: `operation.path`, then `?name=value&…` when any query
     * value is present. Each value is percent-encoded — RFC 3986 unreserved bytes as they
     * are, every other UTF-8 byte as `%XX` — so a value cannot add a field of its own.
     */
    val pathAndQuery: String
        get()
        {
            val fields = query.mapNotNull { (name, value) -> value?.let { name + "=" + percentEncoded(it) } };
            return if (fields.isEmpty()) operation.path else operation.path + "?" + fields.joinToString("&");
        }

    companion object
    {
        /**
         * A call on an operation the contract declares no response type for.
         *
         * The decoder is fixed here rather than left to the caller. There is nothing to
         * decode, so the only correct decoder is the one that answers with the unit value,
         * and writing that lambda at each call site is how one of them ends up different.
         */
        @JvmStatic
        fun <Req> noResponse(
            operation: SpfnOperation,
            encode: (Req) -> SpfnCanonicalValue?,
            query: List<Pair<String, String?>> = emptyList()
        ): SpfnCall<Req, SpfnNoResponse> = SpfnCall(operation, encode, { SpfnNoResponse }, query)
    }
}

private fun percentEncoded(value: String): String = buildString {
    for (byte in value.encodeToByteArray())
    {
        val unsigned = byte.toInt() and 0xFF;
        if (isUnreserved(unsigned))
        {
            append(unsigned.toChar());
        }
        else
        {
            append('%').append(HEX[unsigned shr 4]).append(HEX[unsigned and 0x0F]);
        }
    }
}

private fun isUnreserved(byte: Int): Boolean =
    byte in 'A'.code..'Z'.code || byte in 'a'.code..'z'.code || byte in '0'.code..'9'.code ||
        byte == '-'.code || byte == '.'.code || byte == '_'.code || byte == '~'.code

private const val HEX = "0123456789ABCDEF"
