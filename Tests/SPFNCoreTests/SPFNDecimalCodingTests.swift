// The decimal<scale> case table, Swift half.
//
// The vectors here are the same literals as SpfnDecimalCodingTest.kt, row for row. Two
// implementations exist to be each other's check, and the check only works if both are
// held to one table — a case added here is added there in the same change.

import XCTest
import SPFNCore

final class SPFNDecimalCodingTests: XCTestCase
{
    // MARK: - encode: value × 10^scale, exactly or not at all

    /// (value, scale, wire) — every row must encode to exactly the named integer.
    private static let encodeVectors: [(String, Int, Int64)] = [
        ("19.99", 2, 1999),
        ("19.9", 2, 1990),
        ("20", 2, 2000),
        ("-19.99", 2, -1999),
        ("0", 2, 0),
        ("92233720368547758.07", 2, Int64.max),
        ("-92233720368547758.08", 2, Int64.min),
        ("1.5", 18, 1_500_000_000_000_000_000),
        ("0.000000000000000001", 18, 1),
    ]

    func testAValueAtOrBelowTheScaleEncodesToTheExactScaledInteger() throws
    {
        for (text, scale, wire) in Self.encodeVectors
        {
            let value = try XCTUnwrap(Decimal(string: text))
            XCTAssertEqual(
                try SPFNDecimalCoding.scaledInteger(value, scale: scale, at: "$.v"),
                wire,
                "'\(text)' at decimal<\(scale)>"
            )
        }
    }

    /// (value, scale) — finer than the scale, refused and never rounded.
    private static let scaleExceededVectors: [(String, Int)] = [
        ("19.999", 2),
        ("0.001", 2),
    ]

    func testAFinerValueIsRefusedNotRounded()
    {
        for (text, scale) in Self.scaleExceededVectors
        {
            let value = Decimal(string: text)!
            XCTAssertThrowsError(try SPFNDecimalCoding.scaledInteger(value, scale: scale, at: "$.v"))
            { error in
                XCTAssertEqual((error as? SPFNEncodingError)?.code, "DECIMAL_SCALE_EXCEEDED", "'\(text)'")
            }
        }
    }

    /// (value, scale) — one step past either Int64 bound at that scale.
    private static let overflowVectors: [(String, Int)] = [
        ("92233720368547758.08", 2),
        ("-92233720368547758.09", 2),
        ("10", 18),
    ]

    func testAScaledIntegerOutsideInt64IsRefused()
    {
        for (text, scale) in Self.overflowVectors
        {
            let value = Decimal(string: text)!
            XCTAssertThrowsError(try SPFNDecimalCoding.scaledInteger(value, scale: scale, at: "$.v"))
            { error in
                XCTAssertEqual((error as? SPFNEncodingError)?.code, "DECIMAL_OVERFLOW", "'\(text)'")
            }
        }
    }

    /// Swift-only row: BigDecimal has no NaN, so the Kotlin table has no twin for this.
    func testNaNIsRefusedAsNotFinite()
    {
        XCTAssertThrowsError(try SPFNDecimalCoding.scaledInteger(.nan, scale: 2, at: "$.v"))
        { error in
            XCTAssertEqual((error as? SPFNEncodingError)?.code, "DECIMAL_NOT_FINITE")
        }
    }

    // MARK: - decode: wire ÷ 10^scale

    /// (wire, scale, value) — numeric equality, since 0 and 0.00 are one number.
    private static let decodeVectors: [(Int64, Int, String)] = [
        (1999, 2, "19.99"),
        (0, 2, "0"),
        (-1999, 2, "-19.99"),
        (Int64.max, 2, "92233720368547758.07"),
        (Int64.min, 2, "-92233720368547758.08"),
        (1, 18, "0.000000000000000001"),
    ]

    func testAWireIntegerDecodesToTheExactDecimal() throws
    {
        for (wire, scale, text) in Self.decodeVectors
        {
            XCTAssertEqual(
                try SPFNDecimalCoding.decimal(.integer(wire), scale: scale, at: "$.v"),
                Decimal(string: text)!,
                "\(wire) at decimal<\(scale)>"
            )
        }
    }

    func testANonIntegerWireValueIsATypeMismatch()
    {
        XCTAssertThrowsError(try SPFNDecimalCoding.decimal(.string("1999"), scale: 2, at: "$.v"))
        { error in
            guard case SPFNDecodingError.typeMismatch = error
            else
            {
                return XCTFail("expected typeMismatch, got \(error)")
            }
        }
    }

    func testAMissingRequiredDecimalIsAMissingField()
    {
        XCTAssertThrowsError(try SPFNDecimalCoding.decimal(nil, scale: 2, at: "$.v"))
        { error in
            guard case SPFNDecodingError.missingField = error
            else
            {
                return XCTFail("expected missingField, got \(error)")
            }
        }
    }

    func testAnAbsentOptionalDecimalReadsAsNothing() throws
    {
        XCTAssertNil(try SPFNDecimalCoding.optionalDecimal(nil, scale: 2, at: "$.v"))
        XCTAssertNil(try SPFNDecimalCoding.optionalDecimal(.null, scale: 2, at: "$.v"))
        XCTAssertEqual(try SPFNDecimalCoding.optionalDecimal(.integer(1999), scale: 2, at: "$.v"), Decimal(string: "19.99")!)
    }

    // MARK: - round trip

    /// Every wire value that decodes must encode back to the same integer.
    private static let roundTripWires: [Int64] = [1999, 0, -1, Int64.max, Int64.min]

    func testDecodeThenEncodeIsTheIdentityOnWireValues() throws
    {
        for scale in [2, 18]
        {
            for wire in Self.roundTripWires
            {
                let value = try SPFNDecimalCoding.decimal(.integer(wire), scale: scale, at: "$.v")
                XCTAssertEqual(
                    try SPFNDecimalCoding.scaledInteger(value, scale: scale, at: "$.v"),
                    wire,
                    "\(wire) at decimal<\(scale)>"
                )
            }
        }
    }
}
