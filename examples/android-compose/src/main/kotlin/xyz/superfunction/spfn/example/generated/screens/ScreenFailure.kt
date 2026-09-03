// GENERATED FILE — DO NOT EDIT.
//
// generator:       spfn-ui-codegen 0.1.0-dev
// spec:            examples/ui-spec/device-approval.json
// specSha256:      ea4b08e490fa7f24720859c9b735a9d628949ad1595762d44cb1a833b0b7c164
// bundleSha256:    29c26160b5b62d3e40f76bbf81785c8b6808c85690fe047c715e3f348801d92c
// contractVersion: 0.10.0
//
// Regenerate with: ./gradlew :ui-codegen:spfnGenerateUi
// Verified by:     ./gradlew :ui-codegen:spfnUiVerify

package xyz.superfunction.spfn.example.generated.screens

import xyz.superfunction.spfn.client.SpfnClientError
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.ui.SpfnStrings

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

    /** The code names a device the server is not holding a request for. */
    const val DEVICE_NOT_FOUND_KEY: String = "deviceNotFound";

    /** Nothing was reached, or what came back was not readable. */
    const val NETWORK_KEY: String = "network";

    /** The server refused this device's credentials. */
    const val UNAUTHORIZED_KEY: String = "unauthorized";

    /** The screen refused its own input. Nothing was sent. */
    const val VALIDATION_KEY: String = "validation";

    /** Anything this build classifies as nothing more specific. */
    const val UNEXPECTED_KEY: String = "unexpected";

    /**
     * Which of the five keys [envelope] is shown under.
     *
     * The two families below are the contract's own 401s and 404s, listed from the
     * pinned bundle at generation time.
     */
    fun messageKey(envelope: SpfnErrorEnvelope): String = when (envelope.code)
    {
        VALIDATION -> VALIDATION_KEY
        CALL_FAILED -> NETWORK_KEY
        "InvalidSocialTokenError", "PROOF_EXPIRED", "PROOF_INVALID", "PROOF_REPLAYED", "SESSION_REVOKED" -> UNAUTHORIZED_KEY
        "DeviceAuthNotFoundError" -> DEVICE_NOT_FOUND_KEY
        else -> UNEXPECTED_KEY
    };

    /**
     * The sentence for [envelope], looked up in [SpfnStrings].
     *
     * Never the server's own words: `message` is text a server chose and a screen that
     * drew it would publish whatever the server felt like saying (decision C7).
     */
    fun message(envelope: SpfnErrorEnvelope): String = when (messageKey(envelope))
    {
        DEVICE_NOT_FOUND_KEY -> SpfnStrings.errorDeviceNotFound
        NETWORK_KEY -> SpfnStrings.errorNetwork
        UNAUTHORIZED_KEY -> SpfnStrings.errorUnauthorized
        VALIDATION_KEY -> SpfnStrings.errorValidation
        else -> SpfnStrings.errorUnexpected
    };

    /** Whether this failure belongs under a field rather than to the screen. */
    fun isFieldRefusal(envelope: SpfnErrorEnvelope): Boolean = envelope.code == VALIDATION;

    /**
     * The sentence to draw under [field], or null when this failure is not that field's.
     *
     * The one read of `message` in this file, and it is safe because the value there is
     * this generator's own field name: [validation] above is what put it there.
     */
    fun fieldMessage(envelope: SpfnErrorEnvelope?, field: String): String? =
        if (envelope != null && envelope.code == VALIDATION && envelope.message == field)
        {
            SpfnStrings.errorValidation
        }
        else
        {
            null
        };
}
