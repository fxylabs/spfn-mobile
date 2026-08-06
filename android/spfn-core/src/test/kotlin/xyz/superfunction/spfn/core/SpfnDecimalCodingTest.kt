// The decimal<scale> case table, Kotlin half.
//
// The vectors here are the same literals as SPFNDecimalCodingTests.swift, row for row.
// Two implementations exist to be each other's check, and the check only works if both
// are held to one table — a case added here is added there in the same change. The
// Swift table has one extra row (Decimal.nan → DECIMAL_NOT_FINITE); BigDecimal has no
// NaN, so no twin exists on this side.

package xyz.superfunction.spfn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

class SpfnDecimalCodingTest
{
    // ---- encode: value × 10^scale, exactly or not at all -------------------

    /** (value, scale, wire) — every row must encode to exactly the named integer. */
    private val encodeVectors = listOf(
        Triple("19.99", 2, 1999L),
        Triple("19.9", 2, 1990L),
        Triple("20", 2, 2000L),
        Triple("-19.99", 2, -1999L),
        Triple("0", 2, 0L),
        Triple("92233720368547758.07", 2, Long.MAX_VALUE),
        Triple("-92233720368547758.08", 2, Long.MIN_VALUE),
        Triple("1.5", 18, 1_500_000_000_000_000_000L),
        Triple("0.000000000000000001", 18, 1L)
    )

    @Test
    fun `a value at or below the scale encodes to the exact scaled integer`()
    {
        encodeVectors.forEach { (text, scale, wire) ->
            assertEquals(
                "'$text' at decimal<$scale>",
                wire,
                SpfnDecimalCoding.scaledInteger(BigDecimal(text), scale, "\$.v")
            );
        }
    }

    /** (value, scale) — finer than the scale, refused and never rounded. */
    private val scaleExceededVectors = listOf(
        "19.999" to 2,
        "0.001" to 2
    )

    @Test
    fun `a finer value is refused not rounded`()
    {
        scaleExceededVectors.forEach { (text, scale) ->
            assertRefused("DECIMAL_SCALE_EXCEEDED", text) {
                SpfnDecimalCoding.scaledInteger(BigDecimal(text), scale, "\$.v")
            };
        }
    }

    /** (value, scale) — one step past either Long bound at that scale. */
    private val overflowVectors = listOf(
        "92233720368547758.08" to 2,
        "-92233720368547758.09" to 2,
        "10" to 18
    )

    @Test
    fun `a scaled integer outside Long is refused`()
    {
        overflowVectors.forEach { (text, scale) ->
            assertRefused("DECIMAL_OVERFLOW", text) {
                SpfnDecimalCoding.scaledInteger(BigDecimal(text), scale, "\$.v")
            };
        }
    }

    // ---- decode: wire ÷ 10^scale -------------------------------------------

    /** (wire, scale, value) — numeric equality, since 0 and 0.00 are one number. */
    private val decodeVectors = listOf(
        Triple(1999L, 2, "19.99"),
        Triple(0L, 2, "0"),
        Triple(-1999L, 2, "-19.99"),
        Triple(Long.MAX_VALUE, 2, "92233720368547758.07"),
        Triple(Long.MIN_VALUE, 2, "-92233720368547758.08"),
        Triple(1L, 18, "0.000000000000000001")
    )

    @Test
    fun `a wire integer decodes to the exact decimal`()
    {
        decodeVectors.forEach { (wire, scale, text) ->
            assertEquals(
                "$wire at decimal<$scale>",
                0,
                BigDecimal(text).compareTo(
                    SpfnDecimalCoding.decimal(SpfnCanonicalValue.Integer(wire), scale, "\$.v")
                )
            );
        }
    }

    @Test
    fun `a non-integer wire value is a type mismatch`()
    {
        try
        {
            SpfnDecimalCoding.decimal(SpfnCanonicalValue.Text("1999"), 2, "\$.v");
            fail("a string must not decode as a decimal");
        }
        catch (refused: SpfnDecodingException)
        {
            assertEquals("TYPE_MISMATCH", refused.code);
        }
    }

    @Test
    fun `a missing required decimal is a missing field`()
    {
        try
        {
            SpfnDecimalCoding.decimal(null, 2, "\$.v");
            fail("an absent required field must refuse");
        }
        catch (refused: SpfnDecodingException)
        {
            assertEquals("MISSING_FIELD", refused.code);
        }
    }

    @Test
    fun `an absent optional decimal reads as nothing`()
    {
        assertNull(SpfnDecimalCoding.optionalDecimal(null, 2, "\$.v"));
        assertNull(SpfnDecimalCoding.optionalDecimal(SpfnCanonicalValue.Null, 2, "\$.v"));
        assertEquals(
            0,
            BigDecimal("19.99").compareTo(
                SpfnDecimalCoding.optionalDecimal(SpfnCanonicalValue.Integer(1999), 2, "\$.v")!!
            )
        );
    }

    // ---- round trip --------------------------------------------------------

    /** Every wire value that decodes must encode back to the same integer. */
    private val roundTripWires = listOf(1999L, 0L, -1L, Long.MAX_VALUE, Long.MIN_VALUE)

    @Test
    fun `decode then encode is the identity on wire values`()
    {
        listOf(2, 18).forEach { scale ->
            roundTripWires.forEach { wire ->
                val value = SpfnDecimalCoding.decimal(SpfnCanonicalValue.Integer(wire), scale, "\$.v");
                assertEquals(
                    "$wire at decimal<$scale>",
                    wire,
                    SpfnDecimalCoding.scaledInteger(value, scale, "\$.v")
                );
            }
        }
    }

    private fun assertRefused(code: String, label: String, action: () -> Unit)
    {
        try
        {
            action();
            fail("'$label' must be refused with $code");
        }
        catch (refused: SpfnEncodingException)
        {
            assertEquals("'$label'", code, refused.code);
        }
    }
}
