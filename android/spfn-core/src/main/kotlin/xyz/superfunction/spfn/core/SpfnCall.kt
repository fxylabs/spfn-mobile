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
 */
class SpfnCall<Req, Resp>(
    val operation: SpfnOperation,
    val encode: (Req) -> SpfnCanonicalValue,
    val decode: (SpfnCanonicalValue) -> Resp
)
{
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
            encode: (Req) -> SpfnCanonicalValue
        ): SpfnCall<Req, SpfnNoResponse> = SpfnCall(operation, encode) { SpfnNoResponse }
    }
}
