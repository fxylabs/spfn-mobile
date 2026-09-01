// SPFN Mobile — what a device run reads, and why none of it is committed.
//
// A Maestro run is configured by launch arguments (HarnessConfiguration). A device run
// has no runner to pass them: a person taps an icon. So the device half is configured at
// build time, through `tools/harness/ios/Local.xcconfig` — gitignored — which
// `Harness.xcconfig` optionally includes and `project.yml` feeds into these Info.plist
// keys.
//
// Everything here is a refusal waiting to happen, on purpose. A clean checkout has no
// Local.xcconfig, so every key below expands to an empty string, and this type answers
// `nil` for each of them. The screen then disables the provider buttons and says so.
// Fail closed, never a crash — and the crash is not hypothetical: Google's SDK raises an
// NSException, which Swift cannot catch, when GIDClientID is missing or when the
// reversed-client-id URL scheme it implies is not registered. So this type checks BOTH
// before the harness is ever allowed to call it.

import Foundation

struct HarnessDeviceConfiguration
{
    /// `http://<host>:<port>`, or nil when either half is missing or malformed. Host
    /// only, no path and no query — that is what a receipt records as `serverBaseURL`.
    let serverBaseURL: String?

    /// Google's client id, or nil when it is absent, empty, not plain ASCII, or when the
    /// URL scheme it implies is not registered in this bundle.
    let googleClientID: String?

    // Readiness is deliberately NOT decided here. A launch argument can supply the base
    // URL too, and a build with one of those and no Local.xcconfig is configured — so
    // whether a provider button may be tapped is a question about the base URL the SDK
    // was actually given, which only `HarnessModel` knows. See `HarnessModel.isReady`.

    static func fromBundle(_ bundle: Bundle = .main) -> HarnessDeviceConfiguration
    {
        HarnessDeviceConfiguration(
            serverBaseURL: baseURL(from: bundle),
            googleClientID: googleClientID(from: bundle)
        )
    }

    // MARK: - The server

    /// Host and port are two keys rather than one URL because `//` opens a comment in an
    /// xcconfig: a whole URL written there truncates to `http:` without a word.
    private static func baseURL(from bundle: Bundle) -> String?
    {
        guard let host = string(bundle, "SPFNHarnessServerHost"), isHost(host)
        else
        {
            return nil
        }
        guard let port = string(bundle, "SPFNHarnessServerPort"), isPort(port)
        else
        {
            return nil
        }
        return "http://\(host):\(port)"
    }

    /// Deliberately narrow: ASCII letters, digits, dots, hyphens and colons — enough for
    /// an IPv4 address, a `.local` name and an IPv6 literal, and nothing that could carry
    /// a path, a query or a second URL into the base URL the SDK is handed.
    private static func isHost(_ value: String) -> Bool
    {
        guard !value.isEmpty, value.count <= 255
        else
        {
            return false
        }
        return value.utf8.allSatisfy
        {
            isASCIILetter($0) || isASCIIDigit($0) || $0 == UInt8(ascii: ".")
                || $0 == UInt8(ascii: "-") || $0 == UInt8(ascii: ":")
        }
    }

    /// ASCII digits only, and a real port number. `Character.isNumber` would have
    /// accepted a full-width or Arabic-Indic digit here and handed the SDK a base URL no
    /// socket can open — the same split the registry records as P9, in the one place in
    /// this app where a character class decides anything.
    private static func isPort(_ value: String) -> Bool
    {
        guard (1 ... 5).contains(value.count), value.utf8.allSatisfy(isASCIIDigit)
        else
        {
            return false
        }
        guard let number = Int(value)
        else
        {
            return false
        }
        return (1 ... 65535).contains(number)
    }

    // MARK: - Google

    /// Both halves or nothing. Google derives its callback scheme by reversing the dot
    /// components of the client id, and raises an uncatchable NSException at sign-in time
    /// when that scheme is not among the bundle's registered ones. So this recomputes the
    /// same scheme and refuses the client id when the bundle does not carry it.
    private static func googleClientID(from bundle: Bundle) -> String?
    {
        guard let clientID = string(bundle, "GIDClientID"), clientID.utf8.allSatisfy(isASCIIPrintable)
        else
        {
            return nil
        }
        guard registeredSchemes(bundle).contains(reversedScheme(clientID))
        else
        {
            return nil
        }
        return clientID
    }

    private static func reversedScheme(_ clientID: String) -> String
    {
        asciiLowercased(clientID.split(separator: ".", omittingEmptySubsequences: false)
            .reversed()
            .joined(separator: "."))
    }

    private static func registeredSchemes(_ bundle: Bundle) -> [String]
    {
        let types = bundle.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? []
        return types
            .compactMap { $0["CFBundleURLSchemes"] as? [String] }
            .flatMap { $0 }
            .map(asciiLowercased)
    }

    // MARK: - Reading, and the ASCII rules everything above shares

    /// An Info.plist key whose xcconfig variable was never defined expands to an empty
    /// string rather than disappearing, so empty and absent are the same answer here.
    private static func string(_ bundle: Bundle, _ key: String) -> String?
    {
        guard let value = bundle.object(forInfoDictionaryKey: key) as? String, !value.isEmpty
        else
        {
            return nil
        }
        return value
    }

    /// `String.lowercased()` follows Unicode's default casing, which is more than this
    /// comparison wants and different from what Google's own scheme match does. Mapping
    /// A–Z by hand keeps the two the same rule.
    private static func asciiLowercased(_ value: String) -> String
    {
        String(decoding: value.utf8.map { isASCIIUppercase($0) ? $0 + 32 : $0 }, as: UTF8.self)
    }

    private static func isASCIIDigit(_ byte: UInt8) -> Bool
    {
        byte >= UInt8(ascii: "0") && byte <= UInt8(ascii: "9")
    }

    private static func isASCIIUppercase(_ byte: UInt8) -> Bool
    {
        byte >= UInt8(ascii: "A") && byte <= UInt8(ascii: "Z")
    }

    private static func isASCIILetter(_ byte: UInt8) -> Bool
    {
        isASCIIUppercase(byte) || (byte >= UInt8(ascii: "a") && byte <= UInt8(ascii: "z"))
    }

    private static func isASCIIPrintable(_ byte: UInt8) -> Bool
    {
        byte >= 0x21 && byte <= 0x7E
    }
}
