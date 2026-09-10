// SPFN Mobile — the form's checks and its submit, cell for cell.
//
// Counterpart of android/spfn-ui/src/test/kotlin/xyz/superfunction/spfn/ui/FormTest.kt,
// cell for cell. Every test below is named for a cell of the approved table (F1–F8, plus
// the two custom-rule cells) so a disagreement between the two platforms is one line on
// each side with the same name.
//
// The vectors that matter most are the ones where the two languages' libraries would have
// answered differently and the implementation had to be written by hand instead: the length
// of an emoji, a number with a leading space, a number with an underscore, a sign. Those are
// in F2 and F3 rather than in a suite of their own, because they ARE those cells — what P9
// of the pitfalls records is that a character class is not the same class in two languages.
//
// No SwiftUI. `SPFNUI`'s pure half compiles and runs on the Linux host.

import SPFNCore
import XCTest
@testable import SPFNUI

private let envelope = SPFNErrorEnvelope(code: "CONFLICT", message: "server text", requestID: "req-1")

/// What a field was refused for, flattened.
///
/// `fields` is a dictionary whose VALUES are optional, so a subscript answers twice over:
/// nil for "no such field" and `.some(nil)` for "this field passed". Every assertion below
/// wants the second question answered, and the key set is checked where it is the point.
private func refusal(_ form: Form, _ field: String) -> FieldError?
{
    form.fields[field] ?? nil
}

/// A validator that records what it was asked and answers the same thing every time.
///
/// `@unchecked Sendable` because ``FieldValidator`` is `Sendable` and this one is
/// deliberately mutable: every use of it is on one thread inside one test, and a lock here
/// would say something about the SDK that is not true.
private final class RecordingValidator: FieldValidator, @unchecked Sendable
{
    private(set) var asked: [String] = []
    private let answer: String?

    init(answer: String? = nil)
    {
        self.answer = answer
    }

    func validate(field: String, text: String) -> String?
    {
        asked.append(field)
        return answer
    }
}

final class FormTests: XCTestCase
{
    // F1 — message="" → required, and the custom rule is never asked.
    func testF1AnEmptyRequiredFieldIsRefusedBeforeAnythingElseRuns()
    {
        let validator = RecordingValidator(answer: "never reached")
        let form = Form.check(
            values: ["message": ""],
            rules: ["message": FieldRules(minLength: 2, custom: "polite")],
            custom: validator
        )

        XCTAssertEqual(refusal(form, "message"), .required)
        XCTAssertEqual(validator.asked, [])
        XCTAssertFalse(form.isValid)
        XCTAssertEqual(form.submit, .idle)
    }

    // F2 — message="a" against a minimum of 2 → minLength(2).
    //
    // The second vector is what "length" means: UTF-16 code units on both platforms, so one
    // emoji is two and clears a minimum of two. Swift's own `count` would have said one and
    // refused it while Kotlin's `length` accepted it, which is the whole reason the rule is
    // written by hand (docs/IMPLEMENTATION-PITFALLS.md P9).
    func testF2AFieldShorterThanItsMinimumCarriesTheMinimum()
    {
        let rules = ["message": FieldRules(minLength: 2)]

        XCTAssertEqual(refusal(Form.check(values: ["message": "a"], rules: rules), "message"), .minLength(2))
        XCTAssertNil(refusal(Form.check(values: ["message": "ab"], rules: rules), "message"))
        XCTAssertNil(refusal(Form.check(values: ["message": "😀"], rules: rules), "message"))
    }

    // F3 — sequence="x" against `number` → kind(.number).
    //
    // The vectors after it are the ones a library call would have got wrong in one direction
    // or the other: a leading space, a sign, an underscore, and a value wider than a 32-bit
    // integer — which is accepted, because what `number` names here is the SHAPE and the two
    // platforms' integers are not the same width.
    func testF3AFieldThatIsNotTheKindItDeclaresCarriesTheKind()
    {
        let rules = ["sequence": FieldRules(kind: .number)]

        for refused in ["x", " 12", "12 ", "1_0", "1.5", "+", "-", "", "١٢"]
        {
            let form = Form.check(values: ["sequence": refused], rules: rules)
            XCTAssertNotNil(refusal(form, "sequence"), "\"\(refused)\" should not be a number")
        }

        for accepted in ["12", "+3", "-3", "0", "3000000000"]
        {
            let form = Form.check(values: ["sequence": accepted], rules: rules)
            XCTAssertNil(refusal(form, "sequence"), "\"\(accepted)\" should be a number")
        }

        XCTAssertEqual(
            refusal(Form.check(values: ["sequence": "x"], rules: rules), "sequence"),
            .kind(.number)
        )
        // An empty value is refused by `required` and not by the kind: the kinds are about a
        // value and there is not one.
        XCTAssertEqual(
            refusal(Form.check(values: ["sequence": ""], rules: rules), "sequence"),
            .required
        )
    }

    // F4 — valid → submitting → submitted.
    func testF4AValidFormSubmitsAndComesBackIdle()
    {
        let checked = validForm()

        XCTAssertTrue(checked.isValid)
        XCTAssertTrue(checked.canSubmit)

        let sending = checked.submitting()

        XCTAssertEqual(sending.submit, .busy)
        XCTAssertFalse(sending.canSubmit)

        let done = sending.submitted()

        XCTAssertEqual(done.submit, .idle)
        XCTAssertTrue(done.isValid)
        XCTAssertTrue(done.fields.values.allSatisfy { $0 == nil })
    }

