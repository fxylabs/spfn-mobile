// SPFN Mobile — the form's checks and its submit, cell for cell.
//
// Counterpart of Tests/SPFNUITests/FormTests.swift, cell for cell. Every test below is named
// for a cell of the approved table (F1–F8, plus the two custom-rule cells) so a disagreement
// between the two platforms is one line on each side with the same name.
//
// The vectors that matter most are the ones where the two languages' libraries would have
// answered differently and the implementation had to be written by hand instead: the length
// of an emoji, a number with a leading space, a number with an underscore, a sign, and a
// value wider than this platform's `Int`. Those are in F2 and F3 rather than in a suite of
// their own, because they ARE those cells — what P9 of the pitfalls records is that a
// character class is not the same class in two languages.

package xyz.superfunction.spfn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.superfunction.spfn.core.SpfnErrorEnvelope
import xyz.superfunction.spfn.ui.components.FieldKind

private val ENVELOPE = SpfnErrorEnvelope("CONFLICT", "server text", "req-1");

/** A validator that records what it was asked and answers the same thing every time. */
private class RecordingValidator(private val answer: String? = null) : FieldValidator
{
    val asked: MutableList<String> = mutableListOf();

    override fun validate(field: String, text: String): String?
    {
        asked.add(field);
        return answer;
    }
}

/** Both fields filled in acceptably: the state four of the cells below start from. */
private fun validForm(): Form = Form.check(
    values = mapOf("message" to "hello", "sequence" to "12"),
    rules = mapOf(
        "message" to FieldRules(minLength = 2),
        "sequence" to FieldRules(kind = FieldKind.Number)
    )
);

class FormTest
{
    // F1 — message="" -> Required, and the custom rule is never asked.
    @Test
    fun `F1 an empty required field is refused before anything else runs`()
    {
        val validator = RecordingValidator("never reached");
        val form = Form.check(
            values = mapOf("message" to ""),
            rules = mapOf("message" to FieldRules(minLength = 2, custom = "polite")),
            custom = validator
        );

        assertEquals(FieldError.Required, form.fields["message"]);
        assertEquals(emptyList<String>(), validator.asked);
        assertFalse(form.isValid);
        assertEquals(Busy.Idle, form.submit);
    }

    // F2 — message="a" against a minimum of 2 -> MinLength(2).
    //
    // The third vector is what "length" means: UTF-16 code units on both platforms, so one
    // emoji is two and clears a minimum of two. Swift's own `count` would have said one and
    // refused it while this platform's `length` accepted it, which is the whole reason the
    // rule is written by hand (docs/IMPLEMENTATION-PITFALLS.md P9).
    @Test
    fun `F2 a field shorter than its minimum carries the minimum`()
    {
        val rules = mapOf("message" to FieldRules(minLength = 2));

        assertEquals(
            FieldError.MinLength(2),
            Form.check(mapOf("message" to "a"), rules).fields["message"]
        );
        assertNull(Form.check(mapOf("message" to "ab"), rules).fields["message"]);
        assertNull(Form.check(mapOf("message" to "😀"), rules).fields["message"]);
    }

    // F3 — sequence="x" against `Number` -> Kind(Number).
    //
    // The vectors after it are the ones a library call would have got wrong in one direction
    // or the other: a leading space, a sign, an underscore, and a value wider than a 32-bit
    // integer — which is accepted, because what `Number` names here is the SHAPE and the two
    // platforms' integers are not the same width.
    @Test
    fun `F3 a field that is not the kind it declares carries the kind`()
    {
        val rules = mapOf("sequence" to FieldRules(kind = FieldKind.Number));

        listOf("x", " 12", "12 ", "1_0", "1.5", "+", "-", "", "١٢").forEach { refused ->
            assertNotNull(
                "\"$refused\" should not be a number",
                Form.check(mapOf("sequence" to refused), rules).fields["sequence"]
            );
        };

        listOf("12", "+3", "-3", "0", "3000000000").forEach { accepted ->
            assertNull(
                "\"$accepted\" should be a number",
                Form.check(mapOf("sequence" to accepted), rules).fields["sequence"]
            );
        };

        assertEquals(
            FieldError.Kind(FieldKind.Number),
            Form.check(mapOf("sequence" to "x"), rules).fields["sequence"]
        );
        // An empty value is refused by `required` and not by the kind: the kinds are about a
        // value and there is not one.
        assertEquals(
            FieldError.Required,
            Form.check(mapOf("sequence" to ""), rules).fields["sequence"]
        );
    }

    // F4 — valid -> submitting -> submitted.
    @Test
    fun `F4 a valid form submits and comes back idle`()
    {
        val checked = validForm();

        assertTrue(checked.isValid);
        assertTrue(checked.canSubmit);

        val sending = checked.submitting();

        assertEquals(Busy.Busy, sending.submit);
        assertFalse(sending.canSubmit);

        val done = sending.submitted();

        assertEquals(Busy.Idle, done.submit);
        assertTrue(done.isValid);
        assertTrue(done.fields.values.all { it == null });
    }

