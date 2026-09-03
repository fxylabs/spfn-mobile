// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      cd02e9ed576538e540a939229a0e476a76708e84286a3ccd09f5f680bf7ab8b5
// bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
// contractVersion: 0.10.0
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateHarnessUi
// Verified by:     ./gradlew :ui-codegen:spfnHarnessUiVerify

import SPFNClient
import SPFNCore

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
    /// not. The message carries the SDK's own case name and never any server text.
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
}
