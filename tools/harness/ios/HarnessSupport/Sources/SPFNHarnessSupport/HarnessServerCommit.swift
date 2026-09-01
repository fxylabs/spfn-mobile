// SPFN Mobile — the only header value a receipt is allowed to keep.
//
// A receipt records which build of the server answered, when the server says so in a
// header. That is one string copied from the network straight into a file that leaves
// the phone — and a header is written by whatever is at the other end, which may be a
// server, a proxy, or something that has been misconfigured into echoing a request. An
// unvalidated value there is a hole in the rule that a receipt carries no PII, because
// nothing about "a header called x-spfn-commit" makes its contents a commit hash.
//
// So the value is not trusted, it is recognised. It is kept only when it is what a commit
// hash looks like — `^[0-9a-f]{7,40}$` after lowercasing, which the shared spec fixes —
// and dropped to null otherwise. A dropped value costs a diagnostic; a kept one could
// cost an email address.
//
// The lowercasing is ASCII by hand rather than `String.lowercased()`. That matters less
// here than it does for a locale-sensitive comparison — Swift's own `lowercased()` reads
// no locale — but the hex alphabet is ASCII by definition, and mapping A–Z directly means
// no Unicode casing table stands between the header and the decision. It is the same
// discipline the registry's P9 row asks for, applied where a character class decides
// something.

import Foundation

public enum HarnessServerCommit
{
    /// The shortest and longest a commit hash may be: an abbreviated git object id and a
    /// full SHA-1. Written from the shared spec's pattern, not from anything observed.
    private static let shortest = 7
    private static let longest = 40

    /// The header value to record, or nil when it is absent or is not a commit hash.
    public static func accepted(_ raw: String?) -> String?
    {
        guard let raw
        else
        {
            return nil
        }
        let bytes = raw.utf8.map(asciiLowercased)
        guard (shortest ... longest).contains(bytes.count), bytes.allSatisfy(isHexDigit)
        else
        {
            return nil
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    private static func asciiLowercased(_ byte: UInt8) -> UInt8
    {
        byte >= UInt8(ascii: "A") && byte <= UInt8(ascii: "Z") ? byte + 32 : byte
    }

    private static func isHexDigit(_ byte: UInt8) -> Bool
    {
        (byte >= UInt8(ascii: "0") && byte <= UInt8(ascii: "9"))
            || (byte >= UInt8(ascii: "a") && byte <= UInt8(ascii: "f"))
    }
}
