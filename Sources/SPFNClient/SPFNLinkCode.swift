// SPFN Mobile — the link code a signed-in device shows, as this device reads it.
//
// A link code reaches this device one of two ways: a person types it off the other
// screen, or a camera reads the QR the other device draws. Both arrive here as a string,
// and both must come out as the one spelling `auth.deviceLink.redeem` is sent — or as
// nothing, before any key is generated or any request is built.
//
// Everything is judged scalar by scalar, in ASCII, and the URL form by hand rather than
// by URLComponents: the two platforms' URL parsers disagree about percent-encoding, IDN
// hosts and empty segments, and their case mappings disagree about letters outside
// ASCII. A scan one SDK accepts and the other refuses is a bug report with no fix.
// SpfnLinkCode.kt is the same function in Kotlin, and the two suites share one table.

import Foundation

/// Reads a link code off what a person typed or a camera scanned.
public enum SPFNLinkCode
{
    /// The code a scan or a typed entry names, as `XXXX-XXXX`, or nil.
    ///
    /// Surrounding ASCII whitespace is ignored. A typed code may carry spaces, dashes
    /// and lower case (`abcd efgh`, `ABCD-EFGH`), which fold away; it must then be
    /// eight characters of the device-code alphabet, which leaves out 0/O and 1/I/L.
    ///
    /// A URL is accepted only when all of these hold:
    ///
    /// - the scheme is `https`, in any case; `http` and every other scheme are refused;
    /// - the host, compared in lower case, is one of `allowedHosts`. Only ASCII hosts
    ///   match: an IDN host is compared in its `xn--` form, so an app that serves one
    ///   lists that form, and a look-alike Unicode host never matches;
    /// - there is no userinfo, no port, no query and no fragment — so a code carried in
    ///   a query parameter is refused rather than searched for;
    /// - the path has no empty segment and no percent sign: `/ABCD-EFGH/` (a trailing
    ///   slash) and `/ABCD%2DEFGH` are refused, because a code never needs either;
    /// - the last path segment is a code. A segment after the code is refused by that
    ///   same rule, since it is then the last one.
    public static func parse(_ scanned: String, allowedHosts: Set<String>) -> String?
    {
        let scalars = trimmed(Array(scanned.unicodeScalars))
        let code = isHTTPS(scalars)
            ? codeInURL(scalars.dropFirst(urlPrefix.count), allowedHosts: allowedHosts)
            : normalized(scalars)
        return code.map(displayed)
    }

    /// A typed code in the form the server stores it — eight alphabet characters, upper
    /// case, no dash — or nil. What `enrollByLinkCode` sends.
    static func normalized(_ typed: String) -> String?
    {
        normalized(Array(typed.unicodeScalars)[...])
    }

    /// The characters a link code is drawn from.
    private static let alphabet = Set("ABCDEFGHJKMNPQRSTUVWXYZ23456789".unicodeScalars)

    /// Characters in a code, not counting the dash it is shown with.
    private static let length = 8

    private static let whitespace = Set(" \t\r\n".unicodeScalars)

    private static let urlPrefix = Array("https://".unicodeScalars)

    /// Dropped from a typed code. Only the ASCII hyphen: a dash a keyboard substituted
    /// is refused rather than guessed at.
    private static let separators = whitespace.union(["-"])

    /// Refused anywhere after `https://`: query, fragment, percent-encoding, userinfo,
    /// a backslash some parsers read as a slash, and whitespace.
    private static let refusedInURL = whitespace.union(Set("?#%@\\".unicodeScalars))

    /// ASCII letters upper-cased and nothing else. A locale-aware mapping would turn `ß`
    /// into `SS`, two alphabet characters nobody typed.
    private static func normalized(_ typed: ArraySlice<Unicode.Scalar>) -> String?
    {
        let code = typed.filter { !separators.contains($0) }.map(asciiUpper)
        guard code.count == length, code.allSatisfy(alphabet.contains)
        else
        {
            return nil
        }
        return String(String.UnicodeScalarView(code))
    }

    /// The code in what follows `https://`, or nil when the URL breaks a rule `parse`
    /// lists.
    private static func codeInURL(_ rest: ArraySlice<Unicode.Scalar>, allowedHosts: Set<String>) -> String?
    {
        guard !rest.contains(where: refusedInURL.contains), let slash = rest.firstIndex(of: "/")
        else
        {
            return nil
        }
        let host = String(String.UnicodeScalarView(rest[..<slash].map(asciiLower)))
        guard !host.isEmpty,
              host.unicodeScalars.allSatisfy({ $0.isASCII && $0 != ":" }),
              allowedHosts.contains(where: { $0.lowercased() == host })
        else
        {
            return nil
        }
        let segments = rest[(slash + 1)...].split(separator: "/", omittingEmptySubsequences: false)
        guard let last = segments.last, !segments.contains(where: \.isEmpty)
        else
        {
            return nil
        }
        return normalized(last)
    }

    private static func isHTTPS(_ scalars: ArraySlice<Unicode.Scalar>) -> Bool
    {
        scalars.prefix(urlPrefix.count).map(asciiLower) == urlPrefix
    }

    private static func trimmed(_ scalars: [Unicode.Scalar]) -> ArraySlice<Unicode.Scalar>
    {
        guard let first = scalars.firstIndex(where: { !whitespace.contains($0) }),
              let last = scalars.lastIndex(where: { !whitespace.contains($0) })
        else
        {
            return []
        }
        return scalars[first...last]
    }

    private static func asciiUpper(_ scalar: Unicode.Scalar) -> Unicode.Scalar
    {
        ("a"..."z").contains(scalar) ? Unicode.Scalar(scalar.value - 32)! : scalar
    }

    private static func asciiLower(_ scalar: Unicode.Scalar) -> Unicode.Scalar
    {
        ("A"..."Z").contains(scalar) ? Unicode.Scalar(scalar.value + 32)! : scalar
    }

    /// The stored form as a person reads it: `XXXX-XXXX`.
    private static func displayed(_ code: String) -> String
    {
        "\(code.prefix(length / 2))-\(code.suffix(length / 2))"
    }
}
