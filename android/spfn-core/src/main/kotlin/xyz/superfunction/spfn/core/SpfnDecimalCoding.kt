// The decimal<scale> coding boundary.
//
// The contract's `typeGrammar.decimal` puts an integer on the wire and gives it meaning
// by division: decimal<2> carries 1999 for 19.99. Its `decimalGeneratorRule` fixes what
// a generator does with that: emit a decimal type — BigDecimal here — and reject a value
// finer than the declared scale at encoding time, never round it. Rounding would let the
// client decide, silently, what a value the server declared exactly is worth.
//
// Everything with a judgment in it lives here rather than in generated code, so the
// generated line stays a thin call and the rules are tested once. The rejection happens
// before the proof is signed and before a byte leaves the device: an impossible value
// fails the call that tried to encode it, not a server round trip.
//
// The Swift twin is `SPFNDecimalCoding` in SPFNCore, built over Decimal. The two share
// the vector table in SpfnDecimalCodingTest / SPFNDecimalCodingTests — the same literals
// on both platforms, so a divergence shows up in a test diff rather than on a real
// server (the P9/P15 rule: two implementations exist to be each other's check).

package xyz.superfunction.spfn.core

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Encoding failures a generated request type can raise. The decode direction reports
 * through [SpfnDecodingException]; this is its outbound mirror. Kotlin cannot make the
 * compiler demand a catch the way Swift's `throws` does, which is exactly why the
 * refusal codes here mirror the Swift enum case for case — the shared vector table
 * holds the two to one behaviour.
 */
class SpfnEncodingException(val code: String, message: String) : IllegalArgumentException(message)

/** The scale conversions used by generated types for `decimal<scale>` fields. */
object SpfnDecimalCoding
{
    /**
     * The wire form of a decimal value: the integer `value * 10^scale`, exactly.
     *
     * A value finer than the scale is refused, never rounded. A value whose scaled
     * integer leaves the Long range is refused. The scale itself is generated from the
     * contract (1..18), never user input.
     */
    fun scaledInteger(value: BigDecimal, scale: Int, path: String): Long
    {
        val exact = try
        {
            value.setScale(scale, RoundingMode.UNNECESSARY)
        }
        catch (_: ArithmeticException)
        {
            throw SpfnEncodingException(
                "DECIMAL_SCALE_EXCEEDED",
                "$path is finer than decimal<$scale> and is refused rather than rounded"
            );
        }
        // Bounds compared by hand rather than longValueExact(), which is API 31 on
        // Android against this library's minSdk 24 — the P14 trap: a JVM unit test
        // resolves the desktop JDK and never notices.
        val unscaled = exact.unscaledValue();
        if (unscaled > LONG_MAX || unscaled < LONG_MIN)
        {
            throw SpfnEncodingException(
                "DECIMAL_OVERFLOW",
                "$path scaled by 10^$scale does not fit a signed 64-bit wire value"
            );
        }
        return unscaled.toLong();
    }

    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)

    /** The value form of a wire integer: `integer / 10^scale`, exact in base ten. */
    fun decimal(value: SpfnCanonicalValue?, scale: Int, path: String): BigDecimal =
        BigDecimal.valueOf(SpfnDecoding.integer(value, path), scale)

    /** Absent and null read as nothing, exactly as every other optional field does. */
    fun optionalDecimal(value: SpfnCanonicalValue?, scale: Int, path: String): BigDecimal?
    {
        val wire = SpfnDecoding.optionalInteger(value, path) ?: return null;
        return BigDecimal.valueOf(wire, scale);
    }
}