    // F5 — valid -> submitting -> submitFailed.
    @Test
    fun `F5 a failed submit leaves the fields accepted and the write in error`()
    {
        val failed = validForm().submitting().submitFailed(ENVELOPE);

        assertEquals(Busy.Error(ENVELOPE), failed.submit);
        assertTrue(failed.isValid);
        assertTrue(failed.canSubmit);
    }

    // F6 — submit=Busy -> the form says so, and that is what a model reads before it checks.
    //
    // The rule lives on the type rather than in the transition: `submitting()` is not
    // guarded, so the reason a second press changes nothing is that a model asks this first
    // (rule R2). A form whose last write FAILED may be submitted again.
    @Test
    fun `F6 a form with a write in flight refuses a second submit`()
    {
        val sending = validForm().submitting();

        assertFalse(sending.canSubmit);
        assertTrue(validForm().canSubmit);
        assertTrue(validForm().submitting().submitFailed(ENVELOPE).canSubmit);
    }

    // F7 — a refused field is edited, so its refusal goes and the key stays.
    @Test
    fun `F7 editing a field clears its refusal and keeps the field`()
    {
        val refused = Form.check(mapOf("message" to ""), mapOf("message" to FieldRules()));

        assertEquals(FieldError.Required, refused.fields["message"]);

        val edited = refused.edited("message");

        assertNull(edited.fields["message"]);
        assertTrue(edited.fields.containsKey("message"));
        assertTrue(edited.isValid);
        // A field no rule named is not invented, so a form that has never been checked cannot
        // be made valid by editing it.
        assertEquals(edited, edited.edited("nothing-of-the-sort"));
    }

    // F8 — two fields are wrong and both are reported. The check does not stop at the first.
    @Test
    fun `F8 every field is checked rather than the first that fails`()
    {
        val form = Form.check(
            values = mapOf("message" to "", "sequence" to "x"),
            rules = mapOf(
                "message" to FieldRules(),
                "sequence" to FieldRules(kind = FieldKind.Number)
            )
        );

        assertEquals(FieldError.Required, form.fields["message"]);
        assertEquals(FieldError.Kind(FieldKind.Number), form.fields["sequence"]);
        assertFalse(form.isValid);
    }

    // custom, cell 1 — every rule above it passed, so the validator is asked and its sentence
    // is what the field carries.
    @Test
    fun `custom is asked when every rule before it passed`()
    {
        val validator = RecordingValidator("Say that more kindly.");
        val form = Form.check(
            values = mapOf("message" to "SHOUTING"),
            rules = mapOf("message" to FieldRules(minLength = 2, custom = "polite")),
            custom = validator
        );

        assertEquals(listOf("message"), validator.asked);
        assertEquals(FieldError.Custom("Say that more kindly."), form.fields["message"]);

        // A validator that accepts leaves the field accepted.
        val accepting = RecordingValidator(null);
        val accepted = Form.check(
            values = mapOf("message" to "hello"),
            rules = mapOf("message" to FieldRules(minLength = 2, custom = "polite")),
            custom = accepting
        );

        assertEquals(listOf("message"), accepting.asked);
        assertNull(accepted.fields["message"]);
    }

    // custom, cell 2 — a rule above it failed, so the validator is never asked.
    @Test
    fun `custom is not asked when an earlier rule refused the field`()
    {
        val validator = RecordingValidator("never reached");
        val form = Form.check(
            values = mapOf("address" to "nope"),
            rules = mapOf("address" to FieldRules(kind = FieldKind.Email, custom = "known-domain")),
            custom = validator
        );

        assertEquals(emptyList<String>(), validator.asked);
        assertEquals(FieldError.Kind(FieldKind.Email), form.fields["address"]);
    }

    // The three clauses of `Email`, and no fourth.
    @Test
    fun `an email needs an at with something on both sides and no blanks`()
    {
        val rules = mapOf("address" to FieldRules(kind = FieldKind.Email));

        listOf("nope", "@b", "a@", "a b@c", "a@b c", "a\tb@c", "@").forEach { refused ->
            assertNotNull(
                "\"$refused\" should not be an address",
                Form.check(mapOf("address" to refused), rules).fields["address"]
            );
        };

        listOf("a@b", "someone@example.com", "a@@b").forEach { accepted ->
            assertNull(
                "\"$accepted\" should be an address",
                Form.check(mapOf("address" to accepted), rules).fields["address"]
            );
        };
    }

    // A field that is not required and is empty is checked against nothing else: the rules
    // after `required` are about a value, and there is no value.
    @Test
    fun `an optional field left empty is not refused by the rules below required`()
    {
        val form = Form.check(
            values = mapOf("address" to ""),
            rules = mapOf(
                "address" to FieldRules(required = false, minLength = 3, kind = FieldKind.Email)
            )
        );

        assertNull(form.fields["address"]);
        assertTrue(form.fields.containsKey("address"));
        assertTrue(form.isValid);
    }

    // A rule with no value at all is checked against the empty string, which is what a field a
    // screen never drew amounts to; a value with no rule is not looked at.
    @Test
    fun `the rules decide which fields exist`()
    {
        val form = Form.check(
            values = mapOf("stray" to "whatever"),
            rules = mapOf("message" to FieldRules())
        );

        assertEquals(FieldError.Required, form.fields["message"]);
        assertFalse(form.fields.containsKey("stray"));
    }
}