    // F5 — valid → submitting → submitFailed.
    func testF5AFailedSubmitLeavesTheFieldsAcceptedAndTheWriteInError()
    {
        let failed = validForm().submitting().submitFailed(envelope)

        XCTAssertEqual(failed.submit, .error(envelope))
        XCTAssertTrue(failed.isValid)
        XCTAssertTrue(failed.canSubmit)
    }

    // F6 — submit=busy → the form says so, and that is what a model reads before it checks.
    //
    // The rule lives on the type rather than in the transition: `submitting()` is not
    // guarded, so the reason a second press changes nothing is that a model asks this first
    // (rule R2). A form whose last write FAILED may be submitted again.
    func testF6AFormWithAWriteInFlightRefusesASecondSubmit()
    {
        let sending = validForm().submitting()

        XCTAssertFalse(sending.canSubmit)
        XCTAssertTrue(validForm().canSubmit)
        XCTAssertTrue(validForm().submitting().submitFailed(envelope).canSubmit)
    }

    // F7 — a refused field is edited, so its refusal goes and the key stays.
    func testF7EditingAFieldClearsItsRefusalAndKeepsTheField()
    {
        let refused = Form.check(values: ["message": ""], rules: ["message": FieldRules()])

        XCTAssertEqual(refusal(refused, "message"), .required)

        let edited = refused.edited("message")

        XCTAssertNil(refusal(edited, "message"))
        XCTAssertTrue(edited.fields.keys.contains("message"))
        XCTAssertTrue(edited.isValid)
        // A field no rule named is not invented, so a form that has never been checked
        // cannot be made valid by editing it.
        XCTAssertEqual(edited.edited("nothing-of-the-sort"), edited)
    }

    // F8 — two fields are wrong and both are reported. The check does not stop at the first.
    func testF8EveryFieldIsCheckedRatherThanTheFirstThatFails()
    {
        let form = Form.check(
            values: ["message": "", "sequence": "x"],
            rules: ["message": FieldRules(), "sequence": FieldRules(kind: .number)]
        )

        XCTAssertEqual(refusal(form, "message"), .required)
        XCTAssertEqual(refusal(form, "sequence"), .kind(.number))
        XCTAssertFalse(form.isValid)
    }

    // custom, cell 1 — every rule above it passed, so the validator is asked and its sentence
    // is what the field carries.
    func testCustomIsAskedWhenEveryRuleBeforeItPassed()
    {
        let validator = RecordingValidator(answer: "Say that more kindly.")
        let form = Form.check(
            values: ["message": "SHOUTING"],
            rules: ["message": FieldRules(minLength: 2, custom: "polite")],
            custom: validator
        )

        XCTAssertEqual(validator.asked, ["message"])
        XCTAssertEqual(refusal(form, "message"), .custom(message: "Say that more kindly."))

        // A validator that accepts leaves the field accepted.
        let accepting = RecordingValidator(answer: nil)
        let accepted = Form.check(
            values: ["message": "hello"],
            rules: ["message": FieldRules(minLength: 2, custom: "polite")],
            custom: accepting
        )

        XCTAssertEqual(accepting.asked, ["message"])
        XCTAssertNil(refusal(accepted, "message"))
    }

    // custom, cell 2 — a rule above it failed, so the validator is never asked.
    func testCustomIsNotAskedWhenAnEarlierRuleRefusedTheField()
    {
        let validator = RecordingValidator(answer: "never reached")
        let form = Form.check(
            values: ["address": "nope"],
            rules: ["address": FieldRules(kind: .email, custom: "known-domain")],
            custom: validator
        )

        XCTAssertEqual(validator.asked, [])
        XCTAssertEqual(refusal(form, "address"), .kind(.email))
    }

    // The three clauses of `email`, and no fourth.
    func testAnEmailNeedsAnAtWithSomethingOnBothSidesAndNoBlanks()
    {
        let rules = ["address": FieldRules(kind: .email)]

        for refused in ["nope", "@b", "a@", "a b@c", "a@b c", "a\tb@c", "@"]
        {
            let form = Form.check(values: ["address": refused], rules: rules)
            XCTAssertNotNil(refusal(form, "address"), "\"\(refused)\" should not be an address")
        }

        for accepted in ["a@b", "someone@example.com", "a@@b"]
        {
            let form = Form.check(values: ["address": accepted], rules: rules)
            XCTAssertNil(refusal(form, "address"), "\"\(accepted)\" should be an address")
        }
    }

    // A field that is not required and is empty is checked against nothing else: the rules
    // after `required` are about a value, and there is no value.
    func testAnOptionalFieldLeftEmptyIsNotRefusedByTheRulesBelowRequired()
    {
        let form = Form.check(
            values: ["address": ""],
            rules: ["address": FieldRules(required: false, minLength: 3, kind: .email)]
        )

        XCTAssertNil(refusal(form, "address"))
        XCTAssertTrue(form.fields.keys.contains("address"))
        XCTAssertTrue(form.isValid)
    }

    // A rule with no value at all is checked against the empty string, which is what a field
    // a screen never drew amounts to; a value with no rule is not looked at.
    func testTheRulesDecideWhichFieldsExist()
    {
        let form = Form.check(
            values: ["stray": "whatever"],
            rules: ["message": FieldRules()]
        )

        XCTAssertEqual(refusal(form, "message"), .required)
        XCTAssertFalse(form.fields.keys.contains("stray"))
    }

    /// Both fields filled in acceptably: the state four of the cells above start from.
    private func validForm() -> Form
    {
        Form.check(
            values: ["message": "hello", "sequence": "12"],
            rules: ["message": FieldRules(minLength: 2), "sequence": FieldRules(kind: .number)]
        )
    }
}
