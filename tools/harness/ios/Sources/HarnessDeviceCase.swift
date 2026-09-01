// SPFN Mobile — the five cases a person can run by hand, and the two providers.
//
// The app cannot tell first-enrolment from a re-login by itself: both are the same call
// with the same code path, and what separates them is which account the person picks in
// a sheet this app does not control. So the case is chosen on screen before the tap, and
// the receipt records the choice beside what actually happened. An assertion compares
// the two; nothing here decides whether a run passed.
//
// Two of the five need the harness to arrange something, and both arrangements reuse a
// seam that already existed rather than adding one:
//
//   - network-failure blocks the injected transport for the duration of the attempt. The
//     provider sheet is unaffected — it uses its own network, not this transport — so
//     what fails is the enrolment request, with the same `connectivity` error a real
//     network drop produces.
//   - server-reject sends a token the server cannot verify. The real sheet still runs;
//     the harness appends a marker to the token it returns, so the signature check on
//     the server's side fails on a token that is otherwise exactly what a provider
//     issued. There is no other way to reach that cell without a second account, and a
//     token that never leaves memory is the only thing that is altered.
//
// The remaining three need nothing: first-enrolment and re-login are the plain call, and
// user-cancel is the person dismissing the sheet.

import Foundation

/// Spelled as the shared spec spells it, because this string is a receipt field.
enum HarnessDeviceCase: String, CaseIterable, Sendable
{
    case firstEnroll = "first-enroll"
    case reLogin = "re-login"
    case userCancel = "user-cancel"
    case networkFailure = "network-failure"
    case serverReject = "server-reject"

    /// True when the harness must drop the network around the attempt.
    var blocksNetwork: Bool
    {
        self == .networkFailure
    }

    /// True when the token handed to the SDK must be one the server will refuse.
    var rejectsAtServer: Bool
    {
        self == .serverReject
    }

    /// What a person has to do before tapping, in the fewest words that are still true.
    var precondition: String
    {
        switch self
        {
        case .firstEnroll:
            return "wipe first; use an account this server has never seen"
        case .reLogin:
            return "wipe first; use the account first-enroll used"
        case .userCancel:
            return "wipe first; dismiss the sheet"
        case .networkFailure:
            return "wipe first; complete the sheet"
        case .serverReject:
            return "wipe first; complete the sheet"
        }
    }
}

/// The two providers this platform has an adapter for. The raw value is the provider id
/// the SDK enrols under and the receipt records.
enum HarnessProvider: String, CaseIterable, Sendable
{
    case apple
    case google
}

/// What the token the SDK sent was, when the case called for a token the server refuses.
///
/// Appended rather than substituted: a wholly invented token would be refused before the
/// server ever looked at a signature, and the cell is about the server rejecting a
/// provider token, not about the server rejecting nonsense.
enum HarnessTokenSabotage
{
    static let rejectMarker = "-spfn-harness-server-reject"

    static func applied(to token: String, for deviceCase: HarnessDeviceCase) -> String
    {
        deviceCase.rejectsAtServer ? token + rejectMarker : token
    }
}
