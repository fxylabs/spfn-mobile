// The Kotlin half: the one screen failure every model maps a refusal through.
//
// One file per app rather than one per screen: what a person is told when a call refuses is
// a property of the contract's error taxonomy, and a copy of it beside every model would be
// as many chances to disagree about what a 409 means.
//
// Its twin is `SwiftFailureEmitter.kt`, declaration for declaration.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle

internal class KotlinFailureEmitter(target: Target) : KotlinNames(target)
{
    /**
     * Every refusal a screen can show, as one envelope type.
     *
     * `Loadable.Error` and `Busy.Error` carry core's envelope, so a screen's own refusal —
     * a blank required input, which never reached a server — has to be one too. It is
     * given a code of this generator's own rather than borrowing a contract code that
     * would read as something a server said.
     */
    internal fun failure(spec: Spec, bundle: Bundle, inputs: Inputs): String = buildString {
        val rules = spec.screens.any { screen -> screen.inputs.any { it.rules != null } };
        appendLine(header(inputs));
        appendLine();
        appendLine("package $pkg.screens");
        appendLine();
        appendLine("import xyz.superfunction.spfn.client.SpfnClientError");
        appendLine("import xyz.superfunction.spfn.core.SpfnErrorEnvelope");
        appendLine("import xyz.superfunction.spfn.ui.SpfnStrings");
        appendLine();
        appendLine("/** Turns what a call threw into the envelope a screen state carries. */");
        appendLine("object ScreenFailure");
        appendLine("{");
        appendLine("    /** A refusal this screen made itself. Nothing was sent. */");
        appendLine("    const val VALIDATION: String = \"SPFN_UI_VALIDATION\";");
        appendLine();
        appendLine("    /** A call that failed on a ground the server did not put in an envelope. */");
        appendLine("    const val CALL_FAILED: String = \"SPFN_UI_CALL_FAILED\";");
        appendLine();
        appendLine("    /** The screen's own refusal of a required input. [field] is the field's name. */");
        appendLine("    fun validation(field: String): SpfnErrorEnvelope =");
        appendLine("        SpfnErrorEnvelope(code = VALIDATION, message = field, requestId = \"\");");
        appendLine();
        if (rules)
        {
            append(ruleRefusal());
            appendLine();
        }
        appendLine("    /**");
        appendLine("     * The server's own envelope where there is one, and a local one where there is");
        appendLine("     * not. The message carries the name of the SDK type that failed and never any");
        appendLine("     * server text.");
        appendLine("     *");
        appendLine("     * [Throwable] and not [SpfnClientError]: the SDK throws more than that one");
        appendLine("     * hierarchy, and a screen that could not name what it caught would have nothing");
        appendLine("     * to show for it.");
        appendLine("     */");
        appendLine("    fun envelope(failure: Throwable): SpfnErrorEnvelope = when (failure)");
        appendLine("    {");
        appendLine("        is SpfnClientError.Auth -> failure.failure.envelope");
        appendLine("        is SpfnClientError.Server -> failure.failure.envelope");
        appendLine("        else -> SpfnErrorEnvelope(");
        appendLine("            code = CALL_FAILED,");
        appendLine("            message = failure::class.simpleName ?: CALL_FAILED,");
        appendLine("            requestId = \"\"");
        appendLine("        )");
        appendLine("    };");
        append(classification(bundle, rules));
        appendLine("}");
    }

