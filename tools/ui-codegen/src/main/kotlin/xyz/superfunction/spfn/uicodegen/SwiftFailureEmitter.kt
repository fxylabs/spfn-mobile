// The Swift half: the one screen failure every model maps a refusal through.
//
// One file per app rather than one per screen: what a person is told when a call refuses is
// a property of the contract's error taxonomy, and a copy of it beside every model would be
// as many chances to disagree about what a 409 means.
//
// Its twin is `KotlinFailureEmitter.kt`, declaration for declaration.

package xyz.superfunction.spfn.uicodegen

import xyz.superfunction.spfn.codegen.Bundle

internal class SwiftFailureEmitter(target: Target) : SwiftNames(target)
{
    internal fun failure(spec: Spec, bundle: Bundle, inputs: Inputs): String = buildString {
        val rules = spec.screens.any { screen -> screen.inputs.any { it.rules != null } };
        appendLine(header(inputs));
        appendLine();
        appendLine("import SPFNClient");
        appendLine("import SPFNCore");
        appendLine("import SPFNUI");
        appendLine();
        appendLine("/// Turns what a call threw into the envelope a screen state carries.");
        appendLine("///");
        appendLine("/// `Loadable.error` and `Busy.error` carry core's envelope, so a screen's own refusal —");
        appendLine("/// a blank required input, which never reached a server — has to be one too. It is");
        appendLine("/// given a code of this generator's own rather than borrowing a contract code that");
        appendLine("/// would read as something a server said.");
        appendLine("public enum ScreenFailure");
        appendLine("{");
        appendLine("    /// A refusal this screen made itself. Nothing was sent.");
        appendLine("    public static let validationCode = \"SPFN_UI_VALIDATION\"");
        appendLine();
        appendLine("    /// A call that failed on a ground the server did not put in an envelope.");
        appendLine("    public static let callFailedCode = \"SPFN_UI_CALL_FAILED\"");
        appendLine();
        appendLine("    /// The screen's own refusal of a required input. `field` is the field's name.");
        appendLine("    public static func validation(_ field: String) -> SPFNErrorEnvelope");
        appendLine("    {");
        appendLine("        SPFNErrorEnvelope(code: validationCode, message: field, requestID: \"\")");
        appendLine("    }");
        appendLine();
        if (rules)
        {
            append(ruleRefusal());
            appendLine();
        }
        appendLine("    /// The server's own envelope where there is one, and a local one where there is");
        appendLine("    /// not. The message carries the name of the SDK type that failed and never any");
        appendLine("    /// server text.");
        appendLine("    ///");
        appendLine("    /// `Error` and not `SPFNClientError`: the SDK throws more than that one type, and");
        appendLine("    /// a screen that could not name what it caught would have nothing to show for it.");
        appendLine("    public static func envelope(_ error: Error) -> SPFNErrorEnvelope");
        appendLine("    {");
        appendLine("        switch error");
        appendLine("        {");
        appendLine("        case SPFNClientError.auth(let failure):");
        appendLine("            return failure.envelope");
        appendLine("        case SPFNClientError.server(let failure):");
        appendLine("            return failure.envelope");
        appendLine("        default:");
        appendLine("            return SPFNErrorEnvelope(");
        appendLine("                code: callFailedCode,");
        appendLine("                message: String(describing: type(of: error)),");
        appendLine("                requestID: \"\"");
        appendLine("            )");
        appendLine("        }");
        appendLine("    }");
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
     * The words themselves are `SPFNStrings`'s. Nothing here reaches `envelope.message`
     * except `fieldMessage`, whose message field is this generator's own field name and never
     * a server's text (decision C7).
     */
    private fun classification(bundle: Bundle, rules: Boolean): String = buildString {
        appendLine();
        appendLine("    /// The code names a device the server is not holding a request for.");
        appendLine("    public static let deviceNotFoundKey = \"deviceNotFound\"");
        appendLine();
        appendLine("    /// Nothing was reached, or what came back was not readable.");
        appendLine("    public static let networkKey = \"network\"");
        appendLine();
        appendLine("    /// The server refused this device's credentials.");
        appendLine("    public static let unauthorizedKey = \"unauthorized\"");
        appendLine();
        appendLine("    /// The screen refused its own input. Nothing was sent.");
        appendLine("    public static let validationKey = \"validation\"");
        appendLine();
        appendLine("    /// Anything this build classifies as nothing more specific.");
        appendLine("    public static let unexpectedKey = \"unexpected\"");
        appendLine();
        appendLine("    /// Which of the five keys `envelope` is shown under.");
        appendLine("    ///");
        appendLine("    /// The two families below are the contract's own 401s and 404s, listed from the");
        appendLine("    /// pinned bundle at generation time.");
        appendLine("    public static func messageKey(_ envelope: SPFNErrorEnvelope) -> String");
        appendLine("    {");
        appendLine("        switch envelope.code");
        appendLine("        {");
        appendLine("        case validationCode:");
        appendLine("            return validationKey");
        appendLine("        case callFailedCode:");
        appendLine("            return networkKey");
        appendCases(this, bundle, 401, "unauthorizedKey");
        appendCases(this, bundle, 404, "deviceNotFoundKey");
        appendLine("        default:");
        appendLine("            return unexpectedKey");
        appendLine("        }");
        appendLine("    }");
        appendLine();
        appendLine("    /// The sentence for `envelope`, looked up in `SPFNStrings`.");
        appendLine("    ///");
        appendLine("    /// Never the server's own words: `message` is text a server chose and a screen that");
        appendLine("    /// drew it would publish whatever the server felt like saying (decision C7).");
        appendLine("    public static func message(_ envelope: SPFNErrorEnvelope) -> String");
        appendLine("    {");
        appendLine("        switch messageKey(envelope)");
        appendLine("        {");
        appendLine("        case deviceNotFoundKey:");
        appendLine("            return SPFNStrings.errorDeviceNotFound");
        appendLine("        case networkKey:");
        appendLine("            return SPFNStrings.errorNetwork");
        appendLine("        case unauthorizedKey:");
        appendLine("            return SPFNStrings.errorUnauthorized");
        appendLine("        case validationKey:");
        appendLine("            return SPFNStrings.errorValidation");
        appendLine("        default:");
        appendLine("            return SPFNStrings.errorUnexpected");
        appendLine("        }");
        appendLine("    }");
        appendLine();
        appendLine("    /// Whether this failure belongs under a field rather than to the screen.");
        appendLine("    public static func isFieldRefusal(_ envelope: SPFNErrorEnvelope) -> Bool");
        appendLine("    {");
        appendLine("        envelope.code == validationCode");
        appendLine("    }");
        appendLine();
        appendLine("    /// The sentence to draw under `field`, or nil when this failure is not that field's.");
        appendLine("    ///");
        appendLine("    /// The one read of `message` in this file, and it is safe because the value there is");
        appendLine("    /// this generator's own field name: `validation(_:)` above is what put it there.");
        appendLine("    public static func fieldMessage(_ envelope: SPFNErrorEnvelope?, field: String) -> String?");
        appendLine("    {");
        if (rules)
        {
            // The closure names its parameter rather than taking `$0`, because the `$0` of a
            // closure nested inside another closure's argument list is the inner one and reads
            // as the outer one.
            appendLine("        guard let envelope = envelope, envelope.code == validationCode,");
            appendLine("            String(envelope.message.prefix(while: { character in character != \":\" })) == field");
        }
        else
        {
            appendLine("        guard let envelope = envelope, envelope.code == validationCode, envelope.message == field");
        }
        appendLine("        else");
        appendLine("        {");
        appendLine("            return nil");
        appendLine("        }");
        appendLine("        return SPFNStrings.errorValidation");
        appendLine("    }");
    }

    /**
     * The other refusal a screen makes itself: a field its `rules` turned down, named by the
     * RULE that turned it down. The Kotlin half's comment applies here word for word.
     */
    private fun ruleRefusal(): String = buildString {
        appendLine("    /// A field its own rules refused. The message is `<field>:<rule>`.");
        appendLine("    public static func validation(_ field: String, rule: String) -> SPFNErrorEnvelope");
        appendLine("    {");
        appendLine("        SPFNErrorEnvelope(code: validationCode, message: \"\\(field):\\(rule)\", requestID: \"\")");
        appendLine("    }");
    }

    /** One `case` line per contract error carrying [status], or nothing when there are none. */
    private fun appendCases(out: StringBuilder, bundle: Bundle, status: Long, key: String)
    {
        val codes = bundle.errors.filter { it.httpStatus == status }.map { it.code }.sorted();
        if (codes.isEmpty())
        {
            return;
        }
        out.appendLine("        case " + codes.joinToString(", ") { "\"$it\"" } + ":");
        out.appendLine("            return $key");
    }
}
