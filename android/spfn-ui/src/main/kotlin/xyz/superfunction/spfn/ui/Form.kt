// SPFN Mobile — the state of one screenful of input.
//
// Counterpart of Sources/SPFNUI/Form.swift.
//
// A form is a refusal per field plus one [Busy]. It is not a [Loadable]: what a submit
// produces is a changed read somewhere else, or a pop, and never a value this type holds.
//
// ---------------------------------------------------------------------------
// Every field is checked, and the answer is a KEY
// ---------------------------------------------------------------------------
//
// [Form.check] does not stop at the first refusal. A screen that refused one field at a
// time makes a person press submit four times to be told four things, and the four things
// were all knowable at the first press.
//
// A refusal is a [FieldError] — `Required`, `MinLength(2)` — and not a sentence. The words
// a screen shows are the app's, drawn through `SpfnStrings` or the screen's own wording,
// for the same reason `LoadableView` classifies an envelope into a key rather than drawing
// the server's `message` (decision C7). [FieldError.Custom] is the one that carries text,
// because the whole of what a custom rule is, is a sentence the spec's author wrote.
//
// ---------------------------------------------------------------------------
// Written by hand on both platforms, and never as a regular expression
// ---------------------------------------------------------------------------
//
// The two halves have to answer the same for the same input, and the shortest way to make
// them disagree is to let either language's library decide what a character is
// (docs/IMPLEMENTATION-PITFALLS.md P9). So:
//
//   * a LENGTH is UTF-16 code units — `text.length` here, `text.utf16.count` there.
//     Swift's `count` is grapheme clusters and this platform has no cheap equivalent, so
//     "a" is 1 and an emoji is 2 on both platforms rather than 1 on one and 2 on the other.
//   * a DIGIT is one of the ten ASCII digits, written as the range. `Char.isDigit()` and
//     Swift's `Character.isNumber` both accept digits from other scripts.
//   * BLANK is the four ASCII characters named in `isBlank` and nothing else.
//   * a number is NOT parsed. This platform's `Int` is 32-bit and Swift's is 64, so
//     `3000000000` parses on one platform and not the other; what `Number` means here is
//     the SHAPE — an optional sign and at least one digit — which is the same sentence in
//     both languages.
//   * nothing is trimmed. "What is whitespace" is exactly the classification difference
//     above; a spec that wants a trimmed value trims it before handing the values in.
//
// ---------------------------------------------------------------------------
// What this type does NOT do
// ---------------------------------------------------------------------------
//
// It never decides whether to submit. [Form.canSubmit] says whether a write is already in
// flight and the model is what asks; [Form.submitting] is not guarded, because a type that
// silently ignored a submit would hide the model rule (R2) rather than state it.

package xyz.superfunction.spfn.ui

import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.ui.components.FieldKind

/**
 * An extra rule a spec's author wrote, asked one field at a time.
 *
 * A `fun interface`, so a screen passes a lambda and the rules stay one expression; the
 * answer is null for "this is fine" and the sentence to show otherwise. The sentence is the
 * author's, which is why it is the one refusal that carries text rather than a key.
 */
public fun interface FieldValidator
{
    /**
     * @param field the field's name, as the spec spells it. The rule's own name is not
     *   passed: a screen's validator switches on the field it is asked about.
     * @param text what is in the field right now.
     * @return null when the value is acceptable, or the sentence to draw under it.
     */
    public fun validate(field: String, text: String): String?
}

/** Why one field was refused. */
public sealed interface FieldError
{
    /** The field is required and empty. */
    public data object Required : FieldError

    /** Shorter than the rule's minimum, which is [length]. */
    public data class MinLength(val length: Int) : FieldError

    /** Longer than the rule's maximum, which is [length]. */
    public data class MaxLength(val length: Int) : FieldError

    /** Not the shape the field's [FieldKind] names. */
    public data class Kind(val kind: FieldKind) : FieldError

    /** Refused by the spec's own rule, which supplied the sentence. */
    public data class Custom(val message: String) : FieldError
}

/**
 * What one field is checked against.
 *
 * @param required whether an empty field is a refusal. When false, an empty field is checked
 *   against nothing else either: the rules below are about a VALUE, and there is no value.
 * @param minLength the fewest UTF-16 code units the field accepts, or null.
 * @param maxLength the most UTF-16 code units the field accepts, or null.
 * @param kind the shape the field expects, reusing `SpfnTextField`'s own enum so that a spec
 *   value reaches the keyboard and the check through one name.
 * @param custom the spec's name for the extra rule this field carries, or null for none.
 *   Nothing here interprets it. It is what makes a field opt in to its [FieldValidator], and
 *   it is what a reader of a rules table sees when asking why the validator is consulted
 *   about this field and not that one.
 */
public data class FieldRules(
    public val required: Boolean = true,
    public val minLength: Int? = null,
    public val maxLength: Int? = null,
    public val kind: FieldKind = FieldKind.Text,
    public val custom: String? = null
)

/**
 * What one screenful of input can be: a refusal per field, and the state of the write.
 *
 * @param fields one entry per field the rules named, whether or not it was refused. A key
 *   with a null value is a field that passed, and keeping it is what lets a view ask about a
 *   field without asking whether the check has run.
 * @param submit the state of the write this form starts.
 */
