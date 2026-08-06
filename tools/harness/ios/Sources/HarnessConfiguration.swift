// SPFN Mobile — what the harness reads from its launch.
//
// A Maestro flow starts the app with `launchApp: arguments:`, which arrive on iOS as
// `-key value` pairs and therefore as UserDefaults entries. Nothing here is typed into a
// field: a flow that had to type a base URL would be a flow about typing.
//
// The id token is a launch argument for the same reason the sign-in is a closure in the
// SDK. Maestro drives the app under test, and the Apple and Google sign-in sheets are
// system UI outside it, so an automated flow substitutes a canned token and a device run
// leaves the argument out and takes the real sheet (decision 01kzb8tjxp, D-4).

import Foundation
import SPFNClient

struct HarnessConfiguration
{
    /// Where the SDK sends. No default: an app that guessed a base URL would report a
    /// refusal from somewhere nobody named.
    let baseURL: String

    /// The provider id `enroll` rides in. Lowercase alphanumerics and hyphens, which is
    /// what the SDK itself accepts.
    let provider: String

    /// A real provider token, used verbatim. Only a device run against a real server has
    /// one, because a real server verifies it against the provider's own keys.
    let cannedIDToken: String?

    /// The user id the reference server's test token names. A fixed token cannot serve
    /// that server: it checks that the token's nonce is the fingerprint of the key being
    /// enrolled, and the fingerprint is not known until the key exists. So the harness is
    /// given the user id and composes the token around whatever nonce the SDK hands its
    /// sign-in closure — which is the shape the closure exists for.
    let testUser: String?

    static func fromLaunch() -> HarnessConfiguration
    {
        let defaults = UserDefaults.standard
        return HarnessConfiguration(
            baseURL: defaults.string(forKey: "SPFN_HARNESS_BASE_URL") ?? "",
            provider: defaults.string(forKey: "SPFN_HARNESS_PROVIDER") ?? "google",
            cannedIDToken: nonEmpty(defaults.string(forKey: "SPFN_HARNESS_ID_TOKEN")),
            testUser: nonEmpty(defaults.string(forKey: "SPFN_HARNESS_TEST_USER"))
        )
    }

    /// The token the sign-in closure returns, or `nil` when this launch supplied neither
    /// a real token nor a test user. A verbatim token wins: a run that supplied one meant
    /// to use it.
    func idToken(for nonce: SPFNSocialNonce) -> String?
    {
        if let cannedIDToken
        {
            return cannedIDToken
        }
        guard let testUser
        else
        {
            return nil
        }
        return "spfn-test-idtoken.\(provider).\(testUser).\(nonce.requestValue)"
    }

    /// An argument passed as an empty string is an absent argument. Maestro writes one
    /// when a flow leaves a variable unset, and an empty token would otherwise reach
    /// `enroll` and be refused as `idTokenMissing` — a refusal about the harness rather
    /// than about the SDK.
    private static func nonEmpty(_ value: String?) -> String?
    {
        guard let value, !value.isEmpty
        else
        {
            return nil
        }
        return value
    }
}
