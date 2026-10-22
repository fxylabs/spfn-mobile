// SPFN Mobile — what a provider sign-in hands back to `enroll`: the token, and the name.
//
// Apple sends a person's name once, on the first authorization, beside the id_token rather
// than inside it, and never again. Contract 0.13.5 declares `OauthNativeRequest.profile`
// so that name can reach the server, which keeps it as the display name when the social
// account is first attached. A sign-in closure that returns only a token has nowhere to
// put it; this value is that place.
//
// The name is one string the app already holds. The SDK does not compose one from a
// given and a family name, because the order of the two depends on the locale and the
// app knows the locale.
//
// What the SDK does with it is one decision, made here: a name the server's own trim
// would empty is not sent at all, so the body is byte for byte what the token-only form
// sends. Anything else is sent exactly as given. The contract declares no length for
// it, so none is enforced; a refusal is the server's and is classified like any other.
//
// The name is personal data. Neither description names it, nor the token, and nothing
// in the lifecycle stores it — it lives in the request body and nowhere else.
//
// android/spfn-client/.../SpfnSocialSignIn.kt is the same value in Kotlin.

import Foundation
import SPFNGenerated

public struct SPFNSocialSignIn: Sendable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable
{
    /// The provider's token, as the token-only form's closure returns it.
    public let idToken: String

    /// The person's name as the app will show it, when the provider reported one.
    public let displayName: String?

    public init(idToken: String, displayName: String? = nil)
    {
        self.idToken = idToken
        self.displayName = displayName
    }

    /// The body's `profile`: absent unless the name survives the server's trim.
    package var profile: SPFNOauthNativeProfile?
    {
        guard let displayName, !Self.isBlank(displayName)
        else
        {
            return nil
        }
        return SPFNOauthNativeProfile(name: displayName)
    }

    /// Blank as the server judges it: nothing left once ECMAScript's WhiteSpace and
    /// LineTerminator are trimmed from both ends. Written out rather than taken from
    /// `Character.isWhitespace`, which keeps U+FEFF and drops U+0085 — the opposite of
    /// the server on both — and which the Kotlin counterpart could not match (P9).
    package static func isBlank(_ text: String) -> Bool
    {
        text.unicodeScalars.allSatisfy { trimmedByTheServer.contains($0.value) }
    }

    private static let trimmedByTheServer: Set<UInt32> = [
        0x0009, 0x000A, 0x000B, 0x000C, 0x000D, 0x0020, 0x00A0, 0x1680,
        0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
        0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF,
    ]

    /// Names neither the token nor the person: both would otherwise land in every log
    /// line that interpolates a sign-in.
    public var description: String
    {
        "SPFNSocialSignIn"
    }

    public var debugDescription: String
    {
        description
    }

    /// `dump` and the debugger walk stored properties unless told otherwise.
    public var customMirror: Mirror
    {
        Mirror(self, children: [])
    }
}
