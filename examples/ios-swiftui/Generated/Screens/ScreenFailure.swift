// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      88e5159b5528860daa36d6ebae1f6a6940c8152eb8373bf4cb3656be70599153
// bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
// contractVersion: 0.10.0
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

import SPFNClient
import SPFNCore
import SPFNUI

/// Turns what a call threw into the envelope a screen state carries.
///
/// `Loadable.error` and `Busy.error` carry core's envelope, so a screen's own refusal —
/// a blank required input, which never reached a server — has to be one too. It is
/// given a code of this generator's own rather than borrowing a contract code that
/// would read as something a server said.
public enum ScreenFailure
{
    /// A refusal this screen made itself. Nothing was sent.
    public static let validationCode = "SPFN_UI_VALIDATION"

    /// A call that failed on a ground the server did not put in an envelope.
    public static let callFailedCode = "SPFN_UI_CALL_FAILED"

    /// The screen's own refusal of a required input. `field` is the field's name.
    public static func validation(_ field: String) -> SPFNErrorEnvelope
    {
        SPFNErrorEnvelope(code: validationCode, message: field, requestID: "")
    }

    /// The server's own envelope where there is one, and a local one where there is
    /// not. The message carries the name of the SDK type that failed and never any
    /// server text.
    ///
    /// `Error` and not `SPFNClientError`: the SDK throws more than that one type, and
    /// a screen that could not name what it caught would have nothing to show for it.
    public static func envelope(_ error: Error) -> SPFNErrorEnvelope
    {
        switch error
        {
        case SPFNClientError.auth(let failure):
            return failure.envelope
        case SPFNClientError.server(let failure):
            return failure.envelope
        default:
            return SPFNErrorEnvelope(
                code: callFailedCode,
                message: String(describing: type(of: error)),
                requestID: ""
            )
        }
    }

    /// The code names a device the server is not holding a request for.
    public static let deviceNotFoundKey = "deviceNotFound"

    /// Nothing was reached, or what came back was not readable.
    public static let networkKey = "network"

    /// The server refused this device's credentials.
    public static let unauthorizedKey = "unauthorized"

    /// The screen refused its own input. Nothing was sent.
    public static let validationKey = "validation"

    /// Anything this build classifies as nothing more specific.
    public static let unexpectedKey = "unexpected"

    /// Which of the five keys `envelope` is shown under.
    ///
    /// The two families below are the contract's own 401s and 404s, listed from the
    /// pinned bundle at generation time.
    public static func messageKey(_ envelope: SPFNErrorEnvelope) -> String
    {
        switch envelope.code
        {
        case validationCode:
            return validationKey
        case callFailedCode:
            return networkKey
        case "InvalidSocialTokenError", "PROOF_EXPIRED", "PROOF_INVALID", "PROOF_REPLAYED", "SESSION_REVOKED":
            return unauthorizedKey
        case "DeviceAuthNotFoundError":
            return deviceNotFoundKey
        default:
            return unexpectedKey
        }
    }

    /// The sentence for `envelope`, looked up in `SPFNStrings`.
    ///
    /// Never the server's own words: `message` is text a server chose and a screen that
    /// drew it would publish whatever the server felt like saying (decision C7).
    public static func message(_ envelope: SPFNErrorEnvelope) -> String
    {
        switch messageKey(envelope)
        {
        case deviceNotFoundKey:
            return SPFNStrings.errorDeviceNotFound
        case networkKey:
            return SPFNStrings.errorNetwork
        case unauthorizedKey:
            return SPFNStrings.errorUnauthorized
        case validationKey:
            return SPFNStrings.errorValidation
        default:
            return SPFNStrings.errorUnexpected
        }
    }

    /// Whether this failure belongs under a field rather than to the screen.
    public static func isFieldRefusal(_ envelope: SPFNErrorEnvelope) -> Bool
    {
        envelope.code == validationCode
    }

    /// The sentence to draw under `field`, or nil when this failure is not that field's.
    ///
    /// The one read of `message` in this file, and it is safe because the value there is
    /// this generator's own field name: `validation(_:)` above is what put it there.
    public static func fieldMessage(_ envelope: SPFNErrorEnvelope?, field: String) -> String?
    {
        guard let envelope = envelope, envelope.code == validationCode, envelope.message == field
        else
        {
            return nil
        }
        return SPFNStrings.errorValidation
    }
}
