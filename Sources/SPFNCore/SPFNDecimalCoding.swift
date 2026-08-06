// The decimal<scale> coding boundary.
//
// The contract's `typeGrammar.decimal` puts an integer on the wire and gives it meaning
// by division: decimal<2> carries 1999 for 19.99. Its `decimalGeneratorRule` fixes what
// a generator does with that: emit a decimal type — Swift `Decimal` here — and reject a
// value finer than the declared scale at encoding time, never round it. Rounding would
// let the client decide, silently, what a value the server declared exactly is worth.
//
// Everything with a judgment in it lives here rather than in generated code, so the
// generated line stays a thin call and the rules are tested once. The rejection happens
// before the proof is signed and before a byte leaves the device: an impossible value
// fails the call that tried to encode it, not a server round trip.
//
// The Kotlin twin is `SpfnDecimalCoding` in spfn-core, built over BigDecimal. The two
// share the vector table in SPFNDecimalCodingTests / SpfnDecimalCodingTest — the same
// literals on both platforms, so a divergence shows up in a test diff rather than on a
// real server (the P9/P15 rule: two implementations exist to be each other's check).

import Foundation

/// Encoding failures a generated request type can raise. The decode direction reports
/// through `SPFNDecodingError`; these are its outbound mirror, and every case names the
/// field path so the caller knows which value to fix.
public enum SPFNEncodingError: Error, Equatable, Sendable
{
    /// The value is finer than the declared scale — 19.999 offered to decimal<2>.
    /// Refused rather than rounded, per the contract's decimalGeneratorRule.
    case decimalScaleExceeded(path: String, scale: Int)

    /// The scaled integer does not fit a signed 64-bit wire value.
    case decimalOverflow(path: String, scale: Int)

    /// The value is not a finite number (Decimal.nan has no wire form).
    case decimalNotFinite(path: String)

    public var code: String
    {
        switch self
        {
        case .decimalScaleExceeded:
            return "DECIMAL_SCALE_EXCEEDED"
        case .decimalOverflow:
            return "DECIMAL_OVERFLOW"
        case .decimalNotFinite:
            return "DECIMAL_NOT_FINITE"
        }
    }
}

/// The scale conversions used by generated types for `decimal<scale>` fields.
public enum SPFNDecimalCoding
{
    /// The wire form of a decimal value: the integer `value * 10^scale`, exactly.
    ///
    /// A value finer than the scale is refused, never rounded. A value whose scaled
    /// integer leaves the Int64 range is refused. The scale itself is generated from
    /// the contract (1...18), never user input.
    public static func scaledInteger(_ value: Decimal, scale: Int, at path: String) throws -> Int64
    {
        if value.isNaN
        {
            throw SPFNEncodingError.decimalNotFinite(path: path)
        }

        var scaled = value * pow(Decimal(10), scale)

        var rounded = Decimal()
        NSDecimalRound(&rounded, &scaled, 0, .plain)
        if rounded != scaled
        {
            throw SPFNEncodingError.decimalScaleExceeded(path: path, scale: scale)
        }

        if scaled < Decimal(Int64.min) || scaled > Decimal(Int64.max)
        {
            throw SPFNEncodingError.decimalOverflow(path: path, scale: scale)
        }
        return NSDecimalNumber(decimal: scaled).int64Value
    }

    /// The value form of a wire integer: `integer / 10^scale`, exact in base ten.
    public static func decimal(_ value: SPFNCanonicalValue?, scale: Int, at path: String) throws -> Decimal
    {
        let wire = try SPFNDecoding.integer(value, at: path)
        return Decimal(wire) / pow(Decimal(10), scale)
    }

    /// Absent and null read as nothing, exactly as every other optional field does.
    public static func optionalDecimal(_ value: SPFNCanonicalValue?, scale: Int, at path: String) throws -> Decimal?
    {
        guard let value, value != .null
        else
        {
            return nil
        }
        return try decimal(value, scale: scale, at: path)
    }
}