    /**
     * The five keys a failure can be SHOWN under, and how a code becomes one.
     *
     * Derived from the pinned bundle, not written here: the codes are grouped by the HTTP
     * status the contract gives them, so a contract that adds a 401 adds it to the
     * unauthorized family without anybody remembering to. What is a judgement — that a 401
     * family is worth its own sentence and a 409 family is not — is the grouping below and is
     * stated once.
     *
     * The words themselves are `SpfnStrings`'s. Nothing here reads `envelope.message` except
     * [fieldMessage], whose message field is this generator's own field name and never a
     * server's text (decision C7).
     */
    private fun classification(bundle: Bundle, rules: Boolean): String = buildString {
        appendLine();
        appendLine("    /** The code names a device the server is not holding a request for. */");
        appendLine("    const val DEVICE_NOT_FOUND_KEY: String = \"deviceNotFound\";");
        appendLine();
        appendLine("    /** Nothing was reached, or what came back was not readable. */");
        appendLine("    const val NETWORK_KEY: String = \"network\";");
        appendLine();
        appendLine("    /** The server refused this device's credentials. */");
        appendLine("    const val UNAUTHORIZED_KEY: String = \"unauthorized\";");
        appendLine();
        appendLine("    /** The screen refused its own input. Nothing was sent. */");
        appendLine("    const val VALIDATION_KEY: String = \"validation\";");
        appendLine();
        appendLine("    /** Anything this build classifies as nothing more specific. */");
        appendLine("    const val UNEXPECTED_KEY: String = \"unexpected\";");
        appendLine();
        appendLine("    /**");
        appendLine("     * Which of the five keys [envelope] is shown under.");
        appendLine("     *");
        appendLine("     * The two families below are the contract's own 401s and 404s, listed from the");
        appendLine("     * pinned bundle at generation time.");
        appendLine("     */");
        appendLine("    fun messageKey(envelope: SpfnErrorEnvelope): String = when (envelope.code)");
        appendLine("    {");
        appendLine("        VALIDATION -> VALIDATION_KEY");
        appendLine("        CALL_FAILED -> NETWORK_KEY");
        appendCases(this, bundle, 401, "UNAUTHORIZED_KEY");
        appendCases(this, bundle, 404, "DEVICE_NOT_FOUND_KEY");
        appendLine("        else -> UNEXPECTED_KEY");
        appendLine("    };");
        appendLine();
        appendLine("    /**");
        appendLine("     * The sentence for [envelope], looked up in [SpfnStrings].");
        appendLine("     *");
        appendLine("     * Never the server's own words: `message` is text a server chose and a screen that");
        appendLine("     * drew it would publish whatever the server felt like saying (decision C7).");
        appendLine("     */");
        appendLine("    fun message(envelope: SpfnErrorEnvelope): String = when (messageKey(envelope))");
        appendLine("    {");
        appendLine("        DEVICE_NOT_FOUND_KEY -> SpfnStrings.errorDeviceNotFound");
        appendLine("        NETWORK_KEY -> SpfnStrings.errorNetwork");
        appendLine("        UNAUTHORIZED_KEY -> SpfnStrings.errorUnauthorized");
        appendLine("        VALIDATION_KEY -> SpfnStrings.errorValidation");
        appendLine("        else -> SpfnStrings.errorUnexpected");
        appendLine("    };");
        appendLine();
        appendLine("    /** Whether this failure belongs under a field rather than to the screen. */");
        appendLine("    fun isFieldRefusal(envelope: SpfnErrorEnvelope): Boolean = envelope.code == VALIDATION;");
        appendLine();
        appendLine("    /**");
        appendLine("     * The sentence to draw under [field], or null when this failure is not that field's.");
        appendLine("     *");
        appendLine("     * The one read of `message` in this file, and it is safe because the value there is");
        appendLine("     * this generator's own field name: [validation] above is what put it there.");
        if (rules)
        {
            appendLine("     *");
            appendLine("     * A field its rules refused carries `<field>:<rule>`, so the field is what is read");
            appendLine("     * off the front of the message and the rule's name is not compared to anything.");
        }
        appendLine("     */");
        appendLine("    fun fieldMessage(envelope: SpfnErrorEnvelope?, field: String): String? =");
        appendLine(
            "        if (envelope != null && envelope.code == VALIDATION && " +
                (if (rules) "envelope.message.substringBefore(':') == field)" else "envelope.message == field)")
        );
        appendLine("        {");
        appendLine("            SpfnStrings.errorValidation");
        appendLine("        }");
        appendLine("        else");
        appendLine("        {");
        appendLine("            null");
        appendLine("        };");
    }

    /**
     * The other refusal a screen makes itself: a field its `rules` turned down, named by the
     * RULE that turned it down.
     *
     * Emitted only where some screen of this spec writes `rules`, and that is not thrift — it
     * is what keeps a version 1 spec generating the file it generated before. The message is
     * `<field>:<rule>`, and `fieldMessage` reads the field off the front of it, so the line
     * still lands under the right field; the rule's name is there for a readout and for a
     * person reading a log. The SENTENCE a person sees is still `SpfnStrings`'s, because a
     * rule name is not a sentence (decision C7).
     */
    private fun ruleRefusal(): String = buildString {
        appendLine("    /** A field its own rules refused. The message is `<field>:<rule>`. */");
        appendLine("    fun validation(field: String, rule: String): SpfnErrorEnvelope =");
        appendLine("        SpfnErrorEnvelope(code = VALIDATION, message = \"\$field:\$rule\", requestId = \"\");");
    }

    /** One `when` branch per contract error carrying [status], or nothing when there are none. */
    private fun appendCases(out: StringBuilder, bundle: Bundle, status: Long, key: String)
    {
        val codes = bundle.errors.filter { it.httpStatus == status }.map { it.code }.sorted();
        if (codes.isEmpty())
        {
            return;
        }
        out.appendLine("        " + codes.joinToString(", ") { "\"$it\"" } + " -> $key");
    }
}
