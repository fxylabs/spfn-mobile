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

package xyz.superfunction.spfn.harness.generated.screens

import xyz.superfunction.spfn.client.SpfnClientError
import xyz.superfunction.spfn.core.SpfnErrorEnvelope

/** Turns what a call threw into the envelope a screen state carries. */
object ScreenFailure
{
    /** A refusal this screen made itself. Nothing was sent. */
    const val VALIDATION: String = "SPFN_UI_VALIDATION";

    /** A call that failed on a ground the server did not put in an envelope. */
    const val CALL_FAILED: String = "SPFN_UI_CALL_FAILED";

    /** The screen's own refusal of a required input. [field] is the field's name. */
    fun validation(field: String): SpfnErrorEnvelope =
        SpfnErrorEnvelope(code = VALIDATION, message = field, requestId = "");

    /**
     * The server's own envelope where there is one, and a local one where there is
     * not. The message carries the name of the SDK type that failed and never any
     * server text.
     *
     * [Throwable] and not [SpfnClientError]: the SDK throws more than that one
     * hierarchy, and a screen that could not name what it caught would have nothing
     * to show for it.
     */
    fun envelope(failure: Throwable): SpfnErrorEnvelope = when (failure)
    {
        is SpfnClientError.Auth -> failure.failure.envelope
        is SpfnClientError.Server -> failure.failure.envelope
        else -> SpfnErrorEnvelope(
            code = CALL_FAILED,
            message = failure::class.simpleName ?: CALL_FAILED,
            requestId = ""
        )
    };
}
