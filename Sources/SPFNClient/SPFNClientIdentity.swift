// SPFN Mobile — what this client says about itself, and what it does with the answer.
//
// A deployed app is built once and then does not change; the server it talks to changes
// whenever it is deployed. Contract 0.8.0 is where the two ends started telling each
// other which contract they hold, and this file is this SDK's half of that exchange:
// three headers on every request it sends, two read off every response it receives.
//
// Both halves matter, and the request half is the easier one to get wrong. The server's
// own gate refuses an `ios` or `android` client whose contract version it does not serve
// — but a request naming no kind at all passes, deliberately, because a curl or a health
// probe is not a deployed client. A mobile SDK that names no kind is therefore not
// protected by that gate; it is exempted from it. Saying nothing is not the safe default
// here, it is the one that looks safe.
//
// android/spfn-client/.../SpfnClientIdentity.kt is the same in Kotlin.

import Foundation
import SPFNCore
import SPFNGenerated

/// What this client says about itself on every request.
public enum SPFNClientIdentity
{
    /// The kind this build reports.
    ///
    /// The contract names three kinds — `web`, `ios`, `android` — and this package also
    /// builds for macOS, which is not one of them. A macOS build reports `ios` rather
    /// than inventing a fourth kind or falling silent: silence is the exemption above,
    /// and the macOS build exists to compile and test the iOS code rather than to be a
    /// separate client. What a macOS test run sends is then what a device sends.
    public static let kind = "ios"

    /// The contract version these sources were generated from.
    public static var contractVersion: String
    {
        SPFNGeneratedContract.binding.importedVersion
    }

    /// The host app's own release, read from the app rather than asked of it.
    ///
    /// `CFBundleShortVersionString` is the version a store shows. It is absent in a
    /// context with no app bundle — a command-line test host is the usual one — and the
    /// header is then omitted rather than filled with a placeholder. Nothing is
    /// authorized by this value: the contract calls it unauthenticated and the server's
    /// refusal rule names only the contract version, so an absent one refuses nothing.
    public static var appVersion: String?
    {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
    }

    /// The identity headers, in the order a request carries them.
    public static var headers: [(String, String)]
    {
        var headers: [(String, String)] = [
            (SPFNWireHeaders.clientKind, kind),
            (SPFNWireHeaders.clientContractVersion, contractVersion),
        ]
        if let appVersion
        {
            headers.append((SPFNWireHeaders.clientVersion, appVersion))
        }
        return headers
    }
}

/// Why this client and the server that answered do not hold the same contract.
///
/// `serverVersion` is present only when the announced value parsed as a version. A value
/// that did not is dropped rather than carried: it is text the server chose, an error
/// value reaches logs and crash reports, and the responder may not be the server at all.
public struct SPFNContractMismatch: Equatable, Sendable
{
    public enum Reason: String, Equatable, Sendable
    {
        /// The response announced no contract version. Contract 0.8.0 puts the
        /// announcement on every response including a refusal, so its absence is a
        /// server older than that mechanism, or something between that removed it.
        case unannounced

        /// A version was announced and is not a version this SDK can read.
        case unreadable

        /// A version was announced, read, and is outside the window this SDK admits.
        case outsideAdmittedRange
    }

    public let reason: Reason

    /// The server's announced version, when it parsed. Never raw server text.
    public let serverVersion: String?

    /// The window this SDK admits. This repository's own value, always present.
    public let admittedRange: String

    public init(reason: Reason, serverVersion: String?, admittedRange: String)
    {
        self.reason = reason
        self.serverVersion = serverVersion
        self.admittedRange = admittedRange
    }
}

extension SPFNClientIdentity
{
    /// Reads a response's announcement and returns the mismatch it reveals, or nil.
    ///
    /// Runs before a response is classified, on every read path. A server that refuses
    /// with `CONTRACT_UNSUPPORTED` announces its version on that refusal, and reporting
    /// it as a generic refusal would throw away the one thing that says which end is
    /// stale.
    static func mismatch(
        in response: SPFNTransportResponse,
        against binding: SPFNContractBinding
    ) -> SPFNContractMismatch?
    {
        // Field names are case-insensitive on the wire, and this compares them that way
        // rather than trusting a server to have chosen the same spelling this file did.
        guard let announced = response.headers.first(where: {
            $0.0.caseInsensitiveCompare(SPFNWireHeaders.serverContractVersion) == .orderedSame
        })?.1
        else
        {
            return SPFNContractMismatch(
                reason: .unannounced,
                serverVersion: nil,
                admittedRange: binding.admittedRange
            )
        }

        guard SPFNSemVer.isVersion(announced)
        else
        {
            return SPFNContractMismatch(
                reason: .unreadable,
                serverVersion: nil,
                admittedRange: binding.admittedRange
            )
        }

        do
        {
            try binding.requireSupported(serverContractVersion: announced)
            return nil
        }
        catch
        {
            return SPFNContractMismatch(
                reason: .outsideAdmittedRange,
                serverVersion: announced,
                admittedRange: binding.admittedRange
            )
        }
    }
}