public data class Form(
    public val fields: Map<String, FieldError?> = emptyMap(),
    public val submit: Busy = Busy.Idle
)
{
    /** Whether every field the last check looked at was accepted. */
    public val isValid: Boolean
        get() = fields.values.all { it == null };

    /**
     * Whether a submit may be started: no write is already in flight.
     *
     * Not "and the input is valid" — a person has to be able to press the control to be TOLD
     * what is wrong, and being told is what [check] is for.
     */
    public val canSubmit: Boolean
        get() = submit != Busy.Busy;

    /**
     * The field was edited, so its refusal is stale and goes.
     *
     * A field the last check did not look at is left alone rather than added: the key set is
     * the rules', and inventing one here would make [isValid] true for a form that has never
     * been checked at all.
     */
    public fun edited(field: String): Form =
        if (!fields.containsKey(field)) this else Form(fields + (field to null), submit);

    /** The write started. */
    public fun submitting(): Form = Form(fields, Busy.Busy);

    /**
     * The write succeeded. The refusals are kept as they were — what happens next is a pop
     * or a re-read, and neither is this type's to decide.
     */
    public fun submitted(): Form = Form(fields, Busy.Idle);

    /** The write failed, carrying the envelope the server or the transport produced. */
    public fun submitFailed(error: SpfnErrorEnvelope): Form = Form(fields, Busy.Error(error));

    public companion object
    {
        /**
         * Every field checked against its rules, first failure per field reported.
         *
         * The rules are what decides which fields exist: a value with no rule is not looked
         * at, and a rule with no value is checked against the empty string, which is what a
         * field a screen never drew amounts to.
         *
         * The submit is `Idle`, because a check is what a model runs BEFORE it starts a
         * write; a refusal from a previous attempt is not carried into a fresh answer.
         */
        public fun check(
            values: Map<String, String>,
            rules: Map<String, FieldRules>,
            custom: FieldValidator? = null
        ): Form = Form(
            fields = rules.mapValues { (field, rule) ->
                refusal(field, values[field] ?: "", rule, custom)
            },
            submit = Busy.Idle
        );

        // ---- the checks, in the order a field is checked in ---------------------

        /** The first rule this field breaks, or null. */
        private fun refusal(
            field: String,
            text: String,
            rule: FieldRules,
            custom: FieldValidator?
        ): FieldError?
        {
            if (text.isEmpty())
            {
                return if (rule.required) FieldError.Required else null;
            }
            if (rule.minLength != null && text.length < rule.minLength)
            {
                return FieldError.MinLength(rule.minLength);
            }
            if (rule.maxLength != null && text.length > rule.maxLength)
            {
                return FieldError.MaxLength(rule.maxLength);
            }
            if (!accepts(rule.kind, text))
            {
                return FieldError.Kind(rule.kind);
            }
            return customRefusal(field, text, rule, custom);
        }

        /**
         * The spec's own rule, asked only when the field carries one and every rule above it
         * has passed. A validator that is never reached is never called.
         */
        private fun customRefusal(
            field: String,
            text: String,
            rule: FieldRules,
            custom: FieldValidator?
        ): FieldError?
        {
            if (rule.custom == null || custom == null)
            {
                return null;
            }
            val message = custom.validate(field, text);
            return if (message == null) null else FieldError.Custom(message);
        }

        /** Whether the text is the shape the kind names. `Code` and `Text` name no shape. */
        private fun accepts(kind: FieldKind, text: String): Boolean = when (kind)
        {
            FieldKind.Code, FieldKind.Text -> true
            FieldKind.Email -> isEmail(text)
            FieldKind.Number -> isInteger(text)
        };

        /**
         * The three clauses and no fourth: there is an `@`, nothing is blank, and neither
         * side of the first `@` is empty. A second `@` is not refused — this is the shape a
         * screen checks before it spends a request, and whether an address exists is the
         * server's answer.
         */
        private fun isEmail(text: String): Boolean
        {
            var seenAt = false;
            var local = 0;
            var domain = 0;
            text.forEach { character ->
                if (isBlank(character))
                {
                    return false;
                }
                if (character == '@' && !seenAt)
                {
                    seenAt = true;
                }
                else if (seenAt)
                {
                    domain++;
                }
                else
                {
                    local++;
                }
            };
            return seenAt && local > 0 && domain > 0;
        }

        /**
         * An optional leading `+` or `-` and at least one ASCII digit, and nothing else. Not
         * parsed — see this file's header for why the two platforms' integers are not one
         * integer.
         */
        private fun isInteger(text: String): Boolean
        {
            var digits = 0;
            text.forEachIndexed { offset, character ->
                if (offset != 0 || (character != '+' && character != '-'))
                {
                    if (character !in '0'..'9')
                    {
                        return false;
                    }
                    digits++;
                }
            };
            return digits > 0;
        }

        /**
         * The four ASCII characters an address may not contain. Named rather than asked of a
         * whitespace property, for the reason this file's header gives.
         */
        private fun isBlank(character: Char): Boolean =
            character == ' ' || character == '\t' || character == '\n' || character == '\r';
    }
}
